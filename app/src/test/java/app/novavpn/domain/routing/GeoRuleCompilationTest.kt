package app.novavpn.domain.routing

import app.novavpn.core.process.AetherCommandBuilder
import app.novavpn.core.tun.SingBoxTunEngine
import app.novavpn.data.store.GeoResourceRepository
import app.novavpn.domain.model.AppSettings
import app.novavpn.domain.model.GeoResourceFormat
import app.novavpn.domain.model.GeoResourceItem
import app.novavpn.domain.model.GeoResourceStatus
import app.novavpn.domain.model.GeoSourceType
import app.novavpn.domain.model.RouteChain
import app.novavpn.domain.model.VpnMode
import app.novavpn.domain.model.VpnProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GeoRuleCompilationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun writeFakeIranDat(fileName: String = "iran.dat"): Pair<GeoResourceItem, File> {
        val bytes = GeoDatParserTest.buildGeoSiteList(
            "all" to listOf(2 to "aparat.com", 2 to "digikala.com"),
            "ads" to listOf(2 to "tapsell.ir", 3 to "ad.tracker.ir"),
            "proxy" to listOf(2 to "google.com"),
            "ir" to listOf(2 to "ir", 2 to "bankmellat.ir", 3 to "portal.bank.ir"),
            "other" to listOf(2 to "snapp.ir", 3 to "api.snapp.ir"),
            "tld-ir" to listOf(2 to "ir")
        )
        val file = File(tempFolder.root, fileName).apply { writeBytes(bytes) }
        val item = GeoResourceItem(
            id = "geo-iran-1",
            name = fileName,
            sourceType = GeoSourceType.URL,
            sourceUrl = "https://example.com/iran.dat",
            localFileName = fileName,
            sizeBytes = file.length(),
            sha256 = "sha-${file.length()}",
            lastUpdatedMs = 1_000L,
            status = GeoResourceStatus.READY,
            format = GeoResourceFormat.V2RAY_GEOSITE,
            entryCount = 6,
            sampleTags = listOf("all", "ads", "proxy")
        )
        return item to file
    }

    @Test
    fun `logs unresolved category-ads-all with available tags and surfaces iran dat suggestion`() {
        val (item, file) = writeFakeIranDat()
        val profile = VpnProfile(
            id = "p1",
            name = "Default",
            directRules = "10.0.0.0/8\n*.ir\nmycustom.ir",
            blockRules = "geosite:category-ads-all\ncustomad.example.com"
        )

        val diagnostics = GeoResourceRepository.findGeoDiagnosticsInItems(
            profiles = listOf(profile),
            activeProfile = profile,
            readyItems = listOf(item),
            resolveFile = { file }
        )

        assertTrue(diagnostics.unresolvedTags.contains("geosite:category-ads-all"))
        assertEquals(listOf("all", "ads", "proxy", "ir", "other", "tld-ir"), diagnostics.availableTags)
        assertTrue(diagnostics.suggestIranDatPreset)

        val logs = mutableListOf<Triple<Boolean, String, String>>()
        val compiled = GeoResourceRepository.compileRulesFromItems(
            profile = profile,
            readyItems = listOf(item),
            resolveFile = { file },
            logSink = { isWarn, tag, msg -> logs += Triple(isWarn, tag, msg) }
        )

        assertTrue(compiled.unresolvedTags.contains("category-ads-all"))
        assertTrue(
            logs.any { (warn, _, msg) ->
                warn && msg.contains("geosite:category-ads-all not found in imported geo files; available: all, ads, proxy, ir, other, tld-ir")
            }
        )
    }

    @Test
    fun `applyIranDatSuggestionToProfile updates rules non-destructively and compiles end to end`() {
        val (item, file) = writeFakeIranDat()
        val initialProfile = VpnProfile(
            id = "p-iran",
            name = "Aether",
            routeChain = RouteChain.AETHER,
            directRules = "10.0.0.0/8\n*.ir\nmycustom.example.org",
            blockRules = "geosite:category-ads-all\nmyblocked.example.com"
        )

        val updated = GeoResourceRepository.applyIranDatSuggestionToProfile(initialProfile)

        // Preserves user-edited direct & block rules, replaces unresolved category-ads-all with ads
        assertTrue(updated.directRules.contains("mycustom.example.org"))
        assertTrue(updated.directRules.contains("geosite:other"))
        assertTrue(updated.directRules.contains("geosite:ir"))
        assertTrue(updated.blockRules.contains("myblocked.example.com"))
        assertTrue(updated.blockRules.contains("geosite:ads"))
        assertFalse(updated.blockRules.contains("category-ads-all"))

        val logs = mutableListOf<Triple<Boolean, String, String>>()
        val compiled = GeoResourceRepository.compileRulesFromItems(
            profile = updated,
            readyItems = listOf(item),
            resolveFile = { file },
            logSink = { isWarn, tag, msg -> logs += Triple(isWarn, tag, msg) }
        )

        assertTrue(compiled.unresolvedTags.isEmpty())
        assertTrue(compiled.directDomains.contains("*.snapp.ir"))
        assertTrue(compiled.directDomains.contains("*.bankmellat.ir"))
        assertTrue(compiled.blockDomains.contains("*.tapsell.ir"))
        assertTrue(compiled.blockDomains.contains("ad.tracker.ir"))
        assertTrue(logs.any { !it.first && it.third.contains("geosite:other -> 2 entries found") })
        assertTrue(logs.any { !it.first && it.third.contains("geosite:ir -> 3 entries found") })
        assertTrue(logs.any { !it.first && it.third.contains("geosite:ads -> 2 entries found") })

        // Verify Aether CLI receives compiled rules in --route-direct and --route-block
        val nativeDir = tempFolder.newFolder("jni")
        val workDir = tempFolder.newFolder("work")
        val settings = AppSettings(vpnMode = VpnMode.VPN, localSocksPort = 24808)
        val plan = AetherCommandBuilder.build(
            nativeDir = nativeDir,
            workDir = workDir,
            profile = updated,
            settings = settings,
            resolvedRoute = RouteChain.AETHER,
            useFreshScan = false,
            extraCompiledDirectRules = compiled.directRules,
            extraCompiledBlockRules = compiled.blockRules
        ).getOrThrow()
        val args = plan.aetherArgs!!
        val routeDirectIdx = args.indexOf("--route-direct")
        val routeBlockIdx = args.indexOf("--route-block")
        assertTrue(routeDirectIdx >= 0)
        assertTrue(routeBlockIdx >= 0)
        val directCsv = args[routeDirectIdx + 1]
        val blockCsv = args[routeBlockIdx + 1]
        assertTrue(directCsv.contains("*.ir"))
        assertTrue(directCsv.contains("*.snapp.ir"))
        assertTrue(directCsv.contains("mycustom.example.org"))
        assertTrue(blockCsv.contains("*.tapsell.ir"))
        assertTrue(blockCsv.contains("myblocked.example.com"))

        // Verify sing-box TUN config receives compiled domain_suffix & domain rules
        val singBoxRaw = SingBoxTunEngine.buildSingBoxConfig(
            socksHost = "127.0.0.1",
            socksPort = 24808,
            auth = null,
            profile = updated,
            extraDirectCidrs = compiled.directCidrs,
            extraDirectDomains = compiled.directDomains,
            extraBlockCidrs = compiled.blockCidrs,
            extraBlockDomains = compiled.blockDomains
        )
        val singBoxRules = Json.parseToJsonElement(singBoxRaw)
            .jsonObject["route"]!!
            .jsonObject["rules"]!!
            .jsonArray
            .map { it.jsonObject }
        val directRuleObj = singBoxRules.first {
            it["outbound"]?.jsonPrimitive?.content == "direct" && it.containsKey("domain_suffix")
        }
        val directSuffixes = directRuleObj["domain_suffix"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(directSuffixes.contains("snapp.ir"))
        assertTrue(directSuffixes.contains("bankmellat.ir"))
        assertTrue(directSuffixes.contains("ir"))

        val rejectRuleObj = singBoxRules.first {
            it["action"]?.jsonPrimitive?.content == "reject" && it.containsKey("domain_suffix")
        }
        val rejectSuffixes = rejectRuleObj["domain_suffix"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(rejectSuffixes.contains("tapsell.ir"))
    }

    @Test
    fun `prioritizes domain suffixes over full domains and logs truncation warning when over limit`() {
        val fullDomains = (1..12).map { 3 to "host$it.example.ir" } // Type 3 = Full
        val suffixDomains = (1..600).map { 2 to "site$it.ir" }     // Type 2 = RootDomain -> *.siteX.ir
        val bytes = GeoDatParserTest.buildGeoSiteList("ir" to (fullDomains + suffixDomains))
        val file = File(tempFolder.root, "large_ir.dat").apply { writeBytes(bytes) }
        val item = GeoResourceItem(
            id = "geo-large",
            name = "large_ir.dat",
            sourceType = GeoSourceType.LOCAL_FILE,
            sourceUrl = null,
            localFileName = "large_ir.dat",
            sizeBytes = file.length(),
            sha256 = "sha-large",
            lastUpdatedMs = 1_000L,
            status = GeoResourceStatus.READY,
            format = GeoResourceFormat.V2RAY_GEOSITE,
            entryCount = 1
        )
        val profile = VpnProfile(
            id = "p-large",
            name = "Large",
            directRules = "geosite:ir",
            blockRules = ""
        )

        val logs = mutableListOf<Triple<Boolean, String, String>>()
        val compiled = GeoResourceRepository.compileRulesFromItems(
            profile = profile,
            readyItems = listOf(item),
            resolveFile = { file },
            maxPerDirection = 512,
            logSink = { isWarn, tag, msg -> logs += Triple(isWarn, tag, msg) }
        )

        assertEquals(512, compiled.directDomains.size)
        assertTrue("Expected all 512 retained rules to be wildcard suffixes", compiled.directDomains.all { it.startsWith("*.") })
        assertTrue(
            logs.any { (warn, _, msg) ->
                warn && msg.contains("truncated 512 of 612")
            }
        )
    }
}
