package app.novavpn.core.process

import app.novavpn.domain.model.AetherProtocol
import app.novavpn.domain.model.AppSettings
import app.novavpn.domain.model.DnsMode
import app.novavpn.domain.model.ObfuscationProfile
import app.novavpn.domain.model.RouteChain
import app.novavpn.domain.model.VpnMode
import app.novavpn.domain.model.VpnProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AetherCommandBuilderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `builds Aether MASQUE command list with real v2_4_0 flags and MTU env var`() {
        val nativeDir = tempFolder.newFolder("jni")
        val workDir = tempFolder.newFolder("work")
        val profile = VpnProfile(
            id = "p1",
            name = "Test",
            routeChain = RouteChain.AETHER,
            aetherProtocol = AetherProtocol.MASQUE,
            obfuscation = ObfuscationProfile.QUIC_TLS_CAMO,
            customEndpoint = "162.159.192.1:2408",
            mtu = 1320,
            scanLevel = 1,
            dnsMode = DnsMode.PLAIN,
            customDnsPrimary = "1.1.1.1",
            directRules = "*.ir\n192.168.0.0/16",
            blockRules = "ads.example.com\nport:5353",
            extraCoreArgs = "--verbose"
        )
        val settings = AppSettings(
            vpnMode = VpnMode.PROXY_ONLY,
            localSocksPort = 10808,
            localHttpPort = 10809,
            shareProxyOnLan = false
        )

        val plan = AetherCommandBuilder.build(
            nativeDir = nativeDir,
            workDir = workDir,
            profile = profile,
            settings = settings,
            resolvedRoute = RouteChain.AETHER,
            useFreshScan = false,
            sessionAuth = null
        ).getOrThrow()

        val stage = plan.stages.single()
        val args = plan.aetherArgs!!
        assertNull(plan.sessionAuth)
        assertEquals("1320", stage.envVars["AETHER_MASQUE_MTU"])
        assertTrue(args.first().endsWith("libaether.so"))
        assertTrue(args.containsAll(listOf("--config", "${workDir.absolutePath}/aether.toml")))
        assertTrue(args.containsAll(listOf("--bind", "127.0.0.1:10808")))
        assertTrue(args.containsAll(listOf("--http-proxy", "127.0.0.1:10809")))
        assertTrue(args.containsAll(listOf("--masque", "--h3")))
        assertTrue(args.containsAll(listOf("--noize", "light")))
        assertTrue(args.containsAll(listOf("--peer", "162.159.192.1:2408")))
        assertTrue(args.contains("--turbo"))
        assertTrue(args.contains("-4"))
        assertTrue(args.containsAll(listOf("--dns", "1.1.1.1")))
        assertTrue(args.containsAll(listOf("--route-direct", "*.ir,192.168.0.0/16")))
        assertTrue(args.containsAll(listOf("--route-block", "ads.example.com,port:5353")))
        assertTrue(args.contains("--verbose"))
        assertTrue(stage.readinessMatcher!!.invoke("[+] socks5 server listening on 127.0.0.1:10808"))
    }

    @Test
    fun `builds Aether plus Psiphon unified launch stage with psiphon-bin and readiness matcher`() {
        val nativeDir = tempFolder.newFolder("jni2")
        val workDir = tempFolder.newFolder("work2")
        val profile = VpnProfile(
            id = "p2",
            name = "Chain",
            routeChain = RouteChain.AETHER_PSIPHON,
            psiphonRegion = "DE"
        )
        val settings = AppSettings(vpnMode = VpnMode.PROXY_ONLY, localSocksPort = 10808, localHttpPort = 10809)

        val plan = AetherCommandBuilder.build(
            nativeDir = nativeDir,
            workDir = workDir,
            profile = profile,
            settings = settings,
            resolvedRoute = RouteChain.AETHER_PSIPHON,
            useFreshScan = true,
            sessionAuth = null
        ).getOrThrow()

        val stage = plan.stages.single()
        val args = stage.args
        assertEquals(setOf("libaether.so", "libpsiphon.so"), stage.preflightBinaries.map { it.name }.toSet())
        assertTrue(args.containsAll(listOf("--bind", "127.0.0.1:10828")))
        assertTrue(args.contains("--psiphon"))
        assertTrue(args.containsAll(listOf("--psiphon-bin", "${nativeDir.absolutePath}/libpsiphon.so")))
        assertTrue(args.containsAll(listOf("--psiphon-bind", "127.0.0.1:10808")))
        assertTrue(args.containsAll(listOf("--psiphon-http", "127.0.0.1:10809")))
        assertTrue(args.containsAll(listOf("--psiphon-region", "DE")))
        assertTrue(args.contains("--no-quick-reconnect"))
        assertFalse(stage.readinessMatcher!!.invoke("[+] socks5 server listening on 127.0.0.1:10828"))
        assertTrue(stage.readinessMatcher.invoke("[+] psiphon is ready; socks=127.0.0.1:10808"))
    }

    @Test
    fun `TOR_ONLY and AETHER_TOR require libaether_so and liblyrebird_so and wire PT flags`() {
        val nativeDir = tempFolder.newFolder("jni_tor")
        val workDir = tempFolder.newFolder("work_tor")
        val bridgeLine = "obfs4 192.0.2.1:443 AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA cert=abc iat-mode=0"
        val profile = VpnProfile(
            id = "p_tor",
            name = "Tor",
            routeChain = RouteChain.AETHER_TOR,
            torUseBridges = true,
            torBridges = listOf(bridgeLine)
        )
        val settings = AppSettings(vpnMode = VpnMode.VPN, localSocksPort = 24808)

        val plan = AetherCommandBuilder.build(
            nativeDir = nativeDir,
            workDir = workDir,
            profile = profile,
            settings = settings,
            resolvedRoute = RouteChain.AETHER_TOR,
            useFreshScan = false,
            sessionAuth = null
        ).getOrThrow()

        val stage = plan.stages.single()
        val binNames = stage.preflightBinaries.map { it.name }.toSet()
        assertEquals(setOf("libaether.so", "liblyrebird.so"), binNames)
        assertTrue(stage.args.contains("--tor"))
        assertTrue(stage.args.containsAll(listOf("--tor-bind", "127.0.0.1:24808")))
        assertTrue(stage.args.containsAll(listOf("--tor-pt", "obfs4=${nativeDir.absolutePath}/liblyrebird.so")))
        assertTrue(stage.args.containsAll(listOf("--tor-bridge", bridgeLine)))
        assertFalse(stage.readinessMatcher!!.invoke("[+] socks5 server listening on 127.0.0.1:24828"))
        assertTrue(stage.readinessMatcher.invoke("[+] tor is ready; socks=127.0.0.1:24808"))

        val torOnlyPlan = AetherCommandBuilder.build(
            nativeDir = nativeDir,
            workDir = workDir,
            profile = profile.copy(routeChain = RouteChain.TOR_ONLY),
            settings = settings,
            resolvedRoute = RouteChain.TOR_ONLY,
            useFreshScan = false,
            sessionAuth = null
        ).getOrThrow()
        val torOnlyStage = torOnlyPlan.stages.single()
        assertEquals(setOf("libaether.so", "liblyrebird.so"), torOnlyStage.preflightBinaries.map { it.name }.toSet())
        assertTrue(torOnlyStage.args.contains("--tor-only"))
        assertTrue(torOnlyStage.args.containsAll(listOf("--bind", "127.0.0.1:24808")))
    }

    @Test
    fun `rejects shell metacharacters in extra args and returns failure`() {
        val nativeDir = tempFolder.newFolder("jni3")
        val workDir = tempFolder.newFolder("work3")
        val profile = VpnProfile(
            id = "p3",
            name = "Inject",
            extraCoreArgs = "--workers 2; rm -rf /"
        )
        val result = AetherCommandBuilder.build(
            nativeDir = nativeDir,
            workDir = workDir,
            profile = profile,
            settings = AppSettings(),
            resolvedRoute = RouteChain.AETHER,
            useFreshScan = false
        )
        assertFalse(result.isSuccess)
    }

    @Test
    fun `binds 0_0_0_0 only when shareProxyOnLan is true`() {
        val nativeDir = tempFolder.newFolder("jni4")
        val workDir = tempFolder.newFolder("work4")
        val settings = AppSettings(shareProxyOnLan = true, localSocksPort = 10900)
        val plan = AetherCommandBuilder.build(
            nativeDir = nativeDir,
            workDir = workDir,
            profile = VpnProfile(id = "p4", name = "LAN"),
            settings = settings,
            resolvedRoute = RouteChain.AETHER,
            useFreshScan = false
        ).getOrThrow()

        assertTrue(plan.aetherArgs!!.contains("0.0.0.0:10900"))
        assertEquals(10900, plan.finalSocksPort)
    }

    @Test
    fun `sets readiness timeouts long enough for Aether WG 80s and MASQUE 120s scan budgets`() {
        val nativeDir = tempFolder.newFolder("jni5")
        val workDir = tempFolder.newFolder("work5")
        val settings = AppSettings()

        val masquePlan = AetherCommandBuilder.build(
            nativeDir = nativeDir,
            workDir = workDir,
            profile = VpnProfile(id = "m", name = "M", aetherProtocol = AetherProtocol.MASQUE),
            settings = settings,
            resolvedRoute = RouteChain.AETHER,
            useFreshScan = true
        ).getOrThrow()
        assertTrue(masquePlan.stages.single().readinessTimeoutMs >= 135_000L)

        val wgPlan = AetherCommandBuilder.build(
            nativeDir = nativeDir,
            workDir = workDir,
            profile = VpnProfile(id = "w", name = "W", aetherProtocol = AetherProtocol.WIREGUARD),
            settings = settings,
            resolvedRoute = RouteChain.AETHER,
            useFreshScan = true
        ).getOrThrow()
        assertTrue(wgPlan.stages.single().readinessTimeoutMs >= 95_000L)

        val thoroughMasque = AetherCommandBuilder.build(
            nativeDir = nativeDir,
            workDir = workDir,
            profile = VpnProfile(id = "mt", name = "MT", aetherProtocol = AetherProtocol.MASQUE, scanLevel = 3),
            settings = settings,
            resolvedRoute = RouteChain.AETHER,
            useFreshScan = true
        ).getOrThrow()
        assertTrue(thoroughMasque.stages.single().readinessTimeoutMs >= 165_000L)
    }
}
