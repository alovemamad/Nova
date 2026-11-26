package app.novavpn.domain.routing

import app.novavpn.domain.model.GeoResourceFormat
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.nio.charset.StandardCharsets

/**
 * Pure-Kotlin lightweight reader & compiler for V2Ray/v2fly `.dat` files (`geoip.dat`, `geosite.dat`)
 * as well as plain-text CIDR/domain rule files.
 *
 * V2Ray protobuf wire schema (zero external runtime dependencies):
 * - GeoIPList { repeated GeoIP entry = 1; }
 *   GeoIP { string country_code = 1; repeated CIDR cidr = 2; bool inverse_match = 3; }
 *   CIDR { bytes ip = 1; uint32 prefix = 2; }
 *
 * - GeoSiteList { repeated GeoSite entry = 1; }
 *   GeoSite { string country_code = 1; repeated Domain domain = 2; }
 *   Domain { enum Type { Plain = 0; Regex = 1; RootDomain = 2; Full = 3; } Type type = 1; string value = 2; }
 */
object GeoDatParser {

    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LEN = 2
    private const val WIRE_FIXED32 = 5

    data class GeoInspectResult(
        val format: GeoResourceFormat,
        val entryCount: Int,
        val sampleTags: List<String>
    )

    data class ExtractedGeoRules(
        val cidrs: List<String> = emptyList(),
        val domains: List<String> = emptyList(),
        val matchedTags: Set<String> = emptySet(),
        val detectedFormat: GeoResourceFormat = GeoResourceFormat.UNKNOWN
    ) {
        fun isEmpty(): Boolean = cidrs.isEmpty() && domains.isEmpty()
    }

    data class CompiledProfileGeoRules(
        val directRules: List<String> = emptyList(),
        val directCidrs: List<String> = emptyList(),
        val directDomains: List<String> = emptyList(),
        val blockRules: List<String> = emptyList(),
        val blockCidrs: List<String> = emptyList(),
        val blockDomains: List<String> = emptyList(),
        val unresolvedTags: Set<String> = emptySet()
    )

    /**
     * Quickly validates raw bytes before importing/updating a `.dat` resource.
     * Rejects HTML/XML/JSON/ZIP/ELF/PDF/image payloads and unparseable noise.
     */
    fun inspectFile(file: File): GeoInspectResult? {
        if (!file.exists() || !file.isFile || file.length() <= 0L) return null
        val header = ByteArray(minOf(file.length(), 1024L).toInt())
        file.inputStream().use { input ->
            var offset = 0
            while (offset < header.size) {
                val read = input.read(header, offset, header.size - offset)
                if (read <= 0) break
                offset += read
            }
        }
        if (hasKnownNonDatHeader(header)) return null

        // First try V2Ray protobuf GeoIPList / GeoSiteList
        val pbResult = runCatching {
            file.inputStream().buffered(64 * 1024).use { inspectProtobufStream(it) }
        }.getOrNull()
        if (pbResult != null && pbResult.entryCount > 0) {
            return pbResult
        }

        // Fallback: allow valid plain-text domain / CIDR rule lists saved with .dat extension
        if (file.length() <= 4L * 1024L * 1024L) {
            val textResult = runCatching { inspectTextRulesFile(file) }.getOrNull()
            if (textResult != null && textResult.entryCount > 0) {
                return textResult
            }
        }
        return null
    }

    fun inspectBytes(bytes: ByteArray): GeoInspectResult? {
        if (bytes.isEmpty() || hasKnownNonDatHeader(bytes)) return null
        val pbResult = runCatching {
            bytes.inputStream().use { inspectProtobufStream(it) }
        }.getOrNull()
        if (pbResult != null && pbResult.entryCount > 0) return pbResult

        if (bytes.size <= 4 * 1024 * 1024 && isLikelyUtf8Text(bytes)) {
            val lines = bytes.toString(StandardCharsets.UTF_8).lineSequence()
            return inspectTextRulesSequence(lines)
        }
        return null
    }

