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
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

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

        // Pre-check required native binaries
        val requiredBins = mutableListOf<Pair<String, List<String>>>()
        plan.aetherArgs?.let { requiredBins.add("libaether.so" to it) }
        plan.psiphonArgs?.let { requiredBins.add("libpsiphon.so" to it) }
        plan.lyrebirdArgs?.let { requiredBins.add("liblyrebird.so" to it) }

        for ((name, args) in requiredBins) {
            val file = File(args.first())
            if (!file.exists() || !file.canExecute()) {
                LogRepository.e("CoreProcess", "Missing or non-executable binary: $name at ${file.absolutePath}")
                return@withLock CoreStartResult.MissingBinary(name, file.absolutePath)
            }
        }

        // Pre-check port conflicts before launching
        if (!isPortAvailable(bindHost, plan.finalSocksPort)) {
            LogRepository.e("CoreProcess", "Port conflict on SOCKS5 port ${plan.finalSocksPort}")
            return@withLock CoreStartResult.PortConflict(plan.finalSocksPort)
        }
        plan.finalHttpPort?.let { hp ->
            if (!isPortAvailable(bindHost, hp)) {
                LogRepository.e("CoreProcess", "Port conflict on HTTP port $hp")
                return@withLock CoreStartResult.PortConflict(hp)
            }
        }

        var lastDetectedEndpoint: String? = null

        for ((name, args) in requiredBins) {
            val readyDeferred = CompletableDeferred<Result<String?>>()
            val pb = ProcessBuilder(args)
                .directory(workDir())
                .redirectErrorStream(true)
            pb.environment()["HOME"] = workDir().absolutePath
            pb.environment()["TMPDIR"] = context.cacheDir.absolutePath

            val proc = try {
                withContext(Dispatchers.IO) { pb.start() }
            } catch (e: Exception) {
                stopInternal()
                return@withLock CoreStartResult.Failed("Failed to launch $name: ${e.message}")
            }

            activeProcesses.add(proc)

            val job = scope.launch(Dispatchers.IO) {
                var readySignaled = false
                try {
                    BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                        var line: String?
                        while (isActive) {
                            line = reader.readLine() ?: break
                            LogRepository.i(name.removeSuffix(".so"), line)

                            // Extract discovered endpoint if present
                            if (line.contains("endpoint=", ignoreCase = true) || line.contains("connected to ", ignoreCase = true)) {
                                val candidate = Regex("""(\d{1,3}(?:\.\d{1,3}){3}:\d{2,5})""").find(line)?.groupValues?.get(1)
                                if (candidate != null) lastDetectedEndpoint = candidate
                            }

                            if (!readySignaled && isReadinessLine(name, line)) {
                                readySignaled = true
                                readyDeferred.complete(Result.success(lastDetectedEndpoint))
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
                    readyDeferred.complete(Result.failure(IllegalStateException("$name exited before ready (code=$code)")))
                }
                if (!shuttingDown) {
                    LogRepository.w("CoreProcess", "$name exited unexpectedly with code $code")
                    onUnexpectedExit(code, name)
                }
            }
            readerJobs.add(job)

            val readyResult = withTimeoutOrNull(18_000L) { readyDeferred.await() }
            if (readyResult == null) {
                stopInternal()
                return@withLock CoreStartResult.Failed("$name timed out waiting for readiness log")
            }
            if (readyResult.isFailure) {
                val msg = readyResult.exceptionOrNull()?.message ?: "$name failed to start"
                stopInternal()
                return@withLock CoreStartResult.Failed(msg)
            }
        }

        CoreStartResult.Ready(plan.finalSocksPort, plan.finalHttpPort, lastDetectedEndpoint)
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
                    // Graceful SIGTERM first
                    proc.destroy()
                    val exited = proc.waitFor(1500, TimeUnit.MILLISECONDS)
                    if (!exited && proc.isAlive) {
                        // Forceful SIGKILL
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
        val targets = setOf("libaether.so", "libpsiphon.so", "liblyrebird.so", "libhev-socks5-tunnel.so")
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
                    AndroidProcess.sendSignal(pid, 15) // SIGTERM
                    delay(120)
                    if (entry.exists()) {
                        AndroidProcess.sendSignal(pid, 9) // SIGKILL
                    }
                }
            }
        }
    }

    private fun isPortAvailable(host: String, port: Int): Boolean {
        return try {
            val addr = InetAddress.getByName(host)
            ServerSocket(port, 1, addr).use { }
            DatagramSocket(port, addr).use { }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun isReadinessLine(binaryName: String, line: String): Boolean {
        val lower = line.lowercase()
        return when {
            binaryName.contains("aether") ->
                lower.contains("socks5 listening") ||
                    lower.contains("proxy listening") ||
                    lower.contains("tunnel ready") ||
                    lower.contains("listening on")
            binaryName.contains("psiphon") ->
                lower.contains("socks proxy listening") ||
                    lower.contains("tunnels") && lower.contains("1") ||
                    lower.contains("listening on")
            binaryName.contains("lyrebird") ->
                lower.contains("bootstrapped 100%") ||
                    lower.contains("cmethod") && lower.contains("done") ||
                    lower.contains("socks listener ready")
            else -> lower.contains("listening") || lower.contains("ready")
        }
    }
}
