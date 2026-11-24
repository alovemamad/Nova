package app.novavpn.core.process

import app.novavpn.core.SessionProxyAuth
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
    val preflightBinaries: List<File>
)

data class BuiltLaunchPlan(
    val effectiveRoute: RouteChain,
    val stages: List<LaunchStage>,
    val finalSocksPort: Int,
    val finalHttpPort: Int?,
    val sessionAuth: SessionProxyAuth,
    val generatedConfigFiles: List<File>
) {
    // Convenience accessors for tests & callers
    val aetherArgs: List<String>? get() = stages.firstOrNull { it.adapter == AetherCoreAdapter }?.args
    val psiphonArgs: List<String>? get() = stages.firstOrNull { it.adapter == PsiphonCoreAdapter }?.args
    val torArgs: List<String>? get() = stages.firstOrNull { it.adapter == TorCoreAdapter }?.args
}

object AetherCommandBuilder {

    fun build(
        nativeDir: File,
        workDir: File,
        profile: VpnProfile,
        settings: AppSettings,
        resolvedRoute: RouteChain,
        useFreshScan: Boolean,
        sessionAuth: SessionProxyAuth = SessionProxyAuth.generate()
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
        val generatedFiles = mutableListOf<File>()
        val stages = mutableListOf<LaunchStage>()

        val aetherInternalPort = if (socksPort + 20 <= 65535) socksPort + 20 else socksPort - 20

        fun stageBins(adapter: CoreAdapter): List<File> =
            adapter.requiredBinaryNames.map { File(nativeDir, it) }

        when (resolvedRoute) {
            RouteChain.AETHER, RouteChain.SMART_AUTO -> {
                val args = AetherCoreAdapter.buildArgs(
                    nativeDir = nativeDir,
                    bindAddress = "$bindHost:$socksPort",
                    httpBind = httpPort?.let { "$bindHost:$it" },
                    auth = sessionAuth,
                    profile = profile,
                    settings = settings,
                    useFreshScan = useFreshScan,
                    extraArgs = extraArgs,
                    upstreamSocks = null
                )
                stages.add(LaunchStage(AetherCoreAdapter, args, stageBins(AetherCoreAdapter)))
            }

            RouteChain.PSIPHON_ONLY -> {
                val (args, cfg) = PsiphonCoreAdapter.writeConfigAndBuildArgs(
                    nativeDir = nativeDir,
                    workDir = workDir,
                    socksListen = "$bindHost:$socksPort",
                    httpListen = httpPort?.let { "$bindHost:$it" },
                    region = profile.psiphonRegion,
                    upstreamProxy = profile.upstreamProxyUri.takeIf { it.isNotBlank() }
                )
                generatedFiles.add(cfg)
                stages.add(LaunchStage(PsiphonCoreAdapter, args, stageBins(PsiphonCoreAdapter)))
            }

            RouteChain.AETHER_PSIPHON -> {
                val aetherArgs = AetherCoreAdapter.buildArgs(
                    nativeDir = nativeDir,
                    bindAddress = "127.0.0.1:$aetherInternalPort",
                    httpBind = null,
                    auth = sessionAuth,
                    profile = profile,
                    settings = settings,
                    useFreshScan = useFreshScan,
                    extraArgs = extraArgs,
                    upstreamSocks = null
                )
                val upstreamUrl = "socks5://${sessionAuth.username}:${sessionAuth.password}@127.0.0.1:$aetherInternalPort"
                val (psiphonArgs, cfg) = PsiphonCoreAdapter.writeConfigAndBuildArgs(
                    nativeDir = nativeDir,
                    workDir = workDir,
                    socksListen = "$bindHost:$socksPort",
                    httpListen = httpPort?.let { "$bindHost:$it" },
                    region = profile.psiphonRegion,
                    upstreamProxy = upstreamUrl
                )
                generatedFiles.add(cfg)
                stages.add(LaunchStage(AetherCoreAdapter, aetherArgs, stageBins(AetherCoreAdapter)))
                stages.add(LaunchStage(PsiphonCoreAdapter, psiphonArgs, stageBins(PsiphonCoreAdapter)))
            }

            RouteChain.TOR_ONLY -> {
                val (torArgs, torrc) = TorCoreAdapter.writeTorrcAndBuildArgs(
                    nativeDir = nativeDir,
                    workDir = workDir,
                    socksListen = "$bindHost:$socksPort",
                    auth = sessionAuth,
                    profile = profile,
                    upstreamHostPort = null,
                    upstreamAuth = null
                )
                generatedFiles.add(torrc)
                stages.add(LaunchStage(TorCoreAdapter, torArgs, stageBins(TorCoreAdapter)))
            }

            RouteChain.AETHER_TOR -> {
                val aetherArgs = AetherCoreAdapter.buildArgs(
                    nativeDir = nativeDir,
                    bindAddress = "127.0.0.1:$aetherInternalPort",
                    httpBind = null,
                    auth = sessionAuth,
                    profile = profile,
                    settings = settings,
                    useFreshScan = useFreshScan,
                    extraArgs = extraArgs,
                    upstreamSocks = null
                )
                val (torArgs, torrc) = TorCoreAdapter.writeTorrcAndBuildArgs(
                    nativeDir = nativeDir,
                    workDir = workDir,
                    socksListen = "$bindHost:$socksPort",
                    auth = sessionAuth,
                    profile = profile,
                    upstreamHostPort = "127.0.0.1:$aetherInternalPort",
                    upstreamAuth = sessionAuth
                )
                generatedFiles.add(torrc)
                stages.add(LaunchStage(AetherCoreAdapter, aetherArgs, stageBins(AetherCoreAdapter)))
                stages.add(LaunchStage(TorCoreAdapter, torArgs, stageBins(TorCoreAdapter)))
            }
        }

        BuiltLaunchPlan(
            effectiveRoute = resolvedRoute,
            stages = stages,
            finalSocksPort = socksPort,
            finalHttpPort = httpPort,
            sessionAuth = sessionAuth,
            generatedConfigFiles = generatedFiles
        )
    }
}
