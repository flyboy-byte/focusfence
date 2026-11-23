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

class TrustedProxiesTest {
    @Test
    fun defaultIsLoopbackOnly() {
        val t = TrustedProxies.parse(null)
        assertEquals(true, t("127.0.0.1"))
        assertEquals(false, t("172.18.0.3"))
    }

    @Test
    fun cidrRangesAndSingleAddressesAreHonoured() {
        val t = TrustedProxies.parse("172.16.0.0/12, 10.1.2.3 ,junk")
        assertEquals(true, t("172.18.0.3"))
        assertEquals(true, t("172.31.255.255"))
        assertEquals(false, t("172.32.0.1"))
        assertEquals(true, t("10.1.2.3"))
        assertEquals(false, t("10.1.2.4"))
        assertEquals(true, t("127.0.0.1"), "loopback is always trusted")
        assertEquals(false, t("203.0.113.9"))
        assertEquals(false, t("not an address"))
    }

    @Test
    fun aProxyInTheRangeLetsTheForwardedAddressChooseTheBucket() {
        val t = TrustedProxies.parse("172.16.0.0/12")
        assertEquals("203.0.113.9", clientKey("172.18.0.3", "203.0.113.9", t))
        assertEquals("198.51.100.4", clientKey("198.51.100.4", "203.0.113.9", t), "outside the range the header is ignored")
    }
}
