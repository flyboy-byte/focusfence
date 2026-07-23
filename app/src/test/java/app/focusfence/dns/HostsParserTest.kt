package app.focusfence.dns

import org.junit.Assert.assertEquals
import org.junit.Test

class HostsParserTest {

    @Test
    fun parsesHostsAndPlainFormats() {
        val input = """
            # StevenBlack-style header comment
            127.0.0.1 localhost
            ::1 localhost
            0.0.0.0 ads.example.com
            0.0.0.0 Tracker.Example.NET # trailing comment
            0.0.0.0 a.doubleclick.net b.doubleclick.net

            plain-list-domain.com
            not a valid line at all
        """.trimIndent().lineSequence()

        val parsed = HostsParser.parse(input)
        assertEquals(
            setOf(
                "ads.example.com",
                "tracker.example.net",
                "a.doubleclick.net",
                "b.doubleclick.net",
                "plain-list-domain.com",
            ),
            parsed,
        )
    }
}
