package app.novavpn.core.tun

import app.novavpn.core.process.AetherCommandBuilder
import app.novavpn.data.store.GeoResourceRepository
import app.novavpn.domain.model.AetherProtocol
import app.novavpn.domain.model.AppSettings
import app.novavpn.domain.model.GeoResourceFormat
import app.novavpn.domain.model.GeoResourceItem
import app.novavpn.domain.model.GeoResourceStatus
import app.novavpn.domain.model.GeoSourceType
import app.novavpn.domain.model.RouteChain
import app.novavpn.domain.model.VpnProfile
import app.novavpn.domain.routing.GeoDatParserTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TunnelStartOrchestratorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `describeCarrier reflects H2+Frag, H3, WireGuard, Psiphon, and Tor`() {
        val h2Frag = VpnProfile(
            id = "p1",
            name = "P1",
            aetherProtocol = AetherProtocol.MASQUE,
            masqueOverH2 = true,
            tlsFragment = true
        )
        assertEquals("MASQUE/H2+Frag", TunnelStartOrchestrator.describeCarrier(RouteChain.AETHER, h2Frag))

        val h3 = VpnProfile(
            id = "p2",
            name = "P2",
            aetherProtocol = AetherProtocol.WARP_IN_MASQUE,
            masqueOverH2 = false,
            tlsFragment = false
        )
        assertEquals("WARP_IN_MASQUE/H3", TunnelStartOrchestrator.describeCarrier(RouteChain.SMART_AUTO, h3))

        val wg = VpnProfile(
            id = "p3",
            name = "P3",
            aetherProtocol = AetherProtocol.WIREGUARD
        )
        assertEquals("WIREGUARD", TunnelStartOrchestrator.describeCarrier(RouteChain.AETHER, wg))
        assertEquals("PSIPHON", TunnelStartOrchestrator.describeCarrier(RouteChain.PSIPHON_ONLY, h2Frag))
        assertEquals("TOR", TunnelStartOrchestrator.describeCarrier(RouteChain.TOR_ONLY, h2Frag))
    }

    @Test
    fun `formatConnectSummary emits single readable line with all connect fields`() {
        val line = TunnelStartOrchestrator.formatConnectSummary(
            route = RouteChain.AETHER,
            carrier = "MASQUE/H2+Frag",
            usedSavedEndpoint = false,
            usedQuickCache = true,
            winningCandidate = "AETHER (Fast Cache)",
            timeToConnectedMs = 4321L
        )
        assertEquals(
            "route=AETHER, carrier=MASQUE/H2+Frag, savedEndpoint=false, quickCache=true, winner=AETHER (Fast Cache), timeMs=4321",
            line
        )
    }

    @Test
    fun `findUnresolvedGeoTagsInItems detects missing category-ads-all, ignores private, and clears when dat present`() {
        val profile = VpnProfile(
            id = "geo-profile",
            name = "Geo",
            directRules = "geoip:private\n*.ir",
            blockRules = "geosite:category-ads-all"
        )

        val missing = GeoResourceRepository.findUnresolvedGeoTagsInItems(
            profiles = listOf(profile),
            readyItems = emptyList(),
            resolveFile = { tempFolder.root }
        )
        assertEquals(listOf("geosite:category-ads-all"), missing)

        val geositeFile = File(tempFolder.root, "geosite.dat").apply {
            writeBytes(GeoDatParserTest.buildGeoSiteList("CATEGORY-ADS-ALL" to listOf(2 to "doubleclick.net")))
        }
        val item = GeoResourceItem(
            id = "gs1",
            name = "geosite.dat",
            sourceType = GeoSourceType.LOCAL_FILE,
            localFileName = "geosite.dat",
            sizeBytes = geositeFile.length(),
            sha256 = "abc123",
            lastUpdatedMs = 1L,
            status = GeoResourceStatus.READY,
            format = GeoResourceFormat.V2RAY_GEOSITE,
            entryCount = 1
        )

        val resolved = GeoResourceRepository.findUnresolvedGeoTagsInItems(
            profiles = listOf(profile),
            readyItems = listOf(item),
            resolveFile = { geositeFile }
        )
        assertTrue(resolved.isEmpty())
    }

    @Test
    fun `AetherCommandBuilder never emits --no-data-check and skips unresolved geo tags`() {
        val nativeDir = tempFolder.newFolder("jni-nodc")
        val workDir = tempFolder.newFolder("work-nodc")
        val profile = VpnProfile(
            id = "nodc",
            name = "NoDC",
            routeChain = RouteChain.AETHER,
            aetherProtocol = AetherProtocol.MASQUE,
            directRules = "geoip:private\n*.ir",
            blockRules = "geosite:category-ads-all"
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
        assertFalse(stage.args.contains("--no-data-check"))
        assertFalse(stage.args.joinToString(" ").contains("category-ads-all"))
    }
}
