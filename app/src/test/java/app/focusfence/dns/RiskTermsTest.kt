package app.focusfence.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskTermsTest {

    @Test
    fun findsWholeWordsCaseInsensitive() {
        val hits = RiskTerms.find("New NSFW thread on OnlyFans! Also an Online Casino ad and BlackJack tips.")
        assertEquals(listOf("nsfw", "onlyfans"), hits["adult"])
        assertEquals(listOf("online casino", "blackjack"), hits["gambling"])
        assertEquals("adult:nsfw,onlyfans;gambling:online casino,blackjack", RiskTerms.encode(hits))
    }

    @Test
    fun ignoresPartsOfOtherWords() {
        assertTrue(RiskTerms.find("Pornography research paper").isEmpty())
        assertTrue(RiskTerms.find("casinos").isEmpty())
        assertTrue(RiskTerms.find("xxxl shirt").isEmpty())
    }

    @Test
    fun leavesInnocentWordsAlone() {
        assertTrue(RiskTerms.find("Pick a time slot. Hot weather. I bet you'll like it. Slots available.").isEmpty())
    }
}
