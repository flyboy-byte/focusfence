package app.focusfence.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureStatusTest {

    private val base = CaptureStatus.Inputs(
        screenshotsOn = true, supported = true, accessibilityOn = true, watchAll = false, watchedApps = 2,
        aiOn = true, partnerKeySet = true, lastShotAt = 0, lastPkg = null, lastLabel = null,
        lastScore = null, lastFlagged = false, taken = 0, flagged = 0, failed = 0, aiLoadFailed = false,
    )

    private fun d(i: CaptureStatus.Inputs) = CaptureStatus.describe(i, 1_000_000) { "2 min ago" }

    @Test
    fun blockersInFixOrder() {
        assertEquals(CaptureStatus.Level.BLOCKED, d(base.copy(supported = false)).level)
        assertEquals(CaptureStatus.Level.OFF, d(base.copy(screenshotsOn = false)).level)
        assertTrue(d(base.copy(accessibilityOn = false)).text.contains("accessibility"))
        assertTrue(d(base.copy(watchedApps = 0)).text.contains("choose at least one app"))
        // Accessibility is reported before the app list.
        assertTrue(d(base.copy(accessibilityOn = false, watchedApps = 0)).text.contains("accessibility"))
    }

    @Test
    fun allAppsModeNeedsNoChosenApps() {
        assertEquals(CaptureStatus.Level.WAITING, d(base.copy(watchAll = true, watchedApps = 0)).level)
    }

    @Test
    fun readyButNothingYet() {
        val s = d(base)
        assertEquals(CaptureStatus.Level.WAITING, s.level)
        assertTrue(s.text.contains("Nothing captured yet"))
    }

    @Test
    fun showsLastShotAndVerdict() {
        val s = d(base.copy(lastShotAt = 1, lastPkg = "com.android.chrome", lastLabel = "neutral", lastScore = 0.97, taken = 5))
        assertEquals(CaptureStatus.Level.WORKING, s.level)
        assertTrue(s.text, s.text.contains("2 min ago in com.android.chrome"))
        assertTrue(s.text, s.text.contains("AI: neutral (97%)"))
        assertTrue(s.text, s.text.contains("5 taken, 0 flagged"))
    }

    @Test
    fun aiOffOrBrokenIsSaid() {
        assertTrue(d(base.copy(lastShotAt = 1, aiOn = false, taken = 1)).text.contains("AI flagging is off"))
        val broken = d(base.copy(lastShotAt = 1, aiLoadFailed = true, taken = 1))
        assertEquals(CaptureStatus.Level.WARNING, broken.level)
        assertTrue(broken.text.contains("failed to load"))
    }

    @Test
    fun flaggedWithoutPartnerKeyWarns() {
        val s = d(base.copy(lastShotAt = 1, lastLabel = "porn", lastScore = 0.9, lastFlagged = true, taken = 3, flagged = 1, partnerKeySet = false))
        assertEquals(CaptureStatus.Level.WARNING, s.level)
        assertTrue(s.text.contains("— flagged"))
        assertTrue(s.text.contains("aren't being kept"))
    }

    @Test
    fun failingBeforeAnySuccessIsNotWaiting() {
        val s = d(base.copy(failed = 3))
        assertEquals(CaptureStatus.Level.WARNING, s.level)
        assertTrue(s.text.contains("failing (3 attempts)"))
    }

    @Test
    fun allFailingWarns() {
        assertEquals(CaptureStatus.Level.WARNING, d(base.copy(lastShotAt = 1, taken = 0, failed = 4)).level)
    }
}
