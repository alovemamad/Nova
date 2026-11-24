package app.novavpn.core.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import app.novavpn.domain.model.AppSettings

/**
 * Resolves underlying physical network keys while explicitly ignoring any active VPN transport
 * (`NetworkCapabilities.TRANSPORT_VPN`), preventing TUN open/close events from triggering
 * false network-switch reconnect loops.
 */
object ConnectivityHelper {

    fun isVpnNetwork(cm: ConnectivityManager?, network: Network?): Boolean {
        if (cm == null || network == null) return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    fun underlyingNetworkCapabilities(cm: ConnectivityManager?): NetworkCapabilities? {
        if (cm == null) return null
        val active = cm.activeNetwork
        if (active != null) {
            val activeCaps = cm.getNetworkCapabilities(active)
            if (activeCaps != null && !activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                return activeCaps
            }
        }
        @Suppress("DEPRECATION")
        val all = cm.allNetworks
        return all.asSequence()
            .mapNotNull { cm.getNetworkCapabilities(it) }
            .filter {
                !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            }
            .sortedByDescending {
                when {
                    it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 3
                    it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 2
                    it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 1
                    else -> 0
                }
            }
            .firstOrNull()
    }

    fun currentNetworkKey(context: Context): String {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return "unknown"
        val caps = underlyingNetworkCapabilities(cm) ?: return "offline"
        return keyForCapabilities(context, caps)
    }

    fun keyForCapabilities(context: Context, caps: NetworkCapabilities): String {
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

    fun isUntrustedWifi(context: Context, settings: AppSettings): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = underlyingNetworkCapabilities(cm) ?: return false
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return false
        val wm = context.applicationContext.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION")
        val ssid = wm?.connectionInfo?.ssid?.trim('"') ?: return true
        if (ssid == "<unknown ssid>" || ssid.isBlank()) return true
        return settings.autoConnect.trustedWifiSsids.none { it.equals(ssid, ignoreCase = true) }
    }
}
