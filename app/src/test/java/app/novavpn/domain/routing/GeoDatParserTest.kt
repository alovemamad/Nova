package app.novavpn.domain.routing

import app.novavpn.domain.model.GeoResourceFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress

class GeoDatParserTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `parses valid V2Ray geoip dat protobuf and extracts CIDRs by tag`() {
        val bytes = buildGeoIpList(
            "IR" to listOf("185.143.232.0" to 22, "2a00:1450:4001::" to 48),
            "US" to listOf("8.8.8.0" to 24)
        )
        val file = File(tempFolder.root, "geoip.dat").apply { writeBytes(bytes) }

        val inspected = GeoDatParser.inspectFile(file)
        assertNotNull(inspected)
        assertEquals(GeoResourceFormat.V2RAY_GEOIP, inspected!!.format)
        assertEquals(2, inspected.entryCount)
        assertTrue(inspected.sampleTags.contains("ir"))

        val extracted = GeoDatParser.extractRulesForTags(
            file = file,
            requestedTags = setOf("ir"),
            fileTagHint = "geoip"
        )
        assertEquals(GeoResourceFormat.V2RAY_GEOIP, extracted.detectedFormat)
        assertTrue(extracted.cidrs.contains("185.143.232.0/22"))
        assertTrue(extracted.cidrs.any { it.endsWith("/48") })
        assertTrue(extracted.matchedTags.contains("ir"))
    }

    @Test
    fun `parses valid V2Ray geosite dat protobuf and extracts domain rules by tag`() {
        val bytes = buildGeoSiteList(
            "CATEGORY-ADS-ALL" to listOf(
                2 to "doubleclick.net", // RootDomain
                3 to "ads.example.org"  // Full
            ),
            "IR" to listOf(2 to "aparat.com")
        )
        val file = File(tempFolder.root, "geosite.dat").apply { writeBytes(bytes) }

        val inspected = GeoDatParser.inspectFile(file)
        assertNotNull(inspected)
        assertEquals(GeoResourceFormat.V2RAY_GEOSITE, inspected!!.format)
        assertEquals(2, inspected.entryCount)

        val extracted = GeoDatParser.extractRulesForTags(
            file = file,
            requestedTags = setOf("geosite:category-ads-all"),
            fileTagHint = "geosite"
        )
        assertEquals(GeoResourceFormat.V2RAY_GEOSITE, extracted.detectedFormat)
        assertTrue(extracted.domains.contains("*.doubleclick.net"))
        assertTrue(extracted.domains.contains("ads.example.org"))
        assertEquals(2, extracted.domains.size)
    }

    @Test
    fun `parses plain-text rule list saved with dat extension and rejects HTML ZIP or truncated payloads`() {
        val textDat = File(tempFolder.root, "iran.dat").apply {
            writeText(
                """
                # Iran routing rules
                185.143.232.0/22
                domain:digikala.com
                *.ir
                full:api.bank.ir
                """.trimIndent()
            )
        }
        val inspectedText = GeoDatParser.inspectFile(textDat)
        assertNotNull(inspectedText)
        assertEquals(GeoResourceFormat.TEXT_RULES, inspectedText!!.format)
        assertEquals(4, inspectedText.entryCount)

        val extractedText = GeoDatParser.extractRulesForTags(
            file = textDat,
            requestedTags = setOf("iran"),
            fileTagHint = "iran.dat"
        )
        assertTrue(extractedText.cidrs.contains("185.143.232.0/22"))
        assertTrue(extractedText.domains.contains("*.digikala.com"))
        assertTrue(extractedText.domains.contains("*.ir"))
        assertTrue(extractedText.domains.contains("api.bank.ir"))

        val htmlSpoof = File(tempFolder.root, "err.dat").apply { writeText("<!DOCTYPE html><html>404</html>") }
        assertNull(GeoDatParser.inspectFile(htmlSpoof))

        val zipSpoof = File(tempFolder.root, "zip.dat").apply {
            writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00))
        }
        assertNull(GeoDatParser.inspectFile(zipSpoof))

        val truncated = File(tempFolder.root, "trunc.dat").apply {
            writeBytes(byteArrayOf(0x0A, 0x50, 0x0A, 0x02, 0x49, 0x52))
        }
        assertNull(GeoDatParser.inspectFile(truncated))
    }

    companion object {
        fun buildGeoIpList(vararg entries: Pair<String, List<Pair<String, Int>>>): ByteArray {
            val out = ByteArrayOutputStream()
            for ((code, cidrs) in entries) {
                val entryBytes = ByteArrayOutputStream()
                writeBytesField(entryBytes, 1, code.toByteArray(Charsets.UTF_8))
                for ((ip, prefix) in cidrs) {
                    val cidrBytes = ByteArrayOutputStream()
                    writeBytesField(cidrBytes, 1, InetAddress.getByName(ip).address)
                    writeVarintField(cidrBytes, 2, prefix.toLong())
                    writeBytesField(entryBytes, 2, cidrBytes.toByteArray())
                }
                writeBytesField(out, 1, entryBytes.toByteArray())
            }
            return out.toByteArray()
        }

        fun buildGeoSiteList(vararg entries: Pair<String, List<Pair<Int, String>>>): ByteArray {
            val out = ByteArrayOutputStream()
            for ((code, domains) in entries) {
                val entryBytes = ByteArrayOutputStream()
                writeBytesField(entryBytes, 1, code.toByteArray(Charsets.UTF_8))
                for ((type, value) in domains) {
                    val domBytes = ByteArrayOutputStream()
                    writeVarintField(domBytes, 1, type.toLong())
                    writeBytesField(domBytes, 2, value.toByteArray(Charsets.UTF_8))
                    writeBytesField(entryBytes, 2, domBytes.toByteArray())
                }
                writeBytesField(out, 1, entryBytes.toByteArray())
            }
            return out.toByteArray()
        }

        private fun writeBytesField(out: ByteArrayOutputStream, fieldNum: Int, data: ByteArray) {
            writeVarint(out, ((fieldNum shl 3) or 2).toLong())
            writeVarint(out, data.size.toLong())
            out.write(data)
        }

        private fun writeVarintField(out: ByteArrayOutputStream, fieldNum: Int, value: Long) {
            writeVarint(out, ((fieldNum shl 3) or 0).toLong())
            writeVarint(out, value)
        }

        private fun writeVarint(out: ByteArrayOutputStream, v: Long) {
            var rem = v
            while (true) {
                if ((rem and 0x7FL.inv()) == 0L) {
                    out.write(rem.toInt())
                    return
                }
                out.write(((rem and 0x7FL) or 0x80L).toInt())
                rem = rem ushr 7
            }
        }
    }
}
