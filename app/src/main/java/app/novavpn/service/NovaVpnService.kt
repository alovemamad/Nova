package app.novavpn.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.core.app.NotificationCompat
import app.novavpn.NovaVpnApplication
import app.novavpn.ServiceLocator
import app.novavpn.core.process.AetherCommandBuilder
import app.novavpn.core.process.CoreProcessManager
import app.novavpn.core.process.CoreStartResult
import app.novavpn.core.reconnect.ReconnectActionType
import app.novavpn.core.reconnect.ReconnectStateMachine
import app.novavpn.core.tun.TunEngineManager
import app.novavpn.data.log.LogRepository
import app.novavpn.domain.model.AppSettings
import app.novavpn.domain.model.DnsMode
import app.novavpn.domain.model.DropReason
import app.novavpn.domain.model.RouteChain
import app.novavpn.domain.model.SessionRecord
import app.novavpn.domain.model.SplitTunnelMode
import app.novavpn.domain.model.SuggestedFixAction
import app.novavpn.domain.model.TunEngineType
import app.novavpn.domain.model.VpnProfile
import app.novavpn.domain.model.VpnState
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class NovaVpnService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()

    private lateinit var coreManager: CoreProcessManager
    private lateinit var tunManager: TunEngineManager
    private val reconnectMachine = ReconnectStateMachine()

    @Volatile
    private var tunInterface: ParcelFileDescriptor? = null

    @Volatile
    private var isUserStopping = false

    @Volatile
    private var isPaused = false

    @Volatile
    private var activeSocksPort: Int = 10808

    @Volatile
    private var sessionStartMs: Long = 0L

    private var telemetryJob: Job? = null
    private var reconnectJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(applicationContext)
        NovaVpnApplication.createNotificationChannels(this)
        tunManager = TunEngineManager(applicationContext)
        coreManager = CoreProcessManager(applicationContext, serviceScope) { exitCode, binaryName ->
            if (!isUserStopping && !isPaused) {
                LogRepository.w("NovaVpnService", "Unexpected exit of $binaryName ($exitCode)")
                val decision = reconnectMachine.onCoreDied()
                serviceScope.launch { handleReconnectDecision(decision, null) }
            }
        }
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(buildNotification(getString(R.string.status_connecting), isPaused = false))

        when (intent?.action) {
            ACTION_START -> {
                val fresh = intent.getBooleanExtra(EXTRA_FRESH_SCAN, false)
                serviceScope.launch { startTunnel(useFreshScan = fresh) }
            }
            ACTION_STOP -> {
                serviceScope.launch { stopTunnel(DropReason.USER_DISCONNECTED, errorMessage = null, stopService = true) }
            }
            ACTION_PAUSE -> {
                serviceScope.launch { pauseTunnel() }
            }
            ACTION_RESUME -> {
                serviceScope.launch { resumeTunnel() }
            }
            null -> {
                // Service restarted by OS (START_STICKY)
                serviceScope.launch {
                    val settings = ServiceLocator.settingsRepository.getSettings()
                    if (settings.wasVpnActiveBeforeKill) {
                        LogRepository.i("NovaVpnService", "Restoring tunnel after process kill")
                        startTunnel(useFreshScan = false)
                    } else {
                        stopForegroundAndSelf()
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        super.onRevoke()
        LogRepository.w("NovaVpnService", "VPN permission revoked by system")
        serviceScope.launch {
            stopTunnel(
                reason = DropReason.VPN_REVOKED,
                errorMessage = "VPN permission was revoked or replaced by another VPN app.",
                suggestedFix = SuggestedFixAction.GRANT_VPN_PERMISSION,
                stopService = true
            )
        }
    }

    override fun onDestroy() {
        unregisterNetworkCallback()
        telemetryJob?.cancel()
        reconnectJob?.cancel()
        val fdToClose = tunInterface
        tunInterface = null
        // Never block main thread in onDestroy: dispatch async cleanup then cancel scope once done
        serviceScope.launch(Dispatchers.IO) {
            runCatching { tunManager.stopTun() }
            runCatching { fdToClose?.close() }
            runCatching { coreManager.stopAll() }
            serviceScope.cancel()
        }
        super.onDestroy()
    }

    private suspend fun startTunnel(useFreshScan: Boolean) = lifecycleMutex.withLock {
        isUserStopping = false
        isPaused = false

        val controller = ServiceLocator.vpnController
        val settingsRepo = ServiceLocator.settingsRepository
        val diagnostics = ServiceLocator.diagnosticsEngine

        val settings = settingsRepo.getSettings()
        val profile = settingsRepo.getActiveProfile()
        val networkKey = controller.currentNetworkKey()
        reconnectMachine.updateConfig(settings.preferLastGoodGateway, profile.pingFailureLimit)

        controller.updateRuntimeStatus {
            it.copy(
                state = VpnState.CONNECTING,
                activeProfileName = profile.name,
                activeRoute = profile.routeChain,
                activeProtocol = profile.aetherProtocol,
                errorMessage = null,
                suggestedFix = SuggestedFixAction.NONE
            )
        }
        updateNotification(getString(R.string.status_connecting), false)

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
        var lastError = "Failed to establish VPN tunnel"
        var dropReason = DropReason.CORE_EXITED
        var suggestedFix = SuggestedFixAction.RUN_DIAGNOSTICS

        for (route in candidates) {
            if (isUserStopping) return@withLock
            if (profile.routeChain == RouteChain.SMART_AUTO) {
                controller.updateSmartProbeStatus("Testing route: ${route.name}…")
            }

            val planResult = AetherCommandBuilder.build(
                nativeDir = coreManager.nativeDir(),
                workDir = coreManager.workDir(),
                profile = profile,
                settings = settings,
                resolvedRoute = route,
                useFreshScan = useFreshScan
            )
            if (planResult.isFailure) {
                lastError = planResult.exceptionOrNull()?.message ?: "Invalid configuration"
                dropReason = DropReason.INVALID_CONFIG
                suggestedFix = SuggestedFixAction.APPLY_SAFE_FIX
                continue
            }

            val plan = planResult.getOrThrow()
            when (val coreRes = coreManager.startPlan(plan, bindHost)) {
                is CoreStartResult.Ready -> {
                    activeSocksPort = coreRes.socksPort
                    if (coreRes.detectedEndpoint != null) {
                        settingsRepo.updateSettings { it.copy(lastKnownGoodEndpoint = coreRes.detectedEndpoint) }
                    }

                    // Establish TUN interface first so traffic or diagnostics work cleanly
                    val tunPfd = buildVpnInterface(settings, profile)
                    if (tunPfd == null) {
                        lastError = "Android VpnService.Builder.establish() returned null. Check VPN permission or Always-on VPN."
                        dropReason = DropReason.TUN_START_FAILED
                        suggestedFix = SuggestedFixAction.GRANT_VPN_PERMISSION
                        coreManager.stopAll()
                        continue
                    }
                    tunInterface = tunPfd

                    val tunRes = tunManager.startTun(tunPfd, coreRes.socksPort, profile, settings.tunEnginePreference)
                    if (tunRes.isFailure) {
                        lastError = tunRes.exceptionOrNull()?.message ?: "TUN engine failed"
                        dropReason = DropReason.TUN_START_FAILED
                        suggestedFix = SuggestedFixAction.SWITCH_TO_HEV_TUN
                        runCatching { tunPfd.close() }
                        tunInterface = null
                        coreManager.stopAll()
                        continue
                    }

                    val engineUsed = tunRes.getOrThrow()
                    val httpsCheck = diagnostics.verifyRealHttpsConnectivity(coreRes.socksPort)
                    if (httpsCheck.isFailure && candidates.size > 1) {
                        lastError = httpsCheck.exceptionOrNull()?.message ?: "Route $route failed HTTPS verification"
                        tunManager.stopTun()
                        runCatching { tunInterface?.close() }
                        tunInterface = null
                        coreManager.stopAll()
                        continue
                    }

                    if (profile.routeChain == RouteChain.SMART_AUTO && httpsCheck.isSuccess) {
                        settingsRepo.updateSettings { s ->
                            s.copy(bestRoutePerNetwork = s.bestRoutePerNetwork + (networkKey to route))
                        }
                    }

                    settingsRepo.setVpnActiveState(true)
                    reconnectMachine.reset()
                    sessionStartMs = System.currentTimeMillis()

                    controller.updateRuntimeStatus {
                        it.copy(
                            state = VpnState.CONNECTED,
                            activeProfileName = profile.name,
                            activeRoute = route,
                            activeProtocol = profile.aetherProtocol,
                            activeTunEngine = engineUsed,
                            pingMs = httpsCheck.getOrNull(),
                            smartProbeStatus = null,
                            reconnectAttempt = 0,
                            socksPort = coreRes.socksPort,
                            httpPort = coreRes.httpPort,
                            lanShareActive = settings.shareProxyOnLan,
                            errorMessage = null,
                            suggestedFix = SuggestedFixAction.NONE
                        )
                    }
                    updateNotification(getString(R.string.status_connected) + " · ${route.name}", false)
                    startTelemetryLoop(profile)
                    return@withLock
                }
                is CoreStartResult.MissingBinary -> {
                    lastError = "Native binary missing: ${coreRes.binaryName} (${coreRes.expectedPath}). Build native libs with scripts/build-native.sh."
                    dropReason = DropReason.MISSING_BINARY
                    suggestedFix = SuggestedFixAction.NONE
                    break
                }
                is CoreStartResult.PortConflict -> {
                    lastError = "Local port ${coreRes.port} is already bound by another app."
                    dropReason = DropReason.PORT_CONFLICT
                    suggestedFix = SuggestedFixAction.RESET_PORTS
                    break
                }
                is CoreStartResult.Failed -> {
                    lastError = coreRes.reason
                    dropReason = DropReason.CORE_EXITED
                    suggestedFix = SuggestedFixAction.RUN_DIAGNOSTICS
                }
            }
        }

        stopTunnel(dropReason, lastError, suggestedFix, stopService = true)
    }

    private suspend fun pauseTunnel() = lifecycleMutex.withLock {
        if (isPaused || tunInterface == null) return@withLock
        isPaused = true
        telemetryJob?.cancel()
        telemetryJob = null
        // Keep core process warm while closing TUN interface so traffic resumes instantly
        runCatching { tunManager.stopTun() }
        runCatching { tunInterface?.close() }
        tunInterface = null

        ServiceLocator.vpnController.updateRuntimeStatus {
            it.copy(
                state = VpnState.PAUSED,
                activeTunEngine = TunEngineType.NONE,
                downSpeedBps = 0L,
                upSpeedBps = 0L
            )
        }
        updateNotification(getString(R.string.status_paused), isPaused = true)
        LogRepository.i("NovaVpnService", "TUN interface paused (core kept alive)")
    }

    private suspend fun resumeTunnel() = lifecycleMutex.withLock {
        if (!isPaused) return@withLock
        val settings = ServiceLocator.settingsRepository.getSettings()
        val profile = ServiceLocator.settingsRepository.getActiveProfile()
        val tunPfd = buildVpnInterface(settings, profile)
        if (tunPfd == null) {
            stopTunnel(DropReason.TUN_START_FAILED, "Could not recreate TUN interface on resume", SuggestedFixAction.GRANT_VPN_PERMISSION, true)
            return@withLock
        }
        tunInterface = tunPfd
        val res = tunManager.startTun(tunPfd, activeSocksPort, profile, settings.tunEnginePreference)
        if (res.isFailure) {
            runCatching { tunPfd.close() }
            tunInterface = null
            stopTunnel(DropReason.TUN_START_FAILED, res.exceptionOrNull()?.message, SuggestedFixAction.SWITCH_TO_HEV_TUN, true)
            return@withLock
        }

        isPaused = false
        ServiceLocator.vpnController.updateRuntimeStatus {
            it.copy(
                state = VpnState.CONNECTED,
                activeTunEngine = res.getOrThrow(),
                errorMessage = null
            )
        }
        updateNotification(getString(R.string.status_connected), isPaused = false)
        startTelemetryLoop(profile)
        LogRepository.i("NovaVpnService", "TUN interface resumed")
    }

    private suspend fun stopTunnel(
        reason: DropReason,
        errorMessage: String?,
        suggestedFix: SuggestedFixAction = SuggestedFixAction.NONE,
        stopService: Boolean
    ) = lifecycleMutex.withLock {
        isUserStopping = true
        isPaused = false
        telemetryJob?.cancel()
        telemetryJob = null
        reconnectJob?.cancel()
        reconnectJob = null

        runCatching { tunManager.stopTun() }
        runCatching { tunInterface?.close() }
        tunInterface = null
        runCatching { coreManager.stopAll() }

        ServiceLocator.settingsRepository.setVpnActiveState(false)

        val prev = ServiceLocator.vpnController.status.value
        if (sessionStartMs > 0L && prev.uptimeSeconds >= 2L) {
            ServiceLocator.statsRepository.recordSession(
                SessionRecord(
                    id = UUID.randomUUID().toString(),
                    profileName = prev.activeProfileName,
                    routeChain = prev.activeRoute,
                    startedAtMs = sessionStartMs,
                    durationSec = prev.uptimeSeconds,
                    downloadBytes = prev.sessionDownBytes,
                    uploadBytes = prev.sessionUpBytes,
                    exitCountryCode = prev.exitCountryCode,
                    dropReason = reason
                )
            )
        }
        sessionStartMs = 0L

        ServiceLocator.vpnController.updateRuntimeStatus {
            it.copy(
                state = if (errorMessage != null) VpnState.ERROR else VpnState.DISCONNECTED,
                activeTunEngine = TunEngineType.NONE,
                downSpeedBps = 0L,
                upSpeedBps = 0L,
                smartProbeStatus = null,
                lastDropReason = reason,
                errorMessage = errorMessage,
                suggestedFix = suggestedFix
            )
        }
        if (stopService) {
            stopForegroundAndSelf()
        }
    }

    private fun startTelemetryLoop(profile: VpnProfile) {
        telemetryJob?.cancel()
        val controller = ServiceLocator.vpnController
        val diagnostics = ServiceLocator.diagnosticsEngine

        telemetryJob = serviceScope.launch {
            var lastRx = TrafficStats.getUidRxBytes(Process.myUid()).coerceAtLeast(0L)
            var lastTx = TrafficStats.getUidTxBytes(Process.myUid()).coerceAtLeast(0L)
            val startRx = lastRx
            val startTx = lastTx
            var tick = 0

            // Fetch exit IP asynchronously
            launch {
                val geo = diagnostics.fetchExitIpInfo(activeSocksPort)
                if (geo != null) {
                    controller.updateRuntimeStatus {
                        it.copy(exitIp = geo.ip, exitCountryCode = geo.countryCode, exitCity = geo.city)
                    }
                }
            }

            while (isActive && !isPaused && !isUserStopping) {
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
                val uptime = if (sessionStartMs > 0L) (System.currentTimeMillis() - sessionStartMs) / 1000L else 0L

                controller.pushSpeedSample(downBps, upBps)
                controller.updateRuntimeStatus {
                    it.copy(
                        uptimeSeconds = uptime,
                        downSpeedBps = downBps,
                        upSpeedBps = upBps,
                        sessionDownBytes = totalDown,
                        sessionUpBytes = totalUp
                    )
                }

                if (tick % 2 == 0) {
                    val speedSummary = "↓ ${formatCompactRate(downBps)} · ↑ ${formatCompactRate(upBps)}"
                    updateNotification(speedSummary, isPaused = false)
                }

                if (tick % profile.pingIntervalSec.coerceIn(5, 120) == 0) {
                    val result = diagnostics.verifyRealHttpsConnectivity(activeSocksPort)
                    if (result.isSuccess) {
                        reconnectMachine.onPingSucceeded()
                        controller.updateRuntimeStatus { it.copy(pingMs = result.getOrNull()) }
                    } else {
                        val decision = reconnectMachine.onPingFailed()
                        if (decision.action != ReconnectActionType.NONE) {
                            handleReconnectDecision(decision, null)
                            break
                        }
                    }
                }
            }
        }
    }

    private suspend fun handleReconnectDecision(
        decision: app.novavpn.core.reconnect.ReconnectDecision,
        @Suppress("UNUSED_PARAMETER") hint: String?
    ) {
        if (isUserStopping || isPaused) return
        val controller = ServiceLocator.vpnController
        when (decision.action) {
            ReconnectActionType.NONE -> Unit
            ReconnectActionType.WAIT_FOR_NETWORK -> {
                controller.updateRuntimeStatus {
                    it.copy(state = VpnState.RECONNECTING, lastDropReason = decision.dropReason)
                }
                updateNotification("Waiting for network…", isPaused = false)
            }
            ReconnectActionType.RECONNECT_WITH_DELAY, ReconnectActionType.SWITCH_ROUTE_AND_RECONNECT -> {
                reconnectJob?.cancel()
                reconnectJob = serviceScope.launch {
                    controller.updateRuntimeStatus {
                        it.copy(
                            state = VpnState.RECONNECTING,
                            reconnectAttempt = decision.nextAttemptCount,
                            lastDropReason = decision.dropReason
                        )
                    }
                    updateNotification(getString(R.string.status_reconnecting) + " (#${decision.nextAttemptCount})", false)
                    delay(decision.delayMs)
                    if (!isUserStopping && !isPaused) {
                        runCatching { tunManager.stopTun() }
                        runCatching { tunInterface?.close() }
                        tunInterface = null
                        coreManager.stopAll()
                        startTunnel(useFreshScan = decision.useFreshScan)
                    }
                }
            }
            ReconnectActionType.STOP_FAILED -> {
                stopTunnel(
                    reason = decision.dropReason,
                    errorMessage = "Auto-reconnect exhausted after ${decision.nextAttemptCount} attempts.",
                    suggestedFix = SuggestedFixAction.FRESH_SCAN,
                    stopService = true
                )
            }
        }
    }

    private fun buildVpnInterface(settings: AppSettings, profile: VpnProfile): ParcelFileDescriptor? {
        return runCatching {
            val builder = Builder()
                .setSession("Nova VPN · ${profile.name}")
                .setMtu(profile.mtu.coerceIn(576, 1500))
                .addAddress("10.10.0.1", 24)
                .addRoute("0.0.0.0", 0)
                .setBlocking(settings.killSwitchBlocking)

            if (profile.ipv6Enabled) {
                builder.addAddress("fd00::1", 64)
                builder.addRoute("::", 0)
            }

            // DNS inside TUN
            val primaryDnsIp = when (profile.dnsMode) {
                DnsMode.PLAIN -> profile.customDnsPrimary.takeIf { it.count { c -> c == '.' } == 3 } ?: "1.1.1.1"
                else -> "10.10.0.2" // Intercepted by sing-box DNS outbound
            }
            builder.addDnsServer(primaryDnsIp)
            if (profile.customDnsSecondary.isNotBlank() && profile.customDnsSecondary.count { c -> c == '.' } == 3) {
                builder.addDnsServer(profile.customDnsSecondary.trim())
            }

            // Prevent routing loops by always disallowing our own app package
            runCatching { builder.addDisallowedApplication(packageName) }

            when (settings.splitTunnelMode) {
                SplitTunnelMode.DISABLED -> Unit
                SplitTunnelMode.EXCLUDE -> {
                    settings.splitTunnelPackages.forEach { pkg ->
                        if (pkg != packageName) {
                            runCatching { builder.addDisallowedApplication(pkg) }
                        }
                    }
                }
                SplitTunnelMode.INCLUDE -> {
                    // Only allow selected packages; self remains excluded so core sockets stay outside VPN
                    settings.splitTunnelPackages.forEach { pkg ->
                        if (pkg != packageName) {
                            runCatching { builder.addAllowedApplication(pkg) }
                        }
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }
            builder.establish()
        }.getOrElse { err ->
            LogRepository.e("NovaVpnService", "Failed to establish VpnService interface: ${err.message}")
            null
        }
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (isUserStopping) return
                val key = ServiceLocator.vpnController.currentNetworkKey()
                val decision = reconnectMachine.onNetworkAvailable(key)
                if (decision.action != ReconnectActionType.NONE) {
                    serviceScope.launch { handleReconnectDecision(decision, null) }
                }
            }

            override fun onLost(network: Network) {
                if (isUserStopping) return
                val decision = reconnectMachine.onNetworkLost()
                serviceScope.launch { handleReconnectDecision(decision, null) }
            }
        }
        networkCallback = cb
        runCatching { cm.registerNetworkCallback(req, cb) }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        networkCallback = null
        runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
    }

    private fun buildNotification(text: String, isPaused: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, NovaVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseOrResumeIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, NovaVpnService::class.java).setAction(if (isPaused) ACTION_RESUME else ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NovaVpnApplication.CHANNEL_VPN_STATUS)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(
                0,
                getString(if (isPaused) R.string.notif_action_resume else R.string.notif_action_pause),
                pauseOrResumeIntent
            )
            .addAction(0, getString(R.string.notif_action_disconnect), disconnectIntent)
            .build()
    }

    private fun updateNotification(text: String, isPaused: Boolean) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_ID, buildNotification(text, isPaused))
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundAndSelf() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun formatCompactRate(bps: Long): String {
        return when {
            bps < 1024L -> "${bps} B/s"
            bps < 1024L * 1024L -> "${bps / 1024L} KB/s"
            else -> String.format("%.1f MB/s", bps / (1024.0 * 1024.0))
        }
    }

    companion object {
        const val ACTION_START = "app.novavpn.ACTION_START"
        const val ACTION_STOP = "app.novavpn.ACTION_STOP"
        const val ACTION_PAUSE = "app.novavpn.ACTION_PAUSE"
        const val ACTION_RESUME = "app.novavpn.ACTION_RESUME"
        const val EXTRA_FRESH_SCAN = "extra_fresh_scan"
        private const val NOTIFICATION_ID = 4201
    }
}
