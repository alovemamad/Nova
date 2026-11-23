package app.novavpn.domain.validation

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

sealed class ValidationResult {
    data object Valid : ValidationResult()
    data class Invalid(val reason: String) : ValidationResult()

    val isValid: Boolean get() = this is Valid
}

object InputValidators {
    private val SHELL_META_REGEX = Regex("""[;|&$`><\n\r(){}*?~!]""")
    private val DOMAIN_REGEX = Regex("""^(\*\.)?([a-zA-Z0-9]([a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z]{2,63}$""")
    private val SINGLE_LABEL_HOST = Regex("""^[a-zA-Z0-9]([a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?$""")
    private val HEX_40_REGEX = Regex("""^[0-9a-fA-F]{40}$""")
    private val IPV4_REGEX = Regex("""^((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$""")

    private val BLOCKED_EXTRA_FLAGS = setOf(
        "--config-write",
        "-config-write",
        "--shell",
        "--exec",
        "-e",
        "--plugin-exec",
        "--log-file"
    )

    fun validatePort(port: Int): ValidationResult {
        return if (port in 1..65535) ValidationResult.Valid
        else ValidationResult.Invalid("Port must be between 1 and 65535")
    }

    fun validateMtu(mtu: Int): ValidationResult {
        return if (mtu in 576..1500) ValidationResult.Valid
        else ValidationResult.Invalid("MTU must be between 576 and 1500")
    }

