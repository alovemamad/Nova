package app.novavpn.core.process

import app.novavpn.domain.model.AetherProtocol
import app.novavpn.domain.model.AppSettings
import app.novavpn.domain.model.ObfuscationProfile
import app.novavpn.domain.model.RouteChain
import app.novavpn.domain.model.VpnMode
import app.novavpn.domain.model.VpnProfile
import app.novavpn.domain.routing.RuleKind
import app.novavpn.domain.routing.RoutingRuleParser
import app.novavpn.domain.validation.InputValidators
import java.io.File

data class BuiltLaunchPlan(
    val effectiveRoute: RouteChain,
    val aetherArgs: List<String>?,
    val psiphonArgs: List<String>?,
    val lyrebirdArgs: List<String>?,
    val finalSocksPort: Int,
    val finalHttpPort: Int?,
    val generatedConfigFiles: List<File>
)

object AetherCommandBuilder {

    fun build(
        nativeDir: File,
        workDir: File,
        profile: VpnProfile,
        settings: AppSettings,
        resolvedRoute: RouteChain,
        useFreshScan: Boolean
    ): Result<BuiltLaunchPlan> = runCatching {
        val socksPort = settings.localSocksPort
        require(InputValidators.validatePort(socksPort).isValid) { "Invalid local SOCKS5 port: $socksPort" }

        val httpPort = if (settings.vpnMode == VpnMode.PROXY_ONLY) settings.localHttpPort else null
        if (httpPort != null) {
            require(InputValidators.validatePort(httpPort).isValid) { "Invalid local HTTP port: $httpPort" }
            require(httpPort != socksPort) { "SOCKS5 and HTTP ports must be distinct" }
        }

        if (profile.customEndpoint.isNotBlank()) {
            val check = InputValidators.validateEndpoint(profile.customEndpoint, allowEmpty = false)
            require(check.isValid) { (check as? app.novavpn.domain.validation.ValidationResult.Invalid)?.reason ?: "Invalid endpoint" }
        }

        if (profile.upstreamProxyUri.isNotBlank()) {
            val check = InputValidators.validateUpstreamProxy(profile.upstreamProxyUri)
            require(check.isValid) { (check as? app.novavpn.domain.validation.ValidationResult.Invalid)?.reason ?: "Invalid upstream proxy" }
        }

        val extraArgs = InputValidators.parseAndValidateExtraArgs(profile.extraCoreArgs).getOrThrow()
        val bindHost = if (settings.shareProxyOnLan) "0.0.0.0" else "127.0.0.1"

        val generatedFiles = mutableListOf<File>()
        val aetherBin = File(nativeDir, "libaether.so").absolutePath
        val psiphonBin = File(nativeDir, "libpsiphon.so").absolutePath
        val lyrebirdBin = File(nativeDir, "liblyrebird.so").absolutePath

        val aetherInternalPort = socksPort + 20
        val psiphonInternalPort = socksPort + 21

        when (resolvedRoute) {
            RouteChain.AETHER, RouteChain.SMART_AUTO -> {
                val args = buildAetherArgs(
                    bin = aetherBin,
                    bindAddress = "$bindHost:$socksPort",
                    httpBind = httpPort?.let { "$bindHost:$it" },
                    profile = profile,
                    settings = settings,
                    useFreshScan = useFreshScan,
                    extraArgs = extraArgs,
                    upstreamSocks = null
                )
                BuiltLaunchPlan(resolvedRoute, args, null, null, socksPort, httpPort, generatedFiles)
            }

            RouteChain.PSIPHON_ONLY -> {
                val psiphonConfig = writePsiphonConfig(workDir, "$bindHost:$socksPort", httpPort?.let { "$bindHost:$it" }, profile.psiphonRegion, null)
                generatedFiles.add(psiphonConfig)
                val args = listOf(psiphonBin, "-config", psiphonConfig.absolutePath)
                BuiltLaunchPlan(resolvedRoute, null, args, null, socksPort, httpPort, generatedFiles)
            }

            RouteChain.AETHER_PSIPHON -> {
                val aetherArgs = buildAetherArgs(
                    bin = aetherBin,
                    bindAddress = "127.0.0.1:$aetherInternalPort",
                    httpBind = null,
                    profile = profile,
                    settings = settings,
                    useFreshScan = useFreshScan,
                    extraArgs = extraArgs,
                    upstreamSocks = null
                )
                val psiphonConfig = writePsiphonConfig(
                    workDir = workDir,
                    socksListen = "$bindHost:$socksPort",
                    httpListen = httpPort?.let { "$bindHost:$it" },
                    region = profile.psiphonRegion,
                    upstreamProxy = "socks5://127.0.0.1:$aetherInternalPort"
                )
                generatedFiles.add(psiphonConfig)
                val psiphonArgs = listOf(psiphonBin, "-config", psiphonConfig.absolutePath)
                BuiltLaunchPlan(resolvedRoute, aetherArgs, psiphonArgs, null, socksPort, httpPort, generatedFiles)
            }

            RouteChain.TOR_ONLY -> {
                val torArgs = buildLyrebirdArgs(lyrebirdBin, workDir, "$bindHost:$socksPort", profile, upstreamSocks = null, generatedFiles)
                BuiltLaunchPlan(resolvedRoute, null, null, torArgs, socksPort, httpPort, generatedFiles)
            }

            RouteChain.AETHER_TOR -> {
                val aetherArgs = buildAetherArgs(
                    bin = aetherBin,
                    bindAddress = "127.0.0.1:$aetherInternalPort",
                    httpBind = null,
                    profile = profile,
                    settings = settings,
                    useFreshScan = useFreshScan,
                    extraArgs = extraArgs,
                    upstreamSocks = null
                )
                val torArgs = buildLyrebirdArgs(
                    lyrebirdBin,
                    workDir,
                    "$bindHost:$socksPort",
                    profile,
                    upstreamSocks = "127.0.0.1:$aetherInternalPort",
                    generatedFiles
                )
                BuiltLaunchPlan(resolvedRoute, aetherArgs, null, torArgs, socksPort, httpPort, generatedFiles)
            }
        }
    }

