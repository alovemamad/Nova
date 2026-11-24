package app.novavpn.core.process

import app.novavpn.core.SessionProxyAuth
import app.novavpn.domain.model.AetherProtocol
import app.novavpn.domain.model.AppSettings
import app.novavpn.domain.model.ObfuscationProfile
import app.novavpn.domain.model.VpnProfile
import app.novavpn.domain.routing.RuleKind
import app.novavpn.domain.routing.RoutingRuleParser
import app.novavpn.domain.validation.InputValidators
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

interface CoreAdapter {
    val id: String
    val requiredBinaryNames: List<String>
    fun isReadinessLine(line: String): Boolean
    fun extractEndpoint(line: String): String? = null
}

/**
 * Assumed CLI contract for Aether (`libaether.so`):
 * - `--bind <host:port>` SOCKS5 listen address
 * - `--http-bind <host:port>` optional HTTP proxy listen address
 * - `--socks-user <user> --socks-pass <pass>` SOCKS5 authentication
 * - `--http-user <user> --http-pass <pass>` HTTP proxy authentication (when `--http-bind` is set)
 * - `--protocol masque|wireguard|warp-in-masque|warp-in-warp|masque-in-masque`
 * - `--obfs auto|none|quic-tls|wg-noise|aggressive`
 * - `--endpoint <host:port>` OR `--scan --scan-level <1..3>`
 * - `--ipv4-only` | `--ipv6-only`
 * - `--mtu <576..1500>` `--tcp-buf <N>k`
 * - `--upstream <uri>`
 * - `--direct <cidr|domain>` (repeatable)
 * - Readiness stdout/stderr line: contains "socks5 listening" or "tunnel ready"
 */
object AetherCoreAdapter : CoreAdapter {
    override val id: String = "aether"
    override val requiredBinaryNames: List<String> = listOf("libaether.so")

    private val ENDPOINT_REGEX = Regex("""(\d{1,3}(?:\.\d{1,3}){3}:\d{2,5})""")

    fun buildArgs(
        nativeDir: File,
        bindAddress: String,
        httpBind: String?,
        auth: SessionProxyAuth,
        profile: VpnProfile,
        settings: AppSettings,
        useFreshScan: Boolean,
        extraArgs: List<String>,
        upstreamSocks: String?
    ): List<String> {
        val bin = File(nativeDir, "libaether.so").absolutePath
        val args = mutableListOf(bin)
        args.addAll(listOf("--bind", bindAddress))
        args.addAll(listOf("--socks-user", auth.username, "--socks-pass", auth.password))
        if (httpBind != null) {
            args.addAll(listOf("--http-bind", httpBind))
            args.addAll(listOf("--http-user", auth.username, "--http-pass", auth.password))
        }

        val protoFlag = when (profile.aetherProtocol) {
            AetherProtocol.MASQUE -> "masque"
            AetherProtocol.WIREGUARD -> "wireguard"
            AetherProtocol.WARP_IN_MASQUE -> "warp-in-masque"
            AetherProtocol.WARP_IN_WARP -> "warp-in-warp"
            AetherProtocol.MASQUE_IN_MASQUE -> "masque-in-masque"
        }
        args.addAll(listOf("--protocol", protoFlag))

        val obfsFlag = when (profile.obfuscation) {
            ObfuscationProfile.AUTO -> "auto"
            ObfuscationProfile.NONE -> "none"
            ObfuscationProfile.QUIC_TLS_CAMO -> "quic-tls"
            ObfuscationProfile.WIREGUARD_NOISE -> "wg-noise"
            ObfuscationProfile.AGGRESSIVE_DPI -> "aggressive"
        }
        args.addAll(listOf("--obfs", obfsFlag))

        val endpoint = when {
            profile.customEndpoint.isNotBlank() -> profile.customEndpoint.trim()
            !useFreshScan && settings.preferLastGoodGateway && settings.lastKnownGoodEndpoint.isNotBlank() ->
                settings.lastKnownGoodEndpoint.trim()
            else -> ""
        }
        if (endpoint.isNotBlank()) {
            args.addAll(listOf("--endpoint", endpoint))
        } else {
            args.add("--scan")
            args.addAll(listOf("--scan-level", profile.scanLevel.coerceIn(1, 3).toString()))
        }

        if (profile.ipv4Enabled && !profile.ipv6Enabled) {
            args.add("--ipv4-only")
        } else if (!profile.ipv4Enabled && profile.ipv6Enabled) {
            args.add("--ipv6-only")
        }

        args.addAll(listOf("--mtu", profile.mtu.coerceIn(576, 1500).toString()))
        args.addAll(listOf("--tcp-buf", "${profile.tcpBufferKb.coerceIn(16, 512)}k"))

        if (profile.upstreamProxyUri.isNotBlank()) {
            args.addAll(listOf("--upstream", profile.upstreamProxyUri.trim()))
        } else if (upstreamSocks != null) {
            args.addAll(listOf("--upstream", upstreamSocks))
        }

        val directParsed = RoutingRuleParser.parse(profile.directRules)
        directParsed.validRules.forEach { rule ->
            if (rule.kind == RuleKind.IP_CIDR || rule.kind == RuleKind.DOMAIN || rule.kind == RuleKind.DOMAIN_SUFFIX) {
                args.addAll(listOf("--direct", rule.rawLine))
            }
        }

        args.addAll(extraArgs)
        return args
    }

    override fun isReadinessLine(line: String): Boolean {
        val lower = line.lowercase()
        return lower.contains("socks5 listening") ||
            lower.contains("proxy listening") ||
            lower.contains("tunnel ready") ||
            lower.contains("listening on")
    }

