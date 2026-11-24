package app.novavpn.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import androidx.core.app.NotificationCompat
import app.novavpn.NovaVpnApplication
import app.novavpn.ServiceLocator
import app.novavpn.data.log.LogRepository
import app.novavpn.domain.model.VpnMode
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return

        val appCtx = context.applicationContext
        NovaVpnApplication.createNotificationChannels(appCtx)
        ServiceLocator.init(appCtx)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = ServiceLocator.settingsRepository.getSettings()
                if (!settings.autoConnect.startOnBoot) return@launch

                val prepared = settings.vpnMode == VpnMode.PROXY_ONLY || VpnService.prepare(appCtx) == null
                if (!prepared) {
                    LogRepository.w("BootReceiver", "Cannot auto-start on boot: VPN permission not granted")
                    postOpenAppNotification(appCtx)
                    return@launch
                }

                LogRepository.i("BootReceiver", "Auto-starting Nova VPN on boot")
                try {
                    ServiceLocator.vpnController.connectSyncOrThrow()
                } catch (e: Exception) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                        LogRepository.w("BootReceiver", "Android 12+ blocked background FGS start on boot; posting prompt notification")
                        postOpenAppNotification(appCtx)
                    } else if (e is IllegalStateException) {
                        LogRepository.w("BootReceiver", "Background FGS start rejected on boot (${e.message}); posting prompt notification")
                        postOpenAppNotification(appCtx)
                    } else {
                        LogRepository.e("BootReceiver", "Boot auto-start error: ${e.message}")
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun postOpenAppNotification(context: Context) {
        val openIntent = PendingIntent.getActivity(
            context,
            901,
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(context, NovaVpnApplication.CHANNEL_VPN_ALERTS)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentTitle(context.getString(R.string.boot_fgs_blocked_title))
            .setContentText(context.getString(R.string.boot_fgs_blocked_body))
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(9001, notif)
    }
}
