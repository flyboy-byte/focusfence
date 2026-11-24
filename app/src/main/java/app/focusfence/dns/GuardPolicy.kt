package app.focusfence.dns

/**
 * Per-action friction (pure, unit-testable). Strict mode is the friction
 * system; this decides *how much* friction each loosening action carries, so
 * everyday actions (turning the VPN off) can stay a simple delay while the ones
 * the owner wants to commit to (unblocking an app) can require a partner's PIN.
 * Partner gating is therefore opt-in per action, never the default.
 *
 * A [Level] is the sole authority for its action while strict mode is on:
 * [Level.DELAY] uses the timed unlock window; the PIN levels prompt at the
 * moment of the action and do not open the global window, so unlocking a weak
 * action never grants a stronger one. When strict mode is off, nothing is
 * gated. See docs/specs/friction-tiers.md.
 */
object GuardPolicy {

    enum class Level { NONE, DELAY, OWN_PIN, PARTNER_PIN }

    /** Loosening actions that can be gated. [default] keeps partner gating opt-in. */
    enum class Action(val key: String, val label: String, val default: Level) {
        TURN_OFF_VPN("guard_vpn_off", "Turn off the VPN", Level.DELAY),
        DISABLE_LOGGING("guard_disable_logging", "Turn off activity logging", Level.DELAY),
        DISABLE_CAPTURE("guard_disable_capture", "Turn off screenshot / text capture", Level.DELAY),
        REMOVE_BLOCK_RULE("guard_remove_rule", "Remove a block rule", Level.DELAY),
        LOOSEN_BUNDLE("guard_loosen_bundle", "Loosen a filter bundle", Level.DELAY),
        REMOVE_ALLOWLIST("guard_remove_allow", "Remove an allowlisted site", Level.DELAY),
        UNBLOCK_APP("guard_unblock_app", "Unblock an app", Level.DELAY),
        EXCLUDE_APP("guard_exclude_app", "Exclude a browser/social/video app from the filter", Level.DELAY),
        DISABLE_STRICT("guard_disable_strict", "Turn off strict mode", Level.DELAY),
        REMOVE_PROTECTION("guard_remove_protection", "Turn off uninstall protection", Level.DELAY),
        REMOVE_PARTNER("guard_remove_partner", "Remove an accountability partner", Level.DELAY),
        QUIET_BLOCK_ALERTS("guard_quiet_block_alerts", "Stop telling partners about repeated blocked tries", Level.DELAY),
        /** Leave a group, stop filtering inside it, narrow who sees my report, or remove a member. */
        LEAVE_GROUP("guard_leave_group", "Leave or loosen a group", Level.DELAY);

        companion object {
            fun fromKey(key: String): Action? = values().firstOrNull { it.key == key }
        }
    }

    sealed class Requirement {
        /** Proceed immediately. */
        data object Allowed : Requirement()
        /** Needs the timed strict-unlock window (request, wait, act within the window). */
        data object TimedUnlock : Requirement()
        /** Needs a PIN now; [partner] true = the partner-held PIN, false = the owner PIN. */
        data class Pin(val partner: Boolean) : Requirement()
    }

    /**
     * What satisfying [action] at [level] requires. [timedWindowOpen] is whether
     * the strict unlock window is currently open (only relevant to DELAY).
     * [partnerPinSet]/[ownerPinSet] let a PIN level fall back to a timed unlock
     * rather than locking the owner out when the needed PIN was never set.
     */
    fun requirement(
        strictEnabled: Boolean,
        level: Level,
        timedWindowOpen: Boolean,
        ownerPinSet: Boolean = true,
        partnerPinSet: Boolean = true,
    ): Requirement {
        if (!strictEnabled || level == Level.NONE) return Requirement.Allowed
        return when (level) {
            Level.NONE -> Requirement.Allowed
            Level.DELAY -> if (timedWindowOpen) Requirement.Allowed else Requirement.TimedUnlock
            Level.OWN_PIN -> if (ownerPinSet) Requirement.Pin(partner = false) else Requirement.TimedUnlock
            Level.PARTNER_PIN -> if (partnerPinSet) Requirement.Pin(partner = true) else Requirement.TimedUnlock
        }
    }
}