    override fun extractEndpoint(line: String): String? {
        if (!line.contains("endpoint=", ignoreCase = true) && !line.contains("connected to ", ignoreCase = true)) {
            return null
        }
        return ENDPOINT_REGEX.find(line)?.groupValues?.get(1)
    }
}

/**
 * Assumed CLI contract for Psiphon wrapper (`libpsiphon.so`):
 * - Invoked as `libpsiphon.so -config <psiphon_config.json>`
 * - Config keys: `DataRootDirectory`, `LocalSocksProxyPort`, `ListenInterface`, optional `LocalHttpProxyPort`,
 *   optional `EgressRegion`, optional `UpstreamProxyUrl` (socks5://user:pass@host:port), `EmitDiagnosticNotices: true`
 * - Readiness line: `"SOCKS proxy listening"` OR diagnostic JSON containing `"Tunnels"` and `"count":1`
 */
object PsiphonCoreAdapter : CoreAdapter {
    override val id: String = "psiphon"
    override val requiredBinaryNames: List<String> = listOf("libpsiphon.so")

    private val json = Json { prettyPrint = true }

    fun writeConfigAndBuildArgs(
        nativeDir: File,
        workDir: File,
        socksListen: String,
        httpListen: String?,
        region: String,
        upstreamProxy: String?
    ): Pair<List<String>, File> {
        val dataDir = File(workDir, "psiphon_data").apply { mkdirs() }
        val file = File(workDir, "psiphon_config.json")
        val socksHost = socksListen.substringBeforeLast(':')
        val socksPort = socksListen.substringAfterLast(':').toInt()

        val obj = buildJsonObject {
            put("DataRootDirectory", dataDir.absolutePath)
            put("LocalSocksProxyPort", socksPort)
            put("ListenInterface", socksHost)
            if (httpListen != null) {
                put("LocalHttpProxyPort", httpListen.substringAfterLast(':').toInt())
            }
            if (region.isNotBlank() && region != "AUTO") {
                put("EgressRegion", region)
            }
            if (!upstreamProxy.isNullOrBlank()) {
                put("UpstreamProxyUrl", upstreamProxy)
            }
            put("EmitDiagnosticNotices", true)
        }
        file.writeText(json.encodeToString(obj))
        val args = listOf(File(nativeDir, "libpsiphon.so").absolutePath, "-config", file.absolutePath)
        return args to file
    }

    override fun isReadinessLine(line: String): Boolean {
        val lower = line.lowercase()
        return lower.contains("socks proxy listening") ||
            (lower.contains("\"noticetype\":\"tunnels\"") && lower.contains("\"count\":1")) ||
            (lower.contains("tunnels") && lower.contains("count: 1"))
    }
}

/**
 * Assumed CLI contract for Tor + Lyrebird (`libtor.so` + `liblyrebird.so`):
 * - `libtor.so -f <torrc>` runs the Tor daemon
 * - `liblyrebird.so` is invoked by Tor itself via `ClientTransportPlugin obfs4,webtunnel exec <liblyrebird.so>`
 * - Readiness line emitted by Tor: `"Bootstrapped 100%"`
 * Note: Tor routes fail cleanly with MissingBinary unless BOTH `libtor.so` and `liblyrebird.so` are present.
 */
object TorCoreAdapter : CoreAdapter {
    override val id: String = "tor"
    override val requiredBinaryNames: List<String> = listOf("libtor.so", "liblyrebird.so")

    fun writeTorrcAndBuildArgs(
        nativeDir: File,
        workDir: File,
        socksListen: String,
        auth: SessionProxyAuth,
        profile: VpnProfile,
        upstreamHostPort: String?,
        upstreamAuth: SessionProxyAuth?
    ): Pair<List<String>, File> {
        val stateDir = File(workDir, "tor_state").apply { mkdirs() }
        val torrc = File(workDir, "torrc")
        val torBin = File(nativeDir, "libtor.so").absolutePath
        val lyrebirdBin = File(nativeDir, "liblyrebird.so").absolutePath
        val validBridges = profile.torBridges.filter { InputValidators.validateTorBridgeLine(it).isValid }

        val lines = buildList {
            add("DataDirectory ${stateDir.absolutePath}")
            add("SocksPort $socksListen")
            // Isolate local clients with ephemeral session credentials via Tor's SOCKS auth or IsolateClientAddr
            add("IsolateSOCKSAuth 1")
            if (upstreamHostPort != null) {
                add("Socks5Proxy $upstreamHostPort")
                if (upstreamAuth != null) {
                    add("Socks5ProxyUsername ${upstreamAuth.username}")
                    add("Socks5ProxyPassword ${upstreamAuth.password}")
                }
            }
            if (profile.torUseBridges && validBridges.isNotEmpty()) {
                add("UseBridges 1")
                add("ClientTransportPlugin obfs4,webtunnel,snowflake,meek_lite exec $lyrebirdBin")
                validBridges.forEach { b ->
                    add("Bridge ${b.trim().removePrefix("Bridge ").trim()}")
                }
            }
            add("Log notice stdout")
        }
        torrc.writeText(lines.joinToString("\n"))
        // Only libtor.so is launched directly; Tor spawns liblyrebird.so when UseBridges is enabled
        val args = listOf(torBin, "-f", torrc.absolutePath)
        return args to torrc
    }

    override fun isReadinessLine(line: String): Boolean {
        val lower = line.lowercase()
        return lower.contains("bootstrapped 100%")
    }
}

/**
 * Assumed contract for hev-socks5-tunnel (JNI-only):
 * - Class: `hev.htproxy.TProxyService`
 * - Methods: `TProxyStartService(String configPath, int tunFd)` and `TProxyStopService()`
 * - Native library: `libhev-socks5-tunnel.so` loaded inside the JNI class.
 */
object HevCoreAdapter {
    const val JNI_CLASS_NAME = "hev.htproxy.TProxyService"
}
