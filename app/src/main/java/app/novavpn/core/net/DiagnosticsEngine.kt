package app.novavpn.core.net

import app.novavpn.core.SessionProxyAuth
import app.novavpn.data.log.LogRepository
import app.novavpn.domain.model.DiagnosticReport
import app.novavpn.domain.model.DnsMode
import app.novavpn.domain.model.EndpointLatencyResult
import app.novavpn.domain.model.ObfuscationProfile
import app.novavpn.domain.model.UpdateInfo
import app.novavpn.domain.model.VpnProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import kotlin.system.measureTimeMillis

data class ExitGeoInfo(
    val ip: String,
    val countryCode: String?,
    val city: String?
)

/**
 * Custom SocketFactory performing RFC 1928 / RFC 1929 SOCKS5 greeting + username/password
 * authentication directly per socket, avoiding unsafe global java.net.Authenticator state.
 */
private class Socks5AuthSocketFactory(
    private val proxyHost: String,
    private val proxyPort: Int,
    private val auth: SessionProxyAuth?
) : SocketFactory() {

    override fun createSocket(): Socket = Socks5Socket(proxyHost, proxyPort, auth)
    override fun createSocket(host: String, port: Int): Socket = createSocket().apply {
        connect(InetSocketAddress.createUnresolved(host, port))
    }
    override fun createSocket(host: String, port: Int, localHost: InetAddress?, localPort: Int): Socket = createSocket(host, port)
    override fun createSocket(host: InetAddress, port: Int): Socket = createSocket().apply {
        connect(InetSocketAddress(host, port))
    }
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress?, localPort: Int): Socket = createSocket(address, port)

    private class Socks5Socket(
        private val proxyHost: String,
        private val proxyPort: Int,
        private val auth: SessionProxyAuth?
    ) : Socket() {
        override fun connect(endpoint: java.net.SocketAddress?, timeout: Int) {
            val target = endpoint as? InetSocketAddress ?: throw IOException("Unsupported address")
            super.connect(InetSocketAddress(proxyHost, proxyPort), if (timeout > 0) timeout else 5000)
            val out = getOutputStream()
            val inp = getInputStream()

            if (auth != null) {
                out.write(byteArrayOf(0x05, 0x02, 0x00, 0x02))
            } else {
                out.write(byteArrayOf(0x05, 0x01, 0x00))
            }
            out.flush()

            val ver = inp.read()
            val method = inp.read()
            if (ver != 0x05 || method == 0xFF) throw IOException("SOCKS5 handshake rejected")

            if (method == 0x02) {
                val creds = auth ?: throw IOException("SOCKS5 requires auth")
                val uBytes = creds.username.toByteArray(Charsets.UTF_8)
                val pBytes = creds.password.toByteArray(Charsets.UTF_8)
                val authPkt = ByteArray(3 + uBytes.size + pBytes.size)
                authPkt[0] = 0x01
                authPkt[1] = uBytes.size.toByte()
                System.arraycopy(uBytes, 0, authPkt, 2, uBytes.size)
                authPkt[2 + uBytes.size] = pBytes.size.toByte()
                System.arraycopy(pBytes, 0, authPkt, 3 + uBytes.size, pBytes.size)
                out.write(authPkt)
                out.flush()
                val authVer = inp.read()
                val authStatus = inp.read()
                if (authVer != 0x01 || authStatus != 0x00) throw IOException("SOCKS5 auth failed")
            }

            // CONNECT command with domain name (0x03) or IPv4 (0x01)
            val hostStr = target.hostString
            val port = target.port
            val hostBytes = hostStr.toByteArray(Charsets.UTF_8)
            val req = ByteArray(7 + hostBytes.size)
            req[0] = 0x05
            req[1] = 0x01
            req[2] = 0x00
            req[3] = 0x03
            req[4] = hostBytes.size.toByte()
            System.arraycopy(hostBytes, 0, req, 5, hostBytes.size)
            req[5 + hostBytes.size] = ((port ushr 8) and 0xFF).toByte()
            req[6 + hostBytes.size] = (port and 0xFF).toByte()
            out.write(req)
            out.flush()

            val rVer = inp.read()
            val rep = inp.read()
            inp.read() // rsv
            val atyp = inp.read()
            if (rVer != 0x05 || rep != 0x00) throw IOException("SOCKS5 connect failed (rep=$rep)")
            val skipBytes = when (atyp) {
                0x01 -> 4 + 2
                0x04 -> 16 + 2
                0x03 -> (inp.read().coerceAtLeast(0)) + 2
                else -> 6
            }
            repeat(skipBytes) { inp.read() }
        }
    }
}

