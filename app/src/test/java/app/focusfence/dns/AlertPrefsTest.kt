package app.focusfence.dns

import app.focusfence.dns.AlertPrefs.Level
import app.focusfence.dns.AlertPrefs.Prefs
import app.focusfence.dns.AlertPrefs.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertPrefsTest {
    @Test
    fun theDefaultSendsEverythingAtOnceDayAndNight() {
        val p = Prefs()
        for (h in 0..23) {
            assertEquals(Route.NOW, AlertPrefs.route(p, true, h))
            assertEquals(Route.NOW, AlertPrefs.route(p, false, h))
        }
    }

    @Test
    fun levelsHoldOrSkipOnlyTheHousekeeping() {
        assertEquals(Route.HOLD, AlertPrefs.route(Prefs(Level.DAILY), false, 12))
        assertEquals(Route.NOW, AlertPrefs.route(Prefs(Level.DAILY), true, 12))
        assertEquals(Route.SKIP, AlertPrefs.route(Prefs(Level.IMPORTANT), false, 12))
        assertEquals(Route.NOW, AlertPrefs.route(Prefs(Level.IMPORTANT), true, 12))
        assertEquals(Level.ALL, Level.fromKey("nonsense"))
    }

    @Test
    fun quietHoursHoldEverythingFromTenToSeven() {
        val q = Prefs(quiet = true)
        assertEquals(Route.NOW, AlertPrefs.route(q, true, 21))
        assertEquals(Route.HOLD, AlertPrefs.route(q, true, 22))
        assertEquals(Route.HOLD, AlertPrefs.route(q, true, 3))
        assertEquals(Route.HOLD, AlertPrefs.route(q, true, 6))
        assertEquals(Route.NOW, AlertPrefs.route(q, true, 7))
        // "Important only" still drops housekeeping at night rather than holding it.
        assertEquals(Route.SKIP, AlertPrefs.route(q.copy(level = Level.IMPORTANT), false, 3))
    }

    @Test
    fun heldAlertsGoOutOnceAtSevenTheirTime() {
        val q = Prefs(quiet = true)
        assertFalse(AlertPrefs.flushDue(q, 6, day = 10, lastFlushDay = 9, anyHeld = true))
        assertTrue(AlertPrefs.flushDue(q, 7, day = 10, lastFlushDay = 9, anyHeld = true))
        assertTrue("a phone asleep at 7 still sends at 9", AlertPrefs.flushDue(q, 9, day = 10, lastFlushDay = 9, anyHeld = true))
        assertFalse("once a day", AlertPrefs.flushDue(q, 9, day = 10, lastFlushDay = 10, anyHeld = true))
        assertFalse(AlertPrefs.flushDue(q, 9, day = 10, lastFlushDay = 9, anyHeld = false))
        assertFalse("never inside quiet hours", AlertPrefs.flushDue(q, 23, day = 10, lastFlushDay = 9, anyHeld = true))
        assertTrue(AlertPrefs.flushDue(Prefs(Level.DAILY), 23, day = 10, lastFlushDay = 9, anyHeld = true))
    }

    @Test
    fun hoursAreTheirOwnTimeZone() {
        val t = 1_800_000_000_000L // 2027-01-15 08:00 UTC
        assertEquals(8, AlertPrefs.hour(Prefs(), t))
        assertEquals(1, AlertPrefs.hour(Prefs(zone = "America/Denver"), t))
        assertEquals(8, AlertPrefs.hour(Prefs(zone = "Not/AZone"), t))
        assertEquals("America/Denver", AlertPrefs.cleanZone(" America/Denver "))
        assertNull(AlertPrefs.cleanZone("Mars/Olympus"))
    }

    @Test
    fun housekeepingIsWhatIsNotImportant() {
        fun svc(comp: String, state: String) = mapOf("type" to LogEvents.TYPE_SERVICE, "comp" to comp, "state" to state)
        assertTrue(AlertPrefs.isImportant(svc("vpn", "stopped")))
        assertTrue(AlertPrefs.isImportant(svc("strict", "disabled")))
        assertTrue(AlertPrefs.isImportant(svc("partner", "shot_auto_sent")))
        assertTrue(AlertPrefs.isImportant(mapOf("type" to "integ", "key" to "list_adult")))
        assertFalse(AlertPrefs.isImportant(svc("partner", "group_notice")))
        assertFalse(AlertPrefs.isImportant(svc("partner", "partner_removed")))
        assertFalse(AlertPrefs.isImportant(svc("strict", "partner_approval_used")))
    }
}
