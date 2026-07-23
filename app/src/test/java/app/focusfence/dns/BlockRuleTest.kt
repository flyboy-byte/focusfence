package app.focusfence.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockRuleTest {

    @Test
    fun encodeDecodeRoundTrips() {
        val always = BlockRule("reddit.com")
        assertEquals(always, BlockRule.decode(always.encode()))

        val windowed = BlockRule("x.com", 9 * 60, 18 * 60)
        assertEquals(windowed, BlockRule.decode(windowed.encode()))
    }

    @Test
    fun decodeRejectsMalformed() {
        assertNull(BlockRule.decode(""))
        assertNull(BlockRule.decode("a|b|c"))
        assertNull(BlockRule.decode("x.com|100"))
        assertNull(BlockRule.decode("x.com|-5|100"))
        assertNull(BlockRule.decode("x.com|100|2000"))
    }

    @Test
    fun alwaysRuleIsAlwaysActive() {
        val rule = BlockRule("reddit.com")
        assertTrue(rule.activeAt(0))
        assertTrue(rule.activeAt(720))
        assertTrue(rule.activeAt(1439))
    }

    @Test
    fun windowedRuleRespectsHours() {
        val workHours = BlockRule("reddit.com", 9 * 60, 18 * 60)
        assertFalse(workHours.activeAt(8 * 60 + 59))
        assertTrue(workHours.activeAt(9 * 60))
        assertTrue(workHours.activeAt(12 * 60))
        assertFalse(workHours.activeAt(18 * 60))
        assertFalse(workHours.activeAt(23 * 60))
    }

    @Test
    fun windowMayWrapMidnight() {
        val night = BlockRule("youtube.com", 22 * 60, 6 * 60)
        assertTrue(night.activeAt(23 * 60))
        assertTrue(night.activeAt(0))
        assertTrue(night.activeAt(5 * 60 + 59))
        assertFalse(night.activeAt(6 * 60))
        assertFalse(night.activeAt(12 * 60))
    }

    @Test
    fun findRuleWalksParentDomains() {
        val rules = mapOf(
            "facebook.com" to BlockRule("facebook.com"),
            "x.com" to BlockRule("x.com", 540, 1080),
        )
        assertEquals("facebook.com", Blocklist.findRule("graph.facebook.com", rules)?.domain)
        assertEquals("x.com", Blocklist.findRule("api.x.com", rules)?.domain)
        assertNull(Blocklist.findRule("notfacebook.com", rules))
        assertNull(Blocklist.findRule("com", rules))
    }
}