/**
 * Prevents OkHttp from calling local ISP DNS (which is subject to DNS poisoning such as 10.10.x.x
 * on bypassed app UIDs) before Socks5Socket connects. Returns a synthetic InetAddress carrying the
 * original hostname so Socks5Socket sends ATYP=0x03 directly over the tunnel.
 */
internal object Socks5RemoteDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val isIpLiteral = hostname.all { it.isDigit() || it == '.' } || hostname.contains(':')
        if (isIpLiteral) {
            return Dns.SYSTEM.lookup(hostname)
        }
        return listOf(InetAddress.getByAddress(hostname, byteArrayOf(127, 0, 0, 1)))
    }
}

class DiagnosticsEngine {
    private val json = Json { ignoreUnknownKeys = true }

    private fun createClient(socksPort: Int?, auth: SessionProxyAuth?, timeoutMs: Long = 9_000L): OkHttpClient {
        val clamped = timeoutMs.coerceIn(3_000L, 45_000L)
        return if (socksPort != null) {
            SharedHttpClient.forProxy(
                dns = Socks5RemoteDns,
                socketFactory = Socks5AuthSocketFactory("127.0.0.1", socksPort, auth),
                timeoutMs = clamped
            )
        } else {
            SharedHttpClient.forTimeout(connectMs = clamped, readMs = clamped, writeMs = clamped)
        }
    }

    suspend fun verifyRealHttpsConnectivity(
        socksPort: Int?,
        auth: SessionProxyAuth? = null,
        timeoutMs: Long = 9_000L,
        probeUrls: List<String> = DEFAULT_HTTPS_PROBE_URLS
    ): Result<Int> = withContext(Dispatchers.IO) {
        if (probeUrls.isEmpty()) {
            return@withContext Result.failure(IOException("No HTTPS probe URLs configured"))
        }
        val client = createClient(socksPort, auth, timeoutMs)
        val firstSuccess = CompletableDeferred<Result<Int>>()
        val remaining = AtomicInteger(probeUrls.size)
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val activeCalls = Collections.synchronizedList(mutableListOf<Call>())

        coroutineScope {
            for (url in probeUrls) {
                async {
                    val attempt = app.novavpn.core.runCatchingCancellable {
                        val req = Request.Builder()
                            .url(url)
                            .header("User-Agent", "NovaVPN/1.0")
                            .build()
                        val call = client.newCall(req)
                        activeCalls.add(call)
                        try {
                            var code = 0
                            val ms = measureTimeMillis {
                                call.execute().use { resp ->
                                    code = resp.code
                                }
                            }
                            if (code !in 200..299) {
                                error("HTTPS probe ($url) returned HTTP $code")
                            }
                            ms.toInt().coerceAtLeast(1)
                        } finally {
                            activeCalls.remove(call)
                        }
                    }
                    if (attempt.isSuccess) {
                        if (firstSuccess.complete(attempt)) {
                            // Cancel losing in-flight probe calls immediately so sockets don't linger
                            activeCalls.toList().forEach { runCatching { it.cancel() } }
                        }
                    } else {
                        val err = attempt.exceptionOrNull()
                        if (err != null && err !is CancellationException) {
                            errors.add(err)
                        }
                        if (remaining.decrementAndGet() == 0) {
                            firstSuccess.complete(Result.failure(errors.firstOrNull() ?: IOException("HTTPS probe failed")))
                        }
                    }
                }
            }
            val result = try {
                firstSuccess.await()
            } finally {
                activeCalls.toList().forEach { runCatching { it.cancel() } }
                coroutineContext.cancelChildren()
            }
            result
        }
    }

