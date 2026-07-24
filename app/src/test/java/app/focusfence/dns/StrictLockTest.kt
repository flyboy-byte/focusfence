package app.focusfence.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictLockTest {

    @Test
    fun disabledIsAlwaysOff() {
        assertEquals(StrictLock.State.Off, StrictLock.state(false, 0, 1000))
        assertEquals(StrictLock.State.Off, StrictLock.state(false, 999_999, 1000))
        assertFalse(StrictLock.isLocked(StrictLock.State.Off))
    }

    @Test
    fun enabledWithoutRequestIsLocked() {
        val state = StrictLock.state(true, 0, 1000)
        assertEquals(StrictLock.State.Locked, state)
        assertTrue(StrictLock.isLocked(state))
    }

    @Test
    fun pendingUntilReadyThenUnlockedForWindow() {
        val readyAt = 100_000L
        val pending = StrictLock.state(true, readyAt, readyAt - 30_000)
        assertTrue(pending is StrictLock.State.Pending)
        assertEquals(30_000L, (pending as StrictLock.State.Pending).remainingMs)
        assertTrue(StrictLock.isLocked(pending))

        val unlocked = StrictLock.state(true, readyAt, readyAt + 1)
        assertTrue(unlocked is StrictLock.State.Unlocked)
        assertFalse(StrictLock.isLocked(unlocked))

        val expired = StrictLock.state(true, readyAt, readyAt + StrictLock.UNLOCK_WINDOW_MS)
        assertEquals(StrictLock.State.Locked, expired)
    }
}
