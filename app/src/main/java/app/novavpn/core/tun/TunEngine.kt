package app.novavpn.core.tun

import android.content.Context
import android.os.ParcelFileDescriptor
import app.novavpn.core.SessionProxyAuth
import app.novavpn.core.process.HevCoreAdapter
import app.novavpn.data.log.LogRepository
import app.novavpn.domain.model.DnsMode
import app.novavpn.domain.model.TunEnginePreference
import app.novavpn.domain.model.TunEngineType
import app.novavpn.domain.model.VpnProfile
import app.novavpn.domain.routing.RuleKind
import app.novavpn.domain.routing.RoutingRuleParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

class TunEngineException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface TunEngine {
    val type: TunEngineType
    suspend fun start(
        tunFd: Int,
        socksHost: String,
        socksPort: Int,
        auth: SessionProxyAuth,
        profile: VpnProfile,
        protectFd: (Int) -> Boolean
    )
    suspend fun stop()
}

/**
 * Default TUN engine backed by sing-box (libbox.aar, gVisor stack).
 * Uses reflection against io.nekohasekai.libbox.* so the Kotlin codebase compiles cleanly
 * without libbox.aar, while providing a functional PlatformInterface adapter.
 */
class SingBoxTunEngine(private val context: Context) : TunEngine {
    override val type: TunEngineType = TunEngineType.SING_BOX_LIBBOX

    @Volatile
    private var boxServiceInstance: Any? = null

