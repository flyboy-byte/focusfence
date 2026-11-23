package app.focusfence.dns

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlwaysOnNudgeTest {
    private val h = 3_600_000L

    @Test fun nudgesWhenOffAndNeverNudged() = assertTrue(AlwaysOnNudge.shouldNudge("off", false, 0, 100 * h))

    @Test fun staysQuietWhenOnLockdownOrUnknown() {
        for (s in listOf("on", "lockdown", "unknown")) assertFalse(s, AlwaysOnNudge.shouldNudge(s, false, 0, 100 * h))
    }

    @Test fun staysQuietInManagedMode() = assertFalse(AlwaysOnNudge.shouldNudge("off", true, 0, 100 * h))

    @Test fun atMostOncePerGap() {
        assertFalse(AlwaysOnNudge.shouldNudge("off", false, 100 * h, 105 * h))
        assertTrue(AlwaysOnNudge.shouldNudge("off", false, 100 * h, 112 * h))
    }

    @Test fun aClockThatWentBackwardsDoesNotSilenceItForever() = assertTrue(AlwaysOnNudge.shouldNudge("off", false, 200 * h, 100 * h))
}
