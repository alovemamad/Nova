package app.novavpn.core.tun

import app.novavpn.core.SessionProxyAuth
import app.novavpn.core.process.AetherCommandBuilder
import app.novavpn.core.process.BuiltLaunchPlan
import app.novavpn.core.process.CoreProcessManager
import app.novavpn.core.process.CoreStartResult
import app.novavpn.core.reconnect.ReconnectStateMachine
import app.novavpn.core.net.DiagnosticsEngine
import app.novavpn.data.store.SettingsRepository
import app.novavpn.domain.model.AppSettings
import app.novavpn.domain.model.DropReason
import app.novavpn.domain.model.RouteChain
import app.novavpn.domain.model.SuggestedFixAction
import app.novavpn.domain.model.VpnProfile

sealed class CoreProbeOutcome {
    data class Ready(
        val route: RouteChain,
        val plan: BuiltLaunchPlan,
        val socksPort: Int,
        val httpPort: Int?,
        val auth: SessionProxyAuth?,
        val initialPingMs: Int?
    ) : CoreProbeOutcome()

    data class Failure(
        val dropReason: DropReason,
        val errorMessage: String,
        val suggestedFix: SuggestedFixAction
    ) : CoreProbeOutcome()
}

/**
 * Shared route-selection, command-building, core launch, and HTTPS probing logic
 * used by both NovaVpnService (VPN mode) and VpnController (Proxy-Only mode)
 * to eliminate duplicated connect logic.
 */