    /**
     * Extracts CIDR/domain rules matching any requested tag (e.g. "ir", "category-ads-all").
     * For TEXT_RULES `.dat` files without protobuf sections, matches when [requestedTags] contains
     * [fileTagHint] or `"all"` / `"custom"`.
     */
    fun extractRulesForTags(
        file: File,
        requestedTags: Set<String>,
        fileTagHint: String = "",
        maxRules: Int = 512
    ): ExtractedGeoRules {
        if (requestedTags.isEmpty() || maxRules <= 0 || !file.exists() || !file.isFile) {
            return ExtractedGeoRules()
        }
        val normalizedWanted = requestedTags.map { normalizeTag(it) }.filter { it.isNotBlank() }.toSet()
        if (normalizedWanted.isEmpty()) return ExtractedGeoRules()

        val cidrs = LinkedHashSet<String>()
        val domains = LinkedHashSet<String>()
        val matchedTags = LinkedHashSet<String>()
        var sawGeoIp = false
        var sawGeoSite = false

        val parsedPb = runCatching {
            file.inputStream().buffered(64 * 1024).use { stream ->
                val reader = ProtoReader(stream)
                while (reader.hasMore()) {
                    val tag = reader.readTag() ?: break
                    val fieldNumber = tag ushr 3
                    val wireType = tag and 0x07
                    if (fieldNumber == 1 && wireType == WIRE_LEN) {
                        val entryBytes = reader.readBytes(maxLen = 32 * 1024 * 1024) ?: return@use false
                        val parsedEntry = parseTopLevelEntryHeaderAndPayload(
                            entryBytes = entryBytes,
                            wantedTags = normalizedWanted,
                            remainingBudget = (maxRules - cidrs.size - domains.size).coerceAtLeast(0),
                            cidrsOut = cidrs,
                            domainsOut = domains,
                            matchedTagsOut = matchedTags
                        ) ?: return@use false
                        if (parsedEntry == GeoResourceFormat.V2RAY_GEOIP) sawGeoIp = true
                        if (parsedEntry == GeoResourceFormat.V2RAY_GEOSITE) sawGeoSite = true
                    } else {
                        if (!reader.skipField(wireType)) return@use false
                    }
                }
                true
            }
        }.getOrDefault(false)

        if (parsedPb && (sawGeoIp || sawGeoSite)) {
            return ExtractedGeoRules(
                cidrs = cidrs.take(maxRules),
                domains = domains.take(maxRules),
                matchedTags = matchedTags,
                detectedFormat = if (sawGeoIp && !sawGeoSite) GeoResourceFormat.V2RAY_GEOIP else GeoResourceFormat.V2RAY_GEOSITE
            )
        }

        // Fallback plain-text `.dat` rule file matching filename stem (e.g. geoip:custom from custom.dat)
        val normalizedHint = normalizeTag(fileTagHint.substringBeforeLast('.'))
        if (normalizedHint.isNotBlank() && (normalizedHint in normalizedWanted || "custom" in normalizedWanted || "all" in normalizedWanted)) {
            if (file.length() <= 4L * 1024L * 1024L) {
                runCatching {
                    file.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                        lines.forEach { raw ->
                            if (cidrs.size + domains.size >= maxRules) return@forEach
                            val token = raw.substringBefore('#').trim()
                            if (token.isEmpty()) return@forEach
                            classifyTextRule(token, cidrs, domains)
                        }
                    }
                }
                if (cidrs.isNotEmpty() || domains.isNotEmpty()) {
                    matchedTags.add(normalizedHint)
                    return ExtractedGeoRules(
                        cidrs = cidrs.toList(),
                        domains = domains.toList(),
                        matchedTags = matchedTags,
                        detectedFormat = GeoResourceFormat.TEXT_RULES
                    )
                }
            }
        }

