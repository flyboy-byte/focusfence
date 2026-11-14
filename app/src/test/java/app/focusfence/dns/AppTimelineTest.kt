package app.focusfence.dns

import app.focusfence.dns.AppTimeline.Out
import org.junit.Assert.assertEquals
import org.junit.Test

class AppTimelineTest {

    private val R = AppTimeline.ACTIVITY_RESUMED

    @Test
    fun emitsOnlyPackageChanges() {
        val tl = AppTimeline()
        assertEquals(listOf(Out.App(1, "a", "fg")), tl.feed(1, R, "a"))
        assertEquals(emptyList<Out>(), tl.feed(2, R, "a")) // another activity of the same app
        assertEquals(emptyList<Out>(), tl.feed(3, 2, "a")) // pause ignored
        assertEquals(listOf(Out.App(4, "a", "bg"), Out.App(4, "b", "fg")), tl.feed(4, R, "b"))
        assertEquals("b", tl.foreground)
    }

    @Test
    fun screenOffClosesForegroundApp() {
        val tl = AppTimeline()
        tl.feed(1, R, "a")
        assertEquals(
            listOf(Out.App(5, "a", "bg"), Out.Screen(5, "off")),
            tl.feed(5, AppTimeline.SCREEN_NON_INTERACTIVE, null),
        )
        assertEquals(listOf(Out.Screen(9, "on")), tl.feed(9, AppTimeline.SCREEN_INTERACTIVE, null))
        // After screen-on the same app resuming is a new foreground session.
        assertEquals(listOf(Out.App(10, "a", "fg")), tl.feed(10, R, "a"))
    }

    @Test
    fun ignoredPackagesDoNotBreakTheTimeline() {
        val tl = AppTimeline(ignoredPackages = setOf("com.android.systemui"))
        tl.feed(1, R, "a")
        assertEquals(emptyList<Out>(), tl.feed(2, R, "com.android.systemui"))
        assertEquals("a", tl.foreground)
    }
}
