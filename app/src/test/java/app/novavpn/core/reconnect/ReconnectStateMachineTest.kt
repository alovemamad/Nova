package app.novavpn.core.reconnect

import app.novavpn.domain.model.DropReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectStateMachineTest {

    @Test
    fun `does not burn attempts when device is offline`() {
        val sm = ReconnectStateMachine(ReconnectMachineState(isOnline = false, pingFailureLimit = 2))
        val d1 = sm.onPingFailed()
        val d2 = sm.onCoreDied()

        assertEquals(ReconnectActionType.WAIT_FOR_NETWORK, d1.action)
        assertEquals(ReconnectActionType.WAIT_FOR_NETWORK, d2.action)
        assertEquals(0, sm.state.attemptCount)
        assertTrue(sm.state.waitingForNetwork)
    }

    @Test
    fun `exponential backoff and switches to fresh scan on second attempt`() {
        val sm = ReconnectStateMachine(
            ReconnectMachineState(
                isOnline = true,
                preferLastGoodGateway = true,
                pingFailureLimit = 2,
                baseBackoffMs = 2000L,
                maxBackoffMs = 30000L
            )
        )
        // First ping failure below threshold -> NONE
        assertEquals(ReconnectActionType.NONE, sm.onPingFailed().action)
        // Second ping failure triggers attempt 1 (prefer last good gateway)
        val a1 = sm.onPingFailed()
        assertEquals(ReconnectActionType.RECONNECT_WITH_DELAY, a1.action)
        assertEquals(2000L, a1.delayMs)
        assertFalse(a1.useFreshScan)
        assertEquals(DropReason.PING_TIMEOUT, a1.dropReason)

        // Core died triggers attempt 2 -> 4000ms + forces fresh scan
        val a2 = sm.onCoreDied()
        assertEquals(ReconnectActionType.RECONNECT_WITH_DELAY, a2.action)
        assertEquals(4000L, a2.delayMs)
        assertTrue(a2.useFreshScan)
        assertEquals(DropReason.CORE_EXITED, a2.dropReason)
    }

    @Test
    fun `handles Wi-Fi to mobile handoff and stops after max attempts`() {
        val sm = ReconnectStateMachine(ReconnectMachineState(currentNetworkKey = "wifi:Home", maxAttempts = 2))
        val handoff = sm.onNetworkAvailable("mobile")
        assertEquals(ReconnectActionType.RECONNECT_WITH_DELAY, handoff.action)
        assertTrue(handoff.useFreshScan)
        assertEquals(DropReason.NETWORK_SWITCHED, handoff.dropReason)

        sm.onCoreDied()
        sm.onCoreDied()
        val exhausted = sm.onCoreDied()
        assertEquals(ReconnectActionType.STOP_FAILED, exhausted.action)
    }
}
