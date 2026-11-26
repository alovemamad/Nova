package app.novavpn.core.net

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.system.measureTimeMillis

class DiagnosticsEngineTest {

    @Test
    fun `Socks5RemoteDns preserves domain hostname for SOCKS5 ATYP 0x03 without local DNS`() {
        val resolved = Socks5RemoteDns.lookup("cloudflare-dns.com")
        assertEquals(1, resolved.size)
        val socketAddr = InetSocketAddress(resolved.first(), 443)
        assertEquals("cloudflare-dns.com", socketAddr.hostString)
        assertEquals(443, socketAddr.port)
    }

    @Test
    fun `Socks5RemoteDns passes IP literals through cleanly`() {
        val resolved = Socks5RemoteDns.lookup("1.1.1.1")
        assertEquals(1, resolved.size)
        assertEquals("1.1.1.1", resolved.first().hostAddress)
    }

    @Test
    fun `verifyRealHttpsConnectivity races probe URLs concurrently without waiting on slow sockets`() = runBlocking {
        val slowServer = ServerSocket(0)
        val fastServer = ServerSocket(0)
        val slowThread = thread(isDaemon = true) {
            runCatching {
                slowServer.accept().use {
                    Thread.sleep(6_000L)
                }
            }
        }
        val fastThread = thread(isDaemon = true) {
            runCatching {
                fastServer.accept().use { s ->
                    val reader = s.getInputStream().bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    s.getOutputStream().write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    s.getOutputStream().flush()
                }
            }
        }

        try {
            val engine = DiagnosticsEngine()
            var res: Result<Int>? = null
            val elapsedMs = measureTimeMillis {
                res = engine.verifyRealHttpsConnectivity(
                    socksPort = null,
                    auth = null,
                    timeoutMs = 5_000L,
                    probeUrls = listOf(
                        "http://127.0.0.1:${slowServer.localPort}/slow",
                        "http://127.0.0.1:${fastServer.localPort}/fast"
                    )
                )
            }
            assertTrue("Expected fast probe to succeed, got $res", res?.isSuccess == true)
            assertTrue("Expected concurrent race to finish < 1500ms, took ${elapsedMs}ms", elapsedMs < 1_500L)
        } finally {
            runCatching { slowServer.close() }
            runCatching { fastServer.close() }
            slowThread.interrupt()
            fastThread.interrupt()
        }
    }
}
