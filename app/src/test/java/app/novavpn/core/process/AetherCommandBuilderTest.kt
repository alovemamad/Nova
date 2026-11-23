package app.novavpn.core.process

import app.novavpn.domain.model.AetherProtocol
import app.novavpn.domain.model.AppSettings
import app.novavpn.domain.model.ObfuscationProfile
import app.novavpn.domain.model.RouteChain
import app.novavpn.domain.model.VpnMode
import app.novavpn.domain.model.VpnProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AetherCommandBuilderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `builds Aether MASQUE command list with expected flags`() {
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
            tcpBufferKb = 128,
            extraCoreArgs = "--workers 4"
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
            useFreshScan = false
        ).getOrThrow()

        val args = plan.aetherArgs!!
        assertNull(plan.psiphonArgs)
        assertNull(plan.lyrebirdArgs)
        assertTrue(args.first().endsWith("libaether.so"))
        assertTrue(args.containsAll(listOf("--bind", "127.0.0.1:10808")))
        assertTrue(args.containsAll(listOf("--http-bind", "127.0.0.1:10809")))
        assertTrue(args.containsAll(listOf("--protocol", "masque")))
        assertTrue(args.containsAll(listOf("--obfs", "quic-tls")))
        assertTrue(args.containsAll(listOf("--endpoint", "162.159.192.1:2408")))
        assertTrue(args.containsAll(listOf("--mtu", "1320")))
        assertTrue(args.containsAll(listOf("--workers", "4")))
    }

    @Test
    fun `builds Aether to Psiphon chained plan using internal port`() {
        val nativeDir = tempFolder.newFolder("jni2")
        val workDir = tempFolder.newFolder("work2")
        val profile = VpnProfile(
            id = "p2",
            name = "Chain",
            routeChain = RouteChain.AETHER_PSIPHON,
            psiphonRegion = "DE"
        )
        val settings = AppSettings(vpnMode = VpnMode.VPN, localSocksPort = 10808)

        val plan = AetherCommandBuilder.build(
            nativeDir = nativeDir,
            workDir = workDir,
            profile = profile,
            settings = settings,
            resolvedRoute = RouteChain.AETHER_PSIPHON,
            useFreshScan = true
        ).getOrThrow()

        assertNotNull(plan.aetherArgs)
        assertNotNull(plan.psiphonArgs)
        assertTrue(plan.aetherArgs!!.contains("127.0.0.1:10828"))
        assertTrue(plan.aetherArgs!!.contains("--scan"))
        val cfgFile = plan.generatedConfigFiles.single()
        val cfgText = cfgFile.readText()
        assertTrue(cfgText.contains("\"EgressRegion\": \"DE\""))
        assertTrue(cfgText.contains("socks5://127.0.0.1:10828"))
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
}
