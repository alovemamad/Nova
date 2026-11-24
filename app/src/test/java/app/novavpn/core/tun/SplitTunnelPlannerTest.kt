package app.novavpn.core.tun

import app.novavpn.domain.model.DnsMode
import app.novavpn.domain.model.SplitTunnelMode
import app.novavpn.domain.model.TunEnginePreference
import app.novavpn.domain.model.VpnProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitTunnelPlannerTest {

    private val selfPkg = "com.aistudio.novavpn.kxmvpr"

    @Test
    fun `INCLUDE mode populates only allowedPackages and rejects empty or self-only selection`() {
        val emptyRes = SplitTunnelPlanner.plan(
            selfPackage = selfPkg,
            mode = SplitTunnelMode.INCLUDE,
            selectedPackages = setOf(selfPkg),
            profile = VpnProfile(id = "1", name = "P"),
            tunPreference = TunEnginePreference.AUTO_SINGBOX_FALLBACK_HEV
        )
        assertTrue(emptyRes.isFailure)

        val validRes = SplitTunnelPlanner.plan(
            selfPackage = selfPkg,
            mode = SplitTunnelMode.INCLUDE,
            selectedPackages = setOf("org.mozilla.firefox", selfPkg, "org.telegram.messenger"),
            profile = VpnProfile(id = "1", name = "P"),
            tunPreference = TunEnginePreference.AUTO_SINGBOX_FALLBACK_HEV
        ).getOrThrow()

        assertEquals(listOf("org.mozilla.firefox", "org.telegram.messenger"), validRes.allowedPackages)
        assertTrue(validRes.disallowedPackages.isEmpty())
    }

    @Test
    fun `EXCLUDE and DISABLED modes always disallow self package`() {
        val disabled = SplitTunnelPlanner.plan(
            selfPackage = selfPkg,
            mode = SplitTunnelMode.DISABLED,
            selectedPackages = setOf("com.foo"),
            profile = VpnProfile(id = "1", name = "P"),
            tunPreference = TunEnginePreference.AUTO_SINGBOX_FALLBACK_HEV
        ).getOrThrow()
        assertTrue(disabled.allowedPackages.isEmpty())
        assertEquals(listOf(selfPkg), disabled.disallowedPackages)

        val excluded = SplitTunnelPlanner.plan(
            selfPackage = selfPkg,
            mode = SplitTunnelMode.EXCLUDE,
            selectedPackages = setOf("com.bank.app"),
            profile = VpnProfile(id = "1", name = "P"),
            tunPreference = TunEnginePreference.AUTO_SINGBOX_FALLBACK_HEV
        ).getOrThrow()
        assertTrue(excluded.allowedPackages.isEmpty())
        assertEquals(listOf(selfPkg, "com.bank.app"), excluded.disallowedPackages)
    }

    @Test
    fun `selects virtual DNS 10_10_0_2 for DoH or DoT and IPv4 server for PLAIN`() {
        val dohPlan = SplitTunnelPlanner.plan(
            selfPackage = selfPkg,
            mode = SplitTunnelMode.DISABLED,
            selectedPackages = emptySet(),
            profile = VpnProfile(id = "1", name = "DoH", dnsMode = DnsMode.DOH),
            tunPreference = TunEnginePreference.HEV_SOCKS5_ONLY
        ).getOrThrow()
        assertEquals("10.10.0.2", dohPlan.primaryDnsServer)

        val plainPlan = SplitTunnelPlanner.plan(
            selfPackage = selfPkg,
            mode = SplitTunnelMode.DISABLED,
            selectedPackages = emptySet(),
            profile = VpnProfile(id = "2", name = "Plain", dnsMode = DnsMode.PLAIN, customDnsPrimary = "9.9.9.9"),
            tunPreference = TunEnginePreference.HEV_SOCKS5_ONLY
        ).getOrThrow()
        assertEquals("9.9.9.9", plainPlan.primaryDnsServer)
    }
}
