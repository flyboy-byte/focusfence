package app.focusfence.dns

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestartGraceTest {
    @Test
    fun theGapOfARestartIsNotAStateChange() {
        assertTrue(RestartGrace.hidesState(running = false, until = 6_000, now = 1_000))
    }

    @Test
    fun aRestartThatNeverComesBackIsRecorded() {
        assertFalse(RestartGrace.hidesState(running = false, until = 6_000, now = 6_000))
    }

    @Test
    fun aRunningFilterAndAnOrdinaryStopAreAlwaysRecorded() {
        assertFalse(RestartGrace.hidesState(running = true, until = 6_000, now = 1_000))
        assertFalse(RestartGrace.hidesState(running = false, until = 0, now = 1_000))
    }
}
