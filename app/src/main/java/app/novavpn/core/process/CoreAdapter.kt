package app.novavpn.core.process

import app.novavpn.domain.model.AetherProtocol
import app.novavpn.domain.model.AppSettings
import app.novavpn.domain.model.DnsMode
import app.novavpn.domain.model.ObfuscationProfile
import app.novavpn.domain.model.RouteChain
import app.novavpn.domain.model.VpnProfile
import app.novavpn.domain.routing.RuleKind
import app.novavpn.domain.routing.RoutingRuleParser
import app.novavpn.domain.validation.InputValidators
import java.io.File

interface CoreAdapter {
    val id: String
    val requiredBinaryNames: List<String>
    fun isReadinessLine(line: String): Boolean
    fun extractEndpoint(line: String): String? = null
}

/**
 * Verified CLI contract for CluvexStudio/Aether v2.4.0 (`libaether.so`):
 * - `--config <path>` identity config path inside writable app files dir
 * - `--bind <host:port>` SOCKS5 listen address (no auth; loopback by default)
 * - `--http-proxy <host:port>` optional HTTP CONNECT listen address
 * - `--masque` | `--wg` | `--gool` | `--gool --gool-classic` | `--mim`
 * - `--h3` (default QUIC/HTTP3) or `--h2` (overridable via extra args)
 * - `--noize <off|light|firewall|balanced|gfw|aggressive>`
 * - `--peer <ip:port>` / `--wg-peer <ip:port>` / `--wiw-outer <ip:port>` / `--mim-outer <ip:port>`
 * - `--turbo` | `--balanced` | `--thorough`
 * - `--quick-reconnect` | `--no-quick-reconnect`
 * - `-4` | `-6` | `--dual`
 * - `--dns <ip[,ip]>` (inside-tunnel plain resolvers when plain IP DNS is configured)
 * - `--upstream <uri>`
 * - `--route-direct <comma-separated>` / `--route-block <comma-separated>`
 * - Built-in Psiphon orchestration: `--psiphon` / `--psiphon-only` with `--psiphon-bin`, `--psiphon-dir`, `--psiphon-bind`, `--psiphon-http`, `--psiphon-region`
 * - Built-in Arti Tor orchestration: `--tor` / `--tor-only` with `--tor-dir`, `--tor-bind`, `--tor-http`, `--tor-pt <proto=path>`, `--tor-bridge` / `--tor-bridges`
 * - Env: `AETHER_MASQUE_MTU=<576..1500>`
 */
object AetherCoreAdapter : CoreAdapter {
    override val id: String = "aether"
    override val requiredBinaryNames: List<String> = listOf("libaether.so")

    private val IPV4_ENDPOINT_REGEX = Regex("""(\d{1,3}(?:\.\d{1,3}){3}:\d{2,5})""")
    private val IPV6_ENDPOINT_REGEX = Regex("""(\[[0-9a-fA-F:]+]:\d{2,5})""")