    override suspend fun start(
        tunFd: Int,
        socksHost: String,
        socksPort: Int,
        auth: SessionProxyAuth,
        profile: VpnProfile,
        protectFd: (Int) -> Boolean
    ) = withContext(Dispatchers.IO) {
        val libboxClass = try {
            Class.forName("io.nekohasekai.libbox.Libbox")
        } catch (e: ClassNotFoundException) {
            throw TunEngineException("libbox.aar is not bundled in app/libs/", e)
        }

        val configJson = buildSingBoxConfig(socksHost, socksPort, auth, profile)
        val configFile = File(context.filesDir, "nova_core/singbox_tun.json").apply {
            parentFile?.mkdirs()
            writeText(configJson)
        }
        LogRepository.d("SingBoxTun", "Starting libbox gVisor TUN on fd=$tunFd -> $socksHost:$socksPort")

        try {
            val platformInterfaceClass = Class.forName("io.nekohasekai.libbox.PlatformInterface")
            val newServiceMethod = libboxClass.getMethod("newService", String::class.java, platformInterfaceClass)

            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                platformInterfaceClass.classLoader,
                arrayOf(platformInterfaceClass)
            ) { _, method, args ->
                when (method.name) {
                    "openTun" -> tunFd
                    "autoDetectInterfaceControl", "protect" -> {
                        val fd = (args?.firstOrNull() as? Int) ?: -1
                        val ok = if (fd >= 0) protectFd(fd) else false
                        if (method.returnType == Boolean::class.javaPrimitiveType) ok else Unit
                    }
                    "usePlatformAutoDetectInterfaceControl" -> true
                    "useProcFS" -> false
                    "underNetworkExtension", "includeAllNetworks" -> false
                    "findConnectionOwner" -> 0
                    "packageNameByUid" -> ""
                    "uidByPackageName" -> 0
                    else -> when (method.returnType) {
                        Boolean::class.javaPrimitiveType -> false
                        Int::class.javaPrimitiveType -> 0
                        Long::class.javaPrimitiveType -> 0L
                        String::class.java -> ""
                        Void.TYPE -> Unit
                        else -> null
                    }
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

    companion object {
        private val json = Json { prettyPrint = true }

        /**
         * Builds a modern sing-box 1.12+ JSON configuration using kotlinx.serialization.json
         * (no string interpolation or deprecated `block`/`dns` outbounds or inbound `sniff`).
         */
        fun buildSingBoxConfig(
            socksHost: String,
            socksPort: Int,
            auth: SessionProxyAuth,
            profile: VpnProfile
        ): String {
            val remoteDnsServer = buildRemoteDnsServer(profile)
            val blockRules = RoutingRuleParser.parse(profile.blockRules).validRules
            val directRules = RoutingRuleParser.parse(profile.directRules).validRules

            val routeRules = buildJsonArray {
                // Current sing-box protocol sniffing + DNS hijacking via route action rules
                add(buildJsonObject { put("action", "sniff") })
                add(buildJsonObject {
                    put("protocol", "dns")
                    put("action", "hijack-dns")
                })

                if (blockRules.isNotEmpty()) {
                    addRuleGroup(blockRules, action = "reject", outbound = null)
                }
                if (directRules.isNotEmpty()) {
                    addRuleGroup(directRules, action = "route", outbound = "direct")
                }
            }

            val root = buildJsonObject {
                put("log", buildJsonObject { put("level", "warn") })
                put("dns", buildJsonObject {
                    put("servers", buildJsonArray {
                        add(remoteDnsServer)
                        add(buildJsonObject {
                            put("tag", "local")
                            put("type", "local")
                        })
                    })
                    put("final", "remote")
                    put("strategy", if (profile.ipv6Enabled) "prefer_ipv4" else "ipv4_only")
                })
                put("inbounds", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "tun")
                        put("tag", "tun-in")
                        put("interface_name", "tun0")
                        put("address", buildJsonArray {
                            add("10.10.0.1/24")
                            if (profile.ipv6Enabled) add("fd00::1/64")
                        })
                        put("mtu", profile.mtu)
                        put("stack", "gvisor")
                        put("auto_route", false)
                    })
                })
                put("outbounds", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "socks")
                        put("tag", "proxy")
                        put("server", socksHost)
                        put("server_port", socksPort)
                        put("version", "5")
                        put("username", auth.username)
                        put("password", auth.password)
                    })
                    add(buildJsonObject {
                        put("type", "direct")
                        put("tag", "direct")
                    })
                })
                put("route", buildJsonObject {
                    put("rules", routeRules)
                    put("final", "proxy")
                    put("auto_detect_interface", false)
                })
            }
            return json.encodeToString(root)
        }

        private fun buildRemoteDnsServer(profile: VpnProfile): JsonObject {
            val raw = profile.customDnsPrimary.trim()
            return when (profile.dnsMode) {
                DnsMode.DOH -> {
                    val hostPortPath = raw.removePrefix("https://").removePrefix("http://")
                        .ifBlank { "cloudflare-dns.com/dns-query" }
                    val hostPart = hostPortPath.substringBefore('/')
                    val host = hostPart.substringBefore(':')
                    val port = hostPart.substringAfter(':', "").toIntOrNull() ?: 443
                    val path = "/" + hostPortPath.substringAfter('/', "dns-query").trimStart('/')
                    buildJsonObject {
                        put("tag", "remote")
                        put("type", "https")
                        put("server", host)
                        put("server_port", port)
                        put("path", path)
                        put("detour", "proxy")
                    }
                }
                DnsMode.DOT -> {
                    val hostPart = raw.removePrefix("tls://").ifBlank { "1.1.1.1" }
                    val host = hostPart.substringBefore(':')
                    val port = hostPart.substringAfter(':', "").toIntOrNull() ?: 853
                    buildJsonObject {
                        put("tag", "remote")
                        put("type", "tls")
                        put("server", host)
                        put("server_port", port)
                        put("detour", "proxy")
                    }
                }
                DnsMode.PLAIN -> {
                    val hostPart = raw.ifBlank { "1.1.1.1" }
                    val host = hostPart.substringBefore(':')
                    val port = hostPart.substringAfter(':', "").toIntOrNull() ?: 53
                    buildJsonObject {
                        put("tag", "remote")
                        put("type", "udp")
                        put("server", host)
                        put("server_port", port)
                        put("detour", "proxy")
                    }
                }
            }
        }

        private fun kotlinx.serialization.json.JsonArrayBuilder.addRuleGroup(
            rules: List<app.novavpn.domain.routing.ParsedRoutingRule>,
            action: String,
            outbound: String?
        ) {
            val domains = rules.filter { it.kind == RuleKind.DOMAIN }.map { JsonPrimitive(it.value) }
            val suffixes = rules.filter { it.kind == RuleKind.DOMAIN_SUFFIX }.map { JsonPrimitive(it.value) }
            val cidrs = rules.filter { it.kind == RuleKind.IP_CIDR }.map { JsonPrimitive(it.value) }
            val ports = rules.filter { it.kind == RuleKind.PORT }.mapNotNull { it.value.toIntOrNull()?.let(::JsonPrimitive) }

            if (domains.isNotEmpty() || suffixes.isNotEmpty()) {
                add(buildJsonObject {
                    if (domains.isNotEmpty()) put("domain", JsonArray(domains))
                    if (suffixes.isNotEmpty()) put("domain_suffix", JsonArray(suffixes))
                    put("action", action)
                    if (outbound != null) put("outbound", outbound)
                })
            }
            if (cidrs.isNotEmpty()) {
                add(buildJsonObject {
                    put("ip_cidr", JsonArray(cidrs))
                    put("action", action)
                    if (outbound != null) put("outbound", outbound)
                })
            }
            if (ports.isNotEmpty()) {
                add(buildJsonObject {
                    put("port", JsonArray(ports))
                    put("action", action)
                    if (outbound != null) put("outbound", outbound)
                })
            }
        }
    }
}

