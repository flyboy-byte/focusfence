package app.focusfence.dns

import app.focusfence.dns.Upstreams.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpstreamsTest {

    @Test
    fun parsesServerForms() {
        assertEquals(Entry.Udp("192.168.1.10", 53), Upstreams.parseLine("192.168.1.10"))
        assertEquals(Entry.Udp("192.168.1.10", 5353), Upstreams.parseLine(" 192.168.1.10:5353 "))
        assertEquals(Entry.Udp("2001:db8::1", 53), Upstreams.parseLine("2001:db8::1"))
        assertEquals(Entry.Udp("2001:db8::1", 853), Upstreams.parseLine("[2001:db8::1]:853"))
        assertEquals(Entry.Udp("dns.example.com", 53), Upstreams.parseLine("dns.example.com"))
        assertEquals(Entry.Doh("https://dns.example.com/dns-query"), Upstreams.parseLine("https://dns.example.com/dns-query"))
    }

    @Test
    fun rejectsJunk() {
        for (bad in listOf("", "# comment", "http://x.com/dns-query", "tls://1.1.1.1", "300.1.1.1", "1.2.3.4:0",
            "1.2.3.4:99999", "localhost", "not a host", "[zz::1]", "https://", "-bad.com")) {
            assertNull(bad, Upstreams.parseLine(bad))
        }
    }

    @Test
    fun parseCollectsBadLinesAndDedupes() {
        val (ok, bad) = Upstreams.parse("1.1.1.1\n\n# mine\n1.1.1.1\nnope nope\nhttps://d.example/dq")
        assertEquals(listOf(Entry.Udp("1.1.1.1", 53), Entry.Doh("https://d.example/dq")), ok)
        assertEquals(listOf("nope nope"), bad)
    }

    @Test
    fun renderRoundTrips() {
        val text = "10.0.0.2\n10.0.0.3:5353\n[2001:db8::1]:853\n2001:db8::2\nhttps://d.example/dq"
        val (ok, _) = Upstreams.parse(text)
        assertEquals(ok, Upstreams.parse(Upstreams.render(ok)).first)
    }

    private val net = listOf<Entry>(Entry.Udp("192.168.1.1", 53))

    @Test
    fun autoIsNetworkThenPublic() {
        val o = Upstreams.order(Upstreams.DEFAULT, net, null)
        assertEquals(net + Upstreams.PUBLIC_FALLBACK, o)
    }

    @Test
    fun customFirstThenFallback() {
        val mine = Entry.Udp("10.0.0.2", 53)
        val cfg = Upstreams.Config(Upstreams.Mode.CUSTOM, null, listOf(mine), fallback = true)
        val o = Upstreams.order(cfg, net, null)
        assertEquals(mine, o.first())
        assertEquals(net + Upstreams.PUBLIC_FALLBACK, o.drop(1))
    }

    @Test
    fun workingFallbackNeverDisplacesChosenServer() {
        val mine = Entry.Udp("10.0.0.2", 53)
        val cfg = Upstreams.Config(Upstreams.Mode.CUSTOM, null, listOf(mine), fallback = true)
        // Last success came from the network resolver, but the owner's server is still tried first.
        val o = Upstreams.order(cfg, net, net[0].key)
        assertEquals(mine, o[0])
        assertEquals(net[0], o[1])
    }

    @Test
    fun noFallbackMeansOnlyChosen() {
        val cfg = Upstreams.Config(Upstreams.Mode.PRESET, "quad9", emptyList(), fallback = false)
        val o = Upstreams.order(cfg, net, null)
        assertEquals(Upstreams.PRESETS.first { it.id == "quad9" }.entries, o)
    }

    @Test
    fun emptyOrUnknownChoiceFallsBackToAuto() {
        val emptyCustom = Upstreams.Config(Upstreams.Mode.CUSTOM, null, emptyList(), fallback = false)
        assertEquals(net + Upstreams.PUBLIC_FALLBACK, Upstreams.order(emptyCustom, net, null))
        val unknown = Upstreams.Config(Upstreams.Mode.PRESET, "gone", emptyList(), fallback = false)
        assertEquals(net + Upstreams.PUBLIC_FALLBACK, Upstreams.order(unknown, net, null))
    }

    @Test
    fun prefsRoundTripAndDescribe() {
        val c = Upstreams.fromPrefs("CUSTOM", null, "10.0.0.2\nbad line", true)
        assertEquals(listOf<Entry>(Entry.Udp("10.0.0.2", 53)), c.custom)
        assertTrue(Upstreams.describe(c).contains("10.0.0.2"))
        assertEquals(Upstreams.Mode.AUTO, Upstreams.fromPrefs("garbage", null, null, true).mode)
    }
}
