package app.novavpn.service

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Process
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import app.novavpn.core.net.DiagnosticsEngine
import app.novavpn.core.process.AetherCommandBuilder
import app.novavpn.core.process.CoreProcessManager
import app.novavpn.core.process.CoreStartResult
import app.novavpn.core.reconnect.ReconnectStateMachine
import app.novavpn.data.log.LogRepository
import app.novavpn.data.store.SettingsRepository
import app.novavpn.data.store.StatsRepository
import app.novavpn.domain.model.AppSettings
import app.novavpn.domain.model.DropReason
import app.novavpn.domain.model.RouteChain
import app.novavpn.domain.model.SessionRecord
import app.novavpn.domain.model.SuggestedFixAction
import app.novavpn.domain.model.TunEngineType
import app.novavpn.domain.model.VpnMode
import app.novavpn.domain.model.VpnProfile
import app.novavpn.domain.model.VpnRuntimeStatus
import app.novavpn.domain.model.VpnState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import java.util.UUID

class VpnController(
    private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val statsRepo: StatsRepository,
    private val diagnostics: DiagnosticsEngine
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateMutex = Mutex()

    private val _status = MutableStateFlow(VpnRuntimeStatus())
    val status: StateFlow<VpnRuntimeStatus> = _status.asStateFlow()

    // Last 30 samples of (downBps, upBps) for sparkline
    private val _speedHistory = MutableStateFlow<List<Pair<Long, Long>>>(emptyList())
    val speedHistory: StateFlow<List<Pair<Long, Long>>> = _speedHistory.asStateFlow()

    // Proxy-Only mode process manager (when VpnMode.PROXY_ONLY is active without TUN)
    private val proxyProcessManager = CoreProcessManager(context, scope) { code, bin ->
        scope.launch {
            if (_status.value.state == VpnState.CONNECTED) {
                LogRepository.w("ProxyMode", "$bin exited ($code), disconnecting proxy mode")
                handleStopInternal(DropReason.CORE_EXITED, "Core exited (code $code)", SuggestedFixAction.RUN_DIAGNOSTICS)
            }
        }
    }

    private var proxyMonitorJob: Job? = null
    private var proxySessionStartMs: Long = 0L

    fun toggleConnect(useFreshScan: Boolean = false) {
        scope.launch {
            when (_status.value.state) {
                VpnState.DISCONNECTED, VpnState.ERROR -> connectInternal(useFreshScan)
                VpnState.CONNECTING, VpnState.CONNECTED, VpnState.PAUSED, VpnState.RECONNECTING -> disconnectInternal(DropReason.USER_DISCONNECTED)
            }
        }
    }

    fun connect(useFreshScan: Boolean = false) {
        scope.launch { connectInternal(useFreshScan) }
    }

    fun disconnect(reason: DropReason = DropReason.USER_DISCONNECTED) {
        scope.launch { disconnectInternal(reason) }
    }

    fun pause() {
        scope.launch {
            val settings = settingsRepo.getSettings()
            if (settings.vpnMode == VpnMode.VPN) {
                sendServiceAction(NovaVpnService.ACTION_PAUSE)
            } else {
                // In proxy-only mode pausing toggles disconnect
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
            val state = _status.value.state
            if (state != VpnState.DISCONNECTED) {
                disconnectInternal(DropReason.USER_DISCONNECTED)
                delay(350)
            }
            connectInternal(useFreshScan = true)
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

    fun needsVpnPermission(settings: AppSettings): Boolean {
        return settings.vpnMode == VpnMode.VPN && VpnService.prepare(context) != null
    }

    private suspend fun connectInternal(useFreshScan: Boolean) = stateMutex.withLock {
        val current = _status.value.state
        if (current == VpnState.CONNECTING || current == VpnState.CONNECTED) return@withLock

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
            startProxyOnlyMode(settings, profile, useFreshScan)
        }
    }

    private suspend fun disconnectInternal(reason: DropReason) = stateMutex.withLock {
        val settings = settingsRepo.getSettings()
        if (settings.vpnMode == VpnMode.VPN) {
            sendServiceAction(NovaVpnService.ACTION_STOP)
            // Also ensure proxy-only process manager is stopped if mode switched
            proxyMonitorJob?.cancel()
            proxyMonitorJob = null
            proxyProcessManager.stopAll()
        } else {
            handleStopInternal(reason, null, SuggestedFixAction.NONE)
        }
    }

    private suspend fun startProxyOnlyMode(settings: AppSettings, profile: VpnProfile, useFreshScan: Boolean) {
        val networkKey = currentNetworkKey()
        val candidates = if (profile.routeChain == RouteChain.SMART_AUTO) {
            val cached = settings.bestRoutePerNetwork[networkKey]
            buildList {
                if (cached != null) add(cached)
                ReconnectStateMachine.SMART_AUTO_FALLBACK_ORDER.forEach { if (it !in this) add(it) }
            }
        } else {
            listOf(profile.routeChain)
        }

        val bindHost = if (settings.shareProxyOnLan) "0.0.0.0" else "127.0.0.1"
        var lastErrorMsg = "Unable to start local proxy core"
        var suggested = SuggestedFixAction.RUN_DIAGNOSTICS
        var drop = DropReason.CORE_EXITED

        for (route in candidates) {
            if (profile.routeChain == RouteChain.SMART_AUTO) {
                updateSmartProbeStatus("Probing ${route.name}…")
            }
            val planResult = AetherCommandBuilder.build(
                nativeDir = proxyProcessManager.nativeDir(),
                workDir = proxyProcessManager.workDir(),
                profile = profile,
                settings = settings,
                resolvedRoute = route,
                useFreshScan = useFreshScan
            )
            if (planResult.isFailure) {
                lastErrorMsg = planResult.exceptionOrNull()?.message ?: "Invalid configuration"
                drop = DropReason.INVALID_CONFIG
                suggested = SuggestedFixAction.APPLY_SAFE_FIX
                continue
            }
            val plan = planResult.getOrThrow()
            when (val res = proxyProcessManager.startPlan(plan, bindHost)) {
                is CoreStartResult.Ready -> {
                    val verify = diagnostics.verifyRealHttpsConnectivity(res.socksPort)
                    if (verify.isSuccess || candidates.size == 1) {
                        val ping = verify.getOrNull()
                        if (profile.routeChain == RouteChain.SMART_AUTO && verify.isSuccess) {
                            settingsRepo.updateSettings { s ->
                                s.copy(bestRoutePerNetwork = s.bestRoutePerNetwork + (networkKey to route))
                            }
                        }
                        proxySessionStartMs = System.currentTimeMillis()
                        _status.update {
                            it.copy(
                                state = VpnState.CONNECTED,
                                activeProfileName = profile.name,
                                activeRoute = route,
                                activeProtocol = profile.aetherProtocol,
                                activeTunEngine = TunEngineType.NONE,
                                pingMs = ping,
                                smartProbeStatus = null,
                                socksPort = res.socksPort,
                                httpPort = res.httpPort,
                                lanShareActive = settings.shareProxyOnLan
                            )
                        }
                        notifySystemSurfaces()
                        startProxyOnlyTelemetry(res.socksPort, profile)
                        return
                    } else {
                        lastErrorMsg = verify.exceptionOrNull()?.message ?: "HTTPS check failed over $route"
                        proxyProcessManager.stopAll()
                    }
                }
                is CoreStartResult.MissingBinary -> {
                    lastErrorMsg = "Native binary missing: ${res.binaryName}. Run scripts/build-native.sh or download release binaries."
                    drop = DropReason.MISSING_BINARY
                    suggested = SuggestedFixAction.NONE
                    break
                }
                is CoreStartResult.PortConflict -> {
                    lastErrorMsg = "Port ${res.port} is already in use."
                    drop = DropReason.PORT_CONFLICT
                    suggested = SuggestedFixAction.RESET_PORTS
                    break
                }
                is CoreStartResult.Failed -> {
                    lastErrorMsg = res.reason
                    drop = DropReason.CORE_EXITED
                }
            }
        }

        handleStopInternal(drop, lastErrorMsg, suggested)
    }

    private fun startProxyOnlyTelemetry(socksPort: Int, profile: VpnProfile) {
        proxyMonitorJob?.cancel()
        proxyMonitorJob = scope.launch {
            var lastRx = TrafficStats.getUidRxBytes(Process.myUid()).coerceAtLeast(0L)
            var lastTx = TrafficStats.getUidTxBytes(Process.myUid()).coerceAtLeast(0L)
            var tick = 0
            val startRx = lastRx
            val startTx = lastTx

            // Fetch geo info once in background
            launch {
                val geo = diagnostics.fetchExitIpInfo(socksPort)
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
                    val ping = diagnostics.verifyRealHttpsConnectivity(socksPort).getOrNull()
                    _status.update { it.copy(pingMs = ping) }
                }
            }
        }
    }

    private suspend fun handleStopInternal(
        reason: DropReason,
        errorMsg: String?,
        suggestedFix: SuggestedFixAction
    ) {
        proxyMonitorJob?.cancel()
        proxyMonitorJob = null
        proxyProcessManager.stopAll()

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
                suggestedFix = suggestedFix
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

    internal fun currentNetworkKey(): String {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return "unknown"
        val active = cm.activeNetwork ?: return "offline"
        val caps = cm.getNetworkCapabilities(active) ?: return "unknown"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                val wm = context.applicationContext.getSystemService(WifiManager::class.java)
                @Suppress("DEPRECATION")
                val ssid = wm?.connectionInfo?.ssid?.trim('"')
                if (!ssid.isNullOrBlank() && ssid != "<unknown ssid>") "wifi:$ssid" else "wifi"
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    }

    internal fun isUntrustedWifi(settings: AppSettings): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return false
        val wm = context.applicationContext.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION")
        val ssid = wm?.connectionInfo?.ssid?.trim('"') ?: return true
        if (ssid == "<unknown ssid>" || ssid.isBlank()) return true
        return settings.autoConnect.trustedWifiSsids.none { it.equals(ssid, ignoreCase = true) }
    }

    fun checkAutoConnectRules() {
        scope.launch {
            if (_status.value.state != VpnState.DISCONNECTED) return@launch
            val settings = settingsRepo.getSettings()
            if (settings.autoConnect.onUntrustedWifi && isUntrustedWifi(settings)) {
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