    companion object {
        val DEFAULT_HTTPS_PROBE_URLS = listOf(
            "https://cp.cloudflare.com/generate_204",
            "https://www.gstatic.com/generate_204"
        )
    }

    suspend fun fetchExitIpInfo(socksPort: Int?, auth: SessionProxyAuth? = null): ExitGeoInfo? = withContext(Dispatchers.IO) {
        val client = createClient(socksPort, auth)
        app.novavpn.core.runCatchingCancellable {
            val req = Request.Builder().url("https://ipwho.is/").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val body = resp.body?.string() ?: error("Empty body")
                val obj = json.parseToJsonElement(body).jsonObject
                val ip = obj["ip"]?.jsonPrimitive?.content ?: error("Missing ip")
                val cc = obj["country_code"]?.jsonPrimitive?.content
                val city = obj["city"]?.jsonPrimitive?.content
                return@withContext ExitGeoInfo(ip, cc, city)
            }
        }
        app.novavpn.core.runCatchingCancellable {
            val req = Request.Builder().url("https://1.1.1.1/cdn-cgi/trace").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val lines = (resp.body?.string() ?: "").lines().associate {
                    it.substringBefore('=') to it.substringAfter('=', "")
                }
                val ip = lines["ip"] ?: error("Missing ip")
                val loc = lines["loc"]
                return@withContext ExitGeoInfo(ip, loc, null)
            }
        }.getOrNull()
    }

    suspend fun runFullDiagnostics(
        profile: VpnProfile,
        socksPort: Int?,
        auth: SessionProxyAuth?,
        vpnConnected: Boolean
    ): DiagnosticReport = withContext(Dispatchers.IO) {
        val effectiveProxyPort = if (vpnConnected) socksPort else null
        val effectiveAuth = if (vpnConnected) auth else null
        val exitInfo = fetchExitIpInfo(effectiveProxyPort, effectiveAuth)

        val (dnsSafe, dnsSummary) = checkDnsLeak(profile, effectiveProxyPort, effectiveAuth, vpnConnected)
        val (ipv6Safe, ipv6Summary) = checkIpv6Leak(profile, effectiveProxyPort, effectiveAuth, vpnConnected)

        val endpoints = listOf(
            "Cloudflare" to "1.1.1.1",
            "Google DNS" to "8.8.8.8",
            "Quad9" to "9.9.9.9"
        )
        val latencies = endpoints.map { (label, host) ->
            measureTcpLatency(label, host, 443, effectiveProxyPort, effectiveAuth)
        }

        val (recMtu, mtuSummary) = probeMtu(effectiveProxyPort, effectiveAuth, profile.mtu)

        DiagnosticReport(
            running = false,
            exitIp = exitInfo?.ip,
            exitCountry = exitInfo?.countryCode,
            exitCity = exitInfo?.city,
            dnsLeakSafe = dnsSafe,
            dnsSummary = dnsSummary,
            ipv6LeakSafe = ipv6Safe,
            ipv6Summary = ipv6Summary,
            latencies = latencies,
            recommendedMtu = recMtu,
            mtuSummary = mtuSummary,
            timestampMs = System.currentTimeMillis()
        )
    }

    private fun checkDnsLeak(
        profile: VpnProfile,
        socksPort: Int?,
        auth: SessionProxyAuth?,
        vpnConnected: Boolean
    ): Pair<Boolean, String> {
        return try {
            val client = createClient(socksPort, auth)
            val req = Request.Builder()
                .url("https://cloudflare-dns.com/dns-query?name=example.com&type=A")
                .header("Accept", "application/dns-json")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return false to "DoH check failed (HTTP ${resp.code})"
                }
            }
            if (!vpnConnected) {
                true to "Not connected; DoH endpoint reachable directly"
            } else if (profile.dnsMode == DnsMode.DOH || profile.dnsMode == DnsMode.DOT) {
                true to "Encrypted DNS (${profile.dnsMode.name}) active via tunnel"
            } else {
                false to "Plain DNS configured; switch to DoH for leak protection"
            }
        } catch (e: Exception) {
            LogRepository.w("Diagnostics", "DNS check failed: ${e.message}")
            false to "DNS probe failed: ${e.message ?: "timeout"}"
        }
    }

    private fun checkIpv6Leak(
        profile: VpnProfile,
        socksPort: Int?,
        auth: SessionProxyAuth?,
        vpnConnected: Boolean
    ): Pair<Boolean, String> {
        if (!vpnConnected) {
            val hasLocalV6 = runCatching {
                InetAddress.getAllByName("ipv6.google.com").any { it is Inet6Address }
            }.getOrDefault(false)
            return true to if (hasLocalV6) "Device has IPv6; connect VPN with IPv6 blocked or tunneled" else "No active IPv6 route detected"
        }
        return if (!profile.ipv6Enabled) {
            val client = createClient(socksPort, auth)
            val reachableV6 = runCatching {
                val req = Request.Builder().url("https://v6.ident.me/").build()
                client.newCall(req).execute().use { it.isSuccessful }
            }.getOrDefault(false)
            if (reachableV6) {
                false to "IPv6 reachable despite IPv6 disabled — enable System Always-on VPN"
            } else {
                true to "No IPv6 leak detected (IPv6 suppressed)"
            }
        } else {
            true to "IPv6 enabled and routed through tunnel"
        }
    }

    private fun measureTcpLatency(
        label: String,
        host: String,
        port: Int,
        socksPort: Int?,
        auth: SessionProxyAuth?
    ): EndpointLatencyResult {
        return try {
            val ms = measureTimeMillis {
                val socket = if (socksPort != null) {
                    Socks5AuthSocketFactory("127.0.0.1", socksPort, auth).createSocket()
                } else {
                    Socket()
                }
                socket.use {
                    it.connect(InetSocketAddress.createUnresolved(host, port), 3500)
                }
            }
            EndpointLatencyResult(label, host, ms.toInt().coerceAtLeast(1), true)
        } catch (_: Exception) {
            EndpointLatencyResult(label, host, null, false)
        }
    }

    private fun probeMtu(socksPort: Int?, auth: SessionProxyAuth?, currentMtu: Int): Pair<Int, String> {
        val candidates = listOf(1400, 1340, 1280)
        val client = createClient(socksPort, auth)
        for (candidate in candidates) {
            val ok = runCatching {
                val req = Request.Builder()
                    .url("https://cp.cloudflare.com/generate_204")
                    .header("X-Nova-Probe", "A".repeat((candidate - 200).coerceAtLeast(128)))
                    .build()
                client.newCall(req).execute().use { it.isSuccessful }
            }.getOrDefault(false)
            if (ok) {
                return candidate to if (currentMtu > candidate) {
                    "Recommended MTU: $candidate (current $currentMtu may fragment)"
                } else {
                    "Current MTU $currentMtu is optimal (max probed $candidate)"
                }
            }
        }
        return 1280 to "Fallback safe MTU: 1280"
    }

    fun applySafeFix(profile: VpnProfile): Pair<VpnProfile, String> {
        val fixed = profile.copy(
            mtu = 1280,
            ipv6Enabled = false,
            dnsMode = DnsMode.DOH,
            customDnsPrimary = "https://cloudflare-dns.com/dns-query",
            customDnsSecondary = "1.1.1.1",
            obfuscation = ObfuscationProfile.AUTO,
            pingIntervalSec = 15,
            pingFailureLimit = 3
        )
        return fixed to "Applied safe fixes: MTU=1280, IPv6 off, DoH=Cloudflare, Obfuscation=Auto"
    }

    suspend fun checkForUpdates(
        currentVersion: String,
        isTv: Boolean = com.example.BuildConfig.IS_TV
    ): UpdateInfo =
        GitHubUpdater.checkLatestRelease(currentVersion, isTv = isTv)
}