    fun buildTunnelArgs(
        nativeDir: File,
        workDir: File,
        socksBindAddress: String,
        httpBindAddress: String?,
        profile: VpnProfile,
        settings: AppSettings,
        useFreshScan: Boolean,
        extraArgs: List<String>,
        routeChain: RouteChain,
        chainExitSocksBind: String? = null,
        chainExitHttpBind: String? = null
    ): List<String> {
        val bin = File(nativeDir, "libaether.so").absolutePath
        val configPath = File(workDir, "aether.toml").absolutePath
        val args = mutableListOf(bin, "--config", configPath)

        when (routeChain) {
            RouteChain.PSIPHON_ONLY -> {
                args.add("--psiphon-only")
                args.addAll(listOf("--bind", socksBindAddress))
                appendPsiphonArgs(
                    args = args,
                    nativeDir = nativeDir,
                    workDir = workDir,
                    profile = profile,
                    bindOverride = null,
                    httpBind = httpBindAddress
                )
                appendUpstream(args, profile)
                args.addAll(extraArgs)
                return args
            }

            RouteChain.TOR_ONLY -> {
                args.add("--tor-only")
                args.addAll(listOf("--bind", socksBindAddress))
                appendTorArgs(
                    args = args,
                    nativeDir = nativeDir,
                    workDir = workDir,
                    profile = profile,
                    bindOverride = null,
                    httpBind = httpBindAddress
                )
                appendUpstream(args, profile)
                args.addAll(extraArgs)
                return args
            }

            RouteChain.AETHER, RouteChain.SMART_AUTO -> {
                args.addAll(listOf("--bind", socksBindAddress))
                if (httpBindAddress != null) {
                    args.addAll(listOf("--http-proxy", httpBindAddress))
                }
            }

            RouteChain.AETHER_PSIPHON -> {
                args.addAll(listOf("--bind", socksBindAddress))
                args.add("--psiphon")
                appendPsiphonArgs(
                    args = args,
                    nativeDir = nativeDir,
                    workDir = workDir,
                    profile = profile,
                    bindOverride = chainExitSocksBind,
                    httpBind = chainExitHttpBind
                )
            }

            RouteChain.AETHER_TOR -> {
                args.addAll(listOf("--bind", socksBindAddress))
                args.add("--tor")
                appendTorArgs(
                    args = args,
                    nativeDir = nativeDir,
                    workDir = workDir,
                    profile = profile,
                    bindOverride = chainExitSocksBind,
                    httpBind = chainExitHttpBind
                )
            }
        }

        // Core WARP/MASQUE transport selection (explicit so interactive prompts never trigger)
        when (profile.aetherProtocol) {
            AetherProtocol.MASQUE -> {
                args.add("--masque")
                args.add("--h3")
            }
            AetherProtocol.WIREGUARD -> args.add("--wg")
            AetherProtocol.WARP_IN_MASQUE -> {
                args.add("--gool")
                args.add("--h3")
            }
            AetherProtocol.WARP_IN_WARP -> {
                args.add("--gool")
                args.add("--gool-classic")
            }
            AetherProtocol.MASQUE_IN_MASQUE -> {
                args.add("--mim")
                args.add("--h3")
            }
        }

        val noizeFlag = when (profile.obfuscation) {
            ObfuscationProfile.AUTO -> "balanced"
            ObfuscationProfile.NONE -> "off"
            ObfuscationProfile.QUIC_TLS_CAMO -> "light"
            ObfuscationProfile.WIREGUARD_NOISE -> "balanced"
            ObfuscationProfile.AGGRESSIVE_DPI -> "gfw"
        }
        args.addAll(listOf("--noize", noizeFlag))

        val explicitEndpoint = profile.customEndpoint.trim()
        if (explicitEndpoint.isNotBlank()) {
            when (profile.aetherProtocol) {
                AetherProtocol.WIREGUARD -> args.addAll(listOf("--wg-peer", explicitEndpoint))
                AetherProtocol.WARP_IN_WARP -> args.addAll(listOf("--wiw-outer", explicitEndpoint))
                AetherProtocol.MASQUE_IN_MASQUE -> args.addAll(listOf("--mim-outer", explicitEndpoint))
                AetherProtocol.MASQUE, AetherProtocol.WARP_IN_MASQUE -> args.addAll(listOf("--peer", explicitEndpoint))
            }
        } else if (!useFreshScan && settings.preferLastGoodGateway) {
            args.add("--quick-reconnect")
        } else {
            args.add("--no-quick-reconnect")
        }

        val scanFlag = when (profile.scanLevel.coerceIn(1, 3)) {
            1 -> "--turbo"
            3 -> "--thorough"
            else -> "--balanced"
        }
        args.add(scanFlag)

        when {
            profile.ipv4Enabled && profile.ipv6Enabled -> args.add("--dual")
            !profile.ipv4Enabled && profile.ipv6Enabled -> args.add("-6")
            else -> args.add("-4")
        }

        if (profile.dnsMode == DnsMode.PLAIN) {
            val dnsIps = listOf(profile.customDnsPrimary.trim(), profile.customDnsSecondary.trim())
                .filter { it.isNotBlank() && InputValidators.isValidIp(it) }
                .distinct()
            if (dnsIps.isNotEmpty()) {
                args.addAll(listOf("--dns", dnsIps.joinToString(",")))
            }
        }

        appendUpstream(args, profile)

        val directCsv = formatRoutingRulesCsv(profile.directRules)
        if (directCsv.isNotEmpty()) {
            args.addAll(listOf("--route-direct", directCsv))
        }
        val blockCsv = formatRoutingRulesCsv(profile.blockRules)
        if (blockCsv.isNotEmpty()) {
            args.addAll(listOf("--route-block", blockCsv))
        }

        args.addAll(extraArgs)
        return args
    }

