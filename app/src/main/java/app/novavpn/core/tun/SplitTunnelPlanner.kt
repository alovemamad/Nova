package app.novavpn.core.tun

import app.novavpn.domain.model.DnsMode
import app.novavpn.domain.model.RouteChain
import app.novavpn.domain.model.SplitTunnelMode
import app.novavpn.domain.model.TunEnginePreference
import app.novavpn.domain.model.VpnProfile

data class VpnInterfacePlan(
    val allowedPackages: List<String>,
    val disallowedPackages: List<String>,
    val primaryDnsServer: String,
    val secondaryDnsServer: String?
)

object SplitTunnelPlanner {

    /**
     * Computes non-conflicting allowed vs disallowed package lists for VpnService.Builder:
     * - INCLUDE: uses ONLY allowedPackages (never mixes disallowedPackages; self is excluded so core sockets stay outside TUN)
     * - EXCLUDE: disallows self + selected packages
     * - DISABLED: disallows self
     */
    fun plan(
        selfPackage: String,
        mode: SplitTunnelMode,
        selectedPackages: Set<String>,
        profile: VpnProfile,
        tunPreference: TunEnginePreference
    ): Result<VpnInterfacePlan> {
        val cleanedSelected = selectedPackages.map { it.trim() }.filter { it.isNotEmpty() && it != selfPackage }.distinct()

        val (allowed, disallowed) = when (mode) {
            SplitTunnelMode.INCLUDE -> {
                if (cleanedSelected.isEmpty()) {
                    return Result.failure(
                        IllegalArgumentException("Split tunneling INCLUDE mode requires at least one app selected")
                    )
                }
                cleanedSelected to emptyList()
            }
            SplitTunnelMode.EXCLUDE -> emptyList<String>() to (listOf(selfPackage) + cleanedSelected)
            SplitTunnelMode.DISABLED -> emptyList<String>() to listOf(selfPackage)
        }

        val isTorRoute = profile.routeChain == RouteChain.TOR_ONLY || profile.routeChain == RouteChain.AETHER_TOR

        // Virtual DNS 10.10.0.2 is handled by sing-box `hijack-dns` AND by hev-socks5-tunnel `mapdns`.
        // Tor does not relay arbitrary UDP traffic, so Tor routes must always use virtual DNS 10.10.0.2.
        val primaryDns = when {
            isTorRoute -> "10.10.0.2"
            tunPreference == TunEnginePreference.HEV_SOCKS5_ONLY &&
                profile.dnsMode == DnsMode.PLAIN &&
                profile.customDnsPrimary.count { it == '.' } == 3 -> profile.customDnsPrimary.trim()
            profile.dnsMode == DnsMode.PLAIN && profile.customDnsPrimary.count { it == '.' } == 3 ->
                profile.customDnsPrimary.trim()
            else -> "10.10.0.2"
        }

        val secondaryDns = if (isTorRoute) {
            null
        } else {
            profile.customDnsSecondary.trim()
                .takeIf { it.isNotBlank() && it.count { c -> c == '.' } == 3 && it != primaryDns }
        }

        return Result.success(
            VpnInterfacePlan(
                allowedPackages = allowed,
                disallowedPackages = disallowed,
                primaryDnsServer = primaryDns,
                secondaryDnsServer = secondaryDns
            )
        )
    }
}
