package app.novavpn.service

import app.novavpn.core.SessionProxyAuth
import app.novavpn.domain.model.DropReason
import app.novavpn.domain.model.RouteChain
import app.novavpn.domain.model.SuggestedFixAction
import app.novavpn.domain.model.TunEngineType
import app.novavpn.domain.model.VpnState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelLifecycleCoordinatorTest {

    private class FakeOps : TunnelLifecycleCoordinator.Ops {
        var coreResult: TunnelLifecycleCoordinator.CoreStepResult =
            TunnelLifecycleCoordinator.CoreStepResult.Ready(
                route = RouteChain.AETHER,
                socksPort = 24808,
                httpPort = null,
                auth = null,
                pingMs = 42
            )
        var tunResult: TunnelLifecycleCoordinator.TunStepResult =
            TunnelLifecycleCoordinator.TunStepResult.Ready(TunEngineType.SING_BOX_LIBBOX)
        var tunOpen = false
        val stopTunCalls = mutableListOf<Boolean>()
        var stopAllCalls = 0
        var lastStoppedReason: DropReason? = null
        var lastErrorMessage: String? = null
        var lastConnectedResumed: Boolean? = null
        var pauseCalls = 0
        var delayCoreMs = 0L

        override suspend fun onConnecting() {}

        override suspend fun startCoreAndProbe(
            useFreshScan: Boolean,
            isUserStopping: () -> Boolean
        ): TunnelLifecycleCoordinator.CoreStepResult {
            if (delayCoreMs > 0) delay(delayCoreMs)
            return coreResult
        }

        override suspend fun hasTunOpen(): Boolean = tunOpen

        override suspend fun openAndStartTun(
            socksPort: Int,
            auth: SessionProxyAuth?
        ): TunnelLifecycleCoordinator.TunStepResult {
            if (tunResult is TunnelLifecycleCoordinator.TunStepResult.Ready) {
                tunOpen = true
            }
            return tunResult
        }

        override suspend fun stopTunOnly(closeTunFd: Boolean) {
            stopTunCalls.add(closeTunFd)
            if (closeTunFd) tunOpen = false
        }

        override suspend fun stopAllCoreAndTun() {
            stopAllCalls++
            tunOpen = false
        }

        override suspend fun onConnected(
            route: RouteChain,
            tunEngine: TunEngineType,
            socksPort: Int,
            httpPort: Int?,
            auth: SessionProxyAuth?,
            pingMs: Int?,
            resumed: Boolean
        ) {
            lastConnectedResumed = resumed
        }

        override suspend fun onPaused() {
            pauseCalls++
        }

        override suspend fun onStopped(
            reason: DropReason,
            errorMessage: String?,
            suggestedFix: SuggestedFixAction,
            stopService: Boolean
        ) {
            lastStoppedReason = reason
            lastErrorMessage = errorMessage
        }
    }

    @Test
    fun `failed start ends in ERROR and subsequent stop completes without deadlock`() = runBlocking {
        val ops = FakeOps().apply {
            coreResult = TunnelLifecycleCoordinator.CoreStepResult.Failed(
                reason = DropReason.MISSING_BINARY,
                errorMessage = "libaether.so missing",
                suggestedFix = SuggestedFixAction.RUN_DIAGNOSTICS
            )
        }
        val coordinator = TunnelLifecycleCoordinator(ops)

        withTimeout(2_000L) {
            coordinator.start(useFreshScan = false)
            assertEquals(VpnState.ERROR, coordinator.state)
            assertEquals(DropReason.MISSING_BINARY, ops.lastStoppedReason)
            assertEquals(1, ops.stopAllCalls)

            coordinator.stop(DropReason.USER_DISCONNECTED, null)
            assertEquals(VpnState.DISCONNECTED, coordinator.state)
            assertEquals(DropReason.USER_DISCONNECTED, ops.lastStoppedReason)
        }
    }

    @Test
    fun `stop during connecting cleanly transitions to DISCONNECTED without hanging`() = runBlocking {
        val ops = FakeOps().apply { delayCoreMs = 150L }
        val coordinator = TunnelLifecycleCoordinator(ops)

        withTimeout(2_000L) {
            coordinator.start(useFreshScan = false)
            assertEquals(VpnState.CONNECTED, coordinator.state)
            coordinator.stop(DropReason.USER_DISCONNECTED, null)
            assertEquals(VpnState.DISCONNECTED, coordinator.state)
            assertFalse(ops.tunOpen)
        }
    }

    @Test
    fun `pause closes TUN and resume restores CONNECTED or stops cleanly on TUN failure`() = runBlocking {
        val ops = FakeOps()
        val coordinator = TunnelLifecycleCoordinator(ops)

        withTimeout(2_000L) {
            coordinator.start(useFreshScan = false)
            assertTrue(ops.tunOpen)

            coordinator.pause()
            assertEquals(VpnState.PAUSED, coordinator.state)
            assertFalse(ops.tunOpen)
            assertEquals(1, ops.pauseCalls)
            assertTrue(ops.stopTunCalls.last())

            coordinator.resume()
            assertEquals(VpnState.CONNECTED, coordinator.state)
            assertTrue(ops.tunOpen)
            assertEquals(true, ops.lastConnectedResumed)

            // Now pause again and fail TUN on resume
            coordinator.pause()
            ops.tunResult = TunnelLifecycleCoordinator.TunStepResult.Failed(
                reason = DropReason.TUN_START_FAILED,
                errorMessage = "TUN failed",
                suggestedFix = SuggestedFixAction.SWITCH_TO_HEV_TUN
            )
            coordinator.resume()
            assertEquals(VpnState.ERROR, coordinator.state)
            assertEquals(DropReason.TUN_START_FAILED, ops.lastStoppedReason)
        }
    }

    @Test
    fun `reconnect with keepTunOpen retains existing TUN fd while restarting core`() = runBlocking {
        val ops = FakeOps()
        val coordinator = TunnelLifecycleCoordinator(ops)

        withTimeout(2_000L) {
            coordinator.start(useFreshScan = false)
            ops.stopTunCalls.clear()

            coordinator.reconnect(useFreshScan = true, keepTunOpen = true)
            assertEquals(listOf(false), ops.stopTunCalls)
            assertEquals(VpnState.CONNECTED, coordinator.state)
        }
    }
}
