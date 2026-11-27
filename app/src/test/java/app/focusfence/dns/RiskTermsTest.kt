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
        assertEquals(listOf("milf", "porn"), hits["adult"]) // in the sealed report
        val f = LogEvents.service(1, "monitor", "text", RiskTerms.alertDetail(hits, "Chrome"))
        // The alert passes the server readable: the app, never the words (they can be site names).
        assertEquals("Explicit words on screen in Chrome", Digest.urgentText(f))
        assertTrue(AlertPrefs.isImportant(f))
    }

    @Test
    fun screensAboutQuittingNeverAlert() {
        // Made-up screens of the kinds a real export showed: every "porn" there was about quitting it.
        for (t in listOf(
            "App store · Quit Porn: daily tracker · Install",
            "Stop watching porn. Accountability software that works",
            "Day 12 of recovery from porn addiction",
            "How the open-source porn detector scores nsfw and hentai labels",
        )) assertFalse(t, RiskTerms.alertable(t, RiskTerms.find(t)))
        assertFalse(RiskTerms.alertable("porn", RiskTerms.find("porn"))) // one weak word
        val real = "Hot MILFs near you · free pornhub premium · Partner sites · Disable your ad blocker"
        assertTrue(RiskTerms.alertable(real, RiskTerms.find(real)))
    }

    @Test
    fun phrasesFromTheIndex() {
        assertEquals(listOf("send nudes"), RiskTerms.find("pls s3nd nudes")["adult"])
        assertEquals(listOf("nude pics"), RiskTerms.find("Nude pics of her")["adult"])
        assertTrue(RiskTerms.find("Only fans of the show will get this").isEmpty())
        assertTrue(RiskTerms.find("Bet 365 days of the year · 2026 · rule 3").isEmpty())
        assertEquals(listOf("rule 34"), RiskTerms.find("rule 34 of anything")["adult"])
    }

    @Test
    fun aLongScreenIsFast() {
        val screen = (1..400).joinToString(" · ") { "Post $it by someone about weather, slots, bets and p0rn-free living" }
        RiskTerms.find(screen) // warm up
        val t0 = System.nanoTime()
        repeat(20) { RiskTerms.find(screen) }
        val msEach = (System.nanoTime() - t0) / 20 / 1_000_000.0
        assertTrue("took $msEach ms", msEach < 50)
    }
}
