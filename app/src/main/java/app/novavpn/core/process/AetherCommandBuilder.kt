package app.novavpn.core.process

import app.novavpn.core.SessionProxyAuth
import app.novavpn.domain.model.AetherProtocol
import app.novavpn.domain.model.AppSettings
import app.novavpn.domain.model.RouteChain
import app.novavpn.domain.model.VpnMode
import app.novavpn.domain.model.VpnProfile
import app.novavpn.domain.validation.InputValidators
import app.novavpn.domain.validation.ValidationResult
import java.io.File

data class LaunchStage(
    val adapter: CoreAdapter,
    val args: List<String>,
    val preflightBinaries: List<File>,
    val envVars: Map<String, String> = emptyMap(),
    val readinessTimeoutMs: Long = 45_000L,
    val readinessMatcher: ((String) -> Boolean)? = null,
    /**
     * Lines that prove the core is past discovery and is validating a chosen gateway. When one
     * appears, the readiness deadline is pushed out (once, capped) so a nearly-finished attempt
     * is not killed by a short fast-path timeout.
     */
    val progressMatcher: ((String) -> Boolean)? = null,
    val progressExtensionMs: Long = 0L
)

data class BuiltLaunchPlan(
    val effectiveRoute: RouteChain,
    val stages: List<LaunchStage>,
    val finalSocksPort: Int,
    val finalHttpPort: Int?,
    val sessionAuth: SessionProxyAuth?,
    val generatedConfigFiles: List<File>
) {
    val aetherArgs: List<String>? get() = stages.firstOrNull { it.adapter == AetherCoreAdapter }?.args
}

object AetherCommandBuilder {

    /** Readiness budget for attempts that reuse a cached/saved gateway (stale ones must fail fast). */
    const val FAST_CACHE_READINESS_MS = 16_000L

    /** Extra time granted once Aether reports it is validating the selected gateway. */
    const val PROGRESS_EXTENSION_MS = 8_000L

    private val ENV_TOKEN_REGEX = Regex("""^[A-Z][A-Z0-9_]*=.*""")

