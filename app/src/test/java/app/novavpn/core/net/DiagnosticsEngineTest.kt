package app.novavpn.core.net

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetSocketAddress

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
}
