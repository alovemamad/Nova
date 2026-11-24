package app.novavpn.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import kotlin.math.min

/**
 * Hardened App-Lock PIN cryptography & lockout state:
 * - Uses PBKDF2WithHmacSHA256 (>= 120,000 iterations) with a 16-byte per-PIN SecureRandom salt.
 * - Uses pure-Kotlin hex encoding so it runs on Android API 24+ and headless JVM unit tests alike.
 * - Encrypts the salt+hash record inside an Android Keystore AES-256-GCM key when available.
 * - Supports transparent migration from legacy SHA-256 hashes.
 * - Enforces exponential lockout after 5 consecutive failed attempts.
 */
object AppLockCrypto {
    const val PBKDF2_ITERATIONS = 120_000
    const val MAX_FREE_ATTEMPTS = 5
    private const val SALT_BYTES = 16
    private const val HASH_BITS = 256
    private const val KEYSTORE_ALIAS = "nova_vpn_pin_aes_key"
    private const val GCM_TAG_BITS = 128

    private val random = SecureRandom()

    fun generateSaltHex(): String {
        val salt = ByteArray(SALT_BYTES)
        random.nextBytes(salt)
        return toHex(salt)
    }

    fun hashPinPbkdf2(pin: String, saltHex: String, iterations: Int = PBKDF2_ITERATIONS): String {
        val salt = fromHex(saltHex) ?: error("Invalid salt")
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, HASH_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val derived = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return "pbkdf2:$iterations:$saltHex:${toHex(derived)}"
    }

    fun verifyPbkdf2Record(pin: String, record: String): Boolean {
        val parts = record.split(':')
        if (parts.size != 4 || parts[0] != "pbkdf2") return false
        val iterations = parts[1].toIntOrNull() ?: return false
        val saltHex = parts[2]
        val expectedHex = parts[3]
        val candidate = runCatching {
            hashPinPbkdf2(pin, saltHex, iterations).substringAfterLast(':')
        }.getOrNull() ?: return false
        return MessageDigest.isEqual(
            candidate.toByteArray(Charsets.UTF_8),
            expectedHex.toByteArray(Charsets.UTF_8)
        )
    }

    fun hashLegacySha256(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("novavpn:$pin".toByteArray())
        return toHex(digest)
    }

    fun verifyLegacySha256(pin: String, legacyHash: String): Boolean {
        if (legacyHash.isBlank()) return false
        val candidate = hashLegacySha256(pin)
        return MessageDigest.isEqual(
            candidate.toByteArray(Charsets.UTF_8),
            legacyHash.toByteArray(Charsets.UTF_8)
        )
    }

    fun sealWithKeystore(pin: String): String {
        val record = hashPinPbkdf2(pin, generateSaltHex(), PBKDF2_ITERATIONS)
        return runCatching {
            val key = getOrCreateAesKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ct = cipher.doFinal(record.toByteArray(Charsets.UTF_8))
            "ks:${toHex(iv)}:${toHex(ct)}"
        }.getOrElse {
            // Fallback for headless JVM tests where AndroidKeyStore provider is unavailable
            record
        }
    }

    fun verifySealedBlob(pin: String, blob: String): Boolean {
        if (blob.isBlank()) return false
        val record = if (blob.startsWith("ks:")) {
            val parts = blob.split(':')
            if (parts.size != 3) return false
            val iv = fromHex(parts[1]) ?: return false
            val ct = fromHex(parts[2]) ?: return false
            runCatching {
                val key = getOrCreateAesKey()
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
                String(cipher.doFinal(ct), Charsets.UTF_8)
            }.getOrNull() ?: return false
        } else {
            blob
        }
        return verifyPbkdf2Record(pin, record)
    }

    fun computeNextLockout(failedAttempts: Int, nowMs: Long): Pair<Int, Long> {
        val nextAttempts = failedAttempts + 1
        if (nextAttempts < MAX_FREE_ATTEMPTS) {
            return nextAttempts to 0L
        }
        val over = (nextAttempts - MAX_FREE_ATTEMPTS).coerceAtMost(5)
        val lockoutSec = min(30L * (1L shl over), 900L) // 30s, 60s, 120s ... max 15m
        return nextAttempts to (nowMs + lockoutSec * 1000L)
    }

    private fun getOrCreateAesKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun toHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun fromHex(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        return runCatching {
            ByteArray(hex.length / 2) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }.getOrNull()
    }
}
