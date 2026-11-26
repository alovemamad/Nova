package app.novavpn.data.store

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.novavpn.core.net.SharedHttpClient
import app.novavpn.data.log.LogRepository
import app.novavpn.domain.model.GeoBatchUpdateSummary
import app.novavpn.domain.model.GeoResourceFormat
import app.novavpn.domain.model.GeoResourceItem
import app.novavpn.domain.model.GeoResourceStatus
import app.novavpn.domain.model.GeoSourceType
import app.novavpn.domain.model.VpnProfile
import app.novavpn.domain.routing.GeoDatParser
import app.novavpn.domain.routing.RoutingRuleParser
import app.novavpn.domain.routing.RuleKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

sealed class GeoValidationException(message: String) : IOException(message) {
    class InvalidUrl(message: String) : GeoValidationException(message)
    class InvalidDatFile(message: String) : GeoValidationException(message)
    class DuplicateInFlight(message: String) : GeoValidationException(message)
    class NetworkFailure(message: String) : GeoValidationException(message)
}

class GeoResourceRepository(private val context: Context) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    private val storageDir: File = File(context.filesDir, "geo_resources").apply { mkdirs() }
    private val indexFile: File = File(storageDir, "index.json")

    private val indexMutex = Mutex()
    private val batchMutex = Mutex()
    private val inFlightLock = Any()
    private val inFlightKeys = mutableSetOf<String>()

    private val _resources = MutableStateFlow<List<GeoResourceItem>>(emptyList())
    val resources: StateFlow<List<GeoResourceItem>> = _resources.asStateFlow()

    private val _isUpdatingAll = MutableStateFlow(false)
    val isUpdatingAll: StateFlow<Boolean> = _isUpdatingAll.asStateFlow()

    private val compiledTagCache = ConcurrentHashMap<String, GeoDatParser.ExtractedGeoRules>()

    suspend fun loadInitial() {
        withContext(Dispatchers.IO) {
            indexMutex.withLock {
                val loaded = readIndexFromDisk()
                val reconciled = loaded.map { item ->
                    val file = File(storageDir, item.localFileName)
                    val baseItem = if (item.status == GeoResourceStatus.UPDATING) {
                        // Recover cleanly if killed mid-update: healthy prior file stays READY
                        if (file.isFile && file.length() > 0L) {
                            item.copy(status = GeoResourceStatus.READY, lastError = null)
                        } else {
                            item.copy(status = GeoResourceStatus.ERROR)
                        }
                    } else {
                        item
                    }
                    if (baseItem.status == GeoResourceStatus.READY &&
                        baseItem.format == GeoResourceFormat.UNKNOWN &&
                        file.isFile &&
                        file.length() > 0L
                    ) {
                        val inspected = GeoDatParser.inspectFile(file)
                        if (inspected != null) {
                            baseItem.copy(
                                format = inspected.format,
                                entryCount = inspected.entryCount,
                                sampleTags = inspected.sampleTags
                            )
                        } else {
                            baseItem
                        }
                    } else {
                        baseItem
                    }
                }
                _resources.value = reconciled
                if (reconciled != loaded) {
                    writeIndexToDisk(reconciled)
                }
            }
        }
    }

    fun resolveResourceFile(item: GeoResourceItem): File = File(storageDir, item.localFileName)

    fun findActiveDatFile(nameContains: String): File? {
        val needle = nameContains.lowercase(Locale.US)
        val match = _resources.value.firstOrNull {
            it.status == GeoResourceStatus.READY &&
                it.name.lowercase(Locale.US).contains(needle)
        } ?: return null
        val file = File(storageDir, match.localFileName)
        return if (file.isFile && file.length() > 0L) file else null
    }

    suspend fun importFromUri(uri: Uri): Result<GeoResourceItem> = withContext(Dispatchers.IO) {
        val rawName = queryDisplayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "geo_resource.dat"
        val cleanName = sanitizeDisplayName(rawName)
        if (!isValidDatFileName(cleanName)) {
            return@withContext Result.failure(
                GeoValidationException.InvalidDatFile("Only valid .dat geo-resource files are supported ($cleanName)")
            )
        }

        val uriKey = "uri:$uri"
        if (!tryAcquireInFlight(uriKey)) {
            return@withContext Result.failure(
                GeoValidationException.DuplicateInFlight("Import already in progress for $cleanName")
            )
        }

        try {
            val id = UUID.randomUUID().toString()
            val targetFileName = "${id}_${safeDiskName(cleanName)}"
            val targetFile = File(storageDir, targetFileName)
            val partFile = File(storageDir, "$targetFileName.part")

            val stream = context.contentResolver.openInputStream(uri)
                ?: return@withContext Result.failure(
                    GeoValidationException.InvalidDatFile("Unable to open selected file")
                )

            val writeStats = stream.use { input ->
                streamAndValidateToTemp(input, partFile)
            }

            if (writeStats.isFailure) {
                partFile.delete()
                return@withContext Result.failure(writeStats.exceptionOrNull()!!)
            }

            val (byteCount, sha256) = writeStats.getOrThrow()
            val inspected = GeoDatParser.inspectFile(partFile)
                ?: run {
                    partFile.delete()
                    return@withContext Result.failure(
                        GeoValidationException.InvalidDatFile("File is not a valid V2Ray .dat database or CIDR/domain list")
                    )
                }
            atomicReplaceFile(partFile, targetFile)

            val item = GeoResourceItem(
                id = id,
                name = cleanName,
                sourceType = GeoSourceType.LOCAL_FILE,
                sourceUrl = null,
                localFileName = targetFileName,
                sizeBytes = byteCount,
                sha256 = sha256,
                lastUpdatedMs = System.currentTimeMillis(),
                status = GeoResourceStatus.READY,
                lastError = null,
                format = inspected.format,
                entryCount = inspected.entryCount,
                sampleTags = inspected.sampleTags
            )

            indexMutex.withLock {
                val updated = _resources.value + item
                writeIndexToDisk(updated)
                _resources.value = updated
            }
            LogRepository.i(
                "GeoResources",
                "Imported .dat file '$cleanName' ($byteCount bytes, format=${inspected.format.name}, entries=${inspected.entryCount})"
            )
            Result.success(item)
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            releaseInFlight(uriKey)
        }
    }

    suspend fun addFromUrl(rawUrl: String, customName: String? = null): Result<GeoResourceItem> = withContext(Dispatchers.IO) {
        val validUrl = validateUrl(rawUrl).getOrElse { return@withContext Result.failure(it) }
        val normalizedKey = "url:${validUrl.lowercase(Locale.US)}"

        val existing = _resources.value.firstOrNull {
            it.sourceType == GeoSourceType.URL && it.sourceUrl.equals(validUrl, ignoreCase = true)
        }
        if (existing != null) {
            return@withContext updateSingleUrlResource(existing.id)
        }

        if (!tryAcquireInFlight(normalizedKey)) {
            return@withContext Result.failure(
                GeoValidationException.DuplicateInFlight("Download already in progress for URL")
            )
        }

        try {
            val fileNameFromUrl = deriveDatNameFromUrl(validUrl, customName)
            val id = UUID.randomUUID().toString()
            val targetFileName = "${id}_${safeDiskName(fileNameFromUrl)}"
            val targetFile = File(storageDir, targetFileName)
            val partFile = File(storageDir, "$targetFileName.part")

            val dlResult = downloadAndValidateToTemp(validUrl, partFile)
            if (dlResult.isFailure) {
                partFile.delete()
                return@withContext Result.failure(dlResult.exceptionOrNull()!!)
            }

            val (byteCount, sha256) = dlResult.getOrThrow()
            val inspected = GeoDatParser.inspectFile(partFile)
                ?: run {
                    partFile.delete()
                    return@withContext Result.failure(
                        GeoValidationException.InvalidDatFile("Downloaded file is not a valid V2Ray .dat database or rule list")
                    )
                }
            atomicReplaceFile(partFile, targetFile)

            val item = GeoResourceItem(
                id = id,
                name = fileNameFromUrl,
                sourceType = GeoSourceType.URL,
                sourceUrl = validUrl,
                localFileName = targetFileName,
                sizeBytes = byteCount,
                sha256 = sha256,
                lastUpdatedMs = System.currentTimeMillis(),
                status = GeoResourceStatus.READY,
                lastError = null,
                format = inspected.format,
                entryCount = inspected.entryCount,
                sampleTags = inspected.sampleTags
            )

            indexMutex.withLock {
                val updated = _resources.value + item
                writeIndexToDisk(updated)
                _resources.value = updated
            }
            LogRepository.i(
                "GeoResources",
                "Downloaded geo-resource '$fileNameFromUrl' ($byteCount bytes, format=${inspected.format.name}, entries=${inspected.entryCount})"
            )
            Result.success(item)
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            releaseInFlight(normalizedKey)
        }
    }

    suspend fun updateSingleUrlResource(id: String): Result<GeoResourceItem> = withContext(Dispatchers.IO) {
        val snapshot = _resources.value.firstOrNull { it.id == id }
            ?: return@withContext Result.failure(IllegalArgumentException("Geo resource not found"))
        val url = snapshot.sourceUrl
            ?: return@withContext Result.failure(GeoValidationException.InvalidUrl("Resource has no update URL"))

        val lockKey = "res:$id"
        if (!tryAcquireInFlight(lockKey)) {
            return@withContext Result.failure(
                GeoValidationException.DuplicateInFlight("Update already in progress for ${snapshot.name}")
            )
        }

        try {
            markStatus(id, GeoResourceStatus.UPDATING, error = null)

            val targetFile = File(storageDir, snapshot.localFileName)
            val partFile = File(storageDir, "${snapshot.localFileName}.part")

            val dlResult = downloadAndValidateToTemp(url, partFile)
            if (dlResult.isFailure) {
                partFile.delete()
                val err = dlResult.exceptionOrNull() ?: IOException("Download failed")
                // Keep previous valid .dat file intact! Record error status for UI visibility
                markStatus(
                    id = id,
                    status = GeoResourceStatus.ERROR,
                    error = err.message ?: "Update failed; previous file preserved"
                )
                return@withContext Result.failure(err)
            }

            val (byteCount, sha256) = dlResult.getOrThrow()
            val inspected = GeoDatParser.inspectFile(partFile)
                ?: run {
                    partFile.delete()
                    val msg = "Updated payload failed .dat structure validation; previous file preserved"
                    markStatus(id = id, status = GeoResourceStatus.ERROR, error = msg)
                    return@withContext Result.failure(GeoValidationException.InvalidDatFile(msg))
                }
            // Preserve identical content without redundant rewrite if sha256 matches and healthy file exists
            if (targetFile.isFile && targetFile.length() == byteCount && snapshot.sha256 == sha256) {
                partFile.delete()
            } else {
                atomicReplaceFile(partFile, targetFile)
                compiledTagCache.keys.removeAll { it.startsWith("$id:") }
            }

            var finalItem = snapshot
            indexMutex.withLock {
                val updatedList = _resources.value.map { item ->
                    if (item.id == id) {
                        item.copy(
                            sizeBytes = byteCount,
                            sha256 = sha256,
                            lastUpdatedMs = System.currentTimeMillis(),
                            status = GeoResourceStatus.READY,
                            lastError = null,
                            format = inspected.format,
                            entryCount = inspected.entryCount,
                            sampleTags = inspected.sampleTags
                        ).also { finalItem = it }
                    } else {
                        item
                    }
                }
                writeIndexToDisk(updatedList)
                _resources.value = updatedList
            }
            LogRepository.i("GeoResources", "Updated '${finalItem.name}' ($byteCount bytes, entries=${inspected.entryCount})")
            Result.success(finalItem)
        } finally {
            releaseInFlight(lockKey)
        }
    }

    suspend fun updateAllUrlResources(): GeoBatchUpdateSummary = withContext(Dispatchers.IO) {
        if (!batchMutex.tryLock()) {
            val count = _resources.value.count { it.sourceType == GeoSourceType.URL }
            return@withContext GeoBatchUpdateSummary(
                totalUrlResources = count,
                updatedCount = 0,
                failedCount = 0,
                skippedDuplicate = true
            )
        }
        _isUpdatingAll.value = true
        try {
            val urlItems = _resources.value.filter { it.sourceType == GeoSourceType.URL }
            var ok = 0
            var fail = 0
            for (item in urlItems) {
                val res = updateSingleUrlResource(item.id)
                if (res.isSuccess) ok++ else fail++
            }
            GeoBatchUpdateSummary(
                totalUrlResources = urlItems.size,
                updatedCount = ok,
                failedCount = fail,
                skippedDuplicate = false
            )
        } finally {
            _isUpdatingAll.value = false
            batchMutex.unlock()
        }
    }

    suspend fun deleteResource(id: String) = withContext(Dispatchers.IO) {
        indexMutex.withLock {
            val target = _resources.value.firstOrNull { it.id == id }
            if (target != null) {
                File(storageDir, target.localFileName).delete()
                File(storageDir, "${target.localFileName}.part").delete()
            }
            compiledTagCache.keys.removeAll { it.startsWith("$id:") }
            val updated = _resources.value.filterNot { it.id == id }
            writeIndexToDisk(updated)
            _resources.value = updated
        }
    }

    /**
     * Expands `geoip:<tag>` and `geosite:<tag>` rules declared in [profile] against imported READY `.dat`
     * resources so both Aether (`--route-direct`/`--route-block`) and sing-box (`route.rules`) consume them.
     * Cached by `(resource.id, resource.sha256, tags)` to avoid re-parsing multi-megabyte `.dat` files on reconnect.
     */
    suspend fun compileRulesForProfile(
        profile: VpnProfile,
        maxPerDirection: Int = 512
    ): GeoDatParser.CompiledProfileGeoRules = withContext(Dispatchers.IO) {
        val directParsed = RoutingRuleParser.parse(profile.directRules).validRules
        val blockParsed = RoutingRuleParser.parse(profile.blockRules).validRules

        val directGeoIpTags = directParsed.filter { it.kind == RuleKind.GEOIP }.map { it.value }.toSet()
        val directGeoSiteTags = directParsed.filter { it.kind == RuleKind.GEOSITE }.map { it.value }.toSet()
        val blockGeoIpTags = blockParsed.filter { it.kind == RuleKind.GEOIP }.map { it.value }.toSet()
        val blockGeoSiteTags = blockParsed.filter { it.kind == RuleKind.GEOSITE }.map { it.value }.toSet()

        if (directGeoIpTags.isEmpty() && directGeoSiteTags.isEmpty() &&
            blockGeoIpTags.isEmpty() && blockGeoSiteTags.isEmpty()
        ) {
            return@withContext GeoDatParser.CompiledProfileGeoRules()
        }

        val readyItems = _resources.value.filter { it.status == GeoResourceStatus.READY }
        val directCidrs = LinkedHashSet<String>()
        val directDomains = LinkedHashSet<String>()
        val blockCidrs = LinkedHashSet<String>()
        val blockDomains = LinkedHashSet<String>()
        val matchedTags = LinkedHashSet<String>()

        fun extractCached(item: GeoResourceItem, wantedTags: Set<String>, limit: Int): GeoDatParser.ExtractedGeoRules {
            if (wantedTags.isEmpty() || limit <= 0) return GeoDatParser.ExtractedGeoRules()
            val file = File(storageDir, item.localFileName)
            if (!file.isFile || file.length() <= 0L) return GeoDatParser.ExtractedGeoRules()
            val key = "${item.id}:${item.sha256}:${wantedTags.sorted().joinToString(",")}:$limit"
            compiledTagCache[key]?.let { return it }
            val extracted = GeoDatParser.extractRulesForTags(
                file = file,
                requestedTags = wantedTags,
                fileTagHint = item.name,
                maxRules = limit
            )
            if (compiledTagCache.size > 64) compiledTagCache.clear()
            compiledTagCache[key] = extracted
            return extracted
        }

        for (item in readyItems) {
            if (directGeoIpTags.isNotEmpty() &&
                (item.format == GeoResourceFormat.V2RAY_GEOIP || item.format == GeoResourceFormat.TEXT_RULES || item.format == GeoResourceFormat.UNKNOWN)
            ) {
                val res = extractCached(item, directGeoIpTags, maxPerDirection)
                directCidrs.addAll(res.cidrs)
                directDomains.addAll(res.domains)
                matchedTags.addAll(res.matchedTags)
            }
            if (directGeoSiteTags.isNotEmpty() &&
                (item.format == GeoResourceFormat.V2RAY_GEOSITE || item.format == GeoResourceFormat.TEXT_RULES || item.format == GeoResourceFormat.UNKNOWN)
            ) {
                val res = extractCached(item, directGeoSiteTags, maxPerDirection)
                directCidrs.addAll(res.cidrs)
                directDomains.addAll(res.domains)
                matchedTags.addAll(res.matchedTags)
            }
            if (blockGeoIpTags.isNotEmpty() &&
                (item.format == GeoResourceFormat.V2RAY_GEOIP || item.format == GeoResourceFormat.TEXT_RULES || item.format == GeoResourceFormat.UNKNOWN)
            ) {
                val res = extractCached(item, blockGeoIpTags, maxPerDirection)
                blockCidrs.addAll(res.cidrs)
                blockDomains.addAll(res.domains)
                matchedTags.addAll(res.matchedTags)
            }
            if (blockGeoSiteTags.isNotEmpty() &&
                (item.format == GeoResourceFormat.V2RAY_GEOSITE || item.format == GeoResourceFormat.TEXT_RULES || item.format == GeoResourceFormat.UNKNOWN)
            ) {
                val res = extractCached(item, blockGeoSiteTags, maxPerDirection)
                blockCidrs.addAll(res.cidrs)
                blockDomains.addAll(res.domains)
                matchedTags.addAll(res.matchedTags)
            }
        }

        val allRequested = directGeoIpTags + directGeoSiteTags + blockGeoIpTags + blockGeoSiteTags
        val unresolved = allRequested - matchedTags
        val dCidrs = directCidrs.take(maxPerDirection)
        val dDomains = directDomains.take((maxPerDirection - dCidrs.size).coerceAtLeast(0))
        val bCidrs = blockCidrs.take(maxPerDirection)
        val bDomains = blockDomains.take((maxPerDirection - bCidrs.size).coerceAtLeast(0))

        GeoDatParser.CompiledProfileGeoRules(
            directRules = dCidrs + dDomains,
            directCidrs = dCidrs,
            directDomains = dDomains,
            blockRules = bCidrs + bDomains,
            blockCidrs = bCidrs,
            blockDomains = bDomains,
            unresolvedTags = unresolved
        )
    }

    private suspend fun markStatus(id: String, status: GeoResourceStatus, error: String?) {
        indexMutex.withLock {
            val updated = _resources.value.map {
                if (it.id == id) it.copy(status = status, lastError = error) else it
            }
            writeIndexToDisk(updated)
            _resources.value = updated
        }
    }

    private fun downloadAndValidateToTemp(url: String, partFile: File): Result<Pair<Long, String>> {
        val client = SharedHttpClient.forTimeout(connectMs = 15_000L, readMs = 45_000L, writeMs = 15_000L)
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "NovaVPN-GeoUpdater/1.0")
            .build()

        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return Result.failure(
                        GeoValidationException.NetworkFailure("HTTP ${resp.code} while downloading resource")
                    )
                }
                val contentType = resp.header("Content-Type")?.lowercase(Locale.US).orEmpty()
                if (contentType.contains("text/html")) {
                    return Result.failure(
                        GeoValidationException.InvalidDatFile("Server returned HTML webpage instead of binary .dat file")
                    )
                }
                val body = resp.body
                    ?: return Result.failure(GeoValidationException.NetworkFailure("Empty response body"))

                val expectedLen = body.contentLength()
                val res = body.byteStream().use { input ->
                    streamAndValidateToTemp(input, partFile)
                }
                if (res.isFailure) return res
                val (written, sha) = res.getOrThrow()
                if (expectedLen > 0L && written != expectedLen) {
                    return Result.failure(
                        GeoValidationException.InvalidDatFile(
                            "Incomplete download: expected $expectedLen bytes, received $written bytes"
                        )
                    )
                }
                Result.success(written to sha)
            }
        } catch (e: IOException) {
            Result.failure(GeoValidationException.NetworkFailure(e.message ?: "Network error"))
        }
    }

    private fun streamAndValidateToTemp(input: InputStream, partFile: File): Result<Pair<Long, String>> {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(16 * 1024)
        var totalBytes = 0L
        var headerPrefix = ""

        partFile.parentFile?.mkdirs()
        partFile.outputStream().buffered(16 * 1024).use { out ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                if (totalBytes == 0L) {
                    val sampleLen = minOf(read, 128)
                    headerPrefix = String(buffer, 0, sampleLen, Charsets.UTF_8)
                        .trimStart()
                        .lowercase(Locale.US)
                    if (headerPrefix.startsWith("<!doctype html") ||
                        headerPrefix.startsWith("<html") ||
                        headerPrefix.startsWith("<?xml")
                    ) {
                        return Result.failure(
                            GeoValidationException.InvalidDatFile("File appears to be HTML/XML instead of a valid .dat binary")
                        )
                    }
                }
                totalBytes += read
                if (totalBytes > MAX_RESOURCE_BYTES) {
                    return Result.failure(
                        GeoValidationException.InvalidDatFile("Resource exceeds maximum supported size (150 MB)")
                    )
                }
                digest.update(buffer, 0, read)
                out.write(buffer, 0, read)
            }
            out.flush()
        }

        if (totalBytes <= 0L) {
            return Result.failure(GeoValidationException.InvalidDatFile("Resource file is empty (0 bytes)"))
        }
        val shaHex = digest.digest().joinToString("") { "%02x".format(it) }
        return Result.success(totalBytes to shaHex)
    }

    private fun atomicReplaceFile(partFile: File, targetFile: File) {
        val backupFile = File(targetFile.parentFile, "${targetFile.name}.bak")
        if (targetFile.exists()) {
            backupFile.delete()
            if (!targetFile.renameTo(backupFile)) {
                partFile.copyTo(targetFile, overwrite = true)
                partFile.delete()
                return
            }
        }
        if (partFile.renameTo(targetFile)) {
            backupFile.delete()
        } else {
            // Rollback backup if rename fails
            if (!targetFile.exists() && backupFile.exists()) {
                backupFile.renameTo(targetFile)
            }
            throw IOException("Failed to finalize atomic write for ${targetFile.name}")
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        }.getOrNull()
    }

    private fun readIndexFromDisk(): List<GeoResourceItem> {
        if (!indexFile.isFile) return emptyList()
        return runCatching {
            json.decodeFromString<List<GeoResourceItem>>(indexFile.readText(Charsets.UTF_8))
        }.getOrDefault(emptyList())
    }

    private fun writeIndexToDisk(items: List<GeoResourceItem>) {
        val tmp = File(storageDir, "index.json.tmp")
        tmp.writeText(json.encodeToString(items), Charsets.UTF_8)
        if (!tmp.renameTo(indexFile)) {
            tmp.copyTo(indexFile, overwrite = true)
            tmp.delete()
        }
    }

    private fun tryAcquireInFlight(key: String): Boolean = synchronized(inFlightLock) {
        if (key in inFlightKeys) false else {
            inFlightKeys.add(key)
            true
        }
    }

    private fun releaseInFlight(key: String) {
        synchronized(inFlightLock) {
            inFlightKeys.remove(key)
        }
    }

    companion object {
        private const val MAX_RESOURCE_BYTES = 150L * 1024L * 1024L

        fun validateUrl(raw: String): Result<String> {
            val trimmed = raw.trim()
            if (trimmed.isBlank()) {
                return Result.failure(GeoValidationException.InvalidUrl("URL cannot be empty"))
            }
            val parsed = runCatching { URI(trimmed) }.getOrNull()
                ?: return Result.failure(GeoValidationException.InvalidUrl("Malformed URL format"))
            val scheme = parsed.scheme?.lowercase(Locale.US)
            if (scheme != "http" && scheme != "https") {
                return Result.failure(GeoValidationException.InvalidUrl("URL must start with http:// or https://"))
            }
            val host = parsed.host
            if (host.isNullOrBlank()) {
                return Result.failure(GeoValidationException.InvalidUrl("URL is missing a valid host"))
            }
            return Result.success(trimmed)
        }

        fun isValidDatFileName(name: String): Boolean {
            return name.trim().lowercase(Locale.US).endsWith(".dat")
        }

        fun deriveDatNameFromUrl(url: String, customName: String? = null): String {
            val chosen = customName?.trim().takeUnless { it.isNullOrEmpty() }
                ?: runCatching {
                    URI(url).path?.substringAfterLast('/')?.substringBefore('?')
                }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                ?: "resource_${System.currentTimeMillis()}.dat"
            val sanitized = sanitizeDisplayName(chosen)
            return if (sanitized.lowercase(Locale.US).endsWith(".dat")) sanitized else "$sanitized.dat"
        }

        private fun sanitizeDisplayName(name: String): String {
            return name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "resource.dat" }
        }

        private fun safeDiskName(name: String): String {
            return name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(64).ifBlank { "resource.dat" }
        }
    }
}
