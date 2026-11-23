package app.novavpn.core.tun

import android.content.Context
import android.os.ParcelFileDescriptor
import app.novavpn.data.log.LogRepository
import app.novavpn.domain.model.DnsMode
import app.novavpn.domain.model.TunEnginePreference
import app.novavpn.domain.model.TunEngineType
import app.novavpn.domain.model.VpnProfile
import app.novavpn.domain.routing.RuleKind
import app.novavpn.domain.routing.RoutingRuleParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

class TunEngineException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface TunEngine {
    val type: TunEngineType
    suspend fun start(
        tunFd: Int,
        socksHost: String,
        socksPort: Int,
        profile: VpnProfile
    )
    suspend fun stop()
}

/**
 * Default TUN engine backed by sing-box (libbox.aar, gVisor stack).
 * Uses reflection against io.nekohasekai.libbox.* so the Kotlin codebase compiles cleanly
 * both before and after libbox.aar is dropped into app/libs/.
 */
class SingBoxTunEngine(private val context: Context) : TunEngine {
    override val type: TunEngineType = TunEngineType.SING_BOX_LIBBOX
    private var boxServiceInstance: Any? = null

    override suspend fun start(tunFd: Int, socksHost: String, socksPort: Int, profile: VpnProfile) = withContext(Dispatchers.IO) {
        val libboxClass = try {
            Class.forName("io.nekohasekai.libbox.Libbox")
        } catch (e: ClassNotFoundException) {
            throw TunEngineException("libbox.aar is not bundled in app/libs/", e)
        }

        val configJson = buildSingBoxConfig(tunFd, socksHost, socksPort, profile)
        val configFile = File(context.filesDir, "nova_core/singbox_tun.json").apply {
            parentFile?.mkdirs()
            writeText(configJson)
        }
        LogRepository.d("SingBoxTun", "Starting libbox gVisor TUN on fd=$tunFd -> $socksHost:$socksPort")

        try {
            val platformInterfaceClass = Class.forName("io.nekohasekai.libbox.PlatformInterface")
            val newServiceMethod = libboxClass.getMethod("newService", String::class.java, platformInterfaceClass)
            // Null platform interface proxy when only running fd-passed TUN -> SOCKS5
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                platformInterfaceClass.classLoader,
                arrayOf(platformInterfaceClass)
            ) { _, method, _ ->
                when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    String::class.java -> ""
                    else -> null
                }
            }
            val service = newServiceMethod.invoke(null, configFile.readText(), proxy)
                ?: throw TunEngineException("Libbox.newService returned null")
            service.javaClass.getMethod("start").invoke(service)
            boxServiceInstance = service
            LogRepository.i("SingBoxTun", "libbox gVisor TUN started successfully")
        } catch (e: Throwable) {
            boxServiceInstance = null
            throw TunEngineException("libbox failed to start: ${e.cause?.message ?: e.message}", e)
        }
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        val instance = boxServiceInstance ?: return@withContext
        boxServiceInstance = null
        runCatching {
            instance.javaClass.getMethod("close").invoke(instance)
        }
        LogRepository.i("SingBoxTun", "libbox TUN stopped")
    }

    fun buildSingBoxConfig(tunFd: Int, socksHost: String, socksPort: Int, profile: VpnProfile): String {
        val dnsAddress = when (profile.dnsMode) {
            DnsMode.DOH -> profile.customDnsPrimary.ifBlank { "https://cloudflare-dns.com/dns-query" }
            DnsMode.DOT -> profile.customDnsPrimary.ifBlank { "tls://1.1.1.1" }
            DnsMode.PLAIN -> profile.customDnsPrimary.ifBlank { "1.1.1.1" }
        }
        val blockRules = RoutingRuleParser.parse(profile.blockRules).validRules
        val directRules = RoutingRuleParser.parse(profile.directRules).validRules

        val routeRules = buildList {
            add("""{"protocol":"dns","outbound":"dns-out"}""")
            if (blockRules.isNotEmpty()) {
                val domains = blockRules.filter { it.kind == RuleKind.DOMAIN }.map { "\"${it.value}\"" }
                val suffixes = blockRules.filter { it.kind == RuleKind.DOMAIN_SUFFIX }.map { "\"${it.value}\"" }
                val cidrs = blockRules.filter { it.kind == RuleKind.IP_CIDR }.map { "\"${it.value}\"" }
                if (domains.isNotEmpty() || suffixes.isNotEmpty()) {
                    add("""{"domain":[${domains.joinToString(",")}],"domain_suffix":[${suffixes.joinToString(",")}],"outbound":"block"}""")
                }
                if (cidrs.isNotEmpty()) {
                    add("""{"ip_cidr":[${cidrs.joinToString(",")}],"outbound":"block"}""")
                }
            }
            if (directRules.isNotEmpty()) {
                val domains = directRules.filter { it.kind == RuleKind.DOMAIN }.map { "\"${it.value}\"" }
                val suffixes = directRules.filter { it.kind == RuleKind.DOMAIN_SUFFIX }.map { "\"${it.value}\"" }
                val cidrs = directRules.filter { it.kind == RuleKind.IP_CIDR }.map { "\"${it.value}\"" }
                if (domains.isNotEmpty() || suffixes.isNotEmpty()) {
                    add("""{"domain":[${domains.joinToString(",")}],"domain_suffix":[${suffixes.joinToString(",")}],"outbound":"direct"}""")
                }
                if (cidrs.isNotEmpty()) {
                    add("""{"ip_cidr":[${cidrs.joinToString(",")}],"outbound":"direct"}""")
                }
            }
        }

        return """
        {
          "log": { "level": "warn" },
          "dns": {
            "servers": [
              { "tag": "remote", "address": "$dnsAddress", "detour": "proxy" },
              { "tag": "local", "address": "local", "detour": "direct" }
            ],
            "strategy": "${if (profile.ipv6Enabled) "prefer_ipv4" else "ipv4_only"}"
          },
          "inbounds": [
            {
              "type": "tun",
              "tag": "tun-in",
              "interface_name": "tun0",
              "file_descriptor": $tunFd,
              "mtu": ${profile.mtu},
              "stack": "gvisor",
              "sniff": true
            }
          ],
          "outbounds": [
            {
              "type": "socks",
              "tag": "proxy",
              "server": "$socksHost",
              "server_port": $socksPort,
              "version": "5"
            },
            { "type": "direct", "tag": "direct" },
            { "type": "block", "tag": "block" },
            { "type": "dns", "tag": "dns-out" }
          ],
          "route": {
            "rules": [${routeRules.joinToString(",")}],
            "final": "proxy"
          }
        }
        """.trimIndent()
    }
}

