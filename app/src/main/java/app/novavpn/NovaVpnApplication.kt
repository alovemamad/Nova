package app.novavpn

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import app.novavpn.core.net.DiagnosticsEngine
import app.novavpn.data.store.SettingsRepository
import app.novavpn.data.store.StatsRepository
import app.novavpn.service.VpnController

class NovaVpnApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannels(this)
        ServiceLocator.init(this)
    }

    companion object {
        const val CHANNEL_VPN_STATUS = "nova_vpn_status"

        fun createNotificationChannels(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(NotificationManager::class.java) ?: return
                val channel = NotificationChannel(
                    CHANNEL_VPN_STATUS,
                    context.getString(com.example.R.string.notif_channel_vpn),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(com.example.R.string.notif_channel_vpn_desc)
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }
}

object ServiceLocator {
    @Volatile
    private var initialized = false

    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var statsRepository: StatsRepository
        private set
    lateinit var diagnosticsEngine: DiagnosticsEngine
        private set
    lateinit var vpnController: VpnController
        private set

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val appCtx = context.applicationContext
            settingsRepository = SettingsRepository(appCtx)
            statsRepository = StatsRepository(appCtx)
            diagnosticsEngine = DiagnosticsEngine()
            vpnController = VpnController(appCtx, settingsRepository, statsRepository, diagnosticsEngine)
            initialized = true
        }
    }
}
