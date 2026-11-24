package app.novavpn.core.net

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
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
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class ReleaseApkAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long
)

object GitHubUpdater {
    const val REPO_OWNER = "alovemamad"
    const val REPO_NAME = "Nova"
    const val REPO_HTML_URL = "https://github.com/$REPO_OWNER/$REPO_NAME"
    const val LATEST_RELEASE_API_URL = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"

    private val json = Json { ignoreUnknownKeys = true }

    private fun httpClient(readTimeoutSec: Long = 20L): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

    suspend fun checkLatestRelease(
        currentVersion: String,
        supportedAbis: List<String> = Build.SUPPORTED_ABIS.toList()
    ): UpdateInfo = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val req = Request.Builder()
                .url(LATEST_RELEASE_API_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "NovaVPN/$currentVersion")
                .build()
            httpClient().newCall(req).execute().use { resp ->
                if (resp.code == 404) {
                    val listReq = Request.Builder()
                        .url("https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases?per_page=1")
                        .header("Accept", "application/vnd.github+json")
                        .header("User-Agent", "NovaVPN/$currentVersion")
                        .build()
                    httpClient().newCall(listReq).execute().use { listResp ->
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
                        error = "No published GitHub release found yet (alovemamad/Nova)."
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

        return UpdateInfo(
            checking = false,
            latestTag = tag.ifBlank { null },
            releaseUrl = htmlUrl,
            releaseNotes = notes.take(800).ifBlank { null },
            updateAvailable = available,
            apkDownloadUrl = matchedAsset?.downloadUrl,
            apkAssetName = matchedAsset?.name,
            apkSizeBytes = matchedAsset?.sizeBytes
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
            ReleaseApkAsset(name = name, downloadUrl = url, sizeBytes = size)
        }
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
            val normalizedAbi = abi.lowercase()
            val aliases = abiPatterns[normalizedAbi] ?: listOf(normalizedAbi)
            val found = assets.firstOrNull { asset ->
                val lower = asset.name.lowercase()
                if (normalizedAbi == "x86") {
                    Regex("(^|[-_])x86(?!_?64)([-_.]|$)").containsMatchIn(lower)
                } else {
                    aliases.any { alias -> lower.contains(alias) }
                }
            }
            if (found != null) return found
        }
        return assets.firstOrNull { it.name.contains("universal", ignoreCase = true) }
            ?: assets.firstOrNull { it.name.contains("release", ignoreCase = true) }
            ?: assets.firstOrNull()
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
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            updatesDir.listFiles()?.forEach { old ->
                if (old.isFile && old.name.endsWith(".apk", ignoreCase = true)) {
                    runCatching { old.delete() }
                }
            }
            val safeName = assetName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                .ifBlank { "nova-update.apk" }
            val outFile = File(updatesDir, safeName)
            val tmpFile = File(updatesDir, "$safeName.part")

            val req = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "NovaVPN-Updater")
                .build()

            httpClient(readTimeoutSec = 120L).newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    error("APK download failed (HTTP ${resp.code})")
                }
                val body = resp.body ?: error("Empty APK response")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    FileOutputStream(tmpFile).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var copied = 0L
                        var lastReported = -1f
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            if (total > 0L) {
                                val pct = (copied.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                                if (pct - lastReported >= 0.02f || pct >= 1f) {
                                    lastReported = pct
                                    onProgress(pct)
                                }
                            }
                        }
                        output.flush()
                    }
                }
            }
            if (tmpFile.exists()) {
                if (outFile.exists()) outFile.delete()
                check(tmpFile.renameTo(outFile)) { "Failed to finalize APK file" }
            }
            onProgress(1f)
            outFile
        }
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
            runCatching { context.startActivity(permIntent) }
            return false
        }

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
        context.startActivity(installIntent)
        return true
    }
}
