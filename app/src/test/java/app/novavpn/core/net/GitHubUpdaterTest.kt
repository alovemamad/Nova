package app.novavpn.core.net

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubUpdaterTest {

    @Test
    fun `compareSemVer compares major minor patch accurately`() {
        assertTrue(GitHubUpdater.compareSemVer("1.0.2", "1.0.1") > 0)
        assertTrue(GitHubUpdater.compareSemVer("1.2.0", "1.1.9") > 0)
        assertTrue(GitHubUpdater.compareSemVer("2.0.0", "1.99.99") > 0)
        assertEquals(0, GitHubUpdater.compareSemVer("1.0.1", "1.0.1"))
        assertEquals(0, GitHubUpdater.compareSemVer("1.0.1-beta", "1.0.1"))
        assertTrue(GitHubUpdater.compareSemVer("1.0.0", "1.0.1") < 0)
    }

    @Test
    fun `parseLatestReleaseJson detects update and selects matching ABI apk from alovemamad Nova`() {
        val payload = """
            {
              "tag_name": "v1.2.0",
              "html_url": "https://github.com/alovemamad/Nova/releases/tag/v1.2.0",
              "body": "Liquid Glass theme & HTTP/2 default",
              "assets": [
                {
                  "name": "NovaVPN-armeabi-v7a-release.apk",
                  "browser_download_url": "https://github.com/alovemamad/Nova/releases/download/v1.2.0/NovaVPN-armeabi-v7a-release.apk",
                  "size": 24000000
                },
                {
                  "name": "NovaVPN-arm64-v8a-release.apk",
                  "browser_download_url": "https://github.com/alovemamad/Nova/releases/download/v1.2.0/NovaVPN-arm64-v8a-release.apk",
                  "size": 26500000
                },
                {
                  "name": "checksums.txt",
                  "browser_download_url": "https://github.com/alovemamad/Nova/releases/download/v1.2.0/checksums.txt",
                  "size": 512
                }
              ]
            }
        """.trimIndent()

        val info = GitHubUpdater.parseLatestReleaseJson(
            body = payload,
            currentVersion = "1.0.1",
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
            isTv = false
        )
        assertTrue(info.updateAvailable)
        assertEquals("v1.2.0", info.latestTag)
        assertEquals("https://github.com/alovemamad/Nova/releases/tag/v1.2.0", info.releaseUrl)
        assertEquals("NovaVPN-arm64-v8a-release.apk", info.apkAssetName)
        assertEquals(26500000L, info.apkSizeBytes)
        assertNotNull(info.apkDownloadUrl)

        val upToDate = GitHubUpdater.parseLatestReleaseJson(
            body = payload,
            currentVersion = "1.2.0",
            supportedAbis = listOf("arm64-v8a"),
            isTv = false
        )
        assertFalse(upToDate.updateAvailable)
    }

    @Test
    fun `selectBestApkAsset falls back to universal or rejects incompatible explicit ABI apks`() {
        val assets = listOf(
            ReleaseApkAsset("Nova-x86_64.apk", "https://example.com/x86_64.apk", 10L),
            ReleaseApkAsset("Nova-universal-release.apk", "https://example.com/uni.apk", 20L)
        )
        val chosen = GitHubUpdater.selectBestApkAsset(assets, listOf("arm64-v8a"), isTv = false)
        assertEquals("Nova-universal-release.apk", chosen?.name)
        val x86Chosen = GitHubUpdater.selectBestApkAsset(assets, listOf("x86"), isTv = false)
        assertEquals("Nova-universal-release.apk", x86Chosen?.name)
        assertNull(GitHubUpdater.selectBestApkAsset(emptyList(), listOf("arm64-v8a"), isTv = false))

        val mismatchedOnly = listOf(
            ReleaseApkAsset("Nova-armeabi-v7a-release.apk", "https://example.com/v7a.apk", 10L)
        )
        assertNull(GitHubUpdater.selectBestApkAsset(mismatchedOnly, listOf("arm64-v8a"), isTv = false))
    }

    @Test
    fun `hasZipMagicHeader rejects non-APK HTML or tiny files`() {
        val dir = createTempDirectory("apk_header_test").toFile()
        try {
            val htmlFile = File(dir, "fake.apk").apply {
                writeBytes(("<html>404 Not Found</html>".repeat(5)).toByteArray())
            }
            assertFalse(GitHubUpdater.hasZipMagicHeader(htmlFile))

            val zipFile = File(dir, "valid.apk").apply {
                val bytes = ByteArray(128)
                bytes[0] = 0x50 // 'P'
                bytes[1] = 0x4B // 'K'
                writeBytes(bytes)
            }
            assertTrue(GitHubUpdater.hasZipMagicHeader(zipFile))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `parseLatestReleaseJson extracts asset digest sha256 and falls back to release notes checksum`() {
        val digestHex = "a".repeat(64)
        val notesHex = "b".repeat(64)
        val withDigest = """
            {
              "tag_name": "v2.0.0",
              "html_url": "https://github.com/alovemamad/Nova/releases/tag/v2.0.0",
              "body": "Release notes",
              "assets": [
                {
                  "name": "NovaVPN-arm64-v8a-release.apk",
                  "browser_download_url": "https://example.com/a.apk",
                  "size": 12345,
                  "digest": "sha256:$digestHex"
                }
              ]
            }
        """.trimIndent()
        val parsedDigest = GitHubUpdater.parseLatestReleaseJson(withDigest, "1.0.1", listOf("arm64-v8a"), isTv = false)
        assertEquals(digestHex, parsedDigest.expectedSha256)

        val withNotes = """
            {
              "tag_name": "v2.0.0",
              "html_url": "https://github.com/alovemamad/Nova/releases/tag/v2.0.0",
              "body": "NovaVPN-arm64-v8a-release.apk $notesHex",
              "assets": [
                {
                  "name": "NovaVPN-arm64-v8a-release.apk",
                  "browser_download_url": "https://example.com/a.apk",
                  "size": 12345
                }
              ]
            }
        """.trimIndent()
        val parsedNotes = GitHubUpdater.parseLatestReleaseJson(withNotes, "1.0.1", listOf("arm64-v8a"), isTv = false)
        assertEquals(notesHex, parsedNotes.expectedSha256)
    }

    @Test
    fun `selectBestApkAsset strictly separates phone and TV APKs in combined release`() {
        val mixedAssets = listOf(
            ReleaseApkAsset("SHA256SUMS.txt", "https://example.com/SHA256SUMS.txt", 256L),
            ReleaseApkAsset("NovaVPN-v2.1.0-arm64-v8a.apk", "https://example.com/phone-arm64.apk", 26_000_000L),
            ReleaseApkAsset("NovaVPN-v2.1.0-armeabi-v7a.apk", "https://example.com/phone-v7a.apk", 24_000_000L),
            ReleaseApkAsset("NovaVPN-v2.1.0-x86_64.apk", "https://example.com/phone-x86_64.apk", 25_000_000L),
            ReleaseApkAsset("NovaVPN-v2.1.0-universal.apk", "https://example.com/phone-uni.apk", 48_000_000L),
            ReleaseApkAsset("NovaVPN-TV-v2.1.0-arm64-v8a.apk", "https://example.com/tv-arm64.apk", 26_100_000L),
            ReleaseApkAsset("NovaVPN-TV-v2.1.0-armeabi-v7a.apk", "https://example.com/tv-v7a.apk", 24_100_000L),
            ReleaseApkAsset("NovaVPN-TV-v2.1.0-x86_64.apk", "https://example.com/tv-x86_64.apk", 25_100_000L),
            ReleaseApkAsset("NovaVPN-TV-v2.1.0-universal.apk", "https://example.com/tv-uni.apk", 48_100_000L)
        )

        // Phone build (isTv = false) picks only phone APKs
        assertEquals("NovaVPN-v2.1.0-arm64-v8a.apk", GitHubUpdater.selectBestApkAsset(mixedAssets, listOf("arm64-v8a"), isTv = false)?.name)
        assertEquals("NovaVPN-v2.1.0-armeabi-v7a.apk", GitHubUpdater.selectBestApkAsset(mixedAssets, listOf("armeabi-v7a"), isTv = false)?.name)
        assertEquals("NovaVPN-v2.1.0-x86_64.apk", GitHubUpdater.selectBestApkAsset(mixedAssets, listOf("x86_64"), isTv = false)?.name)
        assertEquals("NovaVPN-v2.1.0-universal.apk", GitHubUpdater.selectBestApkAsset(mixedAssets, listOf("riscv64"), isTv = false)?.name)

        // TV build (isTv = true) picks only TV APKs
        assertEquals("NovaVPN-TV-v2.1.0-arm64-v8a.apk", GitHubUpdater.selectBestApkAsset(mixedAssets, listOf("arm64-v8a"), isTv = true)?.name)
        assertEquals("NovaVPN-TV-v2.1.0-armeabi-v7a.apk", GitHubUpdater.selectBestApkAsset(mixedAssets, listOf("armeabi-v7a"), isTv = true)?.name)
        assertEquals("NovaVPN-TV-v2.1.0-x86_64.apk", GitHubUpdater.selectBestApkAsset(mixedAssets, listOf("x86_64"), isTv = true)?.name)
        assertEquals("NovaVPN-TV-v2.1.0-universal.apk", GitHubUpdater.selectBestApkAsset(mixedAssets, listOf("riscv64"), isTv = true)?.name)
    }

    @Test
    fun `tv build returns null when release has no TV assets and never selects SHA256SUMS`() {
        val phoneOnlyJson = """
            {
              "tag_name": "v2.2.0",
              "html_url": "https://github.com/alovemamad/Nova/releases/tag/v2.2.0",
              "body": "Phone release only",
              "assets": [
                {
                  "name": "SHA256SUMS.txt",
                  "browser_download_url": "https://example.com/SHA256SUMS.txt",
                  "size": 512
                },
                {
                  "name": "NovaVPN-v2.2.0-arm64-v8a.apk",
                  "browser_download_url": "https://example.com/phone-arm64.apk",
                  "size": 26500000
                },
                {
                  "name": "NovaVPN-v2.2.0-universal.apk",
                  "browser_download_url": "https://example.com/phone-uni.apk",
                  "size": 48000000
                }
              ]
            }
        """.trimIndent()

        val tvInfo = GitHubUpdater.parseLatestReleaseJson(
            body = phoneOnlyJson,
            currentVersion = "1.0.0",
            supportedAbis = listOf("arm64-v8a"),
            isTv = true
        )
        assertTrue(tvInfo.updateAvailable)
        assertNull(tvInfo.apkDownloadUrl)
        assertNull(tvInfo.apkAssetName)

        // Direct call with TV asset named with SHA256SUMS.txt suffix is still rejected
        val textAssetsOnly = listOf(
            ReleaseApkAsset("NovaVPN-TV-v2.2.0-arm64-v8a-SHA256SUMS.txt", "https://example.com/sums.txt", 128L),
            ReleaseApkAsset("SHA256SUMS.txt", "https://example.com/SHA256SUMS.txt", 128L)
        )
        assertNull(GitHubUpdater.selectBestApkAsset(textAssetsOnly, listOf("arm64-v8a"), isTv = true))
        assertNull(GitHubUpdater.selectBestApkAsset(textAssetsOnly, listOf("arm64-v8a"), isTv = false))
    }
}
