package app.novavpn.core

import kotlinx.coroutines.CancellationException
import java.security.SecureRandom

/**
 * Like Kotlin's [runCatching], but never swallows coroutine [CancellationException].
 */
inline fun <R> runCatchingCancellable(block: () -> R): Result<R> {
    return try {
        Result.success(block())
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        Result.failure(t)
    }
}

/**
 * Ephemeral per-session credentials protecting the local SOCKS5/HTTP listeners
 * from unauthorized apps or LAN devices. Stored strictly in memory.
 */
data class SessionProxyAuth(
    val username: String,
    val password: String
) {
    companion object {
        private const val ALPHANUM = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        private val random = SecureRandom()

        fun generate(): SessionProxyAuth {
            val userSuffix = (1..8).map { ALPHANUM[random.nextInt(ALPHANUM.length)] }.joinToString("")
            val pass = (1..24).map { ALPHANUM[random.nextInt(ALPHANUM.length)] }.joinToString("")
            return SessionProxyAuth(username = "nova_$userSuffix", password = pass)
        }
    }
}
