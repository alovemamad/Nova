package app.novavpn.domain.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputValidatorsTest {

    @Test
    fun `validateEndpoint accepts ipv4, ipv6, and domain endpoints`() {
        assertTrue(InputValidators.validateEndpoint("", allowEmpty = true).isValid)
        assertTrue(InputValidators.validateEndpoint("162.159.192.1:2408").isValid)
        assertTrue(InputValidators.validateEndpoint("[2606:4700:d0::a29f:c001]:2408").isValid)
        assertTrue(InputValidators.validateEndpoint("engage.cloudflareclient.com:2408").isValid)
    }

    @Test
    fun `validateEndpoint rejects malformed ports and shell characters`() {
        assertFalse(InputValidators.validateEndpoint("1.1.1.1").isValid)
        assertFalse(InputValidators.validateEndpoint("1.1.1.1:70000").isValid)
        assertFalse(InputValidators.validateEndpoint("1.1.1.1:443;whoami").isValid)
        assertFalse(InputValidators.validateEndpoint("", allowEmpty = false).isValid)
    }

    @Test
    fun `validateCidrOrIp checks prefix ranges`() {
        assertTrue(InputValidators.validateCidrOrIp("10.0.0.0/8").isValid)
        assertTrue(InputValidators.validateCidrOrIp("1.1.1.1").isValid)
        assertTrue(InputValidators.validateCidrOrIp("2001:db8::/32").isValid)
        assertFalse(InputValidators.validateCidrOrIp("10.0.0.0/33").isValid)
        assertFalse(InputValidators.validateCidrOrIp("999.1.1.1/24").isValid)
    }

    @Test
    fun `validateTorBridgeLine validates obfs4, webtunnel, and plain relay endpoints`() {
        val validObfs4 = "obfs4 192.0.2.1:443 AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA cert=abc123== iat-mode=0"
        val validWebtunnel = "Bridge webtunnel 198.51.100.2:443 BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB url=https://example.org/tunnel"
        assertTrue(InputValidators.validateTorBridgeLine(validObfs4).isValid)
        assertTrue(InputValidators.validateTorBridgeLine(validWebtunnel).isValid)
        assertTrue(InputValidators.validateTorBridgeLine("192.0.2.1:9001").isValid)
        assertTrue(InputValidators.validateTorBridgeLine("Bridge 192.0.2.1:9001 AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA").isValid)
        assertFalse(InputValidators.validateTorBridgeLine("obfs4 192.0.2.1:443 SHORT cert=abc").isValid)
        assertFalse(InputValidators.validateTorBridgeLine("webtunnel 198.51.100.2:443 BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB").isValid)
    }

    @Test
    fun `parseAndValidateExtraArgs splits quoted args and blocks dangerous flags`() {
        val ok = InputValidators.parseAndValidateExtraArgs("--workers 4 --tag \"my node\"").getOrThrow()
        assertEquals(listOf("--workers", "4", "--tag", "my node"), ok)

        assertTrue(InputValidators.parseAndValidateExtraArgs("--exec /bin/sh").isFailure)
        assertTrue(InputValidators.parseAndValidateExtraArgs("--workers 2 | cat").isFailure)
        assertTrue(InputValidators.parseAndValidateExtraArgs("--tag \"unclosed").isFailure)
    }

    @Test
    fun `redactSensitiveForLog strips ANSI escapes and masks credentials, certs, and third-party IPs`() {
        val raw = "\u001B[32m[+] Connecting\u001B[0m cert=SECRETKEY123== via socks5://alice:s3cr3t@198.51.100.42:1080 and 127.0.0.1"
        val redacted = InputValidators.redactSensitiveForLog(raw)
        assertFalse(redacted.contains("\u001B"))
        assertFalse(redacted.contains("SECRETKEY123"))
        assertFalse(redacted.contains("alice:s3cr3t"))
        assertFalse(redacted.contains("100.42"))
        assertTrue(redacted.contains("[+] Connecting"))
        assertTrue(redacted.contains("cert=[REDACTED]"))
        assertTrue(redacted.contains("198.51.x.x"))
        assertTrue(redacted.contains("127.0.0.1"))
    }
}
