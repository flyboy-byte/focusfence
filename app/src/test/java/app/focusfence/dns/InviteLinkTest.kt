package app.focusfence.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InviteLinkTest {
    private val secret = "AbCdEfGhIjKlMnOpQrStUvWxYz012345"

    @Test
    fun aPartnerInviteInAChatMessage() {
        val link = InviteLink.partnerLink("https://ff.example.com/i/$secret", "rk_1-2", "ABCD-EFGH-JKMN-PQRS-TVWX-YZ01-2")
        val p = InviteLink.parse("Be my partner on Focus Fence: $link.\nWorks once.") as InviteLink.Parsed.Partner
        assertEquals("https://ff.example.com", p.server)
        assertEquals(secret, p.secret)
        assertEquals("rk_1-2", p.reportKey)
        assertEquals("ABCD-EFGH-JKMN-PQRS-TVWX-YZ01-2", p.pairingKey)
    }

    @Test
    fun aLinkWithoutThePairingKeyStillParses() {
        val p = InviteLink.parse("https://ff.example.com:8443/i/$secret") as InviteLink.Parsed.Partner
        assertEquals("https://ff.example.com:8443", p.server)
        assertNull(p.pairingKey)
    }

    @Test
    fun theAdminPagePeopleMessage() {
        val msg = "Hi Sam, here's how to join my Focus Fence server:\n1. Install Focus Fence AC: https://github.com/x/y/releases/latest\n" +
            "2. In the app, open Partners, tap Join with an invite and paste this whole message.\nServer: https://ff.example.com\nEnrollment code: K7QX-2MPD (works once)"
        val s = InviteLink.parse(msg) as InviteLink.Parsed.Server
        assertEquals("https://ff.example.com", s.server)
        assertEquals("K7QX-2MPD", s.code)
    }

    @Test
    fun anythingElseIsNotAnInvite() {
        assertNull(InviteLink.parse("hello"))
        assertNull(InviteLink.parse("http://ff.example.com/i/$secret")) // cleartext: the app can't use it anyway
        assertNull(InviteLink.parse("Server: https://ff.example.com")) // no code
    }

    @Test
    fun alertsComeFromTheReportLinksServer() {
        assertEquals("https://ff.example.com" to "/p/$secret/alerts", InviteLink.alertsTarget("https://ff.example.com/p/$secret#k=rk_1"))
        assertEquals("https://ff.example.com:8443" to "/p/$secret/alerts", InviteLink.alertsTarget("https://ff.example.com:8443/p/$secret"))
        assertNull(InviteLink.alertsTarget(""))
        assertNull(InviteLink.alertsTarget("http://ff.example.com/p/$secret"))
        assertNull(InviteLink.alertsTarget("https://ff.example.com/p/short"))
        assertNull(InviteLink.alertsTarget("https://ff.example.com/p/$secret/../../admin"))
        assertEquals("https://ff.example.com" to "/p/$secret/leave", InviteLink.leaveTarget("https://ff.example.com/p/$secret#k=rk_1"))
        assertNull(InviteLink.leaveTarget("http://ff.example.com/p/$secret"))
    }

    @Test
    fun anInviteOpensFromTheOpenInTheAppButton() {
        val secret = "s".repeat(32)
        val invite = "https://ff.example.com/i/$secret#k=${"k".repeat(43)}&p=pair_1"
        assertNull("no server's links open the app by themselves", InviteLink.fromOpened(invite))
        val button = "focusfence://invite?link=" + java.net.URLEncoder.encode(invite, "UTF-8")
        assertEquals(invite, InviteLink.fromOpened(button))
        assertNull(InviteLink.fromOpened("focusfence://invite?link=" + java.net.URLEncoder.encode("https://evil.example/whatever", "UTF-8")))
        assertNull(InviteLink.fromOpened("focusfence://elsewhere?link=x"))
        assertNull(InviteLink.fromOpened("https://ff.example.com/p/$secret"))
        assertNull(InviteLink.fromOpened("javascript:alert(1)"))
    }
}
