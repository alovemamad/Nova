package app.novavpn.core.process

import android.content.Context
import android.os.Process as AndroidProcess
import app.novavpn.data.log.LogRepository
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
    private val onUnexpectedExit: (Int, String) -> Unit
) {
    private val mutex = Mutex()
    private val activeProcesses = mutableListOf<Process>()
    private val readerJobs = mutableListOf<Job>()

    @Volatile
    private var shuttingDown = false

    fun nativeDir(): File = File(context.applicationInfo.nativeLibraryDir)

    fun workDir(): File = File(context.filesDir, "nova_core").apply { mkdirs() }

    suspend fun startPlan(plan: BuiltLaunchPlan, bindHost: String): CoreStartResult = mutex.withLock {
        shuttingDown = false
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

        val lastDetectedEndpoint = AtomicReference<String?>(null)

        for (stage in plan.stages) {
            val stageName = stage.adapter.id
            val readyDeferred = CompletableDeferred<Result<String?>>()
            val pb = ProcessBuilder(stage.args)
                .directory(workDir())
                .redirectErrorStream(true)
            pb.environment()["HOME"] = workDir().absolutePath
            pb.environment()["TMPDIR"] = context.cacheDir.absolutePath

            val proc = try {
                withContext(Dispatchers.IO) { pb.start() }
            } catch (e: Exception) {
                stopInternal()
                return@withLock CoreStartResult.Failed("Failed to launch $stageName: ${e.message}")
            }

            activeProcesses.add(proc)

            val job = scope.launch(Dispatchers.IO) {
                var readySignaled = false
                try {
                    BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                        var line: String?
                        while (isActive) {
                            line = reader.readLine() ?: break
                            LogRepository.i(stageName, line)

                            stage.adapter.extractEndpoint(line)?.let { ep ->
                                lastDetectedEndpoint.set(ep)
                            }

                            if (!readySignaled && stage.adapter.isReadinessLine(line)) {
                                readySignaled = true
                                readyDeferred.complete(Result.success(lastDetectedEndpoint.get()))
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (!shuttingDown && !readySignaled) {
                        readyDeferred.complete(Result.failure(e))
                    }
                }

                val code = runCatching { proc.waitFor() }.getOrDefault(-1)
                if (!readySignaled) {
                    readyDeferred.complete(Result.failure(IllegalStateException("$stageName exited before ready (code=$code)")))
                }
                if (!shuttingDown) {
                    LogRepository.w("CoreProcess", "$stageName exited unexpectedly with code $code")
                    onUnexpectedExit(code, stageName)
                }
            }
            readerJobs.add(job)

            val readyResult = withTimeoutOrNull(18_000L) { readyDeferred.await() }
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
        stopInternal()
        killOrphanedHelpers()
    }

    private suspend fun stopInternal() = withContext(Dispatchers.IO) {
        val procs = activeProcesses.toList()
        activeProcesses.clear()
        procs.forEach { proc ->
            runCatching {
                if (proc.isAlive) {
                    proc.destroy()
                    val exited = proc.waitFor(1500, TimeUnit.MILLISECONDS)
                    if (!exited && proc.isAlive) {
                        proc.destroyForcibly()
                        proc.waitFor(500, TimeUnit.MILLISECONDS)
                    }
                }
            }
        }
        readerJobs.forEach { it.cancel() }
        readerJobs.clear()
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
