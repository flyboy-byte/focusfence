package app.focusfence.dns

import org.junit.Assert.assertEquals
import org.junit.Test

class LeaveServerTest {
    @Test
    fun partnersAreToldAtOnceWhenThePhoneLeaves() {
        val f = LogEvents.service(1, "partner", "server_left", "moving to another server")
        assertEquals("This phone is leaving the server (moving to another server). Partners here won't hear from it after this.", Digest.urgentText(f))
        assertEquals("This phone is leaving the server. Partners here won't hear from it after this.", Digest.urgentText(LogEvents.service(1, "partner", "server_left", null)))
    }

    @Test
    fun leavingIsGuardedLikeOtherLoosening() {
        assertEquals(GuardPolicy.Level.DELAY, GuardPolicy.Action.LEAVE_SERVER.default)
    }
}