        return ExtractedGeoRules()
    }

    internal fun normalizeTag(raw: String): String =
        raw.trim().lowercase()
            .removePrefix("geoip:")
            .removePrefix("geosite:")
            .substringBefore('@')
            .trim()

    internal fun hasKnownNonDatHeader(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        // Skip UTF-8 BOM if present
        var start = if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) 3 else 0
        while (start < bytes.size && bytes[start].toInt().toChar().isWhitespace()) {
            start++
        }
        val sliceLen = minOf(bytes.size - start, 64)
        if (sliceLen > 0) {
            val head = String(bytes, start, sliceLen, StandardCharsets.US_ASCII).lowercase()
            if (head.startsWith("<!doctype") ||
                head.startsWith("<html") ||
                head.startsWith("<head") ||
                head.startsWith("<?xml") ||
                head.startsWith("{") ||
                head.startsWith("[") ||
                head.startsWith("error ") ||
                head.startsWith("not found")
            ) {
                return true
            }
        }
        // Binary non-protobuf magic bytes: ZIP ("PK"), ELF, PDF, PNG, JPEG, GZIP
        if (bytes.size >= 4) {
            if (bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) return true // ZIP / APK
            if (bytes[0] == 0x7F.toByte() && bytes[1] == 0x45.toByte() && bytes[2] == 0x4C.toByte() && bytes[3] == 0x46.toByte()) return true // ELF
            if (bytes[0] == 0x25.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x44.toByte() && bytes[3] == 0x46.toByte()) return true // %PDF
            if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) return true // PNG
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) return true // JPEG
            if (bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()) return true // GZIP
        }
        return false
    }

    private fun inspectProtobufStream(stream: InputStream): GeoInspectResult? {
        val reader = ProtoReader(stream)
        var entryCount = 0
        var geoIpVotes = 0
        var geoSiteVotes = 0
        val sampleTags = ArrayList<String>(8)

        while (reader.hasMore()) {
            val tag = reader.readTag() ?: break
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07
            if (fieldNumber == 1 && wireType == WIRE_LEN) {
                val entryLen = reader.readVarint32() ?: return null
                if (entryLen <= 0 || entryLen > 32 * 1024 * 1024) return null
                val headerSliceLen = minOf(entryLen, 512)
                val headerBytes = reader.readExact(headerSliceLen) ?: return null
                if (entryLen > headerSliceLen) {
                    if (!reader.skipExact((entryLen - headerSliceLen).toLong())) return null
                }
                val meta = inspectEntrySlice(headerBytes) ?: return null
                entryCount++
                when (meta.first) {
                    GeoResourceFormat.V2RAY_GEOIP -> geoIpVotes++
                    GeoResourceFormat.V2RAY_GEOSITE -> geoSiteVotes++
                    else -> Unit
                }
                val code = meta.second
                if (!code.isNullOrBlank() && sampleTags.size < 8 && !sampleTags.contains(code)) {
                    sampleTags.add(code)
                }
            } else {
                if (!reader.skipField(wireType)) return null
            }
        }
        if (entryCount == 0) return null
        val format = when {
            geoSiteVotes > geoIpVotes -> GeoResourceFormat.V2RAY_GEOSITE
            geoIpVotes > 0 -> GeoResourceFormat.V2RAY_GEOIP
            else -> GeoResourceFormat.V2RAY_GEOIP
        }
        return GeoInspectResult(
            format = format,
            entryCount = entryCount,
            sampleTags = sampleTags
        )
    }

    /**
     * Parses only the initial slice of a `GeoIP` or `GeoSite` entry to identify `country_code`
     * and distinguish CIDR `{ bytes ip = 1; uint32 prefix = 2; }` vs Domain `{ enum type = 1; string value = 2; }`.
     */
    private fun inspectEntrySlice(slice: ByteArray): Pair<GeoResourceFormat, String?>? {
        val reader = ByteProtoReader(slice)
        var tagCode: String? = null
        var guessedFormat = GeoResourceFormat.UNKNOWN
        var sawField = false

        while (reader.hasMore()) {
            val tag = reader.readTagOrNull() ?: break
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07
            if (fieldNumber <= 0) return null
            sawField = true
            when {
                fieldNumber == 1 && wireType == WIRE_LEN -> {
                    val strBytes = reader.readBytesOrNull(maxLen = 256) ?: break
                    val decoded = strBytes.toString(StandardCharsets.UTF_8).trim()
                    if (decoded.isNotEmpty() && decoded.all { !it.isISOControl() }) {
                        tagCode = decoded.lowercase()
                    }
                }
                fieldNumber == 2 && wireType == WIRE_LEN -> {
                    val itemBytes = reader.readBytesOrNull(maxLen = 256) ?: break
                    val detected = sniffCidrOrDomain(itemBytes)
                    if (detected != GeoResourceFormat.UNKNOWN) {
                        guessedFormat = detected
                    }
                }
                else -> {
                    if (!reader.skipField(wireType)) break
                }
            }
            if (tagCode != null && guessedFormat != GeoResourceFormat.UNKNOWN) break
        }

        if (!sawField || tagCode.isNullOrBlank()) return null
        return guessedFormat to tagCode
    }

    private fun sniffCidrOrDomain(sub: ByteArray): GeoResourceFormat {
        val reader = ByteProtoReader(sub)
        var hasIpBytes = false
        var hasPrefixVarint = false
        var hasTypeVarint = false
        var hasValueStr = false

        while (reader.hasMore()) {
            val tag = reader.readTagOrNull() ?: break
            val field = tag ushr 3
            val wire = tag and 0x07
            when {
                field == 1 && wire == WIRE_LEN -> {
                    val b = reader.readBytesOrNull(maxLen = 64) ?: break
                    if (b.size == 4 || b.size == 16) hasIpBytes = true
                }
                field == 2 && wire == WIRE_VARINT -> {
                    reader.readVarint64OrNull() ?: break
                    hasPrefixVarint = true
                }
                field == 1 && wire == WIRE_VARINT -> {
                    val v = reader.readVarint64OrNull() ?: break
                    if (v in 0L..4L) hasTypeVarint = true
                }
                field == 2 && wire == WIRE_LEN -> {
                    val b = reader.readBytesOrNull(maxLen = 256) ?: break
                    if (b.isNotEmpty()) hasValueStr = true
                }
                else -> {
                    if (!reader.skipField(wire)) break
                }
            }
        }
        return when {
            hasIpBytes || hasPrefixVarint -> GeoResourceFormat.V2RAY_GEOIP
            hasValueStr || hasTypeVarint -> GeoResourceFormat.V2RAY_GEOSITE
            else -> GeoResourceFormat.UNKNOWN
        }
    }

    private fun parseTopLevelEntryHeaderAndPayload(
        entryBytes: ByteArray,
        wantedTags: Set<String>,
        remainingBudget: Int,
        cidrsOut: MutableSet<String>,
        domainsOut: MutableSet<String>,
        matchedTagsOut: MutableSet<String>
    ): GeoResourceFormat? {
        // Scan field 1 (`country_code`) first without allocating sub-messages unless matched
        val pass1 = ByteProtoReader(entryBytes)
        var entryTag: String? = null
        var formatGuess = GeoResourceFormat.UNKNOWN
        while (pass1.hasMore()) {
            val tag = pass1.readTagOrNull() ?: return null
            val field = tag ushr 3
            val wire = tag and 0x07
            if (field <= 0) return null
            when {
                field == 1 && wire == WIRE_LEN -> {
                    val b = pass1.readBytesOrNull() ?: return null
                    entryTag = normalizeTag(b.toString(StandardCharsets.UTF_8))
                }
                field == 2 && wire == WIRE_LEN && formatGuess == GeoResourceFormat.UNKNOWN -> {
                    val sample = pass1.readBytesOrNull() ?: return null
                    formatGuess = sniffCidrOrDomain(sample)
                }
                else -> if (!pass1.skipField(wire)) return null
            }
        }

        if (entryTag.isNullOrBlank()) return null
        if (entryTag !in wantedTags || remainingBudget <= 0) {
            return formatGuess.takeIf { it != GeoResourceFormat.UNKNOWN } ?: GeoResourceFormat.V2RAY_GEOIP
        }

        matchedTagsOut.add(entryTag)
        var added = 0
        val pass2 = ByteProtoReader(entryBytes)
        while (pass2.hasMore() && added < remainingBudget) {
            val tag = pass2.readTagOrNull() ?: break
            val field = tag ushr 3
            val wire = tag and 0x07
            if (field == 2 && wire == WIRE_LEN) {
                val itemBytes = pass2.readBytesOrNull() ?: break
                val sniff = sniffCidrOrDomain(itemBytes)
                if (sniff == GeoResourceFormat.V2RAY_GEOSITE) {
                    formatGuess = GeoResourceFormat.V2RAY_GEOSITE
                    val domainRule = decodeDomainEntry(itemBytes)
                    if (domainRule != null && domainsOut.add(domainRule)) {
                        added++
                    }
                } else {
                    val cidrRule = decodeCidrEntry(itemBytes)
                    if (cidrRule != null) {
                        formatGuess = GeoResourceFormat.V2RAY_GEOIP
                        if (cidrsOut.add(cidrRule)) {
                            added++
                        }
                    } else {
                        val domainRule = decodeDomainEntry(itemBytes)
                        if (domainRule != null) {
                            formatGuess = GeoResourceFormat.V2RAY_GEOSITE
                            if (domainsOut.add(domainRule)) {
                                added++
                            }
                        }
                    }
                }
            } else {
                if (!pass2.skipField(wire)) break
            }
        }
        return formatGuess
    }

    private fun decodeCidrEntry(bytes: ByteArray): String? {
        val reader = ByteProtoReader(bytes)
        var ipBytes: ByteArray? = null
        var prefix: Int? = null
        while (reader.hasMore()) {
            val tag = reader.readTagOrNull() ?: break
            val field = tag ushr 3
            val wire = tag and 0x07
            when {
                field == 1 && wire == WIRE_LEN -> ipBytes = reader.readBytesOrNull(maxLen = 32)
                field == 2 && wire == WIRE_VARINT -> prefix = reader.readVarint64OrNull()?.toInt()
                else -> if (!reader.skipField(wire)) break
            }
        }
        val rawIp = ipBytes ?: return null
        if (rawIp.size != 4 && rawIp.size != 16) return null
        val maxPrefix = if (rawIp.size == 4) 32 else 128
        val cleanPrefix = (prefix ?: maxPrefix).coerceIn(0, maxPrefix)
        val addr = runCatching { InetAddress.getByAddress(rawIp).hostAddress }.getOrNull() ?: return null
        return "$addr/$cleanPrefix"
    }

    private fun decodeDomainEntry(bytes: ByteArray): String? {
        val reader = ByteProtoReader(bytes)
        var domainType = 0 // Plain=0, Regex=1, RootDomain=2, Full=3
        var value: String? = null
        while (reader.hasMore()) {
            val tag = reader.readTagOrNull() ?: break
            val field = tag ushr 3
            val wire = tag and 0x07
            when {
                field == 1 && wire == WIRE_VARINT -> domainType = reader.readVarint64OrNull()?.toInt() ?: 0
                field == 2 && wire == WIRE_LEN -> {
                    val b = reader.readBytesOrNull(maxLen = 512) ?: break
                    value = b.toString(StandardCharsets.UTF_8).trim().lowercase()
                }
                else -> if (!reader.skipField(wire)) break
            }
        }
        val v = value?.takeIf { it.isNotBlank() && !it.contains(',') && !it.contains(' ') } ?: return null
        return when (domainType) {
            1 -> null // Skip heavy regex patterns for lightweight router lists
            2 -> "*.$v" // RootDomain / domain suffix
            3 -> v // Full match
            else -> "*.$v" // Plain keyword/domain -> domain suffix pattern for router
        }
    }

    private fun inspectTextRulesFile(file: File): GeoInspectResult? {
        file.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
            return inspectTextRulesSequence(lines)
        }
    }

    private fun inspectTextRulesSequence(lines: Sequence<String>): GeoInspectResult? {
        var validCount = 0
        var invalidLines = 0
        lines.take(500).forEach { raw ->
            val trimmed = raw.substringBefore('#').trim()
            if (trimmed.isEmpty()) return@forEach
            if (classifyTextRule(trimmed, null, null)) {
                validCount++
            } else {
                invalidLines++
            }
        }
        if (validCount == 0 || invalidLines > validCount * 2) return null
        return GeoInspectResult(
            format = GeoResourceFormat.TEXT_RULES,
            entryCount = validCount,
            sampleTags = listOf("custom")
        )
    }

    private fun classifyTextRule(
        token: String,
        cidrsOut: MutableSet<String>?,
        domainsOut: MutableSet<String>?
    ): Boolean {
        val clean = token.trim().lowercase()
        if (clean.isEmpty() || clean.length > 253) return false
        if (clean.contains('<') || clean.contains('>') || clean.contains('{') || clean.contains('}')) return false
        val isCidr = clean.contains('/') && (clean.contains('.') || clean.contains(':'))
        if (isCidr) {
            val parts = clean.split('/')
            if (parts.size == 2 && parts[1].toIntOrNull() != null && parts[0].isNotBlank()) {
                cidrsOut?.add(clean)
                return true
            }
            return false
        }
        val stripped = clean
            .removePrefix("domain:")
            .removePrefix("full:")
            .removePrefix("domain_suffix:")
            .removePrefix("*.")
            .removePrefix(".")
        val hasValidChars = stripped.isNotEmpty() &&
            stripped.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
        val looksLikeDomain = hasValidChars && (stripped.contains('.') || (clean.startsWith("*.") && stripped.length >= 2))
        if (looksLikeDomain) {
            val outRule = when {
                clean.startsWith("*.") -> clean
                clean.startsWith("domain:") || clean.startsWith("domain_suffix:") -> "*.$stripped"
                clean.startsWith("full:") -> stripped
                else -> "*.$stripped"
            }
            domainsOut?.add(outRule)
            return true
        }
        return false
    }

    private fun isLikelyUtf8Text(bytes: ByteArray): Boolean {
        val checkLen = minOf(bytes.size, 512)
        var nonPrintable = 0
        for (i in 0 until checkLen) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0) return false
            if (b < 0x20 && b != 0x09 && b != 0x0A && b != 0x0D) {
                nonPrintable++
            }
        }
        return nonPrintable == 0
    }

    private class ProtoReader(stream: InputStream) {
        private val input = stream as? BufferedInputStream ?: BufferedInputStream(stream, 64 * 1024)
        private var nextByte: Int = -2

        fun hasMore(): Boolean {
            if (nextByte == -2) nextByte = input.read()
            return nextByte >= 0
        }

        private fun readByte(): Int {
            if (nextByte != -2) {
                val b = nextByte
                nextByte = -2
                return b
            }
            return input.read()
        }

        fun readTag(): Int? {
            val v = readVarint64OrNull() ?: return null
            if (v <= 0L || v > Int.MAX_VALUE) return null
            return v.toInt()
        }

        fun readVarint32(): Int? {
            val v = readVarint64OrNull() ?: return null
            if (v < 0L || v > Int.MAX_VALUE) return null
            return v.toInt()
        }

        fun readVarint64OrNull(): Long? {
            var shift = 0
            var result = 0L
            while (shift < 64) {
                val b = readByte()
                if (b < 0) return if (shift == 0) null else null
                result = result or ((b and 0x7F).toLong() shl shift)
                if ((b and 0x80) == 0) return result
                shift += 7
            }
            return null
        }

        fun readExact(len: Int): ByteArray? {
            if (len < 0) return null
            val out = ByteArray(len)
            var offset = 0
            while (offset < len) {
                val b = if (offset == 0 && nextByte != -2) {
                    val v = nextByte
                    nextByte = -2
                    if (v < 0) return null
                    out[0] = v.toByte()
                    offset = 1
                    continue
                } else {
                    input.read(out, offset, len - offset)
                }
                if (b <= 0) return null
                offset += b
            }
            return out
        }

        fun readBytes(maxLen: Int): ByteArray? {
            val len = readVarint32() ?: return null
            if (len < 0 || len > maxLen) return null
            return readExact(len)
        }

        fun skipExact(count: Long): Boolean {
            var remaining = count
            if (remaining <= 0L) return true
            if (nextByte != -2) {
                if (nextByte < 0) return false
                nextByte = -2
                remaining--
            }
            val buf = ByteArray(8192)
            while (remaining > 0L) {
                val toRead = minOf(remaining, buf.size.toLong()).toInt()
                val read = input.read(buf, 0, toRead)
                if (read <= 0) return false
                remaining -= read
            }
            return true
        }

        fun skipField(wireType: Int): Boolean = when (wireType) {
            WIRE_VARINT -> readVarint64OrNull() != null
            WIRE_FIXED64 -> skipExact(8L)
            WIRE_LEN -> {
                val len = readVarint32() ?: return false
                skipExact(len.toLong())
            }
            WIRE_FIXED32 -> skipExact(4L)
            else -> false
        }
    }

    private class ByteProtoReader(private val buf: ByteArray) {
        private var pos = 0

        fun hasMore(): Boolean = pos < buf.size

        fun readTagOrNull(): Int? {
            val v = readVarint64OrNull() ?: return null
            if (v <= 0L || v > Int.MAX_VALUE) return null
            return v.toInt()
        }

        fun readVarint64OrNull(): Long? {
            var shift = 0
            var result = 0L
            while (shift < 64 && pos < buf.size) {
                val b = buf[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if ((b and 0x80) == 0) return result
                shift += 7
            }
            return null
        }

        fun readBytesOrNull(maxLen: Int = Int.MAX_VALUE): ByteArray? {
            val lenL = readVarint64OrNull() ?: return null
            if (lenL < 0L || lenL > maxLen || lenL > (buf.size - pos).toLong()) return null
            val len = lenL.toInt()
            val out = buf.copyOfRange(pos, pos + len)
            pos += len
            return out
        }

        fun skipField(wireType: Int): Boolean = when (wireType) {
            WIRE_VARINT -> readVarint64OrNull() != null
            WIRE_FIXED64 -> {
                if (buf.size - pos < 8) false else { pos += 8; true }
            }
            WIRE_LEN -> {
                val lenL = readVarint64OrNull() ?: return false
                if (lenL < 0L || lenL > (buf.size - pos).toLong()) false else { pos += lenL.toInt(); true }
            }
            WIRE_FIXED32 -> {
                if (buf.size - pos < 4) false else { pos += 4; true }
            }
            else -> false
        }
    }
}
