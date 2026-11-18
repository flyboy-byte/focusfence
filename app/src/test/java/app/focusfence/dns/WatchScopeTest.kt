package app.focusfence.dns

import app.focusfence.dns.WatchScope.Group
import app.focusfence.dns.WatchScope.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchScopeTest {

    private val skip = setOf("com.android.systemui", "app.focusfence.accountable")

    @Test
    fun allModeWatchesEverythingButSkipsAndExclusions() {
        assertTrue(WatchScope.watches("com.android.chrome", Mode.ALL, emptySet(), emptySet(), skip))
        assertFalse(WatchScope.watches("com.android.systemui", Mode.ALL, emptySet(), emptySet(), skip))
        assertFalse(WatchScope.watches("com.bank", Mode.ALL, emptySet(), setOf("com.bank"), skip))
    }

    @Test
    fun chosenModeOnlyChosen() {
        assertTrue(WatchScope.watches("a", Mode.CHOSEN, setOf("a"), emptySet(), skip))
        assertFalse(WatchScope.watches("b", Mode.CHOSEN, setOf("a"), emptySet(), skip))
        // A skipped package stays skipped even if chosen.
        assertFalse(WatchScope.watches("com.android.systemui", Mode.CHOSEN, setOf("com.android.systemui"), emptySet(), skip))
    }

    @Test
    fun grouping() {
        assertEquals(Group.BROWSERS, WatchScope.group("com.instagram.android", 4, isBrowser = true))
        assertEquals(Group.SOCIAL, WatchScope.group("com.instagram.android", -1, isBrowser = false))
        assertEquals(Group.VIDEO, WatchScope.group("com.google.android.youtube", -1, false))
        assertEquals(Group.GAMES, WatchScope.group("some.game", 0, false))
        assertEquals(Group.IMAGES, WatchScope.group("some.gallery", 3, false))
        assertEquals(Group.OTHER, WatchScope.group("some.notes", 7, false))
        assertEquals(Group.OTHER, WatchScope.group("some.unknown", -1, false))
    }

    @Test
    fun describe() {
        assertEquals("All apps", WatchScope.describe(Mode.ALL, 0, 0))
        assertEquals("All apps except 2", WatchScope.describe(Mode.ALL, 0, 2))
        assertEquals("1 chosen app", WatchScope.describe(Mode.CHOSEN, 1, 0))
        assertEquals("3 chosen apps", WatchScope.describe(Mode.CHOSEN, 3, 0))
    }
}
