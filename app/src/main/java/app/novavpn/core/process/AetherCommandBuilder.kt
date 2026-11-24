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
    val readinessMatcher: ((String) -> Boolean)? = null
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

    fun build(
        nativeDir: File,
        workDir: File,
        profile: VpnProfile,
        settings: AppSettings,
        resolvedRoute: RouteChain,
        useFreshScan: Boolean,
        sessionAuth: SessionProxyAuth? = null
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
            require(check.isValid) { (check as? ValidationResult.Invalid)?.reason ?: "Invalid endpoint" }
        }

        if (profile.upstreamProxyUri.isNotBlank()) {
            val check = InputValidators.validateUpstreamProxy(profile.upstreamProxyUri)
            require(check.isValid) { (check as? ValidationResult.Invalid)?.reason ?: "Invalid upstream proxy" }
        }

        val extraArgs = InputValidators.parseAndValidateExtraArgs(profile.extraCoreArgs).getOrThrow()
        val bindHost = if (settings.shareProxyOnLan) "0.0.0.0" else "127.0.0.1"
        val aetherInternalPort = if (socksPort + 20 <= 65535) socksPort + 20 else socksPort - 20
        val mtuValue = profile.mtu.coerceIn(576, 1500).toString()
        val aetherEnv = mapOf("AETHER_MASQUE_MTU" to mtuValue)

        val aetherBin = File(nativeDir, "libaether.so")
        val psiphonBin = File(nativeDir, "libpsiphon.so")
        val lyrebirdBin = File(nativeDir, "liblyrebird.so")

        val isMasqueFamily = profile.aetherProtocol == AetherProtocol.MASQUE ||
            profile.aetherProtocol == AetherProtocol.WARP_IN_MASQUE ||
            profile.aetherProtocol == AetherProtocol.MASQUE_IN_MASQUE
        val baseAetherTimeoutMs = if (isMasqueFamily) 135_000L else 95_000L
        val aetherTimeoutMs = if (profile.scanLevel >= 3) baseAetherTimeoutMs + 30_000L else baseAetherTimeoutMs

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
                    extraArgs = extraArgs,
                    routeChain = resolvedRoute
                )
                LaunchStage(
                    adapter = AetherCoreAdapter,
                    args = args,
                    preflightBinaries = listOf(aetherBin),
                    envVars = aetherEnv,
                    readinessTimeoutMs = aetherTimeoutMs,
                    readinessMatcher = AetherCoreAdapter::isReadinessLine
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
                    extraArgs = extraArgs,
                    routeChain = RouteChain.PSIPHON_ONLY
                )
                LaunchStage(
                    adapter = AetherCoreAdapter,
                    args = args,
                    preflightBinaries = listOf(aetherBin, psiphonBin),
                    envVars = emptyMap(),
                    readinessTimeoutMs = 75_000L,
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
                    extraArgs = extraArgs,
                    routeChain = RouteChain.AETHER_PSIPHON,
                    chainExitSocksBind = "$bindHost:$socksPort",
                    chainExitHttpBind = httpPort?.let { "$bindHost:$it" }
                )
                LaunchStage(
                    adapter = AetherCoreAdapter,
                    args = args,
                    preflightBinaries = listOf(aetherBin, psiphonBin),
                    envVars = aetherEnv,
                    readinessTimeoutMs = aetherTimeoutMs + 30_000L,
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
                    extraArgs = extraArgs,
                    routeChain = RouteChain.TOR_ONLY
                )
                LaunchStage(
                    adapter = AetherCoreAdapter,
                    args = args,
                    preflightBinaries = listOf(aetherBin, lyrebirdBin),
                    envVars = emptyMap(),
                    readinessTimeoutMs = 120_000L,
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
                    extraArgs = extraArgs,
                    routeChain = RouteChain.AETHER_TOR,
                    chainExitSocksBind = "$bindHost:$socksPort",
                    chainExitHttpBind = httpPort?.let { "$bindHost:$it" }
                )
                LaunchStage(
                    adapter = AetherCoreAdapter,
                    args = args,
                    preflightBinaries = listOf(aetherBin, lyrebirdBin),
                    envVars = aetherEnv,
                    readinessTimeoutMs = aetherTimeoutMs + 45_000L,
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
            generatedConfigFiles = emptyList()
        )
    }
}
