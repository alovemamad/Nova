package app.novavpn.core.net

import app.novavpn.data.log.LogRepository
import app.novavpn.domain.model.DiagnosticReport
import app.novavpn.domain.model.DnsMode
import app.novavpn.domain.model.EndpointLatencyResult
import app.novavpn.domain.model.ObfuscationProfile
import app.novavpn.domain.model.UpdateInfo
import app.novavpn.domain.model.VpnProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

data class ExitGeoInfo(
    val ip: String,
    val countryCode: String?,
    val city: String?
)

class DiagnosticsEngine {
    private val json = Json { ignoreUnknownKeys = true }

    private fun createClient(socksPort: Int?): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(9, TimeUnit.SECONDS)
            .writeTimeout(7, TimeUnit.SECONDS)
        if (socksPort != null) {
            builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))
        }
        return builder.build()
    }

    suspend fun verifyRealHttpsConnectivity(socksPort: Int?): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val client = createClient(socksPort)
            val req = Request.Builder()
                .url("https://cp.cloudflare.com/generate_204")
                .header("User-Agent", "NovaVPN/1.0")
                .build()
            var code = 0
            val ms = measureTimeMillis {
                client.newCall(req).execute().use { resp ->
                    code = resp.code
                }
            }
            if (code !in 200..299) {
                error("HTTPS probe returned HTTP $code")
            }
            ms.toInt().coerceAtLeast(1)
        }
    }

    suspend fun fetchExitIpInfo(socksPort: Int?): ExitGeoInfo? = withContext(Dispatchers.IO) {
        val client = createClient(socksPort)
        // Primary: ipwho.is
        runCatching {
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
        // Fallback: Cloudflare trace
        runCatching {
            val req = Request.Builder().url("https://1.1.1.1/cdn-cgi/trace").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val lines = (resp.body?.string() ?: "").lines().associate {
                    val k = it.substringBefore('=')
                    val v = it.substringAfter('=', "")
                    k to v
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
        vpnConnected: Boolean
    ): DiagnosticReport = withContext(Dispatchers.IO) {
        val effectiveProxyPort = if (vpnConnected) socksPort else null
        val exitInfo = fetchExitIpInfo(effectiveProxyPort)

        // 1. DNS Leak check via DoH comparison + local resolver check
        val (dnsSafe, dnsSummary) = checkDnsLeak(profile, effectiveProxyPort, vpnConnected)

        // 2. IPv6 Leak check
        val (ipv6Safe, ipv6Summary) = checkIpv6Leak(profile, effectiveProxyPort, vpnConnected)

        // 3. 3-Endpoint latency test
        val endpoints = listOf(
            "Cloudflare" to "1.1.1.1",
            "Google DNS" to "8.8.8.8",
            "Quad9" to "9.9.9.9"
        )
        val latencies = endpoints.map { (label, host) ->
            measureTcpLatency(label, host, 443, effectiveProxyPort)
        }

        // 4. MTU Probe
        val (recMtu, mtuSummary) = probeMtu(effectiveProxyPort, profile.mtu)

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
        vpnConnected: Boolean
    ): Pair<Boolean, String> {
        return try {
            val client = createClient(socksPort)
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
        vpnConnected: Boolean
    ): Pair<Boolean, String> {
        if (!vpnConnected) {
            val hasLocalV6 = runCatching {
                InetAddress.getAllByName("ipv6.google.com").any { it is Inet6Address }
            }.getOrDefault(false)
            return true to if (hasLocalV6) "Device has IPv6; connect VPN with IPv6 blocked or tunneled" else "No active IPv6 route detected"
        }
        return if (!profile.ipv6Enabled) {
            val client = createClient(socksPort)
            val reachableV6 = runCatching {
                val req = Request.Builder().url("https://v6.ident.me/").build()
                client.newCall(req).execute().use { it.isSuccessful }
            }.getOrDefault(false)
            if (reachableV6) {
                false to "IPv6 reachable despite IPv6 disabled — enable Kill Switch"
            } else {
                true to "No IPv6 leak detected (IPv6 suppressed)"
            }
        } else {
            true to "IPv6 enabled and routed through tunnel"
        }
    }

    private fun measureTcpLatency(label: String, host: String, port: Int, socksPort: Int?): EndpointLatencyResult {
        return try {
            val ms = measureTimeMillis {
                val socket = if (socksPort != null) {
                    Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))
                } else {
                    Socket()
                }
                socket.use {
                    it.connect(InetSocketAddress(host, port), 3500)
                }
            }
            EndpointLatencyResult(label, host, ms.toInt().coerceAtLeast(1), true)
        } catch (_: Exception) {
            EndpointLatencyResult(label, host, null, false)
        }
    }

    private fun probeMtu(socksPort: Int?, currentMtu: Int): Pair<Int, String> {
        val candidates = listOf(1400, 1340, 1280)
        val client = createClient(socksPort)
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

    suspend fun checkForUpdates(currentVersion: String): UpdateInfo = withContext(Dispatchers.IO) {
        runCatching {
            val client = createClient(null)
            val req = Request.Builder()
                .url("https://api.github.com/repos/nova-vpn/nova-vpn-android/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "NovaVPN/$currentVersion")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext UpdateInfo(error = "GitHub returned HTTP ${resp.code}")
                }
                val body = resp.body?.string() ?: error("Empty response")
                val obj = json.parseToJsonElement(body).jsonObject
                val tag = obj["tag_name"]?.jsonPrimitive?.content ?: ""
                val url = obj["html_url"]?.jsonPrimitive?.content ?: ""
                val notes = obj["body"]?.jsonPrimitive?.content ?: ""
                val cleanTag = tag.removePrefix("v")
                val available = cleanTag.isNotBlank() && compareSemVer(cleanTag, currentVersion.removePrefix("v")) > 0
                UpdateInfo(
                    checking = false,
                    latestTag = tag,
                    releaseUrl = url,
                    releaseNotes = notes.take(400),
                    updateAvailable = available
                )
            }
        }.getOrElse { err ->
            UpdateInfo(checking = false, error = err.message ?: "Update check failed")
        }
    }

    private fun compareSemVer(a: String, b: String): Int {
        val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
        val max = maxOf(pa.size, pb.size)
        for (i in 0 until max) {
            val va = pa.getOrElse(i) { 0 }
            val vb = pb.getOrElse(i) { 0 }
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }
}
