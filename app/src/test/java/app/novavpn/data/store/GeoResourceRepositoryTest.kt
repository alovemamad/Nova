package app.novavpn.data.store

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.novavpn.domain.model.GeoResourceFormat
import app.novavpn.domain.model.GeoResourceStatus
import app.novavpn.domain.model.GeoSourceType
import app.novavpn.domain.routing.GeoDatParserTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
class GeoResourceRepositoryTest {

    private lateinit var tempDir: File
    private lateinit var repo: GeoResourceRepository

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        File(ctx.filesDir, "geo_resources").deleteRecursively()
        tempDir = createTempDirectory("geo_repo_test").toFile()
        repo = GeoResourceRepository(ctx)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `validateUrl enforces http or https and valid host`() {
        assertTrue(GeoResourceRepository.validateUrl("").isFailure)
        assertTrue(GeoResourceRepository.validateUrl("ftp://example.com/geosite.dat").isFailure)
        assertTrue(GeoResourceRepository.validateUrl("https://").isFailure)
        assertTrue(GeoResourceRepository.validateUrl("https://cdn.example.com/geosite.dat").isSuccess)
    }

    @Test
    fun `isValidDatFileName accepts dot dat case insensitively and rejects others`() {
        assertTrue(GeoResourceRepository.isValidDatFileName("geosite.dat"))
        assertTrue(GeoResourceRepository.isValidDatFileName("GEOIP.DAT"))
        assertFalse(GeoResourceRepository.isValidDatFileName("geosite.txt"))
        assertFalse(GeoResourceRepository.isValidDatFileName("no_extension"))
    }

    @Test
    fun `importFromUri imports valid dat file and rejects invalid HTML or non-dat file`() = runBlocking {
        val validDat = File(tempDir, "iran.dat").apply {
            writeBytes(GeoDatParserTest.buildGeoIpList("IR" to listOf("185.143.232.0" to 22)))
        }
        val okRes = repo.importFromUri(Uri.fromFile(validDat))
        assertTrue(okRes.isSuccess)
        val item = okRes.getOrThrow()
        assertEquals("iran.dat", item.name)
        assertEquals(GeoSourceType.LOCAL_FILE, item.sourceType)
        assertEquals(GeoResourceStatus.READY, item.status)
        assertEquals(GeoResourceFormat.V2RAY_GEOIP, item.format)
        assertEquals(1, item.entryCount)
        assertTrue(repo.resolveResourceFile(item).exists())

        val wrongExt = File(tempDir, "bad.txt").apply { writeText("hello") }
        assertTrue(repo.importFromUri(Uri.fromFile(wrongExt)).isFailure)

        val htmlSpoof = File(tempDir, "spoof.dat").apply { writeText("<!DOCTYPE html><html>err</html>") }
        assertTrue(repo.importFromUri(Uri.fromFile(htmlSpoof)).isFailure)
    }

    @Test
    fun `failed batch update preserves previous healthy dat file intact`() = runBlocking {
        val healthyPayload = GeoDatParserTest.buildGeoSiteList(
            "CATEGORY-ADS-ALL" to listOf(2 to "ads.example.com")
        )
        val requestCount = AtomicInteger(0)

        val server = ServerSocket(0)
        val serverThread = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                val reqNo = requestCount.incrementAndGet()
                runCatching {
                    socket.use { s ->
                        val reader = s.getInputStream().bufferedReader()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        val out = s.getOutputStream()
                        if (reqNo == 1) {
                            val headers = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: ${healthyPayload.size}\r\nConnection: close\r\n\r\n"
                            out.write(headers.toByteArray())
                            out.write(healthyPayload)
                        } else {
                            val errBody = "<!DOCTYPE html><html>502 Bad Gateway</html>".toByteArray()
                            val headers = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${errBody.size}\r\nConnection: close\r\n\r\n"
                            out.write(headers.toByteArray())
                            out.write(errBody)
                        }
                        out.flush()
                    }
                }
            }
        }

        try {
            val url = "http://127.0.0.1:${server.localPort}/geosite.dat"
            val added = repo.addFromUrl(url)
            assertTrue(added.isSuccess)
            val created = added.getOrThrow()
            val storedFile = repo.resolveResourceFile(created)
            assertArrayEquals(healthyPayload, storedFile.readBytes())

            // Trigger second fetch returning invalid HTML -> should mark ERROR yet keep original healthy bytes!
            val summary = repo.updateAllUrlResources()
            assertEquals(1, summary.totalUrlResources)
            assertEquals(0, summary.updatedCount)
            assertEquals(1, summary.failedCount)

            val afterUpdate = repo.resources.value.firstOrNull { it.id == created.id }
            assertNotNull(afterUpdate)
            assertEquals(GeoResourceStatus.ERROR, afterUpdate!!.status)
            assertNotNull(afterUpdate.lastError)
            assertArrayEquals(healthyPayload, storedFile.readBytes())

            // Deleting item removes both index entry and stored .dat file
            repo.deleteResource(created.id)
            assertTrue(repo.resources.value.isEmpty())
            assertFalse(storedFile.exists())
        } finally {
            server.close()
            serverThread.interrupt()
        }
    }
}
