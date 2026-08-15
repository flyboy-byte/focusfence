package app.focusfence.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

        val budgeted = BlockRule("reddit.com", budgetMinutes = 10)
        assertEquals(budgeted, BlockRule.decode(budgeted.encode()))
        assertEquals("reddit.com|b|10", budgeted.encode())
    }

    @Test
    fun decodeRejectsMalformed() {
        assertNull(BlockRule.decode(""))
        assertNull(BlockRule.decode("a|b|c"))
        assertNull(BlockRule.decode("x.com|100"))
        assertNull(BlockRule.decode("x.com|-5|100"))
        assertNull(BlockRule.decode("x.com|100|2000"))
        assertNull(BlockRule.decode("x.com|b|0"))
        assertNull(BlockRule.decode("x.com|b|nope"))
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
    fun commitmentRoundTripsPreservingTheInstant() {
        val commitment = BlockRule("com.zhiliaoapp.musically", commitUntilEpochMillis = 1_755_180_000_000L)
        assertEquals("com.zhiliaoapp.musically|c|1755180000000", commitment.encode())
        assertEquals(commitment, BlockRule.decode(commitment.encode()))
        assertEquals(1_755_180_000_000L, BlockRule.decode(commitment.encode())?.commitUntilEpochMillis)
    }

    @Test
    fun barePackageNameStillDecodesAsAlwaysBlocked() {
        // The backward-compatibility guarantee: everything written before app
        // rules gained a time dimension is a bare package name, and must keep
        // behaving as an always-block rule with no migration.
        val rule = BlockRule.decode("com.zhiliaoapp.musically")
        assertNotNull(rule)
        assertTrue(rule!!.activeAt(0))
        assertTrue(rule.activeAt(1439))
        assertFalse(rule.hasCommitment)
        assertFalse(rule.hasBudget)
        assertFalse(rule.hasPathExceptions)
    }

    @Test
    fun decodeRejectsMalformedCommitment() {
        assertNull(BlockRule.decode("com.foo|c|nope"))
        assertNull(BlockRule.decode("com.foo|c|-5"))
        assertNull(BlockRule.decode("com.foo|c|0"))
        assertNull(BlockRule.decode("com.foo|c|"))
    }

    @Test
    fun commitmentWinsOverBudgetWhenEncoding() {
        // encode()'s `when` ordering is deliberate; a rule carrying both must
        // serialise as the commitment so decode() reproduces that single mode.
        val both = BlockRule("com.foo", budgetMinutes = 10, commitUntilEpochMillis = 1_755_180_000_000L)
        assertEquals("com.foo|c|1755180000000", both.encode())
        assertEquals(both.commitUntilEpochMillis, BlockRule.decode(both.encode())?.commitUntilEpochMillis)
        assertFalse(BlockRule.decode(both.encode())!!.hasBudget)
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
