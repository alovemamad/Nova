package app.novavpn.core.reconnect

import app.novavpn.domain.model.DropReason
import app.novavpn.domain.model.RouteChain
import kotlin.math.min

enum class ReconnectActionType {
    NONE,
    WAIT_FOR_NETWORK,
    RECONNECT_WITH_DELAY,
    SWITCH_ROUTE_AND_RECONNECT,
    STOP_FAILED
}

data class ReconnectDecision(
    val action: ReconnectActionType,
    val delayMs: Long = 0L,
    val useFreshScan: Boolean = false,
    val nextAttemptCount: Int = 0,
    val dropReason: DropReason = DropReason.NONE
)

data class ReconnectMachineState(
    val isOnline: Boolean = true,
    val currentNetworkKey: String = "unknown",
    val consecutivePingFailures: Int = 0,
    val attemptCount: Int = 0,
    val preferLastGoodGateway: Boolean = true,
    val pingFailureLimit: Int = 3,
    val maxAttempts: Int = 8,
    val baseBackoffMs: Long = 2_000L,
    val maxBackoffMs: Long = 30_000L,
    val waitingForNetwork: Boolean = false
)

/**
 * Thread-safe, deterministic state machine for smart reconnection:
 * - Guarded by an internal monitor lock so network callbacks and telemetry threads cannot race
 * - Never burns reconnect attempts while device is offline
 * - Uses exponential backoff
 * - Switches from "last good gateway" to "fresh scan" after repeated failures
 * - Handles core death, ping failure threshold, and Wi-Fi <-> Mobile handoff
 */
class ReconnectStateMachine(
    initialState: ReconnectMachineState = ReconnectMachineState()
) {
    private val lock = Any()

    @Volatile
    private var _state: ReconnectMachineState = initialState
    val state: ReconnectMachineState
        get() = synchronized(lock) { _state }

    fun onPingSucceeded() = synchronized(lock) {
        _state = _state.copy(
            consecutivePingFailures = 0,
            attemptCount = 0,
            waitingForNetwork = false
        )
    }

    fun onPingFailed(): ReconnectDecision = synchronized(lock) {
        if (!_state.isOnline) {
            _state = _state.copy(waitingForNetwork = true)
            return@synchronized ReconnectDecision(
                action = ReconnectActionType.WAIT_FOR_NETWORK,
                dropReason = DropReason.NETWORK_LOST
            )
        }
        val failures = _state.consecutivePingFailures + 1
        _state = _state.copy(consecutivePingFailures = failures)
        if (failures >= _state.pingFailureLimit) {
            scheduleReconnectLocked(DropReason.PING_TIMEOUT)
        } else {
            ReconnectDecision(action = ReconnectActionType.NONE)
        }
    }

    fun onCoreDied(): ReconnectDecision = synchronized(lock) {
        if (!_state.isOnline) {
            _state = _state.copy(waitingForNetwork = true)
            return@synchronized ReconnectDecision(
                action = ReconnectActionType.WAIT_FOR_NETWORK,
                dropReason = DropReason.NETWORK_LOST
            )
        }
        scheduleReconnectLocked(DropReason.CORE_EXITED)
    }

    fun onNetworkLost(): ReconnectDecision = synchronized(lock) {
        _state = _state.copy(isOnline = false, waitingForNetwork = true)
        ReconnectDecision(
            action = ReconnectActionType.WAIT_FOR_NETWORK,
            dropReason = DropReason.NETWORK_LOST
        )
    }

    fun onNetworkAvailable(networkKey: String): ReconnectDecision = synchronized(lock) {
        val switched = _state.currentNetworkKey != "unknown" &&
            _state.currentNetworkKey != "offline" &&
            networkKey != "offline" &&
            _state.currentNetworkKey != networkKey
        val wasWaiting = _state.waitingForNetwork || !_state.isOnline
        _state = _state.copy(
            isOnline = true,
            currentNetworkKey = networkKey,
            waitingForNetwork = false,
            consecutivePingFailures = 0
        )
        if (wasWaiting || switched) {
            ReconnectDecision(
                action = ReconnectActionType.RECONNECT_WITH_DELAY,
                delayMs = 500L,
                useFreshScan = !_state.preferLastGoodGateway || switched,
                nextAttemptCount = _state.attemptCount,
                dropReason = if (switched) DropReason.NETWORK_SWITCHED else DropReason.NETWORK_LOST
            )
        } else {
            ReconnectDecision(action = ReconnectActionType.NONE)
        }
    }

    fun reset() = synchronized(lock) {
        _state = _state.copy(
            consecutivePingFailures = 0,
            attemptCount = 0,
            waitingForNetwork = false
        )
    }

    fun updateConfig(preferLastGoodGateway: Boolean, pingFailureLimit: Int) = synchronized(lock) {
        _state = _state.copy(
            preferLastGoodGateway = preferLastGoodGateway,
            pingFailureLimit = pingFailureLimit.coerceIn(1, 20)
        )
    }

    private fun scheduleReconnectLocked(reason: DropReason): ReconnectDecision {
        val nextAttempt = _state.attemptCount + 1
        if (nextAttempt > _state.maxAttempts) {
            _state = _state.copy(attemptCount = nextAttempt)
            return ReconnectDecision(
                action = ReconnectActionType.STOP_FAILED,
                nextAttemptCount = nextAttempt,
                dropReason = reason
            )
        }
        _state = _state.copy(attemptCount = nextAttempt, consecutivePingFailures = 0)
        val exp = 1L shl min(nextAttempt - 1, 5)
        val backoff = min(_state.baseBackoffMs * exp, _state.maxBackoffMs)
        val forceFreshScan = !_state.preferLastGoodGateway || nextAttempt >= 2
        return ReconnectDecision(
            action = ReconnectActionType.RECONNECT_WITH_DELAY,
            delayMs = backoff,
            useFreshScan = forceFreshScan,
            nextAttemptCount = nextAttempt,
            dropReason = reason
        )
    }

    companion object {
        val SMART_AUTO_FALLBACK_ORDER = listOf(
            RouteChain.AETHER,
            RouteChain.AETHER_PSIPHON,
            RouteChain.PSIPHON_ONLY,
            RouteChain.AETHER_TOR,
            RouteChain.TOR_ONLY
        )
    }
}
