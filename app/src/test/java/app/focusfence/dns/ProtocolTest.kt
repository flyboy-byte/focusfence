package app.focusfence.dns

import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolTest {
    @Test
    fun theWindowIsTwoLevelsEachWay() {
        // App 7 (supports servers back to 5) against servers 4..9 that serve apps back to their level - 2.
        fun at(server: Int) = Protocol.check(7, 5, server, maxOf(1, server - 2))
        assertEquals(Protocol.Status.SERVER_TOO_OLD, at(4))
        assertEquals(Protocol.Status.OK, at(5))
        assertEquals(Protocol.Status.OK, at(7))
        assertEquals(Protocol.Status.OK, at(9))
        assertEquals(Protocol.Status.APP_TOO_OLD, at(10))
        assertEquals(Protocol.Status.UNKNOWN, Protocol.check(7, 5, null, null))
    }

    @Test
    fun oldestIsNeverBelowOne() {
        assertEquals(maxOf(1, Protocol.LEVEL - Protocol.WINDOW), Protocol.OLDEST)
    }
}