    fun build(
        nativeDir: File,
        workDir: File,
        profile: VpnProfile,
        settings: AppSettings,
        resolvedRoute: RouteChain,
        useFreshScan: Boolean,
        sessionAuth: SessionProxyAuth? = null,
        nonVpnDnsServers: List<String> = emptyList(),
        psiphonSlot: Int = 0,
        extraCompiledDirectRules: List<String> = emptyList(),
        extraCompiledBlockRules: List<String> = emptyList(),
        forceQuickReconnect: Boolean? = null,
        endpointOverride: String? = null,
        scanLevelOverride: Int? = null,
        readinessTimeoutOverrideMs: Long? = null
    ): Result<BuiltLaunchPlan> = runCatching {
        val socksPort = settings.localSocksPort
        require(InputValidators.validatePort(socksPort).isValid) { "Invalid local SOCKS5 port: $socksPort" }

        val httpPort = if (settings.vpnMode == VpnMode.PROXY_ONLY) settings.localHttpPort else null
        if (httpPort != null) {
            require(InputValidators.validatePort(httpPort).isValid) { "Invalid local HTTP port: $httpPort" }
            require(httpPort != socksPort) { "SOCKS5 and HTTP ports must be distinct" }
        }

        val effectiveCustomEndpoint = endpointOverride?.trim()?.takeIf { it.isNotBlank() } ?: profile.customEndpoint
        if (effectiveCustomEndpoint.isNotBlank()) {
            val check = InputValidators.validateEndpoint(effectiveCustomEndpoint, allowEmpty = false)
            require(check.isValid) { (check as? ValidationResult.Invalid)?.reason ?: "Invalid endpoint" }
        }

        if (profile.upstreamProxyUri.isNotBlank()) {
            val check = InputValidators.validateUpstreamProxy(profile.upstreamProxyUri)
            require(check.isValid) { (check as? ValidationResult.Invalid)?.reason ?: "Invalid upstream proxy" }
        }

        val parsedExtra = InputValidators.parseAndValidateExtraArgs(profile.extraCoreArgs).getOrThrow()
        val extraCliArgs = parsedExtra.filterNot { ENV_TOKEN_REGEX.matches(it) }
        val extraEnvVars = parsedExtra
            .filter { ENV_TOKEN_REGEX.matches(it) }
            .associate { it.substringBefore('=') to it.substringAfter('=') }

        val bindHost = if (settings.shareProxyOnLan) "0.0.0.0" else "127.0.0.1"
        val aetherInternalPort = if (socksPort + 20 <= 65535) socksPort + 20 else socksPort - 20
        val mtuValue = profile.mtu.coerceIn(576, 1500).toString()
        val validateSecsValue = profile.validateSecs.coerceIn(2, 30).toString()

        val aetherBin = File(nativeDir, "libaether.so")
        val psiphonBin = File(nativeDir, "libpsiphon.so")
        val lyrebirdBin = File(nativeDir, "liblyrebird.so")

        val baseEnv = buildMap {
            put("AETHER_LOG_LEVEL", "info")
            put("AETHER_TOR_PT", "obfs4=${lyrebirdBin.absolutePath};webtunnel=${lyrebirdBin.absolutePath}")
            putAll(extraEnvVars)
        }
        val aetherEnv = baseEnv + mapOf(
            "AETHER_MASQUE_MTU" to mtuValue,
            "AETHER_MASQUE_VALIDATE_SECS" to validateSecsValue,
            "AETHER_WG_VALIDATE_SECS" to validateSecsValue
        )

        val isMasqueFamily = profile.aetherProtocol == AetherProtocol.MASQUE ||
            profile.aetherProtocol == AetherProtocol.WARP_IN_MASQUE ||
            profile.aetherProtocol == AetherProtocol.MASQUE_IN_MASQUE
        val effectiveScanLevel = (scanLevelOverride ?: profile.scanLevel).coerceIn(1, 3)
        val baseAetherTimeoutMs = if (isMasqueFamily) 135_000L else 95_000L
        val computedAetherTimeoutMs = readinessTimeoutOverrideMs ?: when {
            forceQuickReconnect == true -> FAST_CACHE_READINESS_MS
            effectiveScanLevel >= 3 -> baseAetherTimeoutMs + 30_000L
            else -> baseAetherTimeoutMs
        }

        val stage: LaunchStage = when (resolvedRoute) {
            RouteChain.AETHER, RouteChain.SMART_AUTO -> {
                val args = AetherCoreAdapter.buildTunnelArgs(
                    nativeDir = nativeDir,
                    workDir = workDir,
                    socksBindAddress = "$bindHost:$socksPort",
                    httpBindAddress = httpPort?.let { "$bindHost:$it" },
                    profile = profile,
                    settings = settings,
                    useFreshScan = useFreshScan,
                    extraArgs = extraCliArgs,
                    routeChain = resolvedRoute,
                    nonVpnDnsServers = nonVpnDnsServers,
                    psiphonSlot = psiphonSlot,
                    extraCompiledDirectRules = extraCompiledDirectRules,
                    extraCompiledBlockRules = extraCompiledBlockRules,
                    forceQuickReconnect = forceQuickReconnect,
                    endpointOverride = endpointOverride,
                    scanLevelOverride = scanLevelOverride
                )
                LaunchStage(
                    adapter = AetherCoreAdapter,
                    args = args,
                    preflightBinaries = listOf(aetherBin),
                    envVars = aetherEnv,
                    readinessTimeoutMs = computedAetherTimeoutMs,
                    readinessMatcher = AetherCoreAdapter::isReadinessLine,
                    progressMatcher = AetherCoreAdapter::isValidationProgressLine,
                    progressExtensionMs = PROGRESS_EXTENSION_MS
                )
            }

            RouteChain.PSIPHON_ONLY -> {
                val args = AetherCoreAdapter.buildTunnelArgs(
                    nativeDir = nativeDir,
                    workDir = workDir,
                    socksBindAddress = "$bindHost:$socksPort",
                    httpBindAddress = httpPort?.let { "$bindHost:$it" },
                    profile = profile,
                    settings = settings,
                    useFreshScan = useFreshScan,
                    extraArgs = extraCliArgs,
                    routeChain = RouteChain.PSIPHON_ONLY,
                    nonVpnDnsServers = nonVpnDnsServers,
                    psiphonSlot = psiphonSlot
                )
                LaunchStage(
                    adapter = AetherCoreAdapter,
                    args = args,
                    preflightBinaries = listOf(aetherBin, psiphonBin),
                    envVars = baseEnv,
                    readinessTimeoutMs = readinessTimeoutOverrideMs ?: 95_000L,
                    readinessMatcher = AetherCoreAdapter::isPsiphonReadinessLine
                )
            }

            RouteChain.AETHER_PSIPHON -> {
                val args = AetherCoreAdapter.buildTunnelArgs(
                    nativeDir = nativeDir,
                    workDir = workDir,
                    socksBindAddress = "127.0.0.1:$aetherInternalPort",
                    httpBindAddress = null,
                    profile = profile,
                    settings = settings,
                    useFreshScan = useFreshScan,
                    extraArgs = extraCliArgs,
                    routeChain = RouteChain.AETHER_PSIPHON,
                    chainExitSocksBind = "$bindHost:$socksPort",
                    chainExitHttpBind = httpPort?.let { "$bindHost:$it" },
                    nonVpnDnsServers = nonVpnDnsServers,
                    psiphonSlot = psiphonSlot,
                    extraCompiledDirectRules = extraCompiledDirectRules,
                    extraCompiledBlockRules = extraCompiledBlockRules,
                    forceQuickReconnect = forceQuickReconnect,
                    endpointOverride = endpointOverride,
                    scanLevelOverride = scanLevelOverride
                )
                LaunchStage(
                    adapter = AetherCoreAdapter,
                    args = args,
                    preflightBinaries = listOf(aetherBin, psiphonBin),
                    envVars = aetherEnv,
                    readinessTimeoutMs = computedAetherTimeoutMs + 35_000L,
                    readinessMatcher = AetherCoreAdapter::isPsiphonReadinessLine
                )
            }

            RouteChain.TOR_ONLY -> {
                val args = AetherCoreAdapter.buildTunnelArgs(
                    nativeDir = nativeDir,
                    workDir = workDir,
                    socksBindAddress = "$bindHost:$socksPort",
                    httpBindAddress = httpPort?.let { "$bindHost:$it" },
                    profile = profile,
                    settings = settings,
                    useFreshScan = useFreshScan,
                    extraArgs = extraCliArgs,
                    routeChain = RouteChain.TOR_ONLY,
                    nonVpnDnsServers = nonVpnDnsServers,
                    psiphonSlot = psiphonSlot
                )
                LaunchStage(
                    adapter = AetherCoreAdapter,
                    args = args,
                    preflightBinaries = listOf(aetherBin, lyrebirdBin),
                    envVars = baseEnv,
                    readinessTimeoutMs = readinessTimeoutOverrideMs ?: 150_000L,
                    readinessMatcher = AetherCoreAdapter::isTorReadinessLine
                )
            }

            RouteChain.AETHER_TOR -> {
                val args = AetherCoreAdapter.buildTunnelArgs(
                    nativeDir = nativeDir,
                    workDir = workDir,
                    socksBindAddress = "127.0.0.1:$aetherInternalPort",
                    httpBindAddress = null,
                    profile = profile,
                    settings = settings,
                    useFreshScan = useFreshScan,
                    extraArgs = extraCliArgs,
                    routeChain = RouteChain.AETHER_TOR,
                    chainExitSocksBind = "$bindHost:$socksPort",
                    chainExitHttpBind = httpPort?.let { "$bindHost:$it" },
                    nonVpnDnsServers = nonVpnDnsServers,
                    psiphonSlot = psiphonSlot,
                    extraCompiledDirectRules = extraCompiledDirectRules,
                    extraCompiledBlockRules = extraCompiledBlockRules,
                    forceQuickReconnect = forceQuickReconnect,
                    endpointOverride = endpointOverride,
                    scanLevelOverride = scanLevelOverride
                )
                LaunchStage(
                    adapter = AetherCoreAdapter,
                    args = args,
                    preflightBinaries = listOf(aetherBin, lyrebirdBin),
                    envVars = aetherEnv,
                    readinessTimeoutMs = computedAetherTimeoutMs + 50_000L,
                    readinessMatcher = AetherCoreAdapter::isTorReadinessLine
                )
            }
        }

        BuiltLaunchPlan(
            effectiveRoute = resolvedRoute,
            stages = listOf(stage),
            finalSocksPort = socksPort,
            finalHttpPort = httpPort,
            sessionAuth = sessionAuth,
            generatedConfigFiles = listOfNotNull(
                File(workDir, "psiphon-config.json").takeIf { it.exists() },
                File(workDir, "tor-bridges.txt").takeIf { it.exists() }
            )
        )
    }
}