    private fun appendUpstream(args: MutableList<String>, profile: VpnProfile) {
        val upstream = profile.upstreamProxyUri.trim()
        if (upstream.isNotBlank()) {
            args.addAll(listOf("--upstream", upstream))
        }
    }

    private fun appendPsiphonArgs(
        args: MutableList<String>,
        nativeDir: File,
        workDir: File,
        profile: VpnProfile,
        bindOverride: String?,
        httpBind: String?
    ) {
        val psiphonBin = File(nativeDir, "libpsiphon.so").absolutePath
        val psiphonDir = File(workDir, "psiphon_state").apply { mkdirs() }.absolutePath
        args.addAll(listOf("--psiphon-bin", psiphonBin))
        args.addAll(listOf("--psiphon-dir", psiphonDir))
        if (bindOverride != null) {
            args.addAll(listOf("--psiphon-bind", bindOverride))
        }
        if (httpBind != null) {
            args.addAll(listOf("--psiphon-http", httpBind))
        }
        val region = profile.psiphonRegion.trim()
        if (region.isNotBlank() && !region.equals("AUTO", ignoreCase = true)) {
            args.addAll(listOf("--psiphon-region", region))
        }
    }

    private fun appendTorArgs(
        args: MutableList<String>,
        nativeDir: File,
        workDir: File,
        profile: VpnProfile,
        bindOverride: String?,
        httpBind: String?
    ) {
        val lyrebirdBin = File(nativeDir, "liblyrebird.so").absolutePath
        val torDir = File(workDir, "tor_state").apply { mkdirs() }.absolutePath
        args.addAll(listOf("--tor-dir", torDir))
        if (bindOverride != null) {
            args.addAll(listOf("--tor-bind", bindOverride))
        }
        if (httpBind != null) {
            args.addAll(listOf("--tor-http", httpBind))
        }
        listOf("obfs4", "webtunnel", "snowflake", "meek_lite").forEach { ptProto ->
            args.addAll(listOf("--tor-pt", "$ptProto=$lyrebirdBin"))
        }
        if (profile.torUseBridges) {
            val validBridges = profile.torBridges
                .filter { InputValidators.validateTorBridgeLine(it).isValid }
                .map { it.trim().removePrefix("Bridge ").trim() }
            if (validBridges.isNotEmpty()) {
                validBridges.forEach { bridge ->
                    args.addAll(listOf("--tor-bridge", bridge))
                }
            } else {
                args.add("--tor-bridges")
            }
        }
    }

    internal fun formatRoutingRulesCsv(rawRules: String): String {
        val parsed = RoutingRuleParser.parse(rawRules)
        return parsed.validRules.mapNotNull { rule ->
            when (rule.kind) {
                RuleKind.IP_CIDR, RuleKind.DOMAIN, RuleKind.DOMAIN_SUFFIX -> rule.rawLine
                RuleKind.PORT -> "port:${rule.value}"
                RuleKind.GEOIP, RuleKind.GEOSITE -> if (rule.value == "private") "private" else null
            }
        }.distinct().joinToString(",")
    }

    override fun isReadinessLine(line: String): Boolean {
        val lower = line.lowercase()
        return lower.contains("socks5 server listening on") ||
            lower.contains("socks5 listening") ||
            lower.contains("tunnel ready")
    }

    fun isPsiphonReadinessLine(line: String): Boolean {
        val lower = line.lowercase()
        return lower.contains("psiphon is ready") ||
            lower.contains("socks proxy listening")
    }

    fun isTorReadinessLine(line: String): Boolean {
        val lower = line.lowercase()
        return lower.contains("tor is ready") ||
            lower.contains("bootstrapped 100%")
    }

    override fun extractEndpoint(line: String): String? {
        val lower = line.lowercase()
        val hasMarker = lower.contains("selected masque gateway") ||
            lower.contains("selected wireguard endpoint") ||
            lower.contains("using cloudflare edge") ||
            lower.contains("using saved endpoint") ||
            lower.contains("endpoint=") ||
            lower.contains("connected to ")
        if (!hasMarker) return null
        return IPV4_ENDPOINT_REGEX.find(line)?.groupValues?.get(1)
            ?: IPV6_ENDPOINT_REGEX.find(line)?.groupValues?.get(1)
    }
}

/**
 * hev-socks5-tunnel JNI adapter (`app/libs/hev-socks5-tunnel.aar` -> `libhev-socks5-tunnel.so`).
 */
object HevCoreAdapter {
    const val JNI_CLASS_NAME = "hev.htproxy.TProxyService"
}
