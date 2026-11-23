package app.novavpn.domain.routing

import app.novavpn.domain.validation.InputValidators

enum class RuleKind {
    DOMAIN,
    DOMAIN_SUFFIX,
    GEOSITE,
    GEOIP,
    IP_CIDR,
    PORT
}

data class ParsedRoutingRule(
    val kind: RuleKind,
    val value: String,
    val rawLine: String
)

data class RuleParseResult(
    val validRules: List<ParsedRoutingRule>,
    val invalidLines: List<Pair<Int, String>>
)

object RoutingRuleParser {
    fun parse(rawText: String): RuleParseResult {
        val valid = mutableListOf<ParsedRoutingRule>()
        val invalid = mutableListOf<Pair<Int, String>>()

        rawText.lines().forEachIndexed { idx, original ->
            val lineNum = idx + 1
            val line = original.substringBefore('#').trim()
            if (line.isEmpty()) return@forEachIndexed

            val rule = parseSingleLine(line)
            if (rule != null) {
                valid.add(rule.copy(rawLine = line))
            } else {
                invalid.add(lineNum to line)
            }
        }
        return RuleParseResult(valid, invalid)
    }

    private fun parseSingleLine(line: String): ParsedRoutingRule? {
        if (line.startsWith("port:", ignoreCase = true)) {
            val portStr = line.substringAfter(':').trim()
            val port = portStr.toIntOrNull()
            return if (port != null && InputValidators.validatePort(port).isValid) {
                ParsedRoutingRule(RuleKind.PORT, port.toString(), line)
            } else null
        }
        // Bare port number
        line.toIntOrNull()?.let { port ->
            return if (InputValidators.validatePort(port).isValid) {
                ParsedRoutingRule(RuleKind.PORT, port.toString(), line)
            } else null
        }

        if (line.startsWith("geosite:", ignoreCase = true)) {
            val tag = line.substringAfter(':').trim()
            return if (tag.isNotEmpty() && !tag.contains(' ') && InputValidators.isValidDomainOrPattern(line)) {
                ParsedRoutingRule(RuleKind.GEOSITE, tag.lowercase(), line)
            } else null
        }

        if (line.startsWith("geoip:", ignoreCase = true)) {
            val tag = line.substringAfter(':').trim()
            return if (tag.isNotEmpty() && !tag.contains(' ') && InputValidators.isValidDomainOrPattern(line)) {
                ParsedRoutingRule(RuleKind.GEOIP, tag.lowercase(), line)
            } else null
        }

        if (line.startsWith("*.")) {
            val suffix = line.removePrefix("*.")
            return if (InputValidators.isValidDomainOrPattern(line)) {
                ParsedRoutingRule(RuleKind.DOMAIN_SUFFIX, suffix.lowercase(), line)
            } else null
        }

        if (InputValidators.validateCidrOrIp(line).isValid) {
            val normalized = if ('/' in line) line else if (':' in line) "$line/128" else "$line/32"
            return ParsedRoutingRule(RuleKind.IP_CIDR, normalized, line)
        }

        if (InputValidators.isValidDomainOrPattern(line)) {
            return ParsedRoutingRule(RuleKind.DOMAIN, line.lowercase(), line)
        }

        return null
    }
}
