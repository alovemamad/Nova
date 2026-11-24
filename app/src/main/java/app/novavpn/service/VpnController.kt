package app.novavpn.service

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.Process
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import app.novavpn.core.SessionProxyAuth
import app.novavpn.core.net.ConnectivityHelper
import app.novavpn.core.net.DiagnosticsEngine
import app.novavpn.core.process.CoreProcessManager
import app.novavpn.core.tun.CoreProbeOutcome
import app.novavpn.core.tun.TunnelStartOrchestrator
import app.novavpn.data.log.LogRepository
import app.novavpn.data.store.SettingsRepository
import app.novavpn.data.store.StatsRepository
import app.novavpn.domain.model.AppSettings
import app.novavpn.domain.model.DropReason
import app.novavpn.domain.model.SessionRecord
import app.novavpn.domain.model.SuggestedFixAction
import app.novavpn.domain.model.TunEngineType
import app.novavpn.domain.model.VpnMode
import app.novavpn.domain.model.VpnProfile
import app.novavpn.domain.model.VpnRuntimeStatus
import app.novavpn.domain.model.VpnState
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Thin command/state facade between the UI/QuickSettings/Widgets and the underlying tunnel owner:
 * - In [VpnMode.VPN]: delegates tunnel lifecycle directly to [NovaVpnService].
 * - In [VpnMode.PROXY_ONLY]: delegates route probing/core startup to [TunnelStartOrchestrator].
 */
