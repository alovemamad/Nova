package app.novavpn.core.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import app.novavpn.domain.model.AppSettings
import java.net.Inet4Address
import java.net.NetworkInterface

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

    /**
     * Collects real IPv4 DNS servers of non-VPN networks (active network prioritized)
     * so Go-based cores like Psiphon don't fall back to 127.0.0.1:53 on Android.
     */
    fun nonVpnIpv4DnsServers(context: Context): List<String> = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val active = cm.activeNetwork
        @Suppress("DEPRECATION")
        val nets = cm.allNetworks.sortedByDescending { it == active }
        nets.asSequence()
            .filter { net ->
                val caps = cm.getNetworkCapabilities(net)
                caps != null &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            }
            .flatMap { net ->
                cm.getLinkProperties(net)?.dnsServers.orEmpty()
                    .filterIsInstance<Inet4Address>()
                    .mapNotNull { it.hostAddress }
                    .asSequence()
            }
            .filter { it != "0.0.0.0" && !it.startsWith("127.") }
            .distinct()
            .toList()
    }.getOrDefault(emptyList())

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

/**
 * Discovers active local IPv4 addresses (Wi-Fi, Hotspot, USB tethering, Ethernet)
 * so LAN clients know the exact address to point at when Share Proxy to LAN is enabled.
 */
object LanShareHelper {
    data class Addr(val iface: String, val ip: String)

    fun addresses(): List<Addr> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .filterNot {
                it.name.startsWith("tun") ||
                    it.name.startsWith("dummy") ||
                    it.name.startsWith("rmnet") ||
                    it.name.startsWith("ccmni") ||
                    it.name.startsWith("v4-") ||
                    it.name.startsWith("clat")
            }
            .flatMap { nif ->
                nif.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                    .mapNotNull { addr -> addr.hostAddress?.takeIf { it.isNotBlank() }?.let { Addr(nif.name, it) } }
            }
            .distinctBy { it.ip }
    }.getOrDefault(emptyList())

    val fallbackHints: List<Addr> = listOf(
        Addr("wlan", "192.168.43.1"),
        Addr("rndis", "192.168.42.129")
    )

    fun isHotspotOrWifi(iface: String): Boolean =
        iface.startsWith("wlan") || iface.startsWith("swlan") || iface.startsWith("ap")

    fun isUsbOrEthernet(iface: String): Boolean =
        iface.startsWith("rndis") || iface.startsWith("usb") || iface.startsWith("eth")
}
