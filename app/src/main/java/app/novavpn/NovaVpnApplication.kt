package app.novavpn

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import app.novavpn.core.net.DiagnosticsEngine
import app.novavpn.core.tun.TunnelStartOrchestrator
import app.novavpn.data.log.LogRepository
import app.novavpn.data.store.GeoResourceRepository
import app.novavpn.data.store.SettingsRepository
import app.novavpn.data.store.StatsRepository
import app.novavpn.service.VpnController
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NovaVpnApplication : Application() {
    private val appExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e("NovaVpnApp", "Uncaught background error", throwable)
        runCatching {
            LogRepository.e("NovaVpnApp", "${throwable::class.java.simpleName}: ${throwable.message}")
        }
    }
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + appExceptionHandler)

    override fun onCreate() {
        super.onCreate()
        try {
            createNotificationChannels(this)
        } catch (t: Throwable) {
            Log.e("NovaVpnApp", "createNotificationChannels failed", t)
            runCatching { LogRepository.e("NovaVpnApp", "Notification channel setup failed: ${t.message}") }
        }
        try {
            ServiceLocator.init(this)
            appScope.launch {
                try {
                    ServiceLocator.settingsRepository.ensurePerInstallRandomPorts()
                    ServiceLocator.geoResourceRepository.loadInitial()
                } catch (t: Throwable) {
                    Log.e("NovaVpnApp", "startup async init failed", t)
                    LogRepository.e("NovaVpnApp", "Startup init failed: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            Log.e("NovaVpnApp", "ServiceLocator.init failed", t)
            ServiceLocator.recordInitError(t)
            runCatching {
                LogRepository.e("NovaVpnApp", "Startup init failed: ${Log.getStackTraceString(t)}")
            }
        }
    }

    companion object {
        const val CHANNEL_VPN_STATUS = "nova_vpn_status"
        const val CHANNEL_VPN_ALERTS = "nova_vpn_alerts"

        fun createNotificationChannels(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(NotificationManager::class.java) ?: return
                val statusChannel = NotificationChannel(
                    CHANNEL_VPN_STATUS,
                    context.getString(com.example.R.string.notif_channel_vpn),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(com.example.R.string.notif_channel_vpn_desc)
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                }
                val alertsChannel = NotificationChannel(
                    CHANNEL_VPN_ALERTS,
                    context.getString(com.example.R.string.notif_channel_alerts),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = context.getString(com.example.R.string.notif_channel_alerts_desc)
                }
                nm.createNotificationChannel(statusChannel)
                nm.createNotificationChannel(alertsChannel)
            }
        }
    }
}

object ServiceLocator {
    @Volatile
    private var initialized = false

    @Volatile
    var initError: Throwable? = null
        private set

    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var statsRepository: StatsRepository
        private set
    lateinit var geoResourceRepository: GeoResourceRepository
        private set
    lateinit var diagnosticsEngine: DiagnosticsEngine
        private set
    lateinit var tunnelOrchestrator: TunnelStartOrchestrator
        private set
    lateinit var vpnController: VpnController
        private set

    fun recordInitError(error: Throwable) {
        initError = error
    }

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val appCtx = context.applicationContext
            settingsRepository = SettingsRepository(appCtx)
            statsRepository = StatsRepository(appCtx)
            geoResourceRepository = GeoResourceRepository(appCtx)
            diagnosticsEngine = DiagnosticsEngine()
            tunnelOrchestrator = TunnelStartOrchestrator(settingsRepository, diagnosticsEngine)
            vpnController = VpnController(appCtx, settingsRepository, statsRepository, diagnosticsEngine, tunnelOrchestrator)
            initError = null
            initialized = true
        }
    }
}
