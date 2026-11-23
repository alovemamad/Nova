package app.novavpn.domain.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingRuleParserTest {

    @Test
    fun `parses domains, suffixes, geosite, geoip, CIDR, bare IP, and ports`() {
        val input = """
            # Local network bypass
            10.0.0.0/8
            192.168.1.1
            *.ir
            example.com
            geosite:category-ads-all
            geoip:ir
            port:53
            853
        """.trimIndent()

        val result = RoutingRuleParser.parse(input)
        assertTrue(result.invalidLines.isEmpty())
        assertEquals(8, result.validRules.size)
        assertEquals(RuleKind.IP_CIDR, result.validRules[0].kind)
        assertEquals("192.168.1.1/32", result.validRules[1].value)
        assertEquals(RuleKind.DOMAIN_SUFFIX, result.validRules[2].kind)
        assertEquals("ir", result.validRules[2].value)
        assertEquals(RuleKind.DOMAIN, result.validRules[3].kind)
        assertEquals(RuleKind.GEOSITE, result.validRules[4].kind)
        assertEquals(RuleKind.GEOIP, result.validRules[5].kind)
        assertEquals(RuleKind.PORT, result.validRules[6].kind)
        assertEquals("853", result.validRules[7].value)
    }

    @Test
    fun `reports invalid lines with accurate line numbers`() {
        val input = """
            10.0.0.0/8
            not a valid rule!!!
            port:99999
        """.trimIndent()

        val result = RoutingRuleParser.parse(input)
        assertEquals(1, result.validRules.size)
        assertEquals(2, result.invalidLines.size)
        assertEquals(2, result.invalidLines[0].first)
        assertEquals(3, result.invalidLines[1].first)
    }
}