/**
 * Automatic fallback TUN engine backed strictly by hev-socks5-tunnel JNI (`hev.htproxy.TProxyService`).
 * Includes `mapdns` so virtual DNS at `10.10.0.2:53` resolves over SOCKS5 without leaking.
 */
class HevSocks5TunEngine(private val context: Context) : TunEngine {
    override val type: TunEngineType = TunEngineType.HEV_SOCKS5_TUNNEL

    @Volatile
    private var usingJni = false

    override suspend fun start(
        tunFd: Int,
        socksHost: String,
        socksPort: Int,
        auth: SessionProxyAuth,
        profile: VpnProfile,
        protectFd: (Int) -> Boolean
    ) = withContext(Dispatchers.IO) {
        val confFile = File(context.filesDir, "nova_core/hev_socks5.yml").apply {
            parentFile?.mkdirs()
            writeText(buildHevYaml(socksHost, socksPort, auth, profile))
        }

        val clazz = try {
            Class.forName(HevCoreAdapter.JNI_CLASS_NAME)
        } catch (e: ClassNotFoundException) {
            throw TunEngineException("hev-socks5-tunnel JNI wrapper (${HevCoreAdapter.JNI_CLASS_NAME}) is not bundled", e)
        }

        try {
            val startMethod = clazz.getMethod("TProxyStartService", String::class.java, Int::class.javaPrimitiveType)
            startMethod.invoke(null, confFile.absolutePath, tunFd)
            usingJni = true
            LogRepository.i("HevTun", "hev-socks5-tunnel started via JNI (fd=$tunFd)")
        } catch (e: Throwable) {
            usingJni = false
            throw TunEngineException("hev-socks5-tunnel JNI start failed: ${e.cause?.message ?: e.message}", e)
        }
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        if (!usingJni) return@withContext
        usingJni = false
        runCatching {
            val clazz = Class.forName(HevCoreAdapter.JNI_CLASS_NAME)
            clazz.getMethod("TProxyStopService").invoke(null)
        }
        LogRepository.i("HevTun", "hev-socks5-tunnel stopped")
    }

    companion object {
        fun buildHevYaml(
            socksHost: String,
            socksPort: Int,
            auth: SessionProxyAuth,
            profile: VpnProfile
        ): String = buildString {
            appendLine("tunnel:")
            appendLine("  mtu: ${profile.mtu}")
            appendLine("  ipv4: 10.10.0.1")
            if (profile.ipv6Enabled) {
                appendLine("  ipv6: 'fd00::1'")
            }
            appendLine("socks5:")
            appendLine("  port: $socksPort")
            appendLine("  address: $socksHost")
            appendLine("  username: '${auth.username}'")
            appendLine("  password: '${auth.password}'")
            appendLine("  udp: 'udp'")
            appendLine("mapdns:")
            appendLine("  address: 10.10.0.2")
            appendLine("  port: 53")
            appendLine("  network: 240.0.0.0")
            appendLine("  netmask: 240.0.0.0")
            appendLine("  cache-size: 10000")
            appendLine("misc:")
            appendLine("  task-stack-size: ${profile.tcpBufferKb * 1024}")
            appendLine("  connect-timeout: 5000")
            appendLine("  read-write-timeout: 60000")
            appendLine("  log-level: warn")
        }
    }
}

class TunEngineManager(private val context: Context) {
    private val singBox = SingBoxTunEngine(context)
    private val hev = HevSocks5TunEngine(context)

    @Volatile
    private var activeEngine: TunEngine? = null

    suspend fun startTun(
        tunInterface: ParcelFileDescriptor,
        socksPort: Int,
        auth: SessionProxyAuth,
        profile: VpnProfile,
        preference: TunEnginePreference,
        protectFd: (Int) -> Boolean
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
                engine.start(fd, "127.0.0.1", socksPort, auth, profile, protectFd)
                activeEngine = engine
                if (index > 0) {
                    LogRepository.w("TunManager", "Default TUN failed (${lastError?.message}); fell back to ${engine.type}")
                }
                return Result.success(engine.type)
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
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
        if (current != null) {
            try {
                current.stop()
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (_: Throwable) {
            }
        }
    }
}
