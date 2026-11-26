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
        assertTrue(VpnProfile(id = "def", name = "Default").masqueOverH2)
        val profile = VpnProfile(
            id = "p1",
            name = "Test",
            routeChain = RouteChain.AETHER,
            aetherProtocol = AetherProtocol.MASQUE,
            masqueOverH2 = false,
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
            sessionAuth = null,
            nonVpnDnsServers = listOf("192.168.1.1", "8.8.8.8")
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
        assertTrue(args.containsAll(listOf("--psiphon-mode", "auto")))
        val psiConfigIdx = args.indexOf("--psiphon-config")
        assertTrue(psiConfigIdx >= 0)
        val psiConfigFile = java.io.File(args[psiConfigIdx + 1])
        assertTrue(psiConfigFile.exists())
        val psiJson = psiConfigFile.readText()
        assertTrue(psiJson.contains("192.168.1.1"))
        assertTrue(psiJson.contains("1.1.1.1"))
        assertTrue(args.contains("--no-quick-reconnect"))
        assertFalse(stage.readinessMatcher!!.invoke("[+] socks5 server listening on 127.0.0.1:10828"))
        assertTrue(stage.readinessMatcher.invoke("[+] psiphon is ready; socks=127.0.0.1:10808"))
    }

    @Test
    fun `TOR_ONLY and AETHER_TOR require libaether_so and liblyrebird_so and wire PT flags and tor-bridge-file`() {
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
        val bridgeFileIdx = stage.args.indexOf("--tor-bridge-file")
        assertTrue(bridgeFileIdx >= 0)
        assertTrue(java.io.File(stage.args[bridgeFileIdx + 1]).readText().contains(bridgeLine))
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
    fun `emits h2, h2-peer, fragment, and extracts NAME=VALUE tokens into envVars`() {
        val nativeDir = tempFolder.newFolder("jni_h2")
        val workDir = tempFolder.newFolder("work_h2")
        val profile = VpnProfile(
            id = "h2",
            name = "MASQUE H2",
            routeChain = RouteChain.AETHER,
            aetherProtocol = AetherProtocol.MASQUE,
            masqueOverH2 = true,
            tlsFragment = true,
            customEndpoint = "162.159.198.1:443",
            extraCoreArgs = "AETHER_CUSTOM_TEST=42 --no-quic-v2"
        )
        val plan = AetherCommandBuilder.build(
            nativeDir = nativeDir,
            workDir = workDir,
            profile = profile,
            settings = AppSettings(),
            resolvedRoute = RouteChain.AETHER,
            useFreshScan = false
        ).getOrThrow()

        val stage = plan.stages.single()
        assertTrue(stage.args.contains("--h2"))
        assertFalse(stage.args.contains("--h3"))
        assertTrue(stage.args.contains("--fragment"))
        assertTrue(stage.args.containsAll(listOf("--peer", "162.159.198.1:443")))
        assertTrue(stage.args.containsAll(listOf("--h2-peer", "162.159.198.1:443")))
        assertTrue(stage.args.contains("--no-quic-v2"))
        assertFalse(stage.args.contains("AETHER_CUSTOM_TEST=42"))
        assertEquals("42", stage.envVars["AETHER_CUSTOM_TEST"])
        assertEquals("info", stage.envVars["AETHER_LOG_LEVEL"])
        assertTrue(stage.envVars["AETHER_TOR_PT"]!!.contains("liblyrebird.so"))
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

    @Test
    fun `exports validation timeout env vars, bounds forceQuickReconnect timeout, and merges compiled geo rules`() {
        val nativeDir = tempFolder.newFolder("jni_geo")
        val workDir = tempFolder.newFolder("work_geo")
        val profile = VpnProfile(
            id = "geo-test",
            name = "GeoTest",
            routeChain = RouteChain.AETHER,
            aetherProtocol = AetherProtocol.MASQUE,
            validateSecs = 3,
            directRules = "*.ir\ngeoip:ir",
            blockRules = "geosite:category-ads-all"
        )
        val quickPlan = AetherCommandBuilder.build(
            nativeDir = nativeDir,
            workDir = workDir,
            profile = profile,
            settings = AppSettings(),
            resolvedRoute = RouteChain.AETHER,
            useFreshScan = false,
            extraCompiledDirectRules = listOf("185.143.232.0/22"),
            extraCompiledBlockRules = listOf("*.doubleclick.net"),
            forceQuickReconnect = true
        ).getOrThrow()

        val stage = quickPlan.stages.single()
        assertEquals("3", stage.envVars["AETHER_MASQUE_VALIDATE_SECS"])
        assertEquals("3", stage.envVars["AETHER_WG_VALIDATE_SECS"])
        assertEquals(12_000L, stage.readinessTimeoutMs)
        assertTrue(stage.args.contains("--quick-reconnect"))
        assertFalse(stage.args.contains("--no-quick-reconnect"))
        val directArgIdx = stage.args.indexOf("--route-direct")
        assertTrue(directArgIdx >= 0)
        val directArgVal = stage.args[directArgIdx + 1]
        assertTrue(directArgVal.contains("*.ir"))
        assertTrue(directArgVal.contains("185.143.232.0/22"))
        assertFalse(directArgVal.contains("geoip:ir"))

        val blockArgIdx = stage.args.indexOf("--route-block")
        assertTrue(blockArgIdx >= 0)
        val blockArgVal = stage.args[blockArgIdx + 1]
        assertTrue(blockArgVal.contains("*.doubleclick.net"))
        assertFalse(blockArgVal.contains("geosite:category-ads-all"))

        val pinnedPlan = AetherCommandBuilder.build(
            nativeDir = nativeDir,
            workDir = workDir,
            profile = profile,
            settings = AppSettings(),
            resolvedRoute = RouteChain.AETHER,
            useFreshScan = false,
            endpointOverride = "162.159.193.5:443"
        ).getOrThrow()
        assertTrue(pinnedPlan.stages.single().args.containsAll(listOf("--peer", "162.159.193.5:443")))
    }
}
