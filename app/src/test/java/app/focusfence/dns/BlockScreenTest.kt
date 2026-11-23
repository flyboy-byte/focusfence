package app.focusfence.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockScreenTest {

    @Test
    fun whyNamesTheRuleKind() {
        assertEquals(BlockScreen.Why.ALWAYS, BlockScreen.why(BlockRule("reddit.com")))
        assertEquals(BlockScreen.Why.SCHEDULE, BlockScreen.why(BlockRule("x.com", 540, 1080)))
        assertEquals(BlockScreen.Why.BUDGET, BlockScreen.why(BlockRule("youtube.com", budgetMinutes = 30)))
        assertEquals(
            BlockScreen.Why.COMMITMENT,
            BlockScreen.why(BlockRule("tiktok.com", commitUntilEpochMillis = 1_700_000_000_000L)),
        )
        assertEquals(
            BlockScreen.Why.SECTION,
            BlockScreen.why(BlockRule("facebook.com", allowPaths = listOf("/marketplace"))),
        )
    }

    @Test
    fun signalsOnlyForFrontDoorsWithScreenOn() {
        val rule = BlockRule("youtube.com")
        assertTrue(BlockScreen.shouldSignal("youtube.com", rule, screenOn = true))
        assertTrue(BlockScreen.shouldSignal("www.youtube.com", rule, screenOn = true))
        assertTrue(BlockScreen.shouldSignal("m.youtube.com", rule, screenOn = true))
        // SDK/API hosts are background traffic from other apps.
        assertFalse(BlockScreen.shouldSignal("i.ytimg.youtube.com", rule, screenOn = true))
        assertFalse(BlockScreen.shouldSignal("youtubei.googleapis.youtube.com", rule, screenOn = true))
        // Nobody is looking.
        assertFalse(BlockScreen.shouldSignal("youtube.com", rule, screenOn = false))
    }

    @Test
    fun sectionRulesNeverSignalFromDns() {
        // Their domain stays resolvable; the accessibility filter handles them.
        val rule = BlockRule("facebook.com", allowPaths = listOf("/marketplace"))
        assertFalse(BlockScreen.shouldSignal("facebook.com", rule, screenOn = true))
    }

    @Test
    fun hostMatchesDomainAndSubdomainsOnly() {
        assertTrue(BlockScreen.hostMatches("youtube.com", "youtube.com"))
        assertTrue(BlockScreen.hostMatches("m.youtube.com", "youtube.com"))
        assertFalse(BlockScreen.hostMatches("notyoutube.com", "youtube.com"))
        assertFalse(BlockScreen.hostMatches("google.com", "youtube.com"))
    }

    @Test
    fun cooldownSuppressesRepeatsPerDomain() {
        val c = BlockScreen.Cooldown(10_000)
        assertTrue(c.accept("youtube.com", 1_000))
        assertFalse(c.accept("youtube.com", 1_500))   // AAAA right after A
        assertTrue(c.accept("reddit.com", 1_500))     // other domains unaffected
        assertFalse(c.accept("youtube.com", 10_999))
        assertTrue(c.accept("youtube.com", 11_000))
        // A clock that went backwards must not lock the domain out.
        assertTrue(c.accept("youtube.com", 500))
    }
}
