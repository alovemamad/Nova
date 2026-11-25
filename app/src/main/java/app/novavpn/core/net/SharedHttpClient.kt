package app.novavpn.core.net

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * Process-wide shared OkHttpClient pool.
 *
 * Creating standalone `OkHttpClient()` instances per probe or download leaks
 * ConnectionPool cleanup threads and Dispatcher executors (especially during 15s ping probes).
 * Deriving via `base.newBuilder()` shares the single ConnectionPool & Dispatcher safely.
 */
object SharedHttpClient {
    val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(8, 2, TimeUnit.MINUTES))
            .dispatcher(
                Dispatcher().apply {
                    maxRequests = 32
                    maxRequestsPerHost = 8
                }
            )
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    fun forTimeout(
        connectMs: Long = 12_000L,
        readMs: Long = 15_000L,
        writeMs: Long = 15_000L,
        followRedirects: Boolean = true
    ): OkHttpClient {
        return base.newBuilder()
            .connectTimeout(connectMs.coerceIn(2_000L, 60_000L), TimeUnit.MILLISECONDS)
            .readTimeout(readMs.coerceIn(2_000L, 120_000L), TimeUnit.MILLISECONDS)
            .writeTimeout(writeMs.coerceIn(2_000L, 60_000L), TimeUnit.MILLISECONDS)
            .followRedirects(followRedirects)
            .followSslRedirects(followRedirects)
            .build()
    }

    fun forProxy(
        dns: Dns,
        socketFactory: SocketFactory,
        timeoutMs: Long = 9_000L
    ): OkHttpClient {
        val clamped = timeoutMs.coerceIn(3_000L, 45_000L)
        return base.newBuilder()
            .dns(dns)
            .socketFactory(socketFactory)
            .connectTimeout(clamped, TimeUnit.MILLISECONDS)
            .readTimeout(clamped, TimeUnit.MILLISECONDS)
            .writeTimeout(clamped, TimeUnit.MILLISECONDS)
            .build()
    }
}
