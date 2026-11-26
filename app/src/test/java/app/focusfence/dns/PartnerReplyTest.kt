package app.focusfence.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PartnerReplyTest {
    @Test
    fun repliesAreSetWordsOnly() {
        assertEquals("Sam: Let's talk", PartnerReply.line("Sam", PartnerReply.Reply.TALK))
        assertEquals("Your partner: Proud of you", PartnerReply.line("", PartnerReply.Reply.PROUD))
        assertEquals(PartnerReply.Reply.CALL, PartnerReply.Reply.fromKey("call"))
        assertNull(PartnerReply.Reply.fromKey("<script>"))
    }
}
