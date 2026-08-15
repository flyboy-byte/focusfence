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

    // --- app rules (W7) ---

    private fun nowAt(epochMillis: Long, minuteOfDay: Int = 720) = RuleEvaluator.Now(
        minuteOfDay = minuteOfDay,
        epochMillis = epochMillis,
        dayKey = "2026-08-15",
        screenOn = true,
    )

    @Test
    fun blocksAppHonoursCommitmentBoundary() {
        val until = 1_755_180_000_000L
        val rule = BlockRule("com.zhiliaoapp.musically", commitUntilEpochMillis = until)
        assertTrue(RuleEvaluator.blocksApp(rule, nowAt(until - 1)))
        assertFalse(RuleEvaluator.blocksApp(rule, nowAt(until)))
        assertFalse(RuleEvaluator.blocksApp(rule, nowAt(until + 60_000L)))
    }

    @Test
    fun blocksAppIgnoresTimeOfDayForCommitments() {
        // A commitment is an absolute instant, not a daily window: the minute of
        // day must not change the answer.
        val until = 1_755_180_000_000L
        val rule = BlockRule("com.zhiliaoapp.musically", commitUntilEpochMillis = until)
        assertTrue(RuleEvaluator.blocksApp(rule, nowAt(until - 1, minuteOfDay = 0)))
        assertTrue(RuleEvaluator.blocksApp(rule, nowAt(until - 1, minuteOfDay = 1439)))
    }

    @Test
    fun blocksAppFollowsWindowsIncludingWrapping() {
        val work = BlockRule("com.instagram.android", 540, 1080)
        assertFalse(RuleEvaluator.blocksApp(work, nowAt(0, minuteOfDay = 539)))
        assertTrue(RuleEvaluator.blocksApp(work, nowAt(0, minuteOfDay = 540)))
        assertTrue(RuleEvaluator.blocksApp(work, nowAt(0, minuteOfDay = 1079)))
        assertFalse(RuleEvaluator.blocksApp(work, nowAt(0, minuteOfDay = 1080)))

        val night = BlockRule("com.instagram.android", 1320, 360)
        assertTrue(RuleEvaluator.blocksApp(night, nowAt(0, minuteOfDay = 1320)))
        assertTrue(RuleEvaluator.blocksApp(night, nowAt(0, minuteOfDay = 0)))
        assertTrue(RuleEvaluator.blocksApp(night, nowAt(0, minuteOfDay = 359)))
        assertFalse(RuleEvaluator.blocksApp(night, nowAt(0, minuteOfDay = 360)))
    }

    @Test
    fun blocksAppTreatsAnAppBudgetAsNotBlocking() {
        // Counting app foreground time needs UsageStatsManager (W10). Until that
        // exists a budget on an app must NOT silently behave as always-blocked.
        val rule = BlockRule("com.zhiliaoapp.musically", budgetMinutes = 10)
        assertFalse(RuleEvaluator.blocksApp(rule, nowAt(0)))
        assertFalse(RuleEvaluator.blocksApp(rule, nowAt(Long.MAX_VALUE / 2)))
    }

    @Test
    fun blocksAppAlwaysRuleBlocksWheneverAsked() {
        val rule = BlockRule("com.zhiliaoapp.musically")
        assertTrue(RuleEvaluator.blocksApp(rule, nowAt(0, minuteOfDay = 0)))
        assertTrue(RuleEvaluator.blocksApp(rule, nowAt(0, minuteOfDay = 1439)))
    }

    @Test
    fun domainBlocksAlsoHonourCommitments() {
        // blocks() gained the same branch, ordered after the section guard.
        val until = 1_755_180_000_000L
        val rule = BlockRule("reddit.com", commitUntilEpochMillis = until)
        assertTrue(RuleEvaluator.blocks(rule, "reddit.com", nowAt(until - 1)))
        assertFalse(RuleEvaluator.blocks(rule, "reddit.com", nowAt(until)))
        assertFalse(RuleEvaluator.requiresUsage(rule, "reddit.com"))
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