    fun validateEndpoint(raw: String, allowEmpty: Boolean = true): ValidationResult {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return if (allowEmpty) ValidationResult.Valid else ValidationResult.Invalid("Endpoint cannot be empty")
        }
        if (SHELL_META_REGEX.containsMatchIn(trimmed) || trimmed.contains(' ')) {
            return ValidationResult.Invalid("Endpoint contains illegal characters")
        }
        // Check [IPv6]:port
        if (trimmed.startsWith("[")) {
            val closeIdx = trimmed.indexOf(']')
            if (closeIdx <= 1 || closeIdx + 2 >= trimmed.length || trimmed[closeIdx + 1] != ':') {
                return ValidationResult.Invalid("IPv6 endpoint must use [addr]:port format")
            }
            val ip = trimmed.substring(1, closeIdx)
            val portStr = trimmed.substring(closeIdx + 2)
            val port = portStr.toIntOrNull() ?: return ValidationResult.Invalid("Invalid port in endpoint")
            if (!validatePort(port).isValid) return ValidationResult.Invalid("Port out of range")
            return if (isValidIp(ip)) ValidationResult.Valid else ValidationResult.Invalid("Invalid IPv6 address")
        }
        val colonIdx = trimmed.lastIndexOf(':')
        if (colonIdx <= 0 || colonIdx == trimmed.lastIndex) {
            return ValidationResult.Invalid("Endpoint must be host:port or ip:port")
        }
        val host = trimmed.substring(0, colonIdx)
        val port = trimmed.substring(colonIdx + 1).toIntOrNull()
            ?: return ValidationResult.Invalid("Invalid port number")
        if (!validatePort(port).isValid) return ValidationResult.Invalid("Port must be 1..65535")
        return if (isValidIp(host) || DOMAIN_REGEX.matches(host) || SINGLE_LABEL_HOST.matches(host)) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid("Invalid host or IP address")
        }
    }

    fun validateCidrOrIp(raw: String): ValidationResult {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ValidationResult.Invalid("Empty IP/CIDR")
        if (SHELL_META_REGEX.containsMatchIn(trimmed)) return ValidationResult.Invalid("Illegal characters")
        val slashIdx = trimmed.indexOf('/')
        if (slashIdx < 0) {
            return if (isValidIp(trimmed)) ValidationResult.Valid else ValidationResult.Invalid("Invalid IP address: $trimmed")
        }
        val ip = trimmed.substring(0, slashIdx)
        val prefix = trimmed.substring(slashIdx + 1).toIntOrNull()
            ?: return ValidationResult.Invalid("Invalid CIDR prefix length")
        if (IPV4_REGEX.matches(ip)) {
            return if (prefix in 0..32) ValidationResult.Valid else ValidationResult.Invalid("IPv4 CIDR prefix must be 0..32")
        }
        if (isValidIpv6(ip)) {
            return if (prefix in 0..128) ValidationResult.Valid else ValidationResult.Invalid("IPv6 CIDR prefix must be 0..128")
        }
        return ValidationResult.Invalid("Invalid IP in CIDR: $ip")
    }

    fun validateDnsServer(raw: String): ValidationResult {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ValidationResult.Invalid("DNS server cannot be empty")
        if (SHELL_META_REGEX.containsMatchIn(trimmed) || trimmed.contains(' ')) {
            return ValidationResult.Invalid("DNS server contains illegal characters")
        }
        if (trimmed.startsWith("https://", ignoreCase = true)) {
            val rest = trimmed.removePrefix("https://").substringBefore('/')
            val host = rest.substringBefore(':')
            return if (host.isNotBlank() && (isValidIp(host) || DOMAIN_REGEX.matches(host))) {
                ValidationResult.Valid
            } else {
                ValidationResult.Invalid("Invalid DoH URL")
            }
        }
        if (trimmed.startsWith("tls://", ignoreCase = true)) {
            val host = trimmed.removePrefix("tls://").substringBefore(':')
            return if (host.isNotBlank() && (isValidIp(host) || DOMAIN_REGEX.matches(host))) {
                ValidationResult.Valid
            } else {
                ValidationResult.Invalid("Invalid DoT endpoint")
            }
        }
        return if (isValidIp(trimmed) || DOMAIN_REGEX.matches(trimmed)) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid("Invalid DNS IP or hostname")
        }
    }

    fun validateUpstreamProxy(raw: String): ValidationResult {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ValidationResult.Valid
        if (SHELL_META_REGEX.containsMatchIn(trimmed) || trimmed.contains(' ')) {
            return ValidationResult.Invalid("Proxy URI contains illegal characters")
        }
        val scheme = trimmed.substringBefore("://", "").lowercase()
        if (scheme !in setOf("socks5", "http", "https")) {
            return ValidationResult.Invalid("Proxy must start with socks5://, http://, or https://")
        }
        val hostPort = trimmed.substringAfter("://").substringAfterLast('@')
        return validateEndpoint(hostPort, allowEmpty = false)
    }

    fun validateTorBridgeLine(raw: String): ValidationResult {
        val trimmed = raw.trim().removePrefix("Bridge ").trim()
        if (trimmed.isEmpty()) return ValidationResult.Invalid("Bridge line is empty")
        if (SHELL_META_REGEX.containsMatchIn(trimmed)) {
            return ValidationResult.Invalid("Bridge line contains forbidden shell characters")
        }
        val parts = trimmed.split(Regex("""\s+"""))
        if (parts.size < 3) return ValidationResult.Invalid("Incomplete bridge line")
        val transport = parts[0].lowercase()
        if (transport !in setOf("obfs4", "webtunnel", "snowflake", "meek_lite")) {
            return ValidationResult.Invalid("Unsupported pluggable transport: $transport")
        }
        val endpointCheck = validateEndpoint(parts[1], allowEmpty = false)
        if (!endpointCheck.isValid) return ValidationResult.Invalid("Invalid bridge endpoint: ${parts[1]}")
        if (!HEX_40_REGEX.matches(parts[2])) {
            return ValidationResult.Invalid("Bridge fingerprint must be 40 hex characters")
        }
        if (transport == "obfs4" && parts.none { it.startsWith("cert=") }) {
            return ValidationResult.Invalid("obfs4 bridge missing cert= parameter")
        }
        if (transport == "webtunnel" && parts.none { it.startsWith("url=https://") }) {
            return ValidationResult.Invalid("webtunnel bridge missing url=https:// parameter")
        }
        return ValidationResult.Valid
    }

    fun parseAndValidateExtraArgs(raw: String): Result<List<String>> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Result.success(emptyList())
        if (SHELL_META_REGEX.containsMatchIn(trimmed)) {
            return Result.failure(IllegalArgumentException("Extra args contain disallowed shell metacharacters"))
        }
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inSingleQuote = false
        var inDoubleQuote = false
        var i = 0
        while (i < trimmed.length) {
            val ch = trimmed[i]
            when {
                ch == '\'' && !inDoubleQuote -> inSingleQuote = !inSingleQuote
                ch == '"' && !inSingleQuote -> inDoubleQuote = !inDoubleQuote
                ch.isWhitespace() && !inSingleQuote && !inDoubleQuote -> {
                    if (current.isNotEmpty()) {
                        tokens.add(current.toString())
                        current.clear()
                    }
                }
                else -> current.append(ch)
            }
            i++
        }
        if (inSingleQuote || inDoubleQuote) {
            return Result.failure(IllegalArgumentException("Unclosed quote in extra arguments"))
        }
        if (current.isNotEmpty()) tokens.add(current.toString())

        for (token in tokens) {
            val flagName = token.substringBefore('=')
            if (flagName.lowercase() in BLOCKED_EXTRA_FLAGS) {
                return Result.failure(IllegalArgumentException("Forbidden flag: $flagName"))
            }
        }
        return Result.success(tokens)
    }

    private val TLD_REGEX = Regex("""^[a-zA-Z]{2,63}$""")

    fun isValidDomainOrPattern(raw: String): Boolean {
        val t = raw.trim()
        if (t.isEmpty()) return false
        if (t.startsWith("geosite:", ignoreCase = true) || t.startsWith("geoip:", ignoreCase = true)) {
            if (SHELL_META_REGEX.containsMatchIn(t)) return false
            val tag = t.substringAfter(':').trim()
            return tag.isNotEmpty() && !tag.contains(' ')
        }
        if (t.startsWith("*.")) {
            val rest = t.removePrefix("*.")
            if (SHELL_META_REGEX.containsMatchIn(rest)) return false
            return TLD_REGEX.matches(rest) || DOMAIN_REGEX.matches(rest)
        }
        if (SHELL_META_REGEX.containsMatchIn(t)) return false
        return DOMAIN_REGEX.matches(t)
    }

    fun isValidIp(ip: String): Boolean {
        return IPV4_REGEX.matches(ip) || isValidIpv6(ip)
    }

    fun isValidIpv6(ip: String): Boolean {
        if (!ip.contains(':')) return false
        return try {
            val addr = InetAddress.getByName(ip)
            addr is Inet6Address
        } catch (_: Exception) {
            false
        }
    }

    fun isValidIpv4(ip: String): Boolean {
        return IPV4_REGEX.matches(ip) && try {
            InetAddress.getByName(ip) is Inet4Address
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Redacts sensitive bridge certs, proxy credentials, and third-party IPs before storing in logs.
     */
    fun redactSensitiveForLog(raw: String): String {
        var sanitized = raw
        // Redact obfs4 cert= and webtunnel url=
        sanitized = sanitized.replace(Regex("""cert=[A-Za-z0-9+/=]+"""), "cert=[REDACTED]")
        sanitized = sanitized.replace(Regex("""url=https?://\S+"""), "url=[REDACTED]")
        // Redact proxy credentials user:pass@
        sanitized = sanitized.replace(Regex("""(socks5|https?)://[^:@\s]+:[^@\s]+@"""), "$1://[REDACTED]@")
        // Mask non-loopback IPv4 addresses (keep first two octets for troubleshooting)
        sanitized = sanitized.replace(Regex("""\b(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})\b""")) { m ->
            val full = m.value
            if (full == "127.0.0.1" || full == "0.0.0.0" || full.startsWith("10.0.") || full.startsWith("192.168.")) {
                full
            } else {
                "${m.groupValues[1]}.${m.groupValues[2]}.x.x"
            }
        }
        return sanitized
    }
}
