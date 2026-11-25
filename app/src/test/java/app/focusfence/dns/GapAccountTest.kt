package app.focusfence.dns

import org.junit.Assert.assertEquals
import org.junit.Test

class GapAccountTest {
    private val m = 60_000L
    private fun net(t: Long, tr: String, ok: Boolean) = LogEvents.net(t, tr, ok, false, false, 0, 0, null)
    private fun svc(seq: Long, t: Long, comp: String, state: String, detail: String = "") =
        LogEvents.service(t, comp, state, detail) + ("seq" to seq)

    @Test
    fun aDeadZoneWithTheFilterOnIsSaidPlainly() {
        val text = GapAccount.describe(emptyList(), listOf(net(10 * m, "none", false), net(50 * m, "cell", false)), 0, 120 * m)
        assertEquals("While it was quiet: the phone had no internet; protection stayed on the whole time.", text)
    }

    @Test
    fun protectionOffAndARestartAreNamed() {
        val activity = listOf(
            svc(1, 5 * m, "vpn", "stop"), svc(2, 30 * m, "vpn", "start"),
            svc(3, 40 * m, "device", "rebooted", "boot=12"), svc(7, 41 * m, "vpn", "start"),
        )
        val text = GapAccount.describe(activity, listOf(net(10 * m, "wifi", true), net(60 * m, "none", false)), 0, 90 * m)
        assertEquals(
            "While it was quiet: the phone had no internet for part of it; it was switched off or restarted; " +
                "protection was OFF for 25 minutes; 3 records are missing from its log.", text,
        )
    }

    @Test
    fun onlineTheWholeTimeIsNotExcused() {
        assertEquals(
            "While it was quiet: the phone had internet, but its check-ins didn't arrive; protection stayed on the whole time.",
            GapAccount.describe(emptyList(), listOf(net(10 * m, "wifi", true)), 0, 90 * m),
        )
    }
}
