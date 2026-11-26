package app.novavpn.core.process

import android.content.Context
import android.os.Process as AndroidProcess
import android.os.SystemClock
import app.novavpn.core.net.ConnectivityHelper
import app.novavpn.data.log.LogRepository
import app.novavpn.domain.validation.InputValidators
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

sealed class CoreStartResult {
    data class Ready(val socksPort: Int, val httpPort: Int?, val detectedEndpoint: String?) : CoreStartResult()
    data class MissingBinary(val binaryName: String, val expectedPath: String) : CoreStartResult()
    data class PortConflict(val port: Int) : CoreStartResult()
    data class Failed(val reason: String) : CoreStartResult()
}

class CoreProcessManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onUnexpectedExit: (Int, String) -> Unit,
    private val onFatalTunnelError: ((String) -> Unit)? = null
) {
    private data class ManagedProcess(
        val proc: Process,
        val generation: Long,
        val intentionalStop: AtomicBoolean = AtomicBoolean(false)
    )

    private val mutex = Mutex()
    private val procLock = Any()
    private val activeProcesses = mutableListOf<ManagedProcess>()
    private val readerJobs = mutableListOf<Job>()
    private val activeGeneration = AtomicLong(0L)

    @Volatile
    private var currentReadyDeferred: CompletableDeferred<Result<String?>>? = null

    @Volatile
    private var shuttingDown = false

    @Volatile
    var psiphonSlot: Int = 0
        private set

    fun nativeDir(): File = File(context.applicationInfo.nativeLibraryDir)

    fun workDir(): File = File(context.filesDir, "nova_core").apply { mkdirs() }

    fun nonVpnDnsServers(): List<String> = ConnectivityHelper.nonVpnIpv4DnsServers(context)

    /**
     * Immediately interrupts any in-flight readiness wait and destroys live processes
     * without waiting for [mutex] (e.g. when the user cancels during a long scan).
     */
    fun forceAbort() {
        shuttingDown = true
        activeGeneration.incrementAndGet()
        currentReadyDeferred?.complete(Result.failure(CancellationException("Aborted by user")))
        val snapshot = synchronized(procLock) { activeProcesses.toList() }
        snapshot.forEach { managed ->
            managed.intentionalStop.set(true)
            runCatching { managed.proc.inputStream.close() }
            runCatching { managed.proc.errorStream.close() }
            runCatching { managed.proc.outputStream.close() }
            runCatching {
                if (managed.proc.isAlive) {
                    managed.proc.destroy()
                }
            }
        }
    }

    suspend fun startPlan(plan: BuiltLaunchPlan, bindHost: String): CoreStartResult = mutex.withLock {
        shuttingDown = true
        stopInternal()
        killOrphanedHelpers()

        // Pre-check all required native binaries for every stage
        for (stage in plan.stages) {
            for (binFile in stage.preflightBinaries) {
                if (!binFile.exists() || !binFile.canExecute()) {
                    LogRepository.e("CoreProcess", "Missing or non-executable binary: ${binFile.name} at ${binFile.absolutePath}")
                    return@withLock CoreStartResult.MissingBinary(binFile.name, binFile.absolutePath)
                }
            }
        }

        // Pre-check TCP port conflicts before launching
        if (!isTcpPortAvailable(bindHost, plan.finalSocksPort)) {
            LogRepository.e("CoreProcess", "TCP port conflict on SOCKS5 port ${plan.finalSocksPort}")
            return@withLock CoreStartResult.PortConflict(plan.finalSocksPort)
        }
        plan.finalHttpPort?.let { hp ->
            if (!isTcpPortAvailable(bindHost, hp)) {
                LogRepository.e("CoreProcess", "TCP port conflict on HTTP port $hp")
                return@withLock CoreStartResult.PortConflict(hp)
            }
        }

        val myGeneration = activeGeneration.incrementAndGet()
        shuttingDown = false
        val lastDetectedEndpoint = AtomicReference<String?>(null)

        for (stage in plan.stages) {
            if (shuttingDown || activeGeneration.get() != myGeneration) {
                stopInternal()
                return@withLock CoreStartResult.Failed("Cancelled before starting ${stage.adapter.id}")
            }

            val stageName = stage.adapter.id
            val isReadyLine = stage.readinessMatcher ?: stage.adapter::isReadinessLine
            val readyDeferred = CompletableDeferred<Result<String?>>()
            currentReadyDeferred = readyDeferred
            val readinessStartMs = SystemClock.elapsedRealtime()
            val readinessDeadlineMs = AtomicLong(readinessStartMs + stage.readinessTimeoutMs)
            val readinessHardCapMs = readinessStartMs + stage.readinessTimeoutMs + stage.progressExtensionMs
            var progressExtended = false
            val lastErrorLine = AtomicReference<String?>(null)
            val pb = ProcessBuilder(stage.args)
                .directory(workDir())
                .redirectErrorStream(true)
            pb.environment()["HOME"] = workDir().absolutePath
            pb.environment()["TMPDIR"] = context.cacheDir.absolutePath
            stage.envVars.forEach { (k, v) -> pb.environment()[k] = v }

            val proc = try {
                withContext(Dispatchers.IO) { pb.start() }
            } catch (e: Exception) {
                currentReadyDeferred = null
                stopInternal()
                return@withLock CoreStartResult.Failed("Failed to launch $stageName: ${e.message}")
            }

            val managed = ManagedProcess(proc, myGeneration)
            synchronized(procLock) {
                activeProcesses.add(managed)
            }

            if (shuttingDown || activeGeneration.get() != myGeneration) {
                currentReadyDeferred = null
                stopInternal()
                return@withLock CoreStartResult.Failed("Aborted during launch of $stageName")
            }

            val job = scope.launch(Dispatchers.IO) {
                var readySignaled = false
                var fatalSignaled = false
                try {
                    BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                        var line: String?
                        while (isActive) {
                            line = reader.readLine() ?: break
                            LogRepository.i(stageName, line)
                            if (AetherCoreAdapter.isPsiphonDatastoreError(line)) {
                                psiphonSlot = (psiphonSlot + 1) % 8
                            }
                            if (line.contains("Error:", ignoreCase = true) ||
                                line.contains("[-]", ignoreCase = false) ||
                                line.contains("error", ignoreCase = true)
                            ) {
                                lastErrorLine.set(InputValidators.redactSensitiveForLog(line.trim()))
                            }

                            stage.adapter.extractEndpoint(line)?.let { ep ->
                                lastDetectedEndpoint.set(ep)
                            }

                            if (!readySignaled && !progressExtended &&
                                stage.progressExtensionMs > 0L &&
                                stage.progressMatcher?.invoke(line) == true
                            ) {
                                progressExtended = true
                                val extended = SystemClock.elapsedRealtime() + stage.progressExtensionMs
                                readinessDeadlineMs.accumulateAndGet(minOf(extended, readinessHardCapMs)) { a, b -> maxOf(a, b) }
                            }

                            if (!readySignaled && isReadyLine(line)) {
                                readySignaled = true
                                readyDeferred.complete(Result.success(lastDetectedEndpoint.get()))
                            } else if (readySignaled &&
                                !fatalSignaled &&
                                !managed.intentionalStop.get() &&
                                !shuttingDown &&
                                managed.generation == activeGeneration.get() &&
                                AetherCoreAdapter.isFatalTunnelLine(line)
                            ) {
                                fatalSignaled = true
                                val summary = InputValidators.redactSensitiveForLog(line.trim())
                                LogRepository.w("CoreProcess", "Detected fatal tunnel error: $summary")
                                onFatalTunnelError?.invoke(summary)
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (!managed.intentionalStop.get() &&
                        !shuttingDown &&
                        managed.generation == activeGeneration.get() &&
                        !readySignaled
                    ) {
                        readyDeferred.complete(Result.failure(e))
                    }
                }

                val code = runCatching { proc.waitFor() }.getOrDefault(-1)
                val why = when (code) {
                    137 -> " (SIGKILL)"
                    139 -> " (SIGSEGV)"
                    134 -> " (SIGABRT)"
                    143 -> " (SIGTERM)"
                    else -> ""
                }
                val isCurrentGeneration = !managed.intentionalStop.get() &&
                    !shuttingDown &&
                    managed.generation == activeGeneration.get()
                if (!readySignaled) {
                    val detail = lastErrorLine.get()?.let { ": $it" } ?: ""
                    readyDeferred.complete(
                        Result.failure(IllegalStateException("$stageName exited before ready (code=$code$why)$detail"))
                    )
                } else if (isCurrentGeneration) {
                    LogRepository.w("CoreProcess", "$stageName exited unexpectedly with code $code$why")
                    onUnexpectedExit(code, stageName)
                }
            }
            synchronized(procLock) {
                readerJobs.add(job)
            }

            var rawReadyResult: Result<String?>? = null
            while (rawReadyResult == null) {
                val remainingMs = readinessDeadlineMs.get() - SystemClock.elapsedRealtime()
                if (remainingMs <= 0L) break
                rawReadyResult = withTimeoutOrNull(remainingMs) { readyDeferred.await() }
            }
            val readyResult = rawReadyResult
            currentReadyDeferred = null
            if (readyResult == null) {
                stopInternal()
                return@withLock CoreStartResult.Failed("$stageName timed out waiting for readiness log")
            }
            if (readyResult.isFailure) {
                val msg = readyResult.exceptionOrNull()?.message ?: "$stageName failed to start"
                stopInternal()
                return@withLock CoreStartResult.Failed(msg)
            }
        }

        CoreStartResult.Ready(plan.finalSocksPort, plan.finalHttpPort, lastDetectedEndpoint.get())
    }

    suspend fun stopAll() = mutex.withLock {
        shuttingDown = true
        activeGeneration.incrementAndGet()
        currentReadyDeferred?.complete(Result.failure(CancellationException("Stopped")))
        stopInternal()
        killOrphanedHelpers()
    }

    private suspend fun stopInternal() = withContext(Dispatchers.IO) {
        activeGeneration.incrementAndGet()
        val (procs, jobs) = synchronized(procLock) {
            val p = activeProcesses.toList()
            val j = readerJobs.toList()
            activeProcesses.clear()
            readerJobs.clear()
            p to j
        }
        procs.forEach { managed ->
            managed.intentionalStop.set(true)
            runCatching { managed.proc.inputStream.close() }
            runCatching { managed.proc.errorStream.close() }
            runCatching { managed.proc.outputStream.close() }
            runCatching {
                if (managed.proc.isAlive) {
                    managed.proc.destroy()
                    val exited = managed.proc.waitFor(1500, TimeUnit.MILLISECONDS)
                    if (!exited && managed.proc.isAlive) {
                        managed.proc.destroyForcibly()
                        managed.proc.waitFor(500, TimeUnit.MILLISECONDS)
                    }
                }
            }
        }
        jobs.forEach { it.cancel() }
        withTimeoutOrNull(1500L) {
            jobs.forEach { it.join() }
        }
    }

    /**
     * Kills orphaned helper binaries belonging strictly to our own Linux UID.
     */
    private suspend fun killOrphanedHelpers() = withContext(Dispatchers.IO) {
        val myUid = AndroidProcess.myUid()
        val myPid = AndroidProcess.myPid()
        val targets = setOf("libaether.so", "libpsiphon.so", "libtor.so", "liblyrebird.so")
        val procDir = File("/proc")
        val entries = procDir.listFiles() ?: return@withContext

        for (entry in entries) {
            val pid = entry.name.toIntOrNull() ?: continue
            if (pid == myPid) continue
            runCatching {
                val statusText = File(entry, "status").takeIf { it.canRead() }?.readText() ?: return@runCatching
                val uidLine = statusText.lineSequence().firstOrNull { it.startsWith("Uid:") } ?: return@runCatching
                val procUid = uidLine.removePrefix("Uid:").trim().split(Regex("""\s+""")).firstOrNull()?.toIntOrNull()
                if (procUid != myUid) return@runCatching

                val cmdline = File(entry, "cmdline").takeIf { it.canRead() }?.readText()?.replace('\u0000', ' ') ?: ""
                if (targets.any { cmdline.contains(it) }) {
                    LogRepository.w("CoreProcess", "Killing orphaned helper PID $pid")
                    AndroidProcess.sendSignal(pid, 15)
                    delay(120)
                    if (entry.exists()) {
                        AndroidProcess.sendSignal(pid, 9)
                    }
                }
            }
        }
    }

    private fun isTcpPortAvailable(host: String, port: Int): Boolean {
        return try {
            val addr = InetAddress.getByName(host)
            ServerSocket(port, 1, addr).use { }
            true
        } catch (_: Exception) {
            false
        }
    }
}
