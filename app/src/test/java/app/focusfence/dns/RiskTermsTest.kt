package app.focusfence.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertTrue(RiskTerms.find("xxxl shirt").isEmpty())
        assertTrue(RiskTerms.find("Milford, Connecticut").isEmpty())
        assertTrue(RiskTerms.find("Titanic and titles").isEmpty())
    }

    @Test
    fun leavesInnocentWordsAlone() {
        assertTrue(RiskTerms.find("Pick a time slot. Hot weather. I bet you'll like it. Slots available.").isEmpty())
        assertTrue(RiskTerms.find("Seen with the naked eye. Sussex. Essex. Unisex sizing. Post a photo.").isEmpty())
        assertTrue(RiskTerms.find("Version 2.1.0, page 34, rule 3 of 4").isEmpty())
    }

    @Test
    fun seesThroughDisguises() {
        for (s in listOf("p0rn", "P*RN", "p o r n", "p.o.r.n", "p-o-r-n", "pörn", "ｐｏｒｎ", "𝐩𝐨𝐫𝐧", "p​o​rn")) {
            assertEquals(s, listOf("porn"), RiskTerms.find("watch $s now")["adult"])
        }
        assertEquals(listOf("onlyfans"), RiskTerms.find("my 0nlyf4ns link")["adult"])
        assertEquals(listOf("pornhub"), RiskTerms.find("porn hub premium")["adult"])
    }

    @Test
    fun pluralsAndPhrases() {
        assertEquals(listOf("milf"), RiskTerms.find("hot MILFs near you")["adult"])
        assertEquals(listOf("sex video"), RiskTerms.find("new sex-video leaked")["adult"])
        assertEquals(listOf("casino"), RiskTerms.find("best casinos 2026")["gambling"])
    }

    @Test
    fun longerWordWins() {
        assertEquals(listOf("nudes"), RiskTerms.find("leaked nudes")["adult"])
        assertEquals(listOf("porno"), RiskTerms.find("porno")["adult"])
    }

    @Test
    fun strongOnlyWhenClear() {
        assertTrue(RiskTerms.strong(RiskTerms.find("pornhub")))
        assertFalse(RiskTerms.strong(RiskTerms.find("NSFW")))          // a label on a meme
        assertFalse(RiskTerms.strong(RiskTerms.find("online casino"))) // gambling never alerts here
        assertTrue(RiskTerms.strong(RiskTerms.find("NSFW boobs")))     // two weak words together
        assertFalse(RiskTerms.strong(emptyMap()))
    }

    @Test
    fun explicitWordsAlertPartners() {
        val hits = RiskTerms.find("Hot MILFs · watch p0rn free · online casino")
        assertEquals("milf, porn in Chrome", RiskTerms.alertDetail(hits, "Chrome"))
        val f = LogEvents.service(1, "monitor", "text", RiskTerms.alertDetail(hits, "Chrome"))
        assertEquals("Explicit words on screen: milf, porn in Chrome", Digest.urgentText(f))
        assertTrue(AlertPrefs.isImportant(f))
    }
}
