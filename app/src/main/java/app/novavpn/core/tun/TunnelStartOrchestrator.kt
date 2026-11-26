package app.novavpn.core.tun

import app.novavpn.core.SessionProxyAuth
import app.novavpn.core.net.DiagnosticsEngine
import app.novavpn.core.process.AetherCommandBuilder
import app.novavpn.core.process.BuiltLaunchPlan
import app.novavpn.core.process.CoreProcessManager
import app.novavpn.core.process.CoreStartResult
import app.novavpn.core.reconnect.ReconnectStateMachine
import app.novavpn.data.log.LogRepository
import app.novavpn.data.store.GeoResourceRepository
import app.novavpn.data.store.SettingsRepository
import app.novavpn.domain.model.AetherProtocol
import app.novavpn.domain.model.AppSettings
import app.novavpn.domain.model.CarrierHint
import app.novavpn.domain.model.DropReason
import app.novavpn.domain.model.RouteChain
import app.novavpn.domain.model.SuggestedFixAction
import app.novavpn.domain.model.VpnProfile
import app.novavpn.domain.routing.GeoDatParser

sealed class CoreProbeOutcome {
    data class Ready(
        val route: RouteChain,
        val plan: BuiltLaunchPlan,
        val socksPort: Int,
        val httpPort: Int?,
        val auth: SessionProxyAuth?,
        val initialPingMs: Int?,
        val compiledGeoRules: GeoDatParser.CompiledProfileGeoRules = GeoDatParser.CompiledProfileGeoRules(),
        val carrierLabel: String = "",
        val usedSavedEndpoint: Boolean = false,
        val usedQuickCache: Boolean = false,
        val winningCandidate: String = route.name
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
    private val diagnostics: DiagnosticsEngine,
    private val geoRepo: GeoResourceRepository? = null
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
        val forceQuickReconnect: Boolean,
        val endpointOverride: String? = null,
        val scanLevelOverride: Int? = null,
        val readinessTimeoutOverrideMs: Long? = null,
        val labelSuffix: String = ""
    )

