package app.focusfence.server

import kotlin.test.Test
import kotlin.test.assertEquals

class ClientKeyTest {
    @Test
    fun proxyHeaderBelievedOnlyFromLoopback() {
        assertEquals("203.0.113.9", clientKey("127.0.0.1", "203.0.113.9"))
        assertEquals("203.0.113.9", clientKey("::1", "203.0.113.9"))
        // a direct caller cannot pick its own bucket by forging the header
        assertEquals("198.51.100.4", clientKey("198.51.100.4", "203.0.113.9"))
    }

    @Test
    fun rightmostEntryWinsSoClientPrefixIsIgnored() {
        assertEquals("203.0.113.9", clientKey("127.0.0.1", "1.2.3.4, 203.0.113.9"))
    }

    @Test
    fun missingOrJunkHeaderFallsBackToPeer() {
        assertEquals("127.0.0.1", clientKey("127.0.0.1", null))
        assertEquals("127.0.0.1", clientKey("127.0.0.1", ""))
        assertEquals("127.0.0.1", clientKey("127.0.0.1", "x".repeat(100)))
    }
}
