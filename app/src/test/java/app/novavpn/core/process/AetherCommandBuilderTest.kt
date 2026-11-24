package app.novavpn.core.process

import app.novavpn.core.SessionProxyAuth
import app.novavpn.domain.model.AetherProtocol
import app.novavpn.domain.model.AppSettings
import app.novavpn.domain.model.ObfuscationProfile
import app.novavpn.domain.model.RouteChain
import app.novavpn.domain.model.VpnMode
import app.novavpn.domain.model.VpnProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    fun `builds Aether MASQUE command list with expected flags and session auth`() {
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
        val auth = SessionProxyAuth("nova_test", "secretpass123")

        val plan = AetherCommandBuilder.build(
            nativeDir = nativeDir,
            workDir = workDir,
            profile = profile,
            settings = settings,
            resolvedRoute = RouteChain.AETHER,
            useFreshScan = false,
            sessionAuth = auth
        ).getOrThrow()

        val args = plan.aetherArgs!!
        assertNull(plan.psiphonArgs)
        assertNull(plan.torArgs)
        assertTrue(args.first().endsWith("libaether.so"))
        assertTrue(args.containsAll(listOf("--bind", "127.0.0.1:10808")))
        assertTrue(args.containsAll(listOf("--socks-user", "nova_test", "--socks-pass", "secretpass123")))
        assertTrue(args.containsAll(listOf("--http-bind", "127.0.0.1:10809")))
        assertTrue(args.containsAll(listOf("--http-user", "nova_test", "--http-pass", "secretpass123")))
        assertTrue(args.containsAll(listOf("--protocol", "masque")))
        assertTrue(args.containsAll(listOf("--obfs", "quic-tls")))
        assertTrue(args.containsAll(listOf("--endpoint", "162.159.192.1:2408")))
        assertTrue(args.containsAll(listOf("--mtu", "1320")))
        assertTrue(args.containsAll(listOf("--workers", "4")))
    }

    @Test
    fun `builds Aether to Psiphon chained plan using authenticated internal port`() {
        val nativeDir = tempFolder.newFolder("jni2")
        val workDir = tempFolder.newFolder("work2")
        val profile = VpnProfile(
            id = "p2",
            name = "Chain",
            routeChain = RouteChain.AETHER_PSIPHON,
            psiphonRegion = "DE"
        )
        val settings = AppSettings(vpnMode = VpnMode.VPN, localSocksPort = 10808)
        val auth = SessionProxyAuth("nova_u", "nova_p")

        val plan = AetherCommandBuilder.build(
            nativeDir = nativeDir,
            workDir = workDir,
            profile = profile,
            settings = settings,
            resolvedRoute = RouteChain.AETHER_PSIPHON,
            useFreshScan = true,
            sessionAuth = auth
        ).getOrThrow()

        assertNotNull(plan.aetherArgs)
        assertNotNull(plan.psiphonArgs)
        assertTrue(plan.aetherArgs!!.contains("127.0.0.1:10828"))
        assertTrue(plan.aetherArgs!!.contains("--scan"))
        val cfgFile = plan.generatedConfigFiles.single()
        val parsed = Json.parseToJsonElement(cfgFile.readText()).jsonObject
        assertEquals("DE", parsed["EgressRegion"]?.jsonPrimitive?.content)
        assertEquals("socks5://nova_u:nova_p@127.0.0.1:10828", parsed["UpstreamProxyUrl"]?.jsonPrimitive?.content)
    }

    @Test
    fun `TOR_ONLY and AETHER_TOR require both libtor_so and liblyrebird_so in preflightBinaries`() {
        val nativeDir = tempFolder.newFolder("jni_tor")
        val workDir = tempFolder.newFolder("work_tor")
        val profile = VpnProfile(
            id = "p_tor",
            name = "Tor",
            routeChain = RouteChain.AETHER_TOR,
            torUseBridges = true,
            torBridges = listOf("obfs4 192.0.2.1:443 AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA cert=abc iat-mode=0")
        )
        val settings = AppSettings(vpnMode = VpnMode.VPN, localSocksPort = 24808)
        val auth = SessionProxyAuth("nova_tor", "pass_tor")

        val plan = AetherCommandBuilder.build(
            nativeDir = nativeDir,
            workDir = workDir,
            profile = profile,
            settings = settings,
            resolvedRoute = RouteChain.AETHER_TOR,
            useFreshScan = false,
            sessionAuth = auth
        ).getOrThrow()

        val torStage = plan.stages.first { it.adapter == TorCoreAdapter }
        val binNames = torStage.preflightBinaries.map { it.name }.toSet()
        assertEquals(setOf("libtor.so", "liblyrebird.so"), binNames)
        assertNotNull(plan.torArgs)
        assertTrue(plan.torArgs!!.first().endsWith("libtor.so"))

        val torOnlyPlan = AetherCommandBuilder.build(
            nativeDir = nativeDir,
            workDir = workDir,
            profile = profile.copy(routeChain = RouteChain.TOR_ONLY),
            settings = settings,
            resolvedRoute = RouteChain.TOR_ONLY,
            useFreshScan = false,
            sessionAuth = auth
        ).getOrThrow()
        assertEquals(
            setOf("libtor.so", "liblyrebird.so"),
            torOnlyPlan.stages.single().preflightBinaries.map { it.name }.toSet()
        )
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