    private fun buildAetherArgs(
        bin: String,
        bindAddress: String,
        httpBind: String?,
        profile: VpnProfile,
        settings: AppSettings,
        useFreshScan: Boolean,
        extraArgs: List<String>,
        upstreamSocks: String?
    ): List<String> {
        val args = mutableListOf(bin)
        args.addAll(listOf("--bind", bindAddress))
        if (httpBind != null) {
            args.addAll(listOf("--http-bind", httpBind))
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

    private fun writePsiphonConfig(
        workDir: File,
        socksListen: String,
        httpListen: String?,
        region: String,
        upstreamProxy: String?
    ): File {
        val dataDir = File(workDir, "psiphon_data").apply { mkdirs() }
        val file = File(workDir, "psiphon_config.json")
        val sb = StringBuilder()
        sb.appendLine("{")
        sb.appendLine("""  "DataRootDirectory": "${dataDir.absolutePath}",""")
        sb.appendLine("""  "LocalSocksProxyPort": ${socksListen.substringAfterLast(':')},""")
        sb.appendLine("""  "ListenInterface": "${socksListen.substringBeforeLast(':')}", """)
        if (httpListen != null) {
            sb.appendLine("""  "LocalHttpProxyPort": ${httpListen.substringAfterLast(':')},""")
        }
        if (region.isNotBlank() && region != "AUTO") {
            sb.appendLine("""  "EgressRegion": "$region",""")
        }
        if (!upstreamProxy.isNullOrBlank()) {
            sb.appendLine("""  "UpstreamProxyUrl": "$upstreamProxy",""")
        }
        sb.appendLine("""  "EmitDiagnosticNotices": true""")
        sb.appendLine("}")
        file.writeText(sb.toString())
        return file
    }

    private fun buildLyrebirdArgs(
        lyrebirdBin: String,
        workDir: File,
        socksListen: String,
        profile: VpnProfile,
        upstreamSocks: String?,
        generatedFiles: MutableList<File>
    ): List<String> {
        val stateDir = File(workDir, "lyrebird_state").apply { mkdirs() }
        val confFile = File(workDir, "lyrebird_bridges.conf")
        val validBridges = profile.torBridges.filter { InputValidators.validateTorBridgeLine(it).isValid }
        val lines = buildList {
            add("SocksListenAddress $socksListen")
            add("StateDirectory ${stateDir.absolutePath}")
            if (upstreamSocks != null) {
                add("Socks5Proxy $upstreamSocks")
            }
            if (profile.torUseBridges && validBridges.isNotEmpty()) {
                add("UseBridges 1")
                validBridges.forEach { b ->
                    add("Bridge ${b.trim().removePrefix("Bridge ").trim()}")
                }
            }
        }
        confFile.writeText(lines.joinToString("\n"))
        generatedFiles.add(confFile)

        val args = mutableListOf(lyrebirdBin, "-config", confFile.absolutePath, "-socks", socksListen)
        if (upstreamSocks != null) {
            args.addAll(listOf("-upstream", upstreamSocks))
        }
        return args
    }
}
