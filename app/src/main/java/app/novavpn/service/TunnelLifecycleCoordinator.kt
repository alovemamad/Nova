package app.novavpn.service

import app.novavpn.core.SessionProxyAuth
import app.novavpn.core.runCatchingCancellable
import app.novavpn.domain.model.DropReason
import app.novavpn.domain.model.RouteChain
import app.novavpn.domain.model.SuggestedFixAction
import app.novavpn.domain.model.TunEngineType
import app.novavpn.domain.model.VpnState
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Pure coroutine coordinator owning `lifecycleMutex` for all VPN tunnel transitions.
 *
 * Invariant:
 * - Public entrypoints (`start`, `pause`, `resume`, `reconnect`, `stop`) acquire `lifecycleMutex` ONCE.
 * - Internal helpers (`*Locked`) NEVER acquire `lifecycleMutex`, eliminating re-entrant deadlocks.
 * - Cleanup runs under `withContext(NonCancellable)` so external cancellation cannot leave half-torn state.
 */
class TunnelLifecycleCoordinator(
    private val ops: Ops
) {
    interface Ops {
        suspend fun onConnecting()
        suspend fun startCoreAndProbe(
            useFreshScan: Boolean,
            isUserStopping: () -> Boolean
        ): CoreStepResult

        suspend fun hasTunOpen(): Boolean
        suspend fun openAndStartTun(
            socksPort: Int,
            auth: SessionProxyAuth?
        ): TunStepResult

        suspend fun stopTunOnly(closeTunFd: Boolean)
        suspend fun stopAllCoreAndTun()
        suspend fun onConnected(
            route: RouteChain,
            tunEngine: TunEngineType,
            socksPort: Int,
            httpPort: Int?,
            auth: SessionProxyAuth?,
            pingMs: Int?,
            resumed: Boolean
        )
        suspend fun onPaused()
        suspend fun onStopped(
            reason: DropReason,
            errorMessage: String?,
            suggestedFix: SuggestedFixAction,
            stopService: Boolean
        )
    }

    sealed class CoreStepResult {
        data class Ready(
            val route: RouteChain,
            val socksPort: Int,
            val httpPort: Int?,
            val auth: SessionProxyAuth?,
            val pingMs: Int?
        ) : CoreStepResult()

        data class Failed(
            val reason: DropReason,
            val errorMessage: String,
            val suggestedFix: SuggestedFixAction
        ) : CoreStepResult()
    }

    sealed class TunStepResult {
        data class Ready(val engineType: TunEngineType) : TunStepResult()
        data class Failed(
            val reason: DropReason,
            val errorMessage: String,
            val suggestedFix: SuggestedFixAction
        ) : TunStepResult()
    }

    private val lifecycleMutex = Mutex()

    @Volatile
    var isUserStopping: Boolean = false
        private set

    @Volatile
    var isPaused: Boolean = false
        private set

    @Volatile
    var state: VpnState = VpnState.DISCONNECTED
        private set

    @Volatile
    var activeSocksPort: Int = 24808
        private set

    @Volatile
    var activeAuth: SessionProxyAuth? = null
        private set

    @Volatile
    private var hasActiveCoreSession: Boolean = false

    @Volatile
    private var activeRoute: RouteChain = RouteChain.SMART_AUTO

    @Volatile
    private var activeHttpPort: Int? = null

    fun requestStop() {
        isUserStopping = true
    }

    suspend fun start(useFreshScan: Boolean) = lifecycleMutex.withLock {
        startLocked(useFreshScan = useFreshScan, keepExistingTun = false)
    }

    suspend fun reconnect(useFreshScan: Boolean, keepTunOpen: Boolean) = lifecycleMutex.withLock {
        if (isUserStopping || isPaused) return@withLock
        startLocked(useFreshScan = useFreshScan, keepExistingTun = keepTunOpen)
    }

    suspend fun pause() = lifecycleMutex.withLock {
        if (isPaused || state != VpnState.CONNECTED) return@withLock
        isPaused = true
        state = VpnState.PAUSED
        withContext(NonCancellable) {
            runCatchingCancellable { ops.stopTunOnly(closeTunFd = true) }
            ops.onPaused()
        }
    }

    suspend fun resume() = lifecycleMutex.withLock {
        if (!isPaused || isUserStopping) return@withLock
        if (!hasActiveCoreSession) {
            stopLocked(
                reason = DropReason.CORE_EXITED,
                errorMessage = "No active core session to resume",
                suggestedFix = SuggestedFixAction.RUN_DIAGNOSTICS,
                stopService = true
            )
            return@withLock
        }

        val auth = activeAuth
        val tunRes = runCatchingCancellable { ops.openAndStartTun(activeSocksPort, auth) }
            .getOrElse { TunStepResult.Failed(DropReason.TUN_START_FAILED, it.message ?: "TUN resume failed", SuggestedFixAction.SWITCH_TO_HEV_TUN) }

        when (tunRes) {
            is TunStepResult.Ready -> {
                isPaused = false
                state = VpnState.CONNECTED
                ops.onConnected(
                    route = activeRoute,
                    tunEngine = tunRes.engineType,
                    socksPort = activeSocksPort,
                    httpPort = activeHttpPort,
                    auth = auth,
                    pingMs = null,
                    resumed = true
                )
            }
            is TunStepResult.Failed -> {
                stopLocked(tunRes.reason, tunRes.errorMessage, tunRes.suggestedFix, stopService = true)
            }
        }
    }

    suspend fun stop(
        reason: DropReason,
        errorMessage: String?,
        suggestedFix: SuggestedFixAction = SuggestedFixAction.NONE,
        stopService: Boolean = true
    ) = lifecycleMutex.withLock {
        stopLocked(reason, errorMessage, suggestedFix, stopService)
    }

    private suspend fun startLocked(useFreshScan: Boolean, keepExistingTun: Boolean) {
        isUserStopping = false
        isPaused = false
        state = VpnState.CONNECTING
        ops.onConnecting()

        if (!keepExistingTun) {
            runCatchingCancellable { ops.stopTunOnly(closeTunFd = true) }
        } else {
            // Keep the existing TUN fd open (blackholing traffic) while restarting only the TUN consumer + core
            runCatchingCancellable { ops.stopTunOnly(closeTunFd = false) }
        }

        val coreRes = runCatchingCancellable {
            ops.startCoreAndProbe(useFreshScan) { isUserStopping }
        }.getOrElse {
            CoreStepResult.Failed(
                DropReason.CORE_EXITED,
                it.message ?: "Core failed to start",
                SuggestedFixAction.RUN_DIAGNOSTICS
            )
        }

        if (isUserStopping) {
            stopLocked(DropReason.USER_DISCONNECTED, null, SuggestedFixAction.NONE, stopService = true)
            return
        }

        when (coreRes) {
            is CoreStepResult.Failed -> {
                stopLocked(coreRes.reason, coreRes.errorMessage, coreRes.suggestedFix, stopService = true)
            }
            is CoreStepResult.Ready -> {
                hasActiveCoreSession = true
                activeSocksPort = coreRes.socksPort
                activeHttpPort = coreRes.httpPort
                activeAuth = coreRes.auth
                activeRoute = coreRes.route

                val tunRes = runCatchingCancellable {
                    ops.openAndStartTun(coreRes.socksPort, coreRes.auth)
                }.getOrElse {
                    TunStepResult.Failed(
                        DropReason.TUN_START_FAILED,
                        it.message ?: "TUN engine failed",
                        SuggestedFixAction.SWITCH_TO_HEV_TUN
                    )
                }

                if (isUserStopping) {
                    stopLocked(DropReason.USER_DISCONNECTED, null, SuggestedFixAction.NONE, stopService = true)
                    return
                }

                when (tunRes) {
                    is TunStepResult.Ready -> {
                        state = VpnState.CONNECTED
                        ops.onConnected(
                            route = coreRes.route,
                            tunEngine = tunRes.engineType,
                            socksPort = coreRes.socksPort,
                            httpPort = coreRes.httpPort,
                            auth = coreRes.auth,
                            pingMs = coreRes.pingMs,
                            resumed = false
                        )
                    }
                    is TunStepResult.Failed -> {
                        stopLocked(tunRes.reason, tunRes.errorMessage, tunRes.suggestedFix, stopService = true)
                    }
                }
            }
        }
    }

    private suspend fun stopLocked(
        reason: DropReason,
        errorMessage: String?,
        suggestedFix: SuggestedFixAction,
        stopService: Boolean
    ) = withContext(NonCancellable) {
        isUserStopping = true
        isPaused = false
        hasActiveCoreSession = false
        activeAuth = null
        state = if (errorMessage != null) VpnState.ERROR else VpnState.DISCONNECTED
        runCatchingCancellable { ops.stopAllCoreAndTun() }
        ops.onStopped(reason, errorMessage, suggestedFix, stopService)
    }
}
