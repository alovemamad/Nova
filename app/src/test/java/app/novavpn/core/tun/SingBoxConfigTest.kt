package app.novavpn.core.tun

import app.novavpn.core.SessionProxyAuth
import app.novavpn.domain.model.DnsMode
import app.novavpn.domain.model.VpnProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SingBoxConfigTest {

    private val auth = SessionProxyAuth("nova_user", "p@ss\"word\\test")

    @Test
    fun `generates modern sing-box 1_12 DoH config without deprecated fields and escapes credentials`() {
        val profile = VpnProfile(
            id = "p1",
            name = "DoH",
            dnsMode = DnsMode.DOH,
            customDnsPrimary = "https://dns.quad9.net:8443/dns-query",
            directRules = "domain:example.com\n192.168.0.0/16\n*.ir",
            blockRules = "bad\"site.com\nport:5353"
        )

        val raw = SingBoxTunEngine.buildSingBoxConfig("127.0.0.1", 24808, auth, profile)
        val root = Json.parseToJsonElement(raw).jsonObject

        // DNS server checks
        val dnsServers = root["dns"]!!.jsonObject["servers"]!!.jsonArray
        val remoteDns = dnsServers.first().jsonObject
        assertEquals("https", remoteDns["type"]!!.jsonPrimitive.content)
        assertEquals("dns.quad9.net", remoteDns["server"]!!.jsonPrimitive.content)
        assertEquals("8443", remoteDns["server_port"]!!.jsonPrimitive.content)
        assertEquals("/dns-query", remoteDns["path"]!!.jsonPrimitive.content)
        assertEquals("local", dnsServers[1].jsonObject["type"]!!.jsonPrimitive.content)

        // Inbound should not contain deprecated sniff flag
        val inbound = root["inbounds"]!!.jsonArray.first().jsonObject
        assertFalse(inbound.containsKey("sniff"))

        // Route rules use action sniff / hijack-dns / reject
        val routeRules = root["route"]!!.jsonObject["rules"]!!.jsonArray
        assertEquals("sniff", routeRules[0].jsonObject["action"]!!.jsonPrimitive.content)
        assertEquals("hijack-dns", routeRules[1].jsonObject["action"]!!.jsonPrimitive.content)
        assertTrue(routeRules.any { it.jsonObject["action"]?.jsonPrimitive?.content == "reject" })

        // Outbounds have NO deprecated dns-out or block outbounds, and proxy has escaped credentials
        val outbounds = root["outbounds"]!!.jsonArray.map { it.jsonObject }
        assertFalse(outbounds.any { it["type"]?.jsonPrimitive?.content in setOf("dns", "block") })
        val socksOutbound = outbounds.first { it["tag"]?.jsonPrimitive?.content == "proxy" }
        assertEquals("socks", socksOutbound["type"]!!.jsonPrimitive.content)
        assertEquals("nova_user", socksOutbound["username"]!!.jsonPrimitive.content)
        assertEquals("p@ss\"word\\test", socksOutbound["password"]!!.jsonPrimitive.content)
    }

    @Test
    fun `generates typed DoT and PLAIN DNS servers properly`() {
        val dotProfile = VpnProfile(
            id = "dot",
            name = "DoT",
            dnsMode = DnsMode.DOT,
            customDnsPrimary = "tls://one.one.one.one:853"
        )
        val dotJson = Json.parseToJsonElement(
            SingBoxTunEngine.buildSingBoxConfig("127.0.0.1", 24808, auth, dotProfile)
        ).jsonObject
        val dotServer = dotJson["dns"]!!.jsonObject["servers"]!!.jsonArray.first().jsonObject
        assertEquals("tls", dotServer["type"]!!.jsonPrimitive.content)
        assertEquals("one.one.one.one", dotServer["server"]!!.jsonPrimitive.content)
        assertEquals("853", dotServer["server_port"]!!.jsonPrimitive.content)

        val plainProfile = VpnProfile(
            id = "plain",
            name = "Plain",
            dnsMode = DnsMode.PLAIN,
            customDnsPrimary = "9.9.9.9:5353"
        )
        val plainJson = Json.parseToJsonElement(
            SingBoxTunEngine.buildSingBoxConfig("127.0.0.1", 24808, auth, plainProfile)
        ).jsonObject
        val plainServer = plainJson["dns"]!!.jsonObject["servers"]!!.jsonArray.first().jsonObject
        assertEquals("udp", plainServer["type"]!!.jsonPrimitive.content)
        assertEquals("9.9.9.9", plainServer["server"]!!.jsonPrimitive.content)
        assertEquals("5353", plainServer["server_port"]!!.jsonPrimitive.content)
    }

    @Test
    fun `omits socks5 credentials in sing-box JSON and hev YAML when auth is null`() {
        val profile = VpnProfile(id = "noauth", name = "NoAuth", mtu = 1280)

        val singJson = Json.parseToJsonElement(
            SingBoxTunEngine.buildSingBoxConfig("127.0.0.1", 24808, null, profile)
        ).jsonObject
        val proxyOutbound = singJson["outbounds"]!!.jsonArray
            .map { it.jsonObject }
            .first { it["tag"]?.jsonPrimitive?.content == "proxy" }
        assertFalse(proxyOutbound.containsKey("username"))
        assertFalse(proxyOutbound.containsKey("password"))

        val hevNoAuth = HevSocks5TunEngine.buildHevYaml("127.0.0.1", 24808, null, profile)
        assertTrue(hevNoAuth.contains("port: 24808"))
        assertTrue(hevNoAuth.contains("address: 127.0.0.1"))
        assertTrue(hevNoAuth.contains("address: 10.10.0.2"))
        assertTrue(hevNoAuth.contains("network: 100.64.0.0"))
        assertTrue(hevNoAuth.contains("netmask: 255.192.0.0"))
        assertTrue(hevNoAuth.contains("connect-timeout: 15000"))
        assertTrue(hevNoAuth.contains("tcp-read-write-timeout: 300000"))
        assertTrue(hevNoAuth.contains("udp-read-write-timeout: 60000"))
        assertFalse(hevNoAuth.contains("username:"))
        assertFalse(hevNoAuth.contains("password:"))

        val hevTor = HevSocks5TunEngine.buildHevYaml(
            "127.0.0.1",
            24808,
            null,
            profile.copy(routeChain = app.novavpn.domain.model.RouteChain.AETHER_TOR)
        )
        assertTrue(hevTor.contains("connect-timeout: 30000"))
        assertTrue(hevTor.contains("max-session-count: 1024"))

        val singTorJson = Json.parseToJsonElement(
            SingBoxTunEngine.buildSingBoxConfig(
                "127.0.0.1",
                24808,
                null,
                profile.copy(routeChain = app.novavpn.domain.model.RouteChain.TOR_ONLY)
            )
        ).jsonObject
        val torRules = singTorJson["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        assertTrue(torRules.any { it["network"]?.jsonPrimitive?.content == "udp" && it["port"]?.jsonPrimitive?.content == "443" && it["action"]?.jsonPrimitive?.content == "reject" })
        assertTrue(torRules.any { it["network"]?.jsonPrimitive?.content == "udp" && !it.containsKey("port") && it["action"]?.jsonPrimitive?.content == "reject" })

        val hevWithAuth = HevSocks5TunEngine.buildHevYaml("127.0.0.1", 24808, auth, profile)
        assertTrue(hevWithAuth.contains("username: 'nova_user'"))
        assertTrue(hevWithAuth.contains("password: 'p@ss\"word\\test'"))
    }
}
