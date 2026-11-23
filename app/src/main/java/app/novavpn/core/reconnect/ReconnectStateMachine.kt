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
 * Pure, deterministic state machine for smart reconnection:
 * - Never burns reconnect attempts while device is offline
 * - Uses exponential backoff
 * - Switches from "last good gateway" to "fresh scan" after repeated failures
 * - Handles core death, ping failure threshold, and Wi-Fi <-> Mobile handoff
 */
class ReconnectStateMachine(
    initialState: ReconnectMachineState = ReconnectMachineState()
) {
    var state: ReconnectMachineState = initialState
        private set

    fun onPingSucceeded() {
        state = state.copy(
            consecutivePingFailures = 0,
            attemptCount = 0,
            waitingForNetwork = false
        )
    }

    fun onPingFailed(): ReconnectDecision {
        if (!state.isOnline) {
            state = state.copy(waitingForNetwork = true)
            return ReconnectDecision(
                action = ReconnectActionType.WAIT_FOR_NETWORK,
                dropReason = DropReason.NETWORK_LOST
            )
        }
        val failures = state.consecutivePingFailures + 1
        state = state.copy(consecutivePingFailures = failures)
        return if (failures >= state.pingFailureLimit) {
            scheduleReconnect(DropReason.PING_TIMEOUT)
        } else {
            ReconnectDecision(action = ReconnectActionType.NONE)
        }
    }

    fun onCoreDied(): ReconnectDecision {
        if (!state.isOnline) {
            state = state.copy(waitingForNetwork = true)
            return ReconnectDecision(
                action = ReconnectActionType.WAIT_FOR_NETWORK,
                dropReason = DropReason.NETWORK_LOST
            )
        }
        return scheduleReconnect(DropReason.CORE_EXITED)
    }

    fun onNetworkLost(): ReconnectDecision {
        state = state.copy(isOnline = false, waitingForNetwork = true)
        return ReconnectDecision(
            action = ReconnectActionType.WAIT_FOR_NETWORK,
            dropReason = DropReason.NETWORK_LOST
        )
    }

    fun onNetworkAvailable(networkKey: String): ReconnectDecision {
        val switched = state.currentNetworkKey != "unknown" && state.currentNetworkKey != networkKey
        val wasWaiting = state.waitingForNetwork || !state.isOnline
        state = state.copy(
            isOnline = true,
            currentNetworkKey = networkKey,
            waitingForNetwork = false,
            consecutivePingFailures = 0
        )
        return if (wasWaiting || switched) {
            ReconnectDecision(
                action = ReconnectActionType.RECONNECT_WITH_DELAY,
                delayMs = 500L,
                useFreshScan = !state.preferLastGoodGateway || switched,
                nextAttemptCount = state.attemptCount,
                dropReason = if (switched) DropReason.NETWORK_SWITCHED else DropReason.NETWORK_LOST
            )
        } else {
            ReconnectDecision(action = ReconnectActionType.NONE)
        }
    }

    fun reset() {
        state = state.copy(
            consecutivePingFailures = 0,
            attemptCount = 0,
            waitingForNetwork = false
        )
    }

    fun updateConfig(preferLastGoodGateway: Boolean, pingFailureLimit: Int) {
        state = state.copy(
            preferLastGoodGateway = preferLastGoodGateway,
            pingFailureLimit = pingFailureLimit.coerceIn(1, 20)
        )
    }

    private fun scheduleReconnect(reason: DropReason): ReconnectDecision {
        val nextAttempt = state.attemptCount + 1
        if (nextAttempt > state.maxAttempts) {
            state = state.copy(attemptCount = nextAttempt)
            return ReconnectDecision(
                action = ReconnectActionType.STOP_FAILED,
                nextAttemptCount = nextAttempt,
                dropReason = reason
            )
        }
        state = state.copy(attemptCount = nextAttempt, consecutivePingFailures = 0)
        val exp = 1L shl min(nextAttempt - 1, 5)
        val backoff = min(state.baseBackoffMs * exp, state.maxBackoffMs)
        val forceFreshScan = !state.preferLastGoodGateway || nextAttempt >= 2
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