    private fun buildLaunchCandidates(
        profile: VpnProfile,
        settings: AppSettings,
        networkKey: String,
        useFreshScan: Boolean
    ): List<LaunchCandidate> {
        val routes = resolveCandidateRoutes(profile, settings, networkKey)
        val supportsMasqueCarrier = profile.aetherProtocol in setOf(
            AetherProtocol.MASQUE,
            AetherProtocol.WARP_IN_MASQUE,
            AetherProtocol.MASQUE_IN_MASQUE
        )
        val isMasqueFamily = supportsMasqueCarrier

        val carrierKey = "$networkKey:${profile.aetherProtocol.name}"
        val cachedCarrier = settings.bestCarrierPerNetwork[carrierKey]
        val preferredProfile = if (supportsMasqueCarrier &&
            profile.customEndpoint.isBlank() &&
            cachedCarrier != null
        ) {
            profile.copy(
                masqueOverH2 = cachedCarrier.masqueOverH2,
                tlsFragment = cachedCarrier.tlsFragment
            )
        } else {
            profile
        }

        val canTrustQuickCache = !useFreshScan &&
            profile.customEndpoint.isBlank() &&
            settings.preferLastGoodGateway &&
            !settings.lastConnectFailed &&
            settings.lastSuccessfulNetworkKey == networkKey

        val usableSavedEndpoint = canTrustQuickCache &&
            settings.lastKnownGoodEndpoint.isNotBlank() &&
            settings.lastKnownGoodEndpointProtocol == profile.aetherProtocol &&
            settings.lastKnownGoodEndpointNetworkKey == networkKey

        val turboTimeoutMs = if (isMasqueFamily) 35_000L else 28_000L
        val out = mutableListOf<LaunchCandidate>()

        for ((idx, route) in routes.withIndex()) {
            val usesAether = route != RouteChain.PSIPHON_ONLY && route != RouteChain.TOR_ONLY
            val isChainSlow = route in setOf(RouteChain.PSIPHON_ONLY, RouteChain.AETHER_PSIPHON, RouteChain.TOR_ONLY, RouteChain.AETHER_TOR)

            if (idx == 0 && usesAether && profile.customEndpoint.isBlank()) {
                // Step 1: Fast cached attempt only when same network & last connect didn't fail (strict 10-12s cap!)
                if (usableSavedEndpoint) {
                    out.add(
                        LaunchCandidate(
                            route = route,
                            profileOverride = preferredProfile,
                            forceQuickReconnect = false,
                            endpointOverride = settings.lastKnownGoodEndpoint,
                            scanLevelOverride = 1,
                            readinessTimeoutOverrideMs = if (isChainSlow) 30_000L else AetherCommandBuilder.FAST_CACHE_READINESS_MS,
                            labelSuffix = " (Cached)"
                        )
                    )
                } else if (canTrustQuickCache) {
                    out.add(
                        LaunchCandidate(
                            route = route,
                            profileOverride = preferredProfile,
                            forceQuickReconnect = true,
                            scanLevelOverride = 1,
                            readinessTimeoutOverrideMs = if (isChainSlow) 30_000L else AetherCommandBuilder.FAST_CACHE_READINESS_MS,
                            labelSuffix = " (Fast Cache)"
                        )
                    )
                }

                // Step 2: Fresh --no-quick-reconnect --turbo scan (primary path; avoids 40s dead gateway loop)
                out.add(
                    LaunchCandidate(
                        route = route,
                        profileOverride = preferredProfile,
                        forceQuickReconnect = false,
                        scanLevelOverride = 1,
                        readinessTimeoutOverrideMs = if (isChainSlow) null else turboTimeoutMs,
                        labelSuffix = if (canTrustQuickCache) " (Turbo Scan)" else ""
                    )
                )

                // Step 3: User configured deeper scan (--balanced / --thorough) if > 1
                if (profile.scanLevel > 1) {
                    out.add(
                        LaunchCandidate(
                            route = route,
                            profileOverride = preferredProfile,
                            forceQuickReconnect = false,
                            scanLevelOverride = profile.scanLevel,
                            labelSuffix = if (profile.scanLevel >= 3) " (Thorough)" else " (Balanced)"
                        )
                    )
                }
            } else {
                out.add(
                    LaunchCandidate(
                        route = route,
                        profileOverride = preferredProfile,
                        forceQuickReconnect = false,
                        scanLevelOverride = if (usesAether) 1 else null
                    )
                )
            }

            // Carrier fallback (H2+Fragment vs H3) with --no-quick-reconnect --turbo
            if (usesAether && supportsMasqueCarrier && (profile.routeChain == RouteChain.SMART_AUTO || routes.size == 1)) {
                val alt = if (!preferredProfile.masqueOverH2) {
                    preferredProfile.copy(masqueOverH2 = true, tlsFragment = true)
                } else {
                    preferredProfile.copy(masqueOverH2 = false, tlsFragment = false)
                }
                val suffix = if (alt.masqueOverH2) " (H2+Frag)" else " (H3)"
                out.add(
                    LaunchCandidate(
                        route = route,
                        profileOverride = alt,
                        forceQuickReconnect = false,
                        scanLevelOverride = 1,
                        readinessTimeoutOverrideMs = if (isChainSlow) null else turboTimeoutMs,
                        labelSuffix = suffix
                    )
                )
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
        val startOverallMs = android.os.SystemClock.elapsedRealtime()
        val compiledGeo = geoRepo?.compileRulesForProfile(profile) ?: GeoDatParser.CompiledProfileGeoRules()
        if (compiledGeo.directRules.isNotEmpty() || compiledGeo.blockRules.isNotEmpty()) {
            LogRepository.i(
                "GeoResources",
                "Compiled geo rules: direct=${compiledGeo.directRules.size}, block=${compiledGeo.blockRules.size}"
            )
        }

        val candidates = buildLaunchCandidates(profile, settings, networkKey, useFreshScan)
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
                psiphonSlot = coreManager.psiphonSlot,
                extraCompiledDirectRules = compiledGeo.directRules,
                extraCompiledBlockRules = compiledGeo.blockRules,
                forceQuickReconnect = candidate.forceQuickReconnect,
                endpointOverride = candidate.endpointOverride,
                scanLevelOverride = candidate.scanLevelOverride,
                readinessTimeoutOverrideMs = candidate.readinessTimeoutOverrideMs
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
            val probeTimeoutMs = if (isSlowRoute) 20_000L else 6_500L

            val plan = planResult.getOrThrow()
            val candidateStartMs = android.os.SystemClock.elapsedRealtime()
            when (val coreRes = coreManager.startPlan(plan, bindHost)) {
                is CoreStartResult.Ready -> {
                    val coreElapsed = android.os.SystemClock.elapsedRealtime() - candidateStartMs
                    LogRepository.i("Timing", "Core ready (${route.name}${candidate.labelSuffix}) in ${coreElapsed}ms")

                    var pingMs: Int? = null
                    if (verifyHttpsAfterCoreStart) {
                        val probeStartMs = android.os.SystemClock.elapsedRealtime()
                        val check = diagnostics.verifyRealHttpsConnectivity(coreRes.socksPort, plan.sessionAuth, probeTimeoutMs)
                        val probeElapsed = android.os.SystemClock.elapsedRealtime() - probeStartMs
                        LogRepository.i("Timing", "HTTPS probe finished in ${probeElapsed}ms (ok=${check.isSuccess})")
                        if (check.isFailure && candidates.size > 1) {
                            lastError = check.exceptionOrNull()?.message ?: "Route $route failed HTTPS verification"
                            coreManager.stopAll()
                            continue
                        }
                        pingMs = check.getOrNull()
                    }

                    val activeEp = candidate.endpointOverride ?: coreRes.detectedEndpoint
                    val carrierKey = "$networkKey:${candidate.profileOverride.aetherProtocol.name}"
                    val totalCorePhaseMs = android.os.SystemClock.elapsedRealtime() - startOverallMs
                    LogRepository.i("Timing", "Total core+probe phase completed in ${totalCorePhaseMs}ms")

                    settingsRepo.updateSettings { s ->
                        s.copy(
                            bestRoutePerNetwork = if (profile.routeChain == RouteChain.SMART_AUTO) {
                                s.bestRoutePerNetwork + (networkKey to route)
                            } else {
                                s.bestRoutePerNetwork
                            },
                            bestCarrierPerNetwork = s.bestCarrierPerNetwork + (
                                carrierKey to CarrierHint(
                                    masqueOverH2 = candidate.profileOverride.masqueOverH2,
                                    tlsFragment = candidate.profileOverride.tlsFragment
                                )
                            ),
                            lastKnownGoodEndpoint = activeEp ?: s.lastKnownGoodEndpoint,
                            lastKnownGoodEndpointProtocol = if (activeEp != null) candidate.profileOverride.aetherProtocol else s.lastKnownGoodEndpointProtocol,
                            lastKnownGoodEndpointNetworkKey = if (activeEp != null) networkKey else s.lastKnownGoodEndpointNetworkKey,
                            lastSuccessfulNetworkKey = networkKey,
                            lastConnectFailed = false
                        )
                    }

                    return CoreProbeOutcome.Ready(
                        route = route,
                        plan = plan,
                        socksPort = coreRes.socksPort,
                        httpPort = coreRes.httpPort,
                        auth = plan.sessionAuth,
                        initialPingMs = pingMs,
                        compiledGeoRules = compiledGeo,
                        carrierLabel = describeCarrier(route, candidate.profileOverride),
                        usedSavedEndpoint = candidate.endpointOverride != null,
                        usedQuickCache = candidate.forceQuickReconnect,
                        winningCandidate = "${route.name}${candidate.labelSuffix}".ifBlank { route.name }
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
                    if (candidate.forceQuickReconnect || candidate.endpointOverride != null) {
                        LogRepository.w("Orchestrator", "Cached gateway/endpoint failed quickly (${coreRes.reason}); switching to turbo scan")
                    }
                }
            }
        }

        // Mark failure so next connect skips stale gateway cache immediately
        runCatching {
            settingsRepo.updateSettings { s ->
                s.copy(
                    lastConnectFailed = true,
                    lastKnownGoodEndpoint = ""
                )
            }
        }

        return CoreProbeOutcome.Failure(dropReason, lastError, suggestedFix)
    }

    companion object {
        internal fun describeCarrier(route: RouteChain, profile: VpnProfile): String {
            if (route == RouteChain.PSIPHON_ONLY) return "PSIPHON"
            if (route == RouteChain.TOR_ONLY) return "TOR"
            val proto = profile.aetherProtocol
            val isMasqueFamily = proto == AetherProtocol.MASQUE ||
                proto == AetherProtocol.WARP_IN_MASQUE ||
                proto == AetherProtocol.MASQUE_IN_MASQUE
            return if (isMasqueFamily) {
                val transport = if (profile.masqueOverH2) "H2" else "H3"
                val frag = if (profile.tlsFragment) "+Frag" else ""
                "${proto.name}/$transport$frag"
            } else {
                proto.name
            }
        }

        fun formatConnectSummary(
            route: RouteChain,
            carrier: String,
            usedSavedEndpoint: Boolean,
            usedQuickCache: Boolean,
            winningCandidate: String,
            timeToConnectedMs: Long
        ): String = "route=${route.name}, carrier=$carrier, savedEndpoint=$usedSavedEndpoint, " +
            "quickCache=$usedQuickCache, winner=$winningCandidate, timeMs=$timeToConnectedMs"
    }
}
