package app.novavpn.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.widget.RemoteViews
import app.novavpn.ServiceLocator
import app.novavpn.domain.model.VpnState
import com.example.MainActivity
import com.example.R

class NovaVpnWidgetProvider : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        ServiceLocator.init(context.applicationContext)
        super.onReceive(context, intent)
        if (intent.action == ACTION_WIDGET_TOGGLE) {
            val controller = ServiceLocator.vpnController
            val state = controller.status.value.state
            if (state == VpnState.DISCONNECTED || state == VpnState.ERROR) {
                if (VpnService.prepare(context) != null) {
                    context.startActivity(
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
            onUpdate(context, mgr, ids)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        ServiceLocator.init(context.applicationContext)
        val status = ServiceLocator.vpnController.status.value

        val statusText = when (status.state) {
            VpnState.CONNECTED -> "${context.getString(R.string.status_connected)} · ${status.activeRoute.name}"
            VpnState.CONNECTING -> context.getString(R.string.status_connecting)
            VpnState.RECONNECTING -> context.getString(R.string.status_reconnecting)
            VpnState.PAUSED -> context.getString(R.string.status_paused)
            VpnState.ERROR -> context.getString(R.string.status_error)
            VpnState.DISCONNECTED -> context.getString(R.string.status_disconnected)
        }
        val actionText = when (status.state) {
            VpnState.CONNECTED, VpnState.CONNECTING, VpnState.RECONNECTING, VpnState.PAUSED ->
                context.getString(R.string.notif_action_disconnect)
            else -> context.getString(R.string.tap_to_connect)
        }

        val toggleIntent = Intent(context, NovaVpnWidgetProvider::class.java).apply {
            action = ACTION_WIDGET_TOGGLE
        }
        val togglePi = PendingIntent.getBroadcast(
            context,
            20,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openPi = PendingIntent.getActivity(
            context,
            21,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        appWidgetIds.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.widget_nova_vpn).apply {
                setTextViewText(R.id.widget_status, statusText)
                setTextViewText(R.id.widget_toggle_btn, actionText)
                setOnClickPendingIntent(R.id.widget_toggle_btn, togglePi)
                setOnClickPendingIntent(R.id.widget_root, openPi)
            }
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    companion object {
        const val ACTION_WIDGET_TOGGLE = "app.novavpn.ACTION_WIDGET_TOGGLE"
    }
}
