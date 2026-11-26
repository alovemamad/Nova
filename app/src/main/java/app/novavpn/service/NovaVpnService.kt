package app.novavpn.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import app.novavpn.NovaVpnApplication
import app.novavpn.ServiceLocator
import app.novavpn.core.SessionProxyAuth
import app.novavpn.core.net.ConnectivityHelper
import app.novavpn.core.process.CoreProcessManager
import app.novavpn.core.reconnect.ReconnectActionType
import app.novavpn.core.reconnect.ReconnectDecision
import app.novavpn.core.reconnect.ReconnectStateMachine
import app.novavpn.core.runCatchingCancellable
import app.novavpn.core.tun.CoreProbeOutcome
import app.novavpn.core.tun.SplitTunnelPlanner
import app.novavpn.core.tun.TunEngineManager
import app.novavpn.data.log.LogRepository
import app.novavpn.domain.model.AppSettings
import app.novavpn.domain.model.DropReason
import app.novavpn.domain.model.RouteChain
import app.novavpn.domain.model.SessionRecord
import app.novavpn.domain.model.SuggestedFixAction
import app.novavpn.domain.model.TunEngineType
import app.novavpn.domain.model.VpnProfile
import app.novavpn.domain.model.VpnState
import app.novavpn.domain.routing.GeoDatParser
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class NovaVpnService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var coreManager: CoreProcessManager
    private lateinit var tunManager: TunEngineManager
    private lateinit var coordinator: TunnelLifecycleCoordinator
    private val reconnectMachine = ReconnectStateMachine()

    @Volatile
    private var tunInterface: ParcelFileDescriptor? = null

    @Volatile
    private var sessionStartMs: Long = 0L

    @Volatile
    private var connectStartedElapsedMs: Long = 0L

    @Volatile
    private var latestCompiledGeo: GeoDatParser.CompiledProfileGeoRules = GeoDatParser.CompiledProfileGeoRules()

    private var telemetryJob: Job? = null
    private var reconnectJob: Job? = null
    private var networkDebounceJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val openPendingIntent: PendingIntent by lazy {
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private val disconnectPendingIntent: PendingIntent by lazy {
        PendingIntent.getService(
            this,
            1,
            Intent(this, NovaVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private val pausePendingIntent: PendingIntent by lazy {
        PendingIntent.getService(
            this,
            2,
            Intent(this, NovaVpnService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private val resumePendingIntent: PendingIntent by lazy {
        PendingIntent.getService(
            this,
            3,
            Intent(this, NovaVpnService::class.java).setAction(ACTION_RESUME),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(applicationContext)
        NovaVpnApplication.createNotificationChannels(this)
        tunManager = TunEngineManager(applicationContext)
        coreManager = CoreProcessManager(
            context = applicationContext,
            scope = serviceScope,
            onUnexpectedExit = { exitCode, binaryName ->
                if (!coordinator.isUserStopping && !coordinator.isPaused && coordinator.state == VpnState.CONNECTED) {
                    LogRepository.w("NovaVpnService", "Unexpected exit of $binaryName ($exitCode)")
                    val decision = reconnectMachine.onCoreDied()
                    serviceScope.launch { handleReconnectDecision(decision) }
                }
            },
            onFatalTunnelError = { summary ->
                if (!coordinator.isUserStopping && !coordinator.isPaused && coordinator.state == VpnState.CONNECTED) {
                    LogRepository.w("NovaVpnService", "Fatal tunnel error ($summary); triggering immediate reconnect")
                    val decision = reconnectMachine.onCoreDied()
                    serviceScope.launch { handleReconnectDecision(decision) }
                }
            }
        )
        coordinator = TunnelLifecycleCoordinator(createLifecycleOps())
        registerDefaultPhysicalNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(buildNotification(getString(R.string.status_connecting), isPaused = false))

        when (intent?.action) {
            ACTION_START -> {
                val fresh = intent.getBooleanExtra(EXTRA_FRESH_SCAN, false)
                reconnectJob?.cancel()
                reconnectJob = null
                serviceScope.launch {
                    val settings = ServiceLocator.settingsRepository.getSettings()
                    if (coordinator.state == VpnState.CONNECTED || coordinator.state == VpnState.RECONNECTING) {
                        coordinator.reconnect(useFreshScan = fresh, keepTunOpen = settings.keepTunOnReconnect)
                    } else if (coordinator.state != VpnState.CONNECTING) {
                        coordinator.start(useFreshScan = fresh)
                    }
                }
            }
            ACTION_STOP -> {
                cancelBackgroundJobs()
                coordinator.requestStop()
                coreManager.forceAbort()
                serviceScope.launch {
                    coordinator.stop(DropReason.USER_DISCONNECTED, errorMessage = null, stopService = true)
                }
            }
            ACTION_PAUSE -> {
                reconnectJob?.cancel()
                reconnectJob = null
                telemetryJob?.cancel()
                telemetryJob = null
                serviceScope.launch {
                    coordinator.pause()
                }
            }
            ACTION_RESUME -> {
                serviceScope.launch {
                    coordinator.resume()
                }
            }
            null -> {
                // Service restarted by OS (START_STICKY)
                serviceScope.launch {
                    val settings = ServiceLocator.settingsRepository.getSettings()
                    if (settings.wasVpnActiveBeforeKill) {
                        LogRepository.i("NovaVpnService", "Restoring tunnel after process kill")
                        coordinator.start(useFreshScan = false)
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
        cancelBackgroundJobs()
        coordinator.requestStop()
        coreManager.forceAbort()
        serviceScope.launch {
            coordinator.stop(
                reason = DropReason.VPN_REVOKED,
                errorMessage = "VPN permission was revoked or replaced by another VPN app.",
                suggestedFix = SuggestedFixAction.GRANT_VPN_PERMISSION,
                stopService = true
            )
        }
    }

    override fun onDestroy() {
        unregisterNetworkCallback()
        cancelBackgroundJobs()
        releaseLocks()
        val fdToClose = tunInterface
        tunInterface = null
        serviceScope.launch(Dispatchers.IO) {
            withContext(NonCancellable) {
                runCatchingCancellable { tunManager.stopTun() }
                runCatchingCancellable { fdToClose?.close() }
                runCatchingCancellable { coreManager.stopAll() }
            }
            serviceScope.cancel()
        }
        super.onDestroy()
    }

    private fun acquireConnectingLocks() {
        if (wakeLock?.isHeld != true) {
            runCatching {
                val pm = getSystemService(PowerManager::class.java)
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NovaVpn:CoreWakeLock")?.apply {
                    setReferenceCounted(false)
                    acquire(120_000L)
                }
            }
        }
        if (wifiLock?.isHeld != true) {
            runCatching {
                val wm = applicationContext.getSystemService(WifiManager::class.java)
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm?.createWifiLock(mode, "NovaVpn:WifiLock")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        }
    }

    private fun releaseWakeLockOnly() {
        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
        }
        wakeLock = null
    }

    private fun releaseLocks() {
        releaseWakeLockOnly()
        runCatching {
            wifiLock?.let { if (it.isHeld) it.release() }
        }
        wifiLock = null
    }

    private fun cancelBackgroundJobs() {
        telemetryJob?.cancel()
        telemetryJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        networkDebounceJob?.cancel()
        networkDebounceJob = null
    }

    private fun createLifecycleOps(): TunnelLifecycleCoordinator.Ops {
        val controller = ServiceLocator.vpnController
        val settingsRepo = ServiceLocator.settingsRepository
        val orchestrator = ServiceLocator.tunnelOrchestrator

        return object : TunnelLifecycleCoordinator.Ops {
            override suspend fun onConnecting() {
                telemetryJob?.cancel()
                telemetryJob = null
                connectStartedElapsedMs = SystemClock.elapsedRealtime()
                acquireConnectingLocks()
                val settings = settingsRepo.getSettings()
                val profile = settingsRepo.getActiveProfile()
                reconnectMachine.updateConfig(settings.preferLastGoodGateway, profile.pingFailureLimit)

                controller.updateSessionProxyAuth(null)
                controller.updateRuntimeStatus {
                    it.copy(
                        state = VpnState.CONNECTING,
                        activeProfileName = profile.name,
                        activeRoute = profile.routeChain,
                        activeProtocol = profile.aetherProtocol,
                        errorMessage = null,
                        suggestedFix = SuggestedFixAction.NONE,
                        sessionProxyUser = null,
                        sessionProxyPass = null
                    )
                }
                updateNotification(getString(R.string.status_connecting), isPaused = false)
            }

            override suspend fun startCoreAndProbe(
                useFreshScan: Boolean,
                isUserStopping: () -> Boolean
            ): TunnelLifecycleCoordinator.CoreStepResult {
                val settings = settingsRepo.getSettings()
                val profile = settingsRepo.getActiveProfile()
                val networkKey = ConnectivityHelper.currentNetworkKey(applicationContext)

                val outcome = orchestrator.startCoreAndProbe(
                    coreManager = coreManager,
                    settings = settings,
                    profile = profile,
                    networkKey = networkKey,
                    useFreshScan = useFreshScan,
                    isCancelled = isUserStopping,
                    onProbeProgress = { label -> controller.updateSmartProbeStatus(label) },
                    verifyHttpsAfterCoreStart = true
                )

                return when (outcome) {
                    is CoreProbeOutcome.Ready -> {
                        latestCompiledGeo = outcome.compiledGeoRules
                        TunnelLifecycleCoordinator.CoreStepResult.Ready(
                            route = outcome.route,
                            socksPort = outcome.socksPort,
                            httpPort = outcome.httpPort,
                            auth = outcome.auth,
                            pingMs = outcome.initialPingMs
                        )
                    }
                    is CoreProbeOutcome.Failure -> {
                        latestCompiledGeo = GeoDatParser.CompiledProfileGeoRules()
                        TunnelLifecycleCoordinator.CoreStepResult.Failed(
                            reason = outcome.dropReason,
                            errorMessage = outcome.errorMessage,
                            suggestedFix = outcome.suggestedFix
                        )
                    }
                }
            }

            override suspend fun hasTunOpen(): Boolean = tunInterface != null

            override suspend fun openAndStartTun(
                socksPort: Int,
                auth: SessionProxyAuth?
            ): TunnelLifecycleCoordinator.TunStepResult {
                val settings = settingsRepo.getSettings()
                val profile = settingsRepo.getActiveProfile()

                var pfd = tunInterface
                var createdHere = false
                if (pfd == null) {
                    val builtResult = buildVpnInterface(settings, profile)
                    if (builtResult.isFailure) {
                        val err = builtResult.exceptionOrNull()
                        val reason = if (err is IllegalArgumentException) DropReason.INVALID_CONFIG else DropReason.TUN_START_FAILED
                        val fix = if (err is IllegalArgumentException) SuggestedFixAction.NONE else SuggestedFixAction.GRANT_VPN_PERMISSION
                        return TunnelLifecycleCoordinator.TunStepResult.Failed(
                            reason = reason,
                            errorMessage = err?.message ?: "Failed to establish VpnService interface",
                            suggestedFix = fix
                        )
                    }
                    pfd = builtResult.getOrThrow()
                    tunInterface = pfd
                    createdHere = true
                }

                val tunStartMs = SystemClock.elapsedRealtime()
                val geo = latestCompiledGeo
                val tunRes = tunManager.startTun(
                    tunInterface = pfd,
                    socksPort = socksPort,
                    auth = auth,
                    profile = profile,
                    preference = settings.tunEnginePreference,
                    extraDirectCidrs = geo.directCidrs,
                    extraDirectDomains = geo.directDomains,
                    extraBlockCidrs = geo.blockCidrs,
                    extraBlockDomains = geo.blockDomains,
                    protectFd = ::protect
                )
                if (tunRes.isFailure) {
                    if (createdHere) {
                        runCatching { pfd.close() }
                        tunInterface = null
                    }
                    return TunnelLifecycleCoordinator.TunStepResult.Failed(
                        reason = DropReason.TUN_START_FAILED,
                        errorMessage = tunRes.exceptionOrNull()?.message ?: "TUN engine failed",
                        suggestedFix = SuggestedFixAction.SWITCH_TO_HEV_TUN
                    )
                }

                val tunEngine = tunRes.getOrThrow()
                LogRepository.i("Timing", "TUN ($tunEngine) started in ${SystemClock.elapsedRealtime() - tunStartMs}ms")
                return TunnelLifecycleCoordinator.TunStepResult.Ready(tunEngine)
            }

            override suspend fun stopTunOnly(closeTunFd: Boolean) {
                runCatchingCancellable { tunManager.stopTun() }
                if (closeTunFd) {
                    val fd = tunInterface
                    tunInterface = null
                    runCatching { fd?.close() }
                }
            }

            override suspend fun stopAllCoreAndTun() {
                runCatchingCancellable { tunManager.stopTun() }
                val fd = tunInterface
                tunInterface = null
                runCatching { fd?.close() }
                runCatchingCancellable { coreManager.stopAll() }
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
                releaseWakeLockOnly()
                val totalStart = connectStartedElapsedMs
                if (totalStart > 0L) {
                    LogRepository.i("Timing", "Total connect elapsed: ${SystemClock.elapsedRealtime() - totalStart}ms")
                    connectStartedElapsedMs = 0L
                }
                val settings = settingsRepo.getSettings()
                val profile = settingsRepo.getActiveProfile()
                settingsRepo.setVpnActiveState(true)
                reconnectMachine.reset()
                if (!resumed || sessionStartMs == 0L) {
                    sessionStartMs = System.currentTimeMillis()
                }
                controller.updateSessionProxyAuth(auth)
                controller.updateRuntimeStatus {
                    it.copy(
                        state = VpnState.CONNECTED,
                        activeProfileName = profile.name,
                        activeRoute = route,
                        activeProtocol = profile.aetherProtocol,
                        activeTunEngine = tunEngine,
                        pingMs = pingMs ?: it.pingMs,
                        smartProbeStatus = null,
                        reconnectAttempt = 0,
                        socksPort = socksPort,
                        httpPort = httpPort,
                        lanShareActive = settings.shareProxyOnLan,
                        sessionProxyUser = auth?.username,
                        sessionProxyPass = auth?.password,
                        errorMessage = null,
                        suggestedFix = SuggestedFixAction.NONE
                    )
                }
                val isSlowRoute = route in setOf(
                    RouteChain.PSIPHON_ONLY,
                    RouteChain.AETHER_PSIPHON,
                    RouteChain.TOR_ONLY,
                    RouteChain.AETHER_TOR
                )
                val effectivePingLimit = if (isSlowRoute) maxOf(profile.pingFailureLimit, 5) else profile.pingFailureLimit
                reconnectMachine.updateConfig(settings.preferLastGoodGateway, effectivePingLimit)
                updateNotification(getString(R.string.status_connected) + " · ${route.name}", isPaused = false)
                startTelemetryLoop(profile, socksPort, auth, tunEngine, route)
                LogRepository.i("NovaVpnService", if (resumed) "TUN interface resumed" else "VPN connected via $route ($tunEngine)")
            }

            override suspend fun onPaused() {
                releaseLocks()
                controller.updateRuntimeStatus {
                    it.copy(
                        state = VpnState.PAUSED,
                        activeTunEngine = TunEngineType.NONE,
                        downSpeedBps = 0L,
                        upSpeedBps = 0L
                    )
                }
                updateNotification(getString(R.string.status_paused_direct_warning), isPaused = true)
                LogRepository.i("NovaVpnService", "TUN interface paused (direct traffic bypass active; core kept alive)")
            }

            override suspend fun onStopped(
                reason: DropReason,
                errorMessage: String?,
                suggestedFix: SuggestedFixAction,
                stopService: Boolean
            ) {
                releaseLocks()
                connectStartedElapsedMs = 0L
                latestCompiledGeo = GeoDatParser.CompiledProfileGeoRules()
                settingsRepo.setVpnActiveState(false)
                controller.updateSessionProxyAuth(null)

                val prev = controller.status.value
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

                controller.updateRuntimeStatus {
                    it.copy(
                        state = if (errorMessage != null) VpnState.ERROR else VpnState.DISCONNECTED,
                        activeTunEngine = TunEngineType.NONE,
                        downSpeedBps = 0L,
                        upSpeedBps = 0L,
                        smartProbeStatus = null,
                        lastDropReason = reason,
                        errorMessage = errorMessage,
                        suggestedFix = suggestedFix,
                        sessionProxyUser = null,
                        sessionProxyPass = null
                    )
                }
                if (stopService) {
                    stopForegroundAndSelf()
                }
            }
        }
    }

    private fun startTelemetryLoop(
        profile: VpnProfile,
        socksPort: Int,
        auth: SessionProxyAuth?,
        tunEngine: TunEngineType,
        resolvedRoute: RouteChain
    ) {
        telemetryJob?.cancel()
        val controller = ServiceLocator.vpnController
        val diagnostics = ServiceLocator.diagnosticsEngine
        val isSlowRoute = resolvedRoute in setOf(
            RouteChain.PSIPHON_ONLY,
            RouteChain.AETHER_PSIPHON,
            RouteChain.TOR_ONLY,
            RouteChain.AETHER_TOR
        )
        val probeTimeoutMs = if (isSlowRoute) 25_000L else 9_000L
        val aliveDataWindowMs = if (isSlowRoute) 20_000L else 5_000L

        telemetryJob = serviceScope.launch {
            var lastUidRx = TrafficStats.getUidRxBytes(Process.myUid()).coerceAtLeast(0L)
            var lastUidTx = TrafficStats.getUidTxBytes(Process.myUid()).coerceAtLeast(0L)
            val startUidRx = lastUidRx
            val startUidTx = lastUidTx

            var lastHevRx = -1L
            var lastHevTx = -1L
            var totalHevDown = 0L
            var totalHevUp = 0L
            var stallSince = 0L
            var stallTicks = 0
            var lastRecycle = 0L
            var lastRxAtMs = SystemClock.elapsedRealtime()
            var tick = 0

            // Fetch exit IP asynchronously (with retries for slow Psiphon/Tor links)
            launch {
                val maxAttempts = if (isSlowRoute) 3 else 1
                for (attempt in 1..maxAttempts) {
                    val geo = diagnostics.fetchExitIpInfo(socksPort, auth)
                    if (geo != null) {
                        controller.updateRuntimeStatus {
                            it.copy(exitIp = geo.ip, exitCountryCode = geo.countryCode, exitCity = geo.city)
                        }
                        break
                    }
                    if (attempt < maxAttempts) delay(3_000L)
                }
            }

            var latestDownBps = 0L
            var latestRxAtMs = lastRxAtMs
            var pingProbeJob: Job? = null
            val powerManager = getSystemService(PowerManager::class.java)

            while (isActive && !coordinator.isPaused && !coordinator.isUserStopping) {
                delay(1000)
                tick++
                val now = SystemClock.elapsedRealtime()

                val hevStats = if (tunEngine == TunEngineType.HEV_SOCKS5_TUNNEL) {
                    tunManager.getHevStats()?.takeIf { it.size >= 4 }
                } else null

                val downBps: Long
                val upBps: Long
                val totalDown: Long
                val totalUp: Long

                if (hevStats != null) {
                    val rawTx = hevStats[1].coerceAtLeast(0L)
                    val rawRx = hevStats[3].coerceAtLeast(0L)
                    if (lastHevTx < 0L || rawTx < lastHevTx || rawRx < lastHevRx) {
                        lastHevTx = rawTx
                        lastHevRx = rawRx
                    }
                    upBps = (rawTx - lastHevTx).coerceAtLeast(0L)
                    downBps = (rawRx - lastHevRx).coerceAtLeast(0L)
                    lastHevTx = rawTx
                    lastHevRx = rawRx
                    totalHevDown += downBps
                    totalHevUp += upBps
                    totalDown = totalHevDown
                    totalUp = totalHevUp

                    if (downBps > 0L) {
                        lastRxAtMs = now
                        latestRxAtMs = now
                        stallSince = 0L
                        stallTicks = 0
                    } else if (upBps > 0L && coordinator.state == VpnState.CONNECTED) {
                        if (stallSince == 0L) stallSince = now
                        stallTicks++
                    }

                    // Hot-recycle stuck hev-socks5-tunnel after 20s TX-without-RX while keeping core alive
                    if (stallSince != 0L &&
                        now - stallSince >= 20_000L &&
                        stallTicks >= 8 &&
                        now - lastRecycle >= 60_000L
                    ) {
                        val fd = tunInterface?.fd ?: -1
                        if (fd >= 0) {
                            LogRepository.w(
                                "NovaVpnService",
                                "TUN data stall (${(now - stallSince) / 1000}s TX with 0 RX); hot-recycling hev-socks5-tunnel"
                            )
                            lastRecycle = now
                            stallSince = 0L
                            stallTicks = 0
                            if (tunManager.recycleHevIfActive(fd)) {
                                lastHevTx = -1L
                                lastHevRx = -1L
                            }
                        }
                    }
                } else {
                    val nowRx = TrafficStats.getUidRxBytes(Process.myUid()).coerceAtLeast(0L)
                    val nowTx = TrafficStats.getUidTxBytes(Process.myUid()).coerceAtLeast(0L)
                    downBps = (nowRx - lastUidRx).coerceAtLeast(0L)
                    upBps = (nowTx - lastUidTx).coerceAtLeast(0L)
                    lastUidRx = nowRx
                    lastUidTx = nowTx
                    totalDown = (nowRx - startUidRx).coerceAtLeast(0L)
                    totalUp = (nowTx - startUidTx).coerceAtLeast(0L)
                    if (downBps > 0L) {
                        lastRxAtMs = now
                        latestRxAtMs = now
                    }
                }
                latestDownBps = downBps

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

                if (tick % 4 == 0 && powerManager?.isInteractive != false) {
                    val speedSummary = "↓ ${formatCompactRate(downBps)} · ↑ ${formatCompactRate(upBps)}"
                    updateNotification(speedSummary, isPaused = false)
                }

                val effectiveInterval = if (isSlowRoute) {
                    maxOf(profile.pingIntervalSec, 15).coerceIn(5, 120)
                } else {
                    profile.pingIntervalSec.coerceIn(5, 120)
                }
                if (tick % effectiveInterval == 0 && pingProbeJob?.isActive != true) {
                    pingProbeJob = launch {
                        val result = diagnostics.verifyRealHttpsConnectivity(socksPort, auth, probeTimeoutMs)
                        if (coordinator.isPaused || coordinator.isUserStopping) return@launch
                        val nowAfterProbe = SystemClock.elapsedRealtime()
                        val recentData = latestDownBps > 0L || (nowAfterProbe - latestRxAtMs < aliveDataWindowMs)
                        if (result.isSuccess) {
                            reconnectMachine.onPingSucceeded()
                            controller.updateRuntimeStatus { it.copy(pingMs = result.getOrNull()) }
                        } else if (recentData) {
                            // Active tunnel traffic is flowing; probe timeout is likely link saturation rather than a dead tunnel
                            reconnectMachine.onPingSucceeded()
                            controller.updateRuntimeStatus { it.copy(pingMs = null) }
                        } else {
                            val decision = reconnectMachine.onPingFailed()
                            if (decision.action != ReconnectActionType.NONE) {
                                // Never run stop/reconnect inside telemetryJob (it cancels telemetryJob).
                                // Dispatch to a sibling job on serviceScope and cancel telemetry cleanly.
                                serviceScope.launch { handleReconnectDecision(decision) }
                                telemetryJob?.cancel()
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun handleReconnectDecision(decision: ReconnectDecision) {
        if (coordinator.isUserStopping || coordinator.isPaused) return
        val controller = ServiceLocator.vpnController
        when (decision.action) {
            ReconnectActionType.NONE -> Unit
            ReconnectActionType.WAIT_FOR_NETWORK -> {
                controller.updateRuntimeStatus {
                    it.copy(state = VpnState.RECONNECTING, lastDropReason = decision.dropReason)
                }
                updateNotification(getString(R.string.status_waiting_network), isPaused = false)
            }
            ReconnectActionType.RECONNECT_WITH_DELAY, ReconnectActionType.SWITCH_ROUTE_AND_RECONNECT -> {
                reconnectJob?.cancel()
                reconnectJob = serviceScope.launch {
                    val settings = ServiceLocator.settingsRepository.getSettings()
                    controller.updateRuntimeStatus {
                        it.copy(
                            state = VpnState.RECONNECTING,
                            reconnectAttempt = decision.nextAttemptCount,
                            lastDropReason = decision.dropReason
                        )
                    }
                    updateNotification(getString(R.string.status_reconnecting) + " (#${decision.nextAttemptCount})", isPaused = false)
                    delay(decision.delayMs)
                    if (!coordinator.isUserStopping && !coordinator.isPaused && coordinator.state != VpnState.CONNECTING) {
                        coordinator.reconnect(
                            useFreshScan = decision.useFreshScan,
                            keepTunOpen = settings.keepTunOnReconnect
                        )
                    }
                }
            }
            ReconnectActionType.STOP_FAILED -> {
                reconnectJob?.cancel()
                reconnectJob = null
                telemetryJob?.cancel()
                telemetryJob = null
                coordinator.stop(
                    reason = decision.dropReason,
                    errorMessage = "Auto-reconnect exhausted after ${decision.nextAttemptCount} attempts.",
                    suggestedFix = SuggestedFixAction.FRESH_SCAN,
                    stopService = true
                )
            }
        }
    }

    private fun buildVpnInterface(settings: AppSettings, profile: VpnProfile): Result<ParcelFileDescriptor> {
        val planResult = SplitTunnelPlanner.plan(
            selfPackage = packageName,
            mode = settings.splitTunnelMode,
            selectedPackages = settings.splitTunnelPackages,
            profile = profile,
            tunPreference = settings.tunEnginePreference
        )
        if (planResult.isFailure) {
            return Result.failure(planResult.exceptionOrNull()!!)
        }
        val plan = planResult.getOrThrow()

        return runCatching {
            val builder = Builder()
                .setSession("Nova VPN · ${profile.name}")
                .setMtu(profile.mtu.coerceIn(576, 1500))
                .addAddress("10.10.0.1", 24)
                .addRoute("0.0.0.0", 0)

            if (profile.ipv6Enabled) {
                builder.addAddress("fd00::1", 64)
                builder.addRoute("::", 0)
            }

            builder.addDnsServer(plan.primaryDnsServer)
            plan.secondaryDnsServer?.let { builder.addDnsServer(it) }

            if (plan.allowedPackages.isNotEmpty()) {
                var addedCount = 0
                for (pkg in plan.allowedPackages) {
                    try {
                        builder.addAllowedApplication(pkg)
                        addedCount++
                    } catch (e: PackageManager.NameNotFoundException) {
                        LogRepository.w("NovaVpnService", "Split-tunnel allowed app not installed ($pkg): ${e.message}")
                    }
                }
                if (addedCount == 0) {
                    throw IllegalArgumentException("No selected split-tunnel apps are currently installed.")
                }
            } else {
                for (pkg in plan.disallowedPackages) {
                    try {
                        builder.addDisallowedApplication(pkg)
                    } catch (e: PackageManager.NameNotFoundException) {
                        LogRepository.w("NovaVpnService", "Split-tunnel excluded app not installed ($pkg): ${e.message}")
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            builder.establish()
                ?: error("Android VpnService.Builder.establish() returned null. Check VPN permission or Always-on VPN.")
        }.onFailure { err ->
            LogRepository.e("NovaVpnService", "Failed to establish VpnService interface: ${err.message}")
        }
    }

    private fun registerDefaultPhysicalNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (coordinator.isUserStopping) return
                if (ConnectivityHelper.isVpnNetwork(cm, network)) return
                scheduleDebouncedNetworkAvailable(cm, network)
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (coordinator.isUserStopping) return
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return
                scheduleDebouncedNetworkAvailable(cm, network, caps)
            }

            override fun onLost(network: Network) {
                if (coordinator.isUserStopping) return
                if (ConnectivityHelper.isVpnNetwork(cm, network)) return
                // Only mark offline if no non-VPN underlying network remains
                val remaining = ConnectivityHelper.underlyingNetworkCapabilities(cm)
                if (remaining != null) return
                networkDebounceJob?.cancel()
                networkDebounceJob = serviceScope.launch {
                    delay(1_000L)
                    if (!coordinator.isUserStopping && ConnectivityHelper.underlyingNetworkCapabilities(cm) == null) {
                        val decision = reconnectMachine.onNetworkLost()
                        handleReconnectDecision(decision)
                    }
                }
            }
        }
        networkCallback = cb
        runCatching { cm.registerDefaultNetworkCallback(cb) }
    }

    private fun scheduleDebouncedNetworkAvailable(
        cm: ConnectivityManager,
        network: Network,
        knownCaps: NetworkCapabilities? = null
    ) {
        networkDebounceJob?.cancel()
        networkDebounceJob = serviceScope.launch {
            delay(1_000L)
            if (coordinator.isUserStopping) return@launch
            val caps = knownCaps ?: cm.getNetworkCapabilities(network) ?: ConnectivityHelper.underlyingNetworkCapabilities(cm)
            if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@launch
            val key = ConnectivityHelper.keyForCapabilities(applicationContext, caps)
            val decision = reconnectMachine.onNetworkAvailable(key)
            if (decision.action != ReconnectActionType.NONE) {
                handleReconnectDecision(decision)
            }
        }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        networkCallback = null
        runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
    }

    private fun buildNotification(text: String, isPaused: Boolean): Notification {
        val pauseOrResumeIntent = if (isPaused) resumePendingIntent else pausePendingIntent

        return NotificationCompat.Builder(this, NovaVpnApplication.CHANNEL_VPN_STATUS)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPendingIntent)
            .addAction(
                0,
                getString(if (isPaused) R.string.notif_action_resume else R.string.notif_action_pause),
                pauseOrResumeIntent
            )
            .addAction(0, getString(R.string.notif_action_disconnect), disconnectPendingIntent)
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
            bps < 1024L -> "$bps B/s"
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
