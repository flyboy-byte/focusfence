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
    fun oldestIsNeverBelowTheFloor() {
        assertEquals(maxOf(Protocol.FLOOR, Protocol.LEVEL - Protocol.WINDOW), Protocol.OLDEST)
        // Plan 13's clean cut: level 1 (pairing mailboxes, keyed invite links) is never served or spoken again.
        assertEquals(2, Protocol.OLDEST)
        // This app against a level-1 server (1.3.0 and older): the server is the side to update.
        assertEquals(Protocol.Status.SERVER_TOO_OLD, Protocol.check(Protocol.LEVEL, Protocol.OLDEST, 1, 1))
    }
}
