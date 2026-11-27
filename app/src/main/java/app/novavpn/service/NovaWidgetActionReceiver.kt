package app.novavpn.service

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import app.novavpn.ServiceLocator
import app.novavpn.core.DevicePlatform
import app.novavpn.domain.model.VpnState
import com.example.MainActivity

/**
 * Non-exported internal receiver handling widget toggle clicks so third-party apps
 * cannot spoof toggle broadcasts to start/stop the VPN.
 */
class NovaWidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (DevicePlatform.isTv(context)) return
        if (intent.action != ACTION_WIDGET_TOGGLE) return
        ServiceLocator.init(context.applicationContext)
        val controller = ServiceLocator.vpnController
        val state = controller.status.value.state

        if (state == VpnState.DISCONNECTED || state == VpnState.ERROR) {
            if (controller.needsVpnPermission()) {
                DevicePlatform.safeStartActivity(
                    context,
                    Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            } else {
                controller.connect()
            }
        } else {
            controller.disconnect()
        }

        val mgr = AppWidgetManager.getInstance(context)
        val ids = mgr.getAppWidgetIds(ComponentName(context, NovaVpnWidgetProvider::class.java))
        if (ids.isNotEmpty()) {
            val updateIntent = Intent(context, NovaVpnWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            context.sendBroadcast(updateIntent)
        }
    }

    companion object {
        const val ACTION_WIDGET_TOGGLE = "app.novavpn.internal.ACTION_WIDGET_TOGGLE"
    }
}
