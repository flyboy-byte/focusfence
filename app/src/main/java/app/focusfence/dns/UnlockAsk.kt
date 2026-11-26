package app.focusfence.dns

/**
 * Plan 12 §3: instead of waiting out a strict-mode lock, the owner asks a partner, and any one partner can approve or
 * deny from their report page. Compiled by app and server (like [Protocol]), so both read the same rules and words.
 *
 * - An approval covers the one [GuardPolicy.Action] that was asked for, for [GRANT_MS] (Logan: "what was asked …
 *   halfway between, the whole app page"; the actions are already page-sized). Nothing else loosens.
 * - A deny ends the request. The ordinary wait keeps working, so a partner can never lock the owner out.
 * - A request nobody answers lapses after [LAPSE_MS].
 *
 * Trust: the server relays the answer. A self-hosted operator who is also the owner could forge one, the same trust as
 * the partner code, whose seed also sits on the owner's phone. Written down, not built around.
 */
object UnlockAsk {
    const val LAPSE_MS: Long = 15 * 60_000L
    const val GRANT_MS: Long = StrictLock.UNLOCK_WINDOW_MS

    const val PENDING = "pending"
    const val APPROVED = "approved"
    const val DENIED = "denied"
    const val LAPSED = "lapsed"

    /** What the request is as of [now]: a pending one past [LAPSE_MS] has lapsed. */
    fun state(stored: String, askedMs: Long, now: Long): String =
        if (stored == PENDING && now - askedMs > LAPSE_MS) LAPSED else stored

    fun open(stored: String, askedMs: Long, now: Long): Boolean = state(stored, askedMs, now) == PENDING

    /** Whether an approval of [grantedKey], decided at [decidedMs], lets [action] through at [now]. */
    fun allows(grantedKey: String?, decidedMs: Long, action: GuardPolicy.Action, now: Long): Boolean =
        grantedKey == action.key && decidedMs > 0 && now >= decidedMs && now - decidedMs < GRANT_MS

    /** "turn off the VPN": the action's label, for the middle of a sentence. */
    fun what(action: GuardPolicy.Action): String = action.label.replaceFirstChar { it.lowercase() }

    /** What partners are told when the owner asks. */
    fun askLine(action: GuardPolicy.Action): String =
        "The phone's owner asks for your OK to ${what(action)}. Approve or deny it on the report page; the request " +
            "lapses in ${LAPSE_MS / 60_000} minutes. If nobody answers, they can still wait ${StrictLock.UNLOCK_DELAY_MS / 60_000} minutes and do it."

    /** What everyone is told once a partner answers. */
    fun answerLine(by: String, approved: Boolean, action: GuardPolicy.Action): String {
        val who = by.ifBlank { "A partner" }
        return if (approved) "$who approved: ${what(action)}, for the next ${GRANT_MS / 60_000} minutes."
        else "$who said no to: ${what(action)}."
    }
}
