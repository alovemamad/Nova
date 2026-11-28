package app.novavpn.core.net

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import app.novavpn.core.DevicePlatform
import app.novavpn.core.runCatchingCancellable
import app.novavpn.data.log.LogRepository
import app.novavpn.domain.model.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Locale

data class ReleaseApkAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val sha256: String? = null
)

data class ApkValidationResult(
    val valid: Boolean,
    val versionCode: Long? = null,
    val errorMessage: String? = null
)

object GitHubUpdater {
    const val REPO_OWNER = "alovemamad"
    const val REPO_NAME = "Nova"
    const val REPO_HTML_URL = "https://github.com/$REPO_OWNER/$REPO_NAME"
    const val LATEST_RELEASE_API_URL = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"

    private val json = Json { ignoreUnknownKeys = true }

    private fun httpClient(readTimeoutSec: Long = 20L): OkHttpClient =
        SharedHttpClient.forTimeout(
            connectMs = 15_000L,
            readMs = readTimeoutSec * 1_000L,
            writeMs = 15_000L,
            followRedirects = true
        )

    suspend fun checkLatestRelease(
        currentVersion: String,
        supportedAbis: List<String> = Build.SUPPORTED_ABIS.toList(),
        isTv: Boolean
    ): UpdateInfo = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val client = httpClient()
            val req = Request.Builder()
                .url(LATEST_RELEASE_API_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "NovaVPN/$currentVersion")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.code == 404) {
                    val listReq = Request.Builder()
                        .url("https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases?per_page=1")
                        .header("Accept", "application/vnd.github+json")
                        .header("User-Agent", "NovaVPN/$currentVersion")
                        .build()
                    client.newCall(listReq).execute().use { listResp ->
                        if (listResp.isSuccessful) {
                            val arrBody = listResp.body?.string().orEmpty()
                            val arr = runCatching { json.parseToJsonElement(arrBody).jsonArray }.getOrNull()
                            val firstObj = arr?.firstOrNull() as? JsonObject
                            if (firstObj != null) {
                                return@withContext parseLatestReleaseJson(
                                    firstObj.toString(),
                                    currentVersion,
                                    supportedAbis,
                                    isTv = isTv
                                )
                            }
                        }
                    }
                    return@withContext UpdateInfo(
                        checking = false,
                        error = "No published GitHub release found yet ($REPO_OWNER/$REPO_NAME)."
                    )
                }
                if (!resp.isSuccessful) {
                    return@withContext UpdateInfo(
                        checking = false,
                        error = "GitHub returned HTTP ${resp.code}"
                    )
                }
                val body = resp.body?.string() ?: error("Empty response from GitHub")
                parseLatestReleaseJson(body, currentVersion, supportedAbis, isTv = isTv)
            }
        }.getOrElse { err ->
            UpdateInfo(checking = false, error = err.message ?: "Update check failed")
        }
    }

    internal fun parseLatestReleaseJson(
        body: String,
        currentVersion: String,
        supportedAbis: List<String> = listOf("arm64-v8a", "armeabi-v7a", "x86_64"),
        isTv: Boolean = false
    ): UpdateInfo {
        val obj = json.parseToJsonElement(body).jsonObject
        val tag = obj["tag_name"]?.jsonPrimitive?.content.orEmpty()
        val htmlUrl = obj["html_url"]?.jsonPrimitive?.content ?: REPO_HTML_URL
        val notes = obj["body"]?.jsonPrimitive?.content.orEmpty()
        val cleanTag = tag.trim().removePrefix("v").removePrefix("V")
        val cleanCurrent = currentVersion.trim().removePrefix("v").removePrefix("V")
        val available = cleanTag.isNotBlank() && compareSemVer(cleanTag, cleanCurrent) > 0

        val assetsArr = obj["assets"] as? JsonArray
        val apks = parseApkAssets(assetsArr)
        val matchedAsset = selectBestApkAsset(apks, supportedAbis, isTv = isTv)
        val expectedSha = matchedAsset?.sha256 ?: extractSha256FromReleaseNotes(notes, matchedAsset?.name)

        return UpdateInfo(
            checking = false,
            latestTag = tag.ifBlank { null },
            releaseUrl = htmlUrl,
            releaseNotes = notes.take(800).ifBlank { null },
            updateAvailable = available,
            apkDownloadUrl = matchedAsset?.downloadUrl,
            apkAssetName = matchedAsset?.name,
            apkSizeBytes = matchedAsset?.sizeBytes,
            expectedSha256 = expectedSha
        )
    }

    private fun parseApkAssets(assetsArr: JsonArray?): List<ReleaseApkAsset> {
        if (assetsArr == null) return emptyList()
        return assetsArr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            if (!name.endsWith(".apk", ignoreCase = true)) return@mapNotNull null
            val url = obj["browser_download_url"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val size = obj["size"]?.jsonPrimitive?.longOrNull ?: 0L
            val rawDigest = obj["digest"]?.jsonPrimitive?.content
            val sha256 = rawDigest
                ?.substringAfter("sha256:", "")
                ?.trim()
                ?.lowercase(Locale.US)
                ?.takeIf { it.length == 64 && it.all { ch -> ch in '0'..'9' || ch in 'a'..'f' } }
            ReleaseApkAsset(name = name, downloadUrl = url, sizeBytes = size, sha256 = sha256)
        }
    }

    internal fun extractSha256FromReleaseNotes(notes: String, assetName: String?): String? {
        if (notes.isBlank()) return null
        val shaRegex = Regex("([0-9a-fA-F]{64})")
        if (!assetName.isNullOrBlank()) {
            notes.lines().firstOrNull { line ->
                line.contains(assetName, ignoreCase = true) && shaRegex.containsMatchIn(line)
            }?.let { line ->
                return shaRegex.find(line)?.groupValues?.get(1)?.lowercase(Locale.US)
            }
        }
        return null
    }

    internal fun selectBestApkAsset(
        assets: List<ReleaseApkAsset>,
        supportedAbis: List<String>,
        isTv: Boolean = false
    ): ReleaseApkAsset? {
        val filtered = assets.filter { asset ->
            if (!asset.name.endsWith(".apk", ignoreCase = true)) return@filter false
            val isTvAsset = asset.name.contains("-TV-", ignoreCase = true)
            if (isTv) isTvAsset else !isTvAsset
        }
        if (filtered.isEmpty()) return null

        if (isTv) {
            // TV APK filenames intentionally avoid standard ABI substrings ("arm64-v8a", "armeabi-v7a",
            // "x86_64", "universal") so older phone updaters (v1.0.9-v1.0.11) can never match a TV APK.
            val tvAbiTokens = mapOf(
                "arm64-v8a" to listOf("a64"),
                "armeabi-v7a" to listOf("a32"),
                "x86_64" to listOf("intel")
            )
            for (abi in supportedAbis) {
                val normalizedAbi = abi.lowercase(Locale.US)
                val tokens = tvAbiTokens[normalizedAbi] ?: continue
                val found = filtered.firstOrNull { asset ->
                    val lower = asset.name.lowercase(Locale.US)
                    tokens.any { tok -> Regex("(^|[-_.])$tok([-_.]|$)").containsMatchIn(lower) }
                }
                if (found != null) return found
            }
            return filtered.firstOrNull { asset ->
                val lower = asset.name.lowercase(Locale.US)
                Regex("(^|[-_.])all([-_.]|$)").containsMatchIn(lower)
            } ?: filtered.firstOrNull { asset ->
                val lower = asset.name.lowercase(Locale.US)
                !hasExplicitAbiTag(lower) && !hasExplicitTvAbiTag(lower)
            }
        }

        val abiPatterns = mapOf(
            "arm64-v8a" to listOf("arm64-v8a", "arm64", "aarch64"),
            "armeabi-v7a" to listOf("armeabi-v7a", "armeabi", "armv7"),
            "x86_64" to listOf("x86_64", "x64", "amd64"),
            "x86" to listOf("x86")
        )
        for (abi in supportedAbis) {
            val normalizedAbi = abi.lowercase(Locale.US)
            val aliases = abiPatterns[normalizedAbi] ?: listOf(normalizedAbi)
            val found = filtered.firstOrNull { asset ->
                val lower = asset.name.lowercase(Locale.US)
                if (normalizedAbi == "x86") {
                    Regex("(^|[-_])x86(?!_?64)([-_.]|$)").containsMatchIn(lower)
                } else {
                    aliases.any { alias -> lower.contains(alias) }
                }
            }
            if (found != null) return found
        }

        // Universal or ABI-neutral APK fallback — never pick an explicitly incompatible ABI APK
        return filtered.firstOrNull { it.name.contains("universal", ignoreCase = true) }
            ?: filtered.firstOrNull { asset ->
                val lower = asset.name.lowercase(Locale.US)
                !hasExplicitAbiTag(lower)
            }
    }

    private fun hasExplicitTvAbiTag(lowerName: String): Boolean {
        return Regex("(^|[-_.])(a64|a32|intel)([-_.]|$)").containsMatchIn(lowerName)
    }

    private fun hasExplicitAbiTag(lowerName: String): Boolean {
        return lowerName.contains("arm64") ||
            lowerName.contains("aarch64") ||
            lowerName.contains("armeabi") ||
            lowerName.contains("armv7") ||
            lowerName.contains("x86_64") ||
            lowerName.contains("amd64") ||
            Regex("(^|[-_])x(86|64)([-_.]|$)").containsMatchIn(lowerName)
    }

    internal fun compareSemVer(a: String, b: String): Int {
        val coreA = a.substringBefore('-').substringBefore('+')
        val coreB = b.substringBefore('-').substringBefore('+')
        val pa = coreA.split('.').map { it.toIntOrNull() ?: 0 }
        val pb = coreB.split('.').map { it.toIntOrNull() ?: 0 }
        val max = maxOf(pa.size, pb.size)
        for (i in 0 until max) {
            val va = pa.getOrElse(i) { 0 }
            val vb = pb.getOrElse(i) { 0 }
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }

    suspend fun downloadApk(
        context: Context,
        downloadUrl: String,
        assetName: String,
        expectedSizeBytes: Long? = null,
        expectedSha256: String? = null,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            require(downloadUrl.startsWith("https://", ignoreCase = true)) {
                "Update download must use HTTPS"
            }
            val updatesDir = File(context.filesDir, "updates").apply { mkdirs() }
            updatesDir.listFiles()?.forEach { old ->
                if (old.isFile && (old.name.endsWith(".apk", ignoreCase = true) || old.name.endsWith(".part", ignoreCase = true))) {
                    runCatching { old.delete() }
                }
            }
            runCatching { File(context.cacheDir, "updates").deleteRecursively() }

            val safeName = assetName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                .ifBlank { "nova-update.apk" }
            val outFile = File(updatesDir, safeName)
            val tmpFile = File(updatesDir, "$safeName.part")

            val req = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "NovaVPN-Updater")
                .build()

            var expectedTotal = expectedSizeBytes?.takeIf { it > 0L } ?: -1L
            val shaDigest = MessageDigest.getInstance("SHA-256")
            httpClient(readTimeoutSec = 120L).newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    error("APK download failed (HTTP ${resp.code})")
                }
                val body = resp.body ?: error("Empty APK response")
                val headerLen = body.contentLength()
                if (headerLen > 0L) expectedTotal = headerLen

                body.byteStream().use { input ->
                    FileOutputStream(tmpFile).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var copied = 0L
                        var lastReported = -1f
                        var lastIndeterminateAt = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            shaDigest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                            copied += read
                            if (expectedTotal > 0L) {
                                val pct = (copied.toFloat() / expectedTotal.toFloat()).coerceIn(0f, 0.99f)
                                if (pct - lastReported >= 0.02f) {
                                    lastReported = pct
                                    onProgress(pct)
                                }
                            } else {
                                val now = System.currentTimeMillis()
                                if (now - lastIndeterminateAt >= 200L) {
                                    lastIndeterminateAt = now
                                    val synthetic = (copied / (copied + 12_000_000f)).coerceIn(0.05f, 0.92f)
                                    onProgress(synthetic)
                                }
                            }
                        }
                        output.flush()
                    }
                }
            }

            if (expectedTotal > 0L && tmpFile.length() != expectedTotal) {
                tmpFile.delete()
                error("Downloaded APK is incomplete (${tmpFile.length()} / $expectedTotal bytes)")
            }

            if (!expectedSha256.isNullOrBlank()) {
                val actualSha = shaDigest.digest().joinToString("") { "%02x".format(it) }
                if (!actualSha.equals(expectedSha256.trim(), ignoreCase = true)) {
                    tmpFile.delete()
                    error("Downloaded APK SHA-256 checksum mismatch")
                }
            }

            check(hasZipMagicHeader(tmpFile)) {
                tmpFile.delete()
                "Downloaded file is not a valid APK package"
            }

            val validation = validateDownloadedApk(context, tmpFile)
            if (!validation.valid) {
                tmpFile.delete()
                error(validation.errorMessage ?: "Downloaded APK failed package verification")
            }

            if (outFile.exists()) outFile.delete()
            check(tmpFile.renameTo(outFile)) { "Failed to finalize APK file" }
            onProgress(1f)
            LogRepository.i("Updater", "Verified update APK '${outFile.name}' (versionCode=${validation.versionCode})")
            outFile
        }
    }

    internal fun hasZipMagicHeader(file: File): Boolean {
        if (!file.isFile || file.length() < 64L) return false
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val b0 = raf.read()
                val b1 = raf.read()
                b0 == 0x50 && b1 == 0x4B
            }
        }.getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    fun validateDownloadedApk(context: Context, apkFile: File): ApkValidationResult {
        if (!apkFile.isFile || !hasZipMagicHeader(apkFile)) {
            return ApkValidationResult(valid = false, errorMessage = "Invalid or corrupted APK file")
        }
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }

        val archiveInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
            ?: return ApkValidationResult(valid = false, errorMessage = "Unable to parse APK manifest")

        if (archiveInfo.packageName != context.packageName) {
            return ApkValidationResult(
                valid = false,
                errorMessage = "Package ID mismatch (${archiveInfo.packageName} != ${context.packageName})"
            )
        }

        val apkVersionCode = PackageInfoCompat.getLongVersionCode(archiveInfo)
        val installedInfo = runCatching { pm.getPackageInfo(context.packageName, flags) }.getOrNull()
        if (installedInfo != null) {
            val installedVersionCode = PackageInfoCompat.getLongVersionCode(installedInfo)
            if (apkVersionCode < installedVersionCode) {
                return ApkValidationResult(
                    valid = false,
                    versionCode = apkVersionCode,
                    errorMessage = "APK versionCode ($apkVersionCode) is lower than installed ($installedVersionCode)"
                )
            }
            val installedSigs = extractSigningSha256(installedInfo)
            val apkSigs = extractSigningSha256(archiveInfo)
            if (installedSigs.isNotEmpty() && apkSigs.isNotEmpty() && installedSigs.intersect(apkSigs).isEmpty()) {
                return ApkValidationResult(
                    valid = false,
                    versionCode = apkVersionCode,
                    errorMessage = "APK signature mismatch with installed app; cannot upgrade in-place"
                )
            }
        }
        return ApkValidationResult(valid = true, versionCode = apkVersionCode)
    }

    @Suppress("DEPRECATION")
    private fun extractSigningSha256(info: PackageInfo): Set<String> {
        val rawSigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo
            if (signingInfo == null) null
            else if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners
            else signingInfo.signingCertificateHistory
        } else {
            info.signatures
        } ?: return emptySet()

        val md = MessageDigest.getInstance("SHA-256")
        return rawSigs.map { sig ->
            md.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun launchInstallOrRequestPermission(context: Context, apkFile: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !canInstallPackages(context)) {
            val permIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            check(DevicePlatform.safeStartActivity(context, permIntent)) {
                "Install-unknown-apps settings screen is unavailable on this device."
            }
            return false
        }

        val validation = validateDownloadedApk(context, apkFile)
        check(validation.valid) { validation.errorMessage ?: "Invalid update APK" }

        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        check(DevicePlatform.safeStartActivity(context, installIntent)) {
            "Package installer is unavailable on this device."
        }
        return true
    }
}
