package app.novavpn.service

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.novavpn.ServiceLocator
import app.novavpn.core.DevicePlatform
import app.novavpn.domain.model.VpnState
import com.example.MainActivity
import com.example.R

class NovaTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        if (DevicePlatform.isTv(this)) return
        ServiceLocator.init(applicationContext)
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        if (DevicePlatform.isTv(this)) return
        ServiceLocator.init(applicationContext)
        val controller = ServiceLocator.vpnController
        val status = controller.status.value

        if (status.state == VpnState.DISCONNECTED || status.state == VpnState.ERROR) {
            if (VpnService.prepare(this) != null) {
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val pi = android.app.PendingIntent.getActivity(
                        this,
                        11,
                        intent,
                        android.app.PendingIntent.FLAG_IMMUTABLE
                    )
                    startActivityAndCollapse(pi)
                } else {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(intent)
                }
                return
            }
            controller.connect()
        } else {
            controller.disconnect()
        }
        refreshTile()
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val status = ServiceLocator.vpnController.status.value
        tile.label = getString(R.string.tile_label)
        when (status.state) {
            VpnState.CONNECTED -> {
                tile.state = Tile.STATE_ACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = status.activeRoute.name
                }
            }
            VpnState.CONNECTING, VpnState.RECONNECTING -> {
                tile.state = Tile.STATE_ACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = getString(R.string.status_connecting)
                }
            }
            VpnState.PAUSED -> {
                tile.state = Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = getString(R.string.status_paused)
                }
            }
            VpnState.DISCONNECTED, VpnState.ERROR -> {
                tile.state = Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = getString(R.string.status_disconnected)
                }
            }
        }
        tile.updateTile()
    }
}
