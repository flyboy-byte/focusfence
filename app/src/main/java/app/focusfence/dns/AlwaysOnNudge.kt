package app.focusfence.dns

/**
 * When to nudge the owner to turn Always-on VPN back on. Android silently drops the setting when the VPN is
 * toggled from the system switch (seen in the 2026-11-20 export), and without it protection does not come back
 * after a reboot. The integrity trail already records the change; this only reminds the person at the phone.
 *
 * Pure so the timing is tested on the JVM. A reminder, not a nag: at most one per [GAP_MS].
 */
object AlwaysOnNudge {

    const val GAP_MS = 12 * 3_600_000L

    /**
     * @param state `DnsVpnService.alwaysOnState`: "on", "lockdown", "off" or "unknown" (before Android 10 / not running)
     * @param deviceOwner managed mode sets Always-on itself, so there is nothing for the user to tap
     */
    fun shouldNudge(state: String, deviceOwner: Boolean, lastNudgeMs: Long, now: Long): Boolean =
        state == "off" && !deviceOwner && (lastNudgeMs <= 0 || now < lastNudgeMs || now - lastNudgeMs >= GAP_MS)
}
