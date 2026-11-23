package app.novavpn.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import app.novavpn.ServiceLocator
import app.novavpn.data.log.LogRepository
import app.novavpn.domain.model.VpnMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return

        ServiceLocator.init(context.applicationContext)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = ServiceLocator.settingsRepository.getSettings()
                if (settings.autoConnect.startOnBoot) {
                    val prepared = settings.vpnMode == VpnMode.PROXY_ONLY || VpnService.prepare(context) == null
                    if (prepared) {
                        LogRepository.i("BootReceiver", "Auto-starting Nova VPN on boot")
                        ServiceLocator.vpnController.connect()
                    } else {
                        LogRepository.w("BootReceiver", "Cannot auto-start on boot: VPN permission not granted")
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
