package app.novavpn.core.net

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
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a")
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
            supportedAbis = listOf("arm64-v8a")
        )
        assertFalse(upToDate.updateAvailable)
    }

    @Test
    fun `selectBestApkAsset falls back to universal or first apk when ABI unmatched`() {
        val assets = listOf(
            ReleaseApkAsset("Nova-x86_64.apk", "https://example.com/x86_64.apk", 10L),
            ReleaseApkAsset("Nova-universal-release.apk", "https://example.com/uni.apk", 20L)
        )
        val chosen = GitHubUpdater.selectBestApkAsset(assets, listOf("arm64-v8a"))
        assertEquals("Nova-universal-release.apk", chosen?.name)
        assertNull(GitHubUpdater.selectBestApkAsset(emptyList(), listOf("arm64-v8a")))
    }
}
