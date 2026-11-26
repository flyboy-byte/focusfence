package app.focusfence.dns

import app.focusfence.dns.InviteLink.Parsed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InviteLinkTest {
    private val s = "https://ff.example.com"
    private val secret = "aB3_dE5-gH7iJ9kL1mN3oP5qR7sT9uV"

    @Test
    fun theQrCodesLinkAndTheEmailedLinkBothJoin() {
        assertEquals(Parsed.Join(s, code = "K7Q4M2XD"), InviteLink.parse("$s/c/K7Q4M2XD"))
        assertEquals(Parsed.Join(s, link = secret), InviteLink.parse("Open the invite: $s/j/$secret."))
        assertEquals(Parsed.Join("https://ff.test:8443", code = "K7Q4M2XD"), InviteLink.parse("https://ff.test:8443/c/k7q4m2xd"))
        assertNull("too short to be an emailed secret", InviteLink.parse("$s/j/abcdefgh"))
    }

    @Test
    fun theInvitePagesOpenInTheAppButton() {
        val enc = java.net.URLEncoder.encode(s, "UTF-8")
        assertEquals(Parsed.Join(s, code = "K7Q4M2XD"), InviteLink.fromOpened("focusfence://join?server=$enc&code=K7Q4M2XD"))
        assertEquals(Parsed.Join(s, link = secret), InviteLink.parse("focusfence://join?server=$enc&link=$secret"))
        assertNull("http is never a server", InviteLink.fromOpened("focusfence://join?server=http%3A%2F%2Fevil&code=K7Q4M2XD"))
        assertNull(InviteLink.fromOpened("focusfence://join?server=$enc&code=abc"))
        assertNull(InviteLink.fromOpened("focusfence://join?server=$enc&link=bad%20secret%20with%20spaces%20x"))
        assertNull("the old invite button is gone", InviteLink.fromOpened("focusfence://invite?link=x"))
    }

    @Test
    fun codesAreReadTheWayPeopleTypeThem() {
        assertEquals("K7Q4M2XD", InviteLink.normalizeCode(" k7q4-m2xd "))
        assertEquals("10100000", InviteLink.normalizeCode("IOLO OOOO"))
        assertEquals("K7Q4-M2XD", InviteLink.showCode("k7q4m2xd"))
    }

    @Test
    fun aServerAsTyped() {
        assertEquals(s, InviteLink.server("ff.example.com/"))
        assertEquals(s, InviteLink.server(" https://ff.example.com "))
        assertNull(InviteLink.server("http://ff.example.com"))
        assertNull(InviteLink.server("not a server"))
    }

    @Test
    fun theAdminPagesServerMessageStillConnectsThisPhone() {
        val msg = "Focus Fence\nServer: https://ff.example.com\nEnrollment code: ABCD-EFGH\n"
        assertEquals(Parsed.Server(s, "ABCD-EFGH"), InviteLink.parse(msg))
        assertNull(InviteLink.parse("hello"))
    }
}
