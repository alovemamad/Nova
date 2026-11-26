package app.novavpn.core.net

import android.os.Build
import app.novavpn.core.runCatchingCancellable
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
import java.util.Locale

data class ReleaseApkAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val sha256: String? = null
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
        supportedAbis: List<String> = Build.SUPPORTED_ABIS.toList()
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
                                    supportedAbis
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
                parseLatestReleaseJson(body, currentVersion, supportedAbis)
            }
        }.getOrElse { err ->
            UpdateInfo(checking = false, error = err.message ?: "Update check failed")
        }
    }

    internal fun parseLatestReleaseJson(
        body: String,
        currentVersion: String,
        supportedAbis: List<String> = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
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
        val matchedAsset = selectBestApkAsset(apks, supportedAbis)
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
        supportedAbis: List<String>
    ): ReleaseApkAsset? {
        if (assets.isEmpty()) return null
        val abiPatterns = mapOf(
            "arm64-v8a" to listOf("arm64-v8a", "arm64", "aarch64"),
            "armeabi-v7a" to listOf("armeabi-v7a", "armeabi", "armv7"),
            "x86_64" to listOf("x86_64", "x64", "amd64"),
            "x86" to listOf("x86")
        )
        for (abi in supportedAbis) {
            val normalizedAbi = abi.lowercase(Locale.US)
            val aliases = abiPatterns[normalizedAbi] ?: listOf(normalizedAbi)
            val found = assets.firstOrNull { asset ->
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
        return assets.firstOrNull { it.name.contains("universal", ignoreCase = true) }
            ?: assets.firstOrNull { asset ->
                val lower = asset.name.lowercase(Locale.US)
                !hasExplicitAbiTag(lower)
            }
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
}
