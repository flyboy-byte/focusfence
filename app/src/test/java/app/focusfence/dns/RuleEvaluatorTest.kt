package app.focusfence.dns

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleEvaluatorTest {

    /** Budget rules are the only ones that read usage, so the rest of [RuleEvaluator.Now]
     *  can stay fixed across cases; only [minuteOfDay] varies where it matters. */
    private fun now(minuteOfDay: Int, screenOn: Boolean = true) = RuleEvaluator.Now(
        minuteOfDay = minuteOfDay,
        epochMillis = 1_700_000_000_000L,
        dayKey = "2026-08-15",
        screenOn = screenOn,
    )

    @Test
    fun alwaysRuleBlocksAtEveryMinute() {
        val rule = BlockRule("reddit.com")
        assertFalse(RuleEvaluator.requiresUsage(rule, "reddit.com"))
        assertTrue(RuleEvaluator.blocks(rule, "reddit.com", now(0)))
        assertTrue(RuleEvaluator.blocks(rule, "reddit.com", now(720)))
        assertTrue(RuleEvaluator.blocks(rule, "reddit.com", now(1439)))
    }

    @Test
    fun windowRuleRespectsNonWrappingBounds() {
        // 09:00–18:00. activeAt uses `until`, so the end minute is exclusive.
        val rule = BlockRule("x.com", 540, 1080)
        assertFalse(RuleEvaluator.blocks(rule, "x.com", now(539)))
        assertTrue(RuleEvaluator.blocks(rule, "x.com", now(540)))
        assertTrue(RuleEvaluator.blocks(rule, "x.com", now(1079)))
        assertFalse(RuleEvaluator.blocks(rule, "x.com", now(1080)))
    }

    @Test
    fun windowRuleWrapsMidnight() {
        // 22:00–06:00.
        val rule = BlockRule("youtube.com", 1320, 360)
        assertTrue(RuleEvaluator.blocks(rule, "youtube.com", now(1320)))
        assertTrue(RuleEvaluator.blocks(rule, "youtube.com", now(0)))
        assertTrue(RuleEvaluator.blocks(rule, "youtube.com", now(359)))
        assertFalse(RuleEvaluator.blocks(rule, "youtube.com", now(360)))
        assertFalse(RuleEvaluator.blocks(rule, "youtube.com", now(700)))
    }

    @Test
    fun budgetRuleUnderBudgetDoesNotBlock() {
        val rule = BlockRule("reddit.com", budgetMinutes = 10)
        assertTrue(RuleEvaluator.requiresUsage(rule, "reddit.com"))
        assertFalse(RuleEvaluator.blocks(rule, "reddit.com", now(600), usedMillis = 599_999L))
    }

    @Test
    fun budgetRuleAtExactlyBudgetBlocks() {
        // The comparison is >=, so landing exactly on the budget blocks.
        val rule = BlockRule("reddit.com", budgetMinutes = 10)
        assertTrue(RuleEvaluator.blocks(rule, "reddit.com", now(600), usedMillis = 600_000L))
    }

    @Test
    fun budgetRuleOverBudgetBlocksOnEveryFrontDoor() {
        val rule = BlockRule("reddit.com", budgetMinutes = 10)
        for (qname in listOf("reddit.com", "www.reddit.com", "m.reddit.com")) {
            assertTrue(qname, RuleEvaluator.requiresUsage(rule, qname))
            assertTrue(qname, RuleEvaluator.blocks(rule, qname, now(600), usedMillis = 900_000L))
        }
    }

    @Test
    fun budgetRuleIgnoresNonFrontDoorSubdomain() {
        // The regression this guards: SDK/API traffic from unrelated apps must
        // neither drain the budget nor break once it has run out.
        val rule = BlockRule("facebook.com", budgetMinutes = 10)
        assertFalse(RuleEvaluator.requiresUsage(rule, "graph.facebook.com"))
        assertFalse(
            RuleEvaluator.blocks(
                rule, "graph.facebook.com", now(600), usedMillis = 99_999_999L,
            ),
        )
    }

    @Test
    fun sectionRuleNeverBlocksAndNeverAccrues() {
        // Section rules must stay resolvable; the accessibility URL filter enforces them.
        val rule = BlockRule("facebook.com", allowPaths = listOf("/marketplace"))
        assertFalse(RuleEvaluator.requiresUsage(rule, "facebook.com"))
        assertFalse(RuleEvaluator.blocks(rule, "facebook.com", now(0)))
        assertFalse(RuleEvaluator.blocks(rule, "facebook.com", now(720), usedMillis = 99_999_999L))
        assertFalse(RuleEvaluator.blocks(rule, "www.facebook.com", now(1439)))
    }
}
