package app.novavpn.data.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockCryptoTest {

    @Test
    fun `pbkdf2 uses 120k iterations and unique salts and verifies accurately`() {
        val s1 = AppLockCrypto.generateSaltHex()
        val s2 = AppLockCrypto.generateSaltHex()
        assertNotEquals(s1, s2)
        assertEquals(32, s1.length) // 16 bytes -> 32 hex chars

        val h1 = AppLockCrypto.hashPinPbkdf2("123456", s1)
        val h2 = AppLockCrypto.hashPinPbkdf2("123456", s2)
        assertTrue(h1.startsWith("pbkdf2:120000:"))
        assertNotEquals(h1, h2)
        assertTrue(AppLockCrypto.verifyPbkdf2Record("123456", h1))
        assertFalse(AppLockCrypto.verifyPbkdf2Record("654321", h1))
    }

    @Test
    fun `seals and verifies PIN blob round-trip and verifies legacy SHA256 hashes`() {
        val blob = AppLockCrypto.sealWithKeystore("987654")
        assertTrue(AppLockCrypto.verifySealedBlob("987654", blob))
        assertFalse(AppLockCrypto.verifySealedBlob("111111", blob))

        val legacy = AppLockCrypto.hashLegacySha256("246810")
        assertTrue(AppLockCrypto.verifyLegacySha256("246810", legacy))
        assertFalse(AppLockCrypto.verifyLegacySha256("000000", legacy))
    }

    @Test
    fun `computes exponential lockout starting after 5 failures and capped at 900s`() {
        val now = 1_000_000L
        // Failures 1..4 -> no lockout
        var attempts = 0
        repeat(4) {
            val (next, until) = AppLockCrypto.computeNextLockout(attempts, now)
            attempts = next
            assertEquals(0L, until)
        }
        // Failure 5 -> 30s
        val (a5, u5) = AppLockCrypto.computeNextLockout(attempts, now)
        assertEquals(5, a5)
        assertEquals(now + 30_000L, u5)

        // Failure 6 -> 60s
        val (a6, u6) = AppLockCrypto.computeNextLockout(a5, now)
        assertEquals(6, a6)
        assertEquals(now + 60_000L, u6)

        // Failure 10+ -> capped at 900s (15 min)
        val (_, uMax) = AppLockCrypto.computeNextLockout(12, now)
        assertEquals(now + 900_000L, uMax)
    }
}