class VpnController(
    private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val statsRepo: StatsRepository,
    private val diagnostics: DiagnosticsEngine,
    private val orchestrator: TunnelStartOrchestrator
) {
    private val scopeExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        LogRepository.e("VpnController", "Uncaught coroutine error: ${throwable::class.java.simpleName}: ${throwable.message}")
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + scopeExceptionHandler)
    private val stateMutex = Mutex()

    private val _status = MutableStateFlow(VpnRuntimeStatus())
    val status: StateFlow<VpnRuntimeStatus> = _status.asStateFlow()

    // Last 30 samples of (downBps, upBps) for sparkline
    private val _speedHistory = MutableStateFlow<List<Pair<Long, Long>>>(emptyList())
    val speedHistory: StateFlow<List<Pair<Long, Long>>> = _speedHistory.asStateFlow()

    @Volatile
    private var currentSessionAuth: SessionProxyAuth? = null

    @Volatile
    private var proxyUserCancelled: Boolean = false

    private val proxyProcessManager: CoreProcessManager = CoreProcessManager(context, scope) { code, bin ->
        scope.launch {
            if (!proxyUserCancelled && _status.value.state == VpnState.CONNECTED) {
                LogRepository.w("ProxyMode", "$bin exited ($code), disconnecting proxy mode")
                stateMutex.withLock {
                    handleProxyStopLocked(DropReason.CORE_EXITED, "Core exited (code $code)", SuggestedFixAction.RUN_DIAGNOSTICS)
                }
            }
        }
    }

    private var proxyMonitorJob: Job? = null
    private var proxySessionStartMs: Long = 0L

    fun currentProxyAuth(): SessionProxyAuth? = currentSessionAuth

    internal fun updateSessionProxyAuth(auth: SessionProxyAuth?) {
        currentSessionAuth = auth
    }

    fun toggleConnect(useFreshScan: Boolean = false) {
        scope.launch {
            when (_status.value.state) {
                VpnState.DISCONNECTED, VpnState.ERROR -> connectInternal(useFreshScan)
                VpnState.CONNECTING, VpnState.CONNECTED, VpnState.PAUSED, VpnState.RECONNECTING ->
                    disconnectInternal(DropReason.USER_DISCONNECTED)
            }
        }
    }

    fun connect(useFreshScan: Boolean = false) {
        scope.launch { connectInternal(useFreshScan) }
    }

    suspend fun connectSyncOrThrow(useFreshScan: Boolean = false) {
        connectInternal(useFreshScan)
    }

    fun disconnect(reason: DropReason = DropReason.USER_DISCONNECTED) {
        proxyUserCancelled = true
        proxyProcessManager.forceAbort()
        scope.launch { disconnectInternal(reason) }
    }

    fun pause() {
        scope.launch {
            val settings = settingsRepo.getSettings()
            if (settings.vpnMode == VpnMode.VPN) {
                sendServiceAction(NovaVpnService.ACTION_PAUSE)
            } else {
                disconnectInternal(DropReason.USER_DISCONNECTED)
            }
        }
    }

    fun resume() {
        scope.launch {
            val settings = settingsRepo.getSettings()
            if (settings.vpnMode == VpnMode.VPN) {
                sendServiceAction(NovaVpnService.ACTION_RESUME)
            } else {
                connectInternal(false)
            }
        }
    }

    fun reconnectFreshScan() {
        scope.launch {
            val settings = settingsRepo.getSettings()
            if (settings.vpnMode == VpnMode.VPN) {
                if (needsVpnPermission(settings)) {
                    connectInternal(useFreshScan = true)
                } else {
                    // NovaVpnService handles reconnecting directly while honoring keepTunOnReconnect
                    sendServiceAction(NovaVpnService.ACTION_START, useFreshScan = true)
                }
            } else {
                if (_status.value.state != VpnState.DISCONNECTED) {
                    disconnectInternal(DropReason.USER_DISCONNECTED)
                    delay(250)
                }
                connectInternal(useFreshScan = true)
            }
        }
    }

    fun clearError() {
        _status.update {
            if (it.state == VpnState.ERROR) {
                it.copy(state = VpnState.DISCONNECTED, errorMessage = null, suggestedFix = SuggestedFixAction.NONE)
            } else {
                it.copy(errorMessage = null, suggestedFix = SuggestedFixAction.NONE)
            }
        }
        notifySystemSurfaces()
    }

    fun needsVpnPermission(settings: AppSettings? = null): Boolean {
        val effective = settings ?: return VpnService.prepare(context) != null
        return effective.vpnMode == VpnMode.VPN && VpnService.prepare(context) != null
    }

    private suspend fun connectInternal(useFreshScan: Boolean) = stateMutex.withLock {
        val current = _status.value.state
        if (current == VpnState.CONNECTING || current == VpnState.CONNECTED) return@withLock

        proxyUserCancelled = false
        val settings = settingsRepo.getSettings()
        val profile = settingsRepo.getActiveProfile()

        if (settings.vpnMode == VpnMode.VPN && VpnService.prepare(context) != null) {
            _status.update {
                it.copy(
                    state = VpnState.ERROR,
                    activeProfileName = profile.name,
                    errorMessage = "VPN permission is required before starting tunnel.",
                    suggestedFix = SuggestedFixAction.GRANT_VPN_PERMISSION
                )
            }
            notifySystemSurfaces()
            return@withLock
        }

        _speedHistory.value = emptyList()
        _status.update {
            it.copy(
                state = VpnState.CONNECTING,
                activeProfileName = profile.name,
                activeRoute = profile.routeChain,
                activeProtocol = profile.aetherProtocol,
                errorMessage = null,
                suggestedFix = SuggestedFixAction.NONE,
                lastDropReason = DropReason.NONE,
                uptimeSeconds = 0L,
                sessionDownBytes = 0L,
                sessionUpBytes = 0L,
                downSpeedBps = 0L,
                upSpeedBps = 0L,
                pingMs = null,
                exitIp = null,
                exitCountryCode = null,
                exitCity = null
            )
        }
        notifySystemSurfaces()

        if (settings.vpnMode == VpnMode.VPN) {
            sendServiceAction(NovaVpnService.ACTION_START, useFreshScan)
        } else {
            startProxyOnlyLocked(settings, profile, useFreshScan)
        }
    }

    private suspend fun disconnectInternal(reason: DropReason) = stateMutex.withLock {
        val settings = settingsRepo.getSettings()
        if (settings.vpnMode == VpnMode.VPN) {
            sendServiceAction(NovaVpnService.ACTION_STOP)
            // Ensure any leftover proxy-only manager is cleaned up if user switched modes
            proxyMonitorJob?.cancel()
            proxyMonitorJob = null
            proxyProcessManager.stopAll()
        } else {
            handleProxyStopLocked(reason, null, SuggestedFixAction.NONE)
        }
    }

    private suspend fun startProxyOnlyLocked(settings: AppSettings, profile: VpnProfile, useFreshScan: Boolean) {
        val networkKey = ConnectivityHelper.currentNetworkKey(context)
        val outcome = orchestrator.startCoreAndProbe(
            coreManager = proxyProcessManager,
            settings = settings,
            profile = profile,
            networkKey = networkKey,
            useFreshScan = useFreshScan,
            isCancelled = { proxyUserCancelled },
            onProbeProgress = { label -> updateSmartProbeStatus(label) },
            verifyHttpsAfterCoreStart = true
        )

        when (outcome) {
            is CoreProbeOutcome.Ready -> {
                currentSessionAuth = outcome.auth
                proxySessionStartMs = System.currentTimeMillis()
                _status.update {
                    it.copy(
                        state = VpnState.CONNECTED,
                        activeProfileName = profile.name,
                        activeRoute = outcome.route,
                        activeProtocol = profile.aetherProtocol,
                        activeTunEngine = TunEngineType.NONE,
                        pingMs = outcome.initialPingMs,
                        smartProbeStatus = null,
                        socksPort = outcome.socksPort,
                        httpPort = outcome.httpPort,
                        lanShareActive = settings.shareProxyOnLan,
                        sessionProxyUser = outcome.auth?.username,
                        sessionProxyPass = outcome.auth?.password
                    )
                }
                notifySystemSurfaces()
                startProxyOnlyTelemetry(outcome.socksPort, outcome.auth, profile)
            }
            is CoreProbeOutcome.Failure -> {
                handleProxyStopLocked(outcome.dropReason, outcome.errorMessage, outcome.suggestedFix)
            }
        }
    }

    private fun startProxyOnlyTelemetry(socksPort: Int, auth: SessionProxyAuth?, profile: VpnProfile) {
        proxyMonitorJob?.cancel()
        proxyMonitorJob = scope.launch {
            var lastRx = TrafficStats.getUidRxBytes(Process.myUid()).coerceAtLeast(0L)
            var lastTx = TrafficStats.getUidTxBytes(Process.myUid()).coerceAtLeast(0L)
            var tick = 0
            val startRx = lastRx
            val startTx = lastTx

            launch {
                val geo = diagnostics.fetchExitIpInfo(socksPort, auth)
                if (geo != null) {
                    _status.update {
                        it.copy(exitIp = geo.ip, exitCountryCode = geo.countryCode, exitCity = geo.city)
                    }
                }
            }

            while (isActive && _status.value.state == VpnState.CONNECTED) {
                delay(1000)
                tick++
                val nowRx = TrafficStats.getUidRxBytes(Process.myUid()).coerceAtLeast(0L)
                val nowTx = TrafficStats.getUidTxBytes(Process.myUid()).coerceAtLeast(0L)
                val downBps = (nowRx - lastRx).coerceAtLeast(0L)
                val upBps = (nowTx - lastTx).coerceAtLeast(0L)
                lastRx = nowRx
                lastTx = nowTx

                val totalDown = (nowRx - startRx).coerceAtLeast(0L)
                val totalUp = (nowTx - startTx).coerceAtLeast(0L)

                pushSpeedSample(downBps, upBps)
                _status.update {
                    it.copy(
                        uptimeSeconds = (System.currentTimeMillis() - proxySessionStartMs) / 1000L,
                        downSpeedBps = downBps,
                        upSpeedBps = upBps,
                        sessionDownBytes = totalDown,
                        sessionUpBytes = totalUp
                    )
                }

                if (tick % profile.pingIntervalSec.coerceIn(5, 120) == 0) {
                    val ping = diagnostics.verifyRealHttpsConnectivity(socksPort, auth).getOrNull()
                    _status.update { it.copy(pingMs = ping) }
                }
            }
        }
    }

    private suspend fun handleProxyStopLocked(
        reason: DropReason,
        errorMsg: String?,
        suggestedFix: SuggestedFixAction
    ): Unit = withContext(NonCancellable) {
        proxyMonitorJob?.cancel()
        proxyMonitorJob = null
        proxyProcessManager.stopAll()
        currentSessionAuth = null

        val prev = _status.value
        if (proxySessionStartMs > 0L && prev.uptimeSeconds >= 2L) {
            statsRepo.recordSession(
                SessionRecord(
                    id = UUID.randomUUID().toString(),
                    profileName = prev.activeProfileName,
                    routeChain = prev.activeRoute,
                    startedAtMs = proxySessionStartMs,
                    durationSec = prev.uptimeSeconds,
                    downloadBytes = prev.sessionDownBytes,
                    uploadBytes = prev.sessionUpBytes,
                    exitCountryCode = prev.exitCountryCode,
                    dropReason = reason
                )
            )
        }
        proxySessionStartMs = 0L

        val finalState = if (errorMsg != null) VpnState.ERROR else VpnState.DISCONNECTED
        _status.update {
            it.copy(
                state = finalState,
                activeTunEngine = TunEngineType.NONE,
                downSpeedBps = 0L,
                upSpeedBps = 0L,
                smartProbeStatus = null,
                lastDropReason = reason,
                errorMessage = errorMsg,
                suggestedFix = suggestedFix,
                sessionProxyUser = null,
                sessionProxyPass = null
            )
        }
        notifySystemSurfaces()
    }

    internal fun updateRuntimeStatus(transform: (VpnRuntimeStatus) -> VpnRuntimeStatus) {
        _status.update(transform)
        notifySystemSurfaces()
    }

    internal fun updateSmartProbeStatus(label: String?) {
        _status.update { it.copy(smartProbeStatus = label) }
    }

    internal fun pushSpeedSample(downBps: Long, upBps: Long) {
        _speedHistory.update { existing ->
            (existing + (downBps to upBps)).takeLast(30)
        }
    }

    fun checkAutoConnectRules() {
        scope.launch {
            if (_status.value.state != VpnState.DISCONNECTED) return@launch
            val settings = settingsRepo.getSettings()
            if (settings.autoConnect.onUntrustedWifi && ConnectivityHelper.isUntrustedWifi(context, settings)) {
                if (!needsVpnPermission(settings)) {
                    LogRepository.i("AutoConnect", "Untrusted Wi-Fi detected; auto-connecting VPN")
                    connectInternal(false)
                }
            }
        }
    }

    private fun sendServiceAction(action: String, useFreshScan: Boolean = false) {
        val intent = Intent(context, NovaVpnService::class.java).apply {
            this.action = action
            putExtra(NovaVpnService.EXTRA_FRESH_SCAN, useFreshScan)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    internal fun notifySystemSurfaces() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                TileService.requestListeningState(
                    context,
                    ComponentName(context, NovaTileService::class.java)
                )
            }
        }
        runCatching {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, NovaVpnWidgetProvider::class.java))
            if (ids.isNotEmpty()) {
                val intent = Intent(context, NovaVpnWidgetProvider::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                }
                context.sendBroadcast(intent)
            }
        }
    }
}
