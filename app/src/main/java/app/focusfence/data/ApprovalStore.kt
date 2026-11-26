package app.focusfence.data

import android.content.Context
import app.focusfence.dns.GuardPolicy
import app.focusfence.dns.UnlockAsk

/**
 * The owner's latest "ask a partner" request (plan 12 §3) as the server last reported it. Its own prefs file: the guard
 * reads it at the moment of the action, so no service listener needs it (unlike rules in `focusfence`).
 */
/*
 * commit(), not apply(), where it's used here (lint ApplySharedPref): these run on the sync worker, and a process killed
 * before apply() flushes would lose the report key, the enrollment or a partner's report, which partners then can't
 * get back. Everything else uses apply().
 */
@android.annotation.SuppressLint("ApplySharedPref")
object ApprovalStore {
    private const val PREFS = "focusfence_approval"

    data class Ask(val id: String, val action: String, val state: String, val by: String, val askedMs: Long, val decidedMs: Long)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun current(ctx: Context): Ask? {
        val p = prefs(ctx)
        val id = p.getString("id", null) ?: return null
        return Ask(id, p.getString("action", "").orEmpty(), p.getString("state", "").orEmpty(), p.getString("by", "").orEmpty(),
            p.getLong("asked", 0), p.getLong("decided", 0))
    }

    /** Saves what the server says; returns the previous copy so the caller can tell a fresh answer from an old one. */
    fun save(ctx: Context, a: Ask): Ask? {
        val before = current(ctx)
        prefs(ctx).edit().putString("id", a.id).putString("action", a.action).putString("state", a.state)
            .putString("by", a.by).putLong("asked", a.askedMs).putLong("decided", a.decidedMs).commit()
        return before
    }

    /** An approval of exactly [action], still inside its 15 minutes. */
    fun allows(ctx: Context, action: GuardPolicy.Action, now: Long = System.currentTimeMillis()): Boolean {
        val a = current(ctx) ?: return false
        return a.state == UnlockAsk.APPROVED && UnlockAsk.allows(a.action, a.decidedMs, action, now)
    }

    /** A request for [action] still waiting for an answer. */
    fun waiting(ctx: Context, action: GuardPolicy.Action, now: Long = System.currentTimeMillis()): Ask? =
        current(ctx)?.takeIf { it.action == action.key && UnlockAsk.open(it.state, it.askedMs, now) }
}