/**
 * Automatic fallback TUN engine backed by hev-socks5-tunnel (JNI or executable binary).
 */
class HevSocks5TunEngine(private val context: Context) : TunEngine {
    override val type: TunEngineType = TunEngineType.HEV_SOCKS5_TUNNEL
    private var process: Process? = null
    private var usingJni = false

    override suspend fun start(tunFd: Int, socksHost: String, socksPort: Int, profile: VpnProfile) = withContext(Dispatchers.IO) {
        val confFile = File(context.filesDir, "nova_core/hev_socks5.yml").apply {
            parentFile?.mkdirs()
            writeText(buildHevYaml(socksHost, socksPort, profile))
        }

        // First attempt JNI class hev.htproxy.TProxyService if bundled
        val jniLoaded = runCatching {
            val clazz = Class.forName("hev.htproxy.TProxyService")
            val startMethod = clazz.getMethod("TProxyStartService", String::class.java, Int::class.javaPrimitiveType)
            startMethod.invoke(null, confFile.absolutePath, tunFd)
            usingJni = true
            true
        }.getOrDefault(false)

        if (jniLoaded) {
            LogRepository.i("HevTun", "hev-socks5-tunnel started via JNI (fd=$tunFd)")
            return@withContext
        }

        // Fallback to native binary execution
        val bin = File(context.applicationInfo.nativeLibraryDir, "libhev-socks5-tunnel.so")
        if (!bin.exists() || !bin.canExecute()) {
            throw TunEngineException("libhev-socks5-tunnel.so not found at ${bin.absolutePath}")
        }

        val pb = ProcessBuilder(bin.absolutePath, confFile.absolutePath, "--tun-fd", tunFd.toString())
            .redirectErrorStream(true)
        process = pb.start()
        LogRepository.i("HevTun", "hev-socks5-tunnel process started (fd=$tunFd)")
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        if (usingJni) {
            usingJni = false
            runCatching {
                val clazz = Class.forName("hev.htproxy.TProxyService")
                clazz.getMethod("TProxyStopService").invoke(null)
            }
        }
        process?.let { proc ->
            runCatching {
                if (proc.isAlive) {
                    proc.destroy()
                    if (!proc.waitFor(1000, TimeUnit.MILLISECONDS) && proc.isAlive) {
                        proc.destroyForcibly()
                    }
                }
            }
        }
        process = null
        LogRepository.i("HevTun", "hev-socks5-tunnel stopped")
    }

    fun buildHevYaml(socksHost: String, socksPort: Int, profile: VpnProfile): String = """
        tunnel:
          mtu: ${profile.mtu}
          ipv4: 10.10.0.1
          ${if (profile.ipv6Enabled) "ipv6: 'fd00::1'" else ""}
        socks5:
          port: $socksPort
          address: $socksHost
          udp: 'udp'
        misc:
          task-stack-size: ${profile.tcpBufferKb * 1024}
          connect-timeout: 5000
          read-write-timeout: 60000
          log-level: warn
    """.trimIndent()
}

class TunEngineManager(private val context: Context) {
    private val singBox = SingBoxTunEngine(context)
    private val hev = HevSocks5TunEngine(context)

    @Volatile
    private var activeEngine: TunEngine? = null

    suspend fun startTun(
        tunInterface: ParcelFileDescriptor,
        socksPort: Int,
        profile: VpnProfile,
        preference: TunEnginePreference
    ): Result<TunEngineType> {
        stopTun()
        val fd = tunInterface.fd
        val enginesToTry: List<TunEngine> = when (preference) {
            TunEnginePreference.AUTO_SINGBOX_FALLBACK_HEV -> listOf(singBox, hev)
            TunEnginePreference.SINGBOX_ONLY -> listOf(singBox)
            TunEnginePreference.HEV_SOCKS5_ONLY -> listOf(hev)
        }

        var lastError: Throwable? = null
        for ((index, engine) in enginesToTry.withIndex()) {
            try {
                engine.start(fd, "127.0.0.1", socksPort, profile)
                activeEngine = engine
                if (index > 0) {
                    LogRepository.w("TunManager", "Default TUN failed (${lastError?.message}); fell back to ${engine.type}")
                }
                return Result.success(engine.type)
            } catch (e: Throwable) {
                lastError = e
                LogRepository.w("TunManager", "TUN engine ${engine.type} failed to start: ${e.message}")
                runCatching { engine.stop() }
            }
        }
        return Result.failure(lastError ?: TunEngineException("All TUN engines failed"))
    }

    suspend fun stopTun() {
        val current = activeEngine
        activeEngine = null
        runCatching { current?.stop() }
    }
}
