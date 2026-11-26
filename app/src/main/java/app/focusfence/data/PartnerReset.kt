package app.focusfence.data

import android.content.Context
import app.focusfence.dns.LogEvents
import app.focusfence.sync.SyncStore
import java.io.File

/**
 * Plan 13's clean cut, on the phone: the first start of a version with the rebuilt partner system deletes every
 * earlier kind of partner data (pairings this phone made, phones it watched, the group, the group's keys) instead of
 * converting it, so no old path lingers. It runs once; if anything was there, it's logged and the Partners screen says
 * so once ([noticeDue]).
 */
object PartnerReset {
    private const val PREFS = "focusfence_partner_reset"
    private const val DONE = "v2_done"
    private const val NOTICE = "v2_notice"

    fun runOnce(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getBoolean(DONE, false)) return
        var removed = SyncStore.clearOldPartners(ctx)
        File(ctx.noBackupFilesDir, "group").takeIf { it.exists() }?.let { removed += 1; it.deleteRecursively() }
        p.edit().putBoolean(DONE, true).putBoolean(NOTICE, removed > 0).apply()
        if (removed > 0) {
            EventLog.init(ctx)
            EventLog.activity(LogEvents.service(System.currentTimeMillis(), "partner", "partners_reset", "$removed old entries"), urgent = true)
        }
    }

    /** True once, after a reset that removed something: the Partners screen explains it. */
    fun noticeDue(ctx: Context): Boolean {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.getBoolean(NOTICE, false)) return false
        p.edit().putBoolean(NOTICE, false).apply()
        return true
    }
}