class TunnelStartOrchestrator(
    private val settingsRepo: SettingsRepository,
    private val diagnostics: DiagnosticsEngine
) {
    fun resolveCandidateRoutes(
        profile: VpnProfile,
        settings: AppSettings,
        networkKey: String
    ): List<RouteChain> {
        return if (profile.routeChain == RouteChain.SMART_AUTO) {
            val cached = settings.bestRoutePerNetwork[networkKey]
            buildList {
                if (cached != null) add(cached)
                ReconnectStateMachine.SMART_AUTO_FALLBACK_ORDER.forEach { if (it !in this) add(it) }
            }
        } else {
            listOf(profile.routeChain)
        }
    }

    private data class LaunchCandidate(
        val route: RouteChain,
        val profileOverride: VpnProfile,
        val labelSuffix: String = ""
    )

    private fun buildLaunchCandidates(
        profile: VpnProfile,
        settings: AppSettings,
        networkKey: String
    ): List<LaunchCandidate> {
        val routes = resolveCandidateRoutes(profile, settings, networkKey)
        val supportsMasqueCarrier = profile.aetherProtocol in setOf(
            app.novavpn.domain.model.AetherProtocol.MASQUE,
            app.novavpn.domain.model.AetherProtocol.WARP_IN_MASQUE,
            app.novavpn.domain.model.AetherProtocol.MASQUE_IN_MASQUE
        )
        val out = mutableListOf<LaunchCandidate>()
        for (route in routes) {
            out.add(LaunchCandidate(route, profile))
            val usesAether = route != RouteChain.PSIPHON_ONLY && route != RouteChain.TOR_ONLY
            // Resilient carrier fallback inspired by 8vpn: if QUIC/H3 is blocked, try H2+Fragment (or H3 if H2 failed)
            if (usesAether && supportsMasqueCarrier && (profile.routeChain == RouteChain.SMART_AUTO || routes.size == 1)) {
                val alt = if (!profile.masqueOverH2) {
                    profile.copy(masqueOverH2 = true, tlsFragment = true)
                } else {
                    profile.copy(masqueOverH2 = false)
                }
                val suffix = if (alt.masqueOverH2) " (H2+Frag)" else " (H3)"
                out.add(LaunchCandidate(route, alt, suffix))
            }
        }
        return out
    }

    suspend fun startCoreAndProbe(
        coreManager: CoreProcessManager,
        settings: AppSettings,
        profile: VpnProfile,
        networkKey: String,
        useFreshScan: Boolean,
        isCancelled: () -> Boolean,
        onProbeProgress: (String) -> Unit,
        verifyHttpsAfterCoreStart: Boolean = true
    ): CoreProbeOutcome {
        val candidates = buildLaunchCandidates(profile, settings, networkKey)
        val bindHost = if (settings.shareProxyOnLan) "0.0.0.0" else "127.0.0.1"
        val auth: SessionProxyAuth? = null
        val nonVpnDns = coreManager.nonVpnDnsServers()

        var lastError = "Failed to establish tunnel core"
        var dropReason = DropReason.CORE_EXITED
        var suggestedFix = SuggestedFixAction.RUN_DIAGNOSTICS

        for (candidate in candidates) {
            val route = candidate.route
            if (isCancelled()) {
                return CoreProbeOutcome.Failure(
                    DropReason.USER_DISCONNECTED,
                    "Connection cancelled",
                    SuggestedFixAction.NONE
                )
            }
            if (profile.routeChain == RouteChain.SMART_AUTO || candidate.labelSuffix.isNotEmpty()) {
                onProbeProgress("Testing route: ${route.name}${candidate.labelSuffix}…")
            }

            val planResult = AetherCommandBuilder.build(
                nativeDir = coreManager.nativeDir(),
                workDir = coreManager.workDir(),
                profile = candidate.profileOverride,
                settings = settings,
                resolvedRoute = route,
                useFreshScan = useFreshScan,
                sessionAuth = auth,
                nonVpnDnsServers = nonVpnDns,
                psiphonSlot = coreManager.psiphonSlot
            )
            if (planResult.isFailure) {
                lastError = planResult.exceptionOrNull()?.message ?: "Invalid configuration"
                dropReason = DropReason.INVALID_CONFIG
                suggestedFix = SuggestedFixAction.APPLY_SAFE_FIX
                continue
            }

            val isSlowRoute = route in setOf(
                RouteChain.PSIPHON_ONLY,
                RouteChain.AETHER_PSIPHON,
                RouteChain.TOR_ONLY,
                RouteChain.AETHER_TOR
            )
            val probeTimeoutMs = if (isSlowRoute) 20_000L else 9_000L

            val plan = planResult.getOrThrow()
            when (val coreRes = coreManager.startPlan(plan, bindHost)) {
                is CoreStartResult.Ready -> {
                    if (coreRes.detectedEndpoint != null) {
                        settingsRepo.updateSettings { it.copy(lastKnownGoodEndpoint = coreRes.detectedEndpoint) }
                    }
                    var pingMs: Int? = null
                    if (verifyHttpsAfterCoreStart) {
                        val check = diagnostics.verifyRealHttpsConnectivity(coreRes.socksPort, plan.sessionAuth, probeTimeoutMs)
                        if (check.isFailure && candidates.size > 1) {
                            lastError = check.exceptionOrNull()?.message ?: "Route $route failed HTTPS verification"
                            coreManager.stopAll()
                            continue
                        }
                        pingMs = check.getOrNull()
                        if (profile.routeChain == RouteChain.SMART_AUTO && check.isSuccess) {
                            settingsRepo.updateSettings { s ->
                                s.copy(bestRoutePerNetwork = s.bestRoutePerNetwork + (networkKey to route))
                            }
                        }
                    }
                    return CoreProbeOutcome.Ready(
                        route = route,
                        plan = plan,
                        socksPort = coreRes.socksPort,
                        httpPort = coreRes.httpPort,
                        auth = plan.sessionAuth,
                        initialPingMs = pingMs
                    )
                }
                is CoreStartResult.MissingBinary -> {
                    return CoreProbeOutcome.Failure(
                        dropReason = DropReason.MISSING_BINARY,
                        errorMessage = "Native binary missing: ${coreRes.binaryName} (${coreRes.expectedPath}). Build native libs with scripts/build-native.sh.",
                        suggestedFix = SuggestedFixAction.NONE
                    )
                }
                is CoreStartResult.PortConflict -> {
                    return CoreProbeOutcome.Failure(
                        dropReason = DropReason.PORT_CONFLICT,
                        errorMessage = "Local port ${coreRes.port} is already bound by another app.",
                        suggestedFix = SuggestedFixAction.RESET_PORTS
                    )
                }
                is CoreStartResult.Failed -> {
                    lastError = coreRes.reason
                    dropReason = DropReason.CORE_EXITED
                    suggestedFix = SuggestedFixAction.RUN_DIAGNOSTICS
                }
            }
        }

        return CoreProbeOutcome.Failure(dropReason, lastError, suggestedFix)
    }
}
