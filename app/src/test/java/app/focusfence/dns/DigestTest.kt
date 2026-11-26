package app.focusfence.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DigestTest {

    private fun rec(seq: Long, t: Long, vararg kv: Pair<String, Any?>): Map<String, Any?> =
        linkedMapOf<String, Any?>("seq" to seq, "t" to t).apply { putAll(kv) }

    @Test
    fun countsLookupsBlocksAndCategories() {
        val d = Digest(0)
        d.add(rec(1, 10, "type" to "dns", "host" to "a.com", "decision" to "allowed"))
        d.add(rec(2, 11, "type" to "dns", "host" to "ads.x", "decision" to "blocked", "reason" to "ads"))
        d.add(rec(3, 12, "type" to "dns", "host" to "bet.y", "decision" to "allowed", "reason" to "cat", "cat" to "gambling"))
        d.add(rec(4, 13, "type" to "dns", "host" to "bet.y", "decision" to "blocked", "reason" to "cat", "cat" to "gambling"))
        val m = d.build(100)
        assertEquals(4L, m["lookups"])
        assertEquals(2L, m["blocked"])
        assertEquals(listOf("ads" to 1L, "cat" to 1L), Digest.parsePairs(m["blocked_by"] as String))
        assertEquals(listOf("gambling" to 2L), Digest.parsePairs(m["cats"] as String))
        assertEquals(listOf("bet.y [gambling]" to 2L), Digest.parsePairs(m["cat_hosts"] as String))
    }

    @Test
    fun seqGapsAreReportedAsMissing() {
        val d = Digest(0)
        d.add(rec(5, 10, "type" to "screen", "state" to "on"))
        d.add(rec(9, 11, "type" to "screen", "state" to "off"))
        val m = d.build(100)
        assertEquals(3L, m["missing"])
        assertEquals(5L, m["seq_first"])
        assertEquals(9L, m["seq_last"])
    }

    @Test
    fun offTimeFromVpnStopToStart() {
        val d = Digest(0)
        d.add(rec(1, 60_000, "type" to "svc", "comp" to "vpn", "state" to "stop", "detail" to "user"))
        d.add(rec(2, 6 * 60_000, "type" to "svc", "comp" to "vpn", "state" to "start"))
        // A restart is not time off.
        d.add(rec(3, 7 * 60_000, "type" to "svc", "comp" to "vpn", "state" to "stop", "detail" to "restart"))
        d.add(rec(4, 7 * 60_000 + 500, "type" to "svc", "comp" to "vpn", "state" to "start"))
        val m = d.build(10 * 60_000)
        assertEquals(5L, m["off_min"])
        assertEquals("Filter turned off", (m["events"] as String).lines().single())
    }

    @Test
    fun stillOffCountsToTheEnd() {
        val d = Digest(0)
        d.add(rec(1, 0, "type" to "svc", "comp" to "vpn", "state" to "revoked"))
        assertEquals(30L, d.build(30 * 60_000)["off_min"])
    }

    @Test
    fun appMinutesFromForegroundSpans() {
        val d = Digest(0)
        d.add(rec(1, 0, "type" to "app", "pkg" to "com.x", "state" to "fg"))
        d.add(rec(2, 10 * 60_000, "type" to "app", "pkg" to "com.x", "state" to "bg"))
        assertEquals(listOf("com.x" to 10L), Digest.parsePairs(d.build(20 * 60_000)["apps_min"] as String))
    }

    @Test
    fun recordsBeforeWindowIgnored() {
        val d = Digest(1_000)
        d.add(rec(1, 999, "type" to "dns", "host" to "a", "decision" to "allowed"))
        assertEquals(0L, d.build(2_000)["lookups"])
    }

    @Test
    fun routineChangesStayInTheReportButDoNotMail() {
        val update = mapOf<String, Any?>("type" to "svc", "comp" to "app", "state" to "updated", "detail" to "from=2.11.0-accountable,to=2.12.0-accountable")
        assertEquals("App updated (from=2.11.0-accountable,to=2.12.0-accountable)", Digest.alertText(update))
        assertNull(Digest.urgentText(update))
        val downgrade = mapOf<String, Any?>("type" to "svc", "comp" to "app", "state" to "updated", "detail" to "from=2.12.0-accountable,to=2.9.2-accountable")
        assertEquals("App updated (from=2.12.0-accountable,to=2.9.2-accountable)", Digest.urgentText(downgrade))
        assertNull(Digest.urgentText(mapOf("type" to "svc", "comp" to "vpn", "state" to "health", "detail" to "degraded,fail_pct=25,was=ok")))
        assertEquals("Filter problem: bypassed", Digest.urgentText(mapOf("type" to "svc", "comp" to "vpn", "state" to "health", "detail" to "bypassed,was=ok")))
        assertEquals("Filter turned off", Digest.urgentText(mapOf("type" to "svc", "comp" to "vpn", "state" to "stop", "detail" to "user")))
        assertEquals(-1, Digest.compareVersions("2.9.2", "2.10.0"))
    }

    @Test
    fun alertTextRules() {
        // An app update briefly drops the tunnel: vpn_metered flips true -> no_vpn -> true. Not an alert.
        assertNull(Digest.alertText(mapOf("type" to "integ", "key" to "vpn_metered", "value" to "no_vpn", "was" to "true")))
        assertNull(Digest.alertText(mapOf("type" to "integ", "key" to "vpn_metered", "value" to "true", "was" to "no_vpn")))
        assertNull(Digest.alertText(mapOf("type" to "integ", "key" to "vpn", "value" to "on", "was" to null)))
        assertEquals(
            "a11y_enabled changed: true → false",
            Digest.alertText(mapOf("type" to "integ", "key" to "a11y_enabled", "value" to "false", "was" to "true")),
        )
        assertEquals(
            "Filter problem: lockdown",
            Digest.alertText(mapOf("type" to "svc", "comp" to "vpn", "state" to "health", "detail" to "lockdown,was=ok")),
        )
        assertNull(Digest.alertText(mapOf("type" to "svc", "comp" to "vpn", "state" to "health", "detail" to "ok,was=degraded")))
        assertEquals("Shared a blurred screenshot with you (porn 71%). Open the report to see it.", Digest.alertText(mapOf("type" to "svc", "comp" to "partner", "state" to "shot_shared", "detail" to "porn 71%")))
        assertEquals("Activity log cleared", Digest.alertText(mapOf("type" to "svc", "comp" to "log", "state" to "cleared")))
        assertEquals("Strict mode: unlock requested (wait_min=10)", Digest.alertText(mapOf("type" to "svc", "comp" to "strict", "state" to "unlock_requested", "detail" to "wait_min=10")))
        assertEquals("Strict mode: unlocked with a code (partner_code)", Digest.alertText(mapOf("type" to "svc", "comp" to "strict", "state" to "unlocked", "detail" to "partner_code")))
        assertEquals("Strict mode turned off", Digest.alertText(mapOf("type" to "svc", "comp" to "strict", "state" to "disabled")))
        assertEquals("Strict mode: changed with a partner's OK: loosen a filter bundle",
            Digest.alertText(mapOf("type" to "svc", "comp" to "strict", "state" to "partner_approval_used", "detail" to "loosen_bundle")))
        // The server tells partners about the ask and the answer itself; the phone's own records of them stay quiet.
        assertEquals(null, Digest.alertText(mapOf("type" to "svc", "comp" to "strict", "state" to "partner_approved", "detail" to "loosen_bundle by=Sam")))
        assertNull(Digest.alertText(mapOf("type" to "svc", "comp" to "strict", "state" to "relocked")))
        assertNull(Digest.alertText(mapOf("type" to "svc", "comp" to "vpn", "state" to "stop", "detail" to "restart")))
    }

    @Test
    fun messageSurvivesLogJsonRoundTrip() {
        val d = Digest(0)
        d.add(rec(1, 10, "type" to "dns", "host" to "we\"ird\thost", "decision" to "blocked", "reason" to "rule"))
        val m = d.build(100)
        val back = LogJson.decode(LogJson.encode(m))!!
        assertEquals("digest", back["kind"])
        assertEquals(listOf("we\"ird host" to 1L), Digest.parsePairs(back["top_blocked"] as String))
        val text = Digest.render(back) { it.toString() }
        assertTrue(text, text.contains("Protection: on the whole time"))
    }

    @Test
    fun browserAndOnScreenCategoryHitsCount() {
        val d = Digest(0)
        d.add(rec(1, 10, "type" to "url", "pkg" to "b", "host" to "bet.example", "path" to "/", "cat" to "gambling"))
        d.add(rec(2, 11, "type" to "text", "pkg" to "c", "text" to "…", "hits" to "x.example:adult,scam;y.example:adult", "cat" to "adult,scam"))
        val m = d.build(100)
        val cats = Digest.parsePairs(m["cats"] as String).toMap()
        assertEquals(1L, cats["gambling"])
        assertEquals(2L, cats["adult"])
        assertEquals(1L, cats["scam"])
        val hosts = Digest.parsePairs(m["cat_hosts"] as String).map { it.first }
        assertTrue(hosts.toString(), "bet.example [gambling] (browser)" in hosts)
        assertTrue(hosts.toString(), "x.example [adult,scam] (on screen)" in hosts)
    }

    @Test
    fun networkBlipsAreOneCountNotALineEach() {
        val d = Digest(0)
        var seq = 1L
        repeat(5) {
            d.add(rec(seq++, 10L * seq, "type" to "svc", "comp" to "vpn", "state" to "health", "detail" to "degraded,fail_pct=27,was=ok"))
            d.add(rec(seq++, 10L * seq, "type" to "svc", "comp" to "vpn", "state" to "health", "detail" to "ok,fail_pct=8,was=degraded"))
        }
        d.add(rec(seq++, 10L * seq, "type" to "svc", "comp" to "vpn", "state" to "health", "detail" to "upstream_down,was=ok"))
        d.add(rec(seq++, 10L * seq, "type" to "svc", "comp" to "vpn", "state" to "health", "detail" to "bypassed,was=ok"))
        val m = d.build(10_000)
        assertEquals(6L, m["net_trouble"])
        assertEquals("Filter problem: bypassed", m["events"])
        assertTrue(Digest.render(m) { it.toString() }.contains("Bad network 6 times"))
    }

    @Test
    fun repeatedBlockedTriesAlertPartnersByCategoryOnly() {
        val f = mapOf<String, Any?>("type" to "svc", "comp" to "block", "state" to "repeated", "detail" to "cat=adult,n=3")
        assertEquals("Tried to open blocked Adult sites 3 times in 10 minutes", Digest.alertText(f))
        assertEquals(Digest.alertText(f), Digest.urgentText(f))
    }

    @Test
    fun turningOffRepeatedTryAlertsIsItselfAnAlert() {
        val off = mapOf<String, Any?>("type" to "svc", "comp" to "block", "state" to "partner_alerts_off")
        assertEquals("Turned off partner alerts for repeated blocked tries", Digest.urgentText(off))
        assertNull(Digest.alertText(mapOf("type" to "svc", "comp" to "admin", "state" to "legacy_cleared")))
    }

    @org.junit.Test
    fun uninstallProtectionOffIsUrgentAndSaysWhatMayFollow() {
        val requested = mapOf("type" to "svc", "comp" to "admin", "state" to "admin_disable_requested")
        assertEquals("Uninstall protection is being turned off. Focus Fence may be uninstalled next", Digest.urgentText(requested))
        assertEquals("Uninstall protection turned off", Digest.urgentText(mapOf("type" to "svc", "comp" to "admin", "state" to "admin_disabled")))
    }
}
