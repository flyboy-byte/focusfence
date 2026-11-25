package app.focusfence.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoShareTest {
    @Test
    fun anAutomaticShareWaitsForTheNoteWindowAManualOneDoesNot() {
        val at = AutoShare.sendAt(1_000, auto = true)
        assertEquals(1_000 + AutoShare.DELAY_MS, at)
        assertFalse(AutoShare.isDue(at, at - 1))
        assertTrue(AutoShare.isDue(at, at))
        assertTrue(AutoShare.isDue(AutoShare.sendAt(1_000, auto = false), 1_000))
    }

    @Test
    fun thePartnersAlertNamesTheCategoryNotTheContent() {
        val text = Digest.alertText(mapOf("type" to LogEvents.TYPE_SERVICE, "comp" to "partner", "state" to "shot_auto_sent", "detail" to AutoShare.sentText("porn", 92)))
        assertEquals("A screenshot was flagged and sent to you blurred (porn 92%, sent automatically). Open the report to see it.", text)
        // Queued is not news yet: the partner hears once the picture is there.
        assertEquals(null, Digest.alertText(mapOf("type" to LogEvents.TYPE_SERVICE, "comp" to "partner", "state" to "shot_auto_queued", "detail" to "porn 92%")))
    }
}
