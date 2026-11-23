package app.focusfence.monitor

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import app.focusfence.data.EventLog
import app.focusfence.dns.AppTimeline
import app.focusfence.dns.LogEvents

/**
 * Pulls the foreground-app timeline from UsageStats into the activity log.
 *
 * Pull, not push: the system already records these events, so reading them
 * occasionally (piggybacked on battery/screen samples, the diagnostics screen
 * and export) costs no wakeups and misses nothing — the OS keeps several days.
 * Requires the user-granted "Usage access" special permission; its loss is an
 * integrity event (see [Monitor]).
 *
 * Only called on [Monitor]'s worker thread, so the timeline needs no locking.
 */
object UsageCollector {

    private const val KEY_PULLED_UNTIL = "usage_pulled_until"
    private const val FIRST_BACKFILL_MS = 24 * 60 * 60_000L

    /** SystemUI resumes over the current app (shade, volume panel) without the user leaving it. */
    private val timeline = AppTimeline(ignoredPackages = setOf("com.android.systemui"))

    fun hasAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Returns the number of timeline records written. */
    fun pull(context: Context): Int {
        if (!hasAccess(context)) return 0
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return 0
        val prefs = EventLog.prefs()
        val now = System.currentTimeMillis()
        val from = prefs.getLong(KEY_PULLED_UNTIL, now - FIRST_BACKFILL_MS)
            .coerceIn(now - FIRST_BACKFILL_MS, now)
        val events = usm.queryEvents(from, now) ?: return 0
        val e = UsageEvents.Event()
        var written = 0
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            for (out in timeline.feed(e.timeStamp, e.eventType, e.packageName)) {
                when (out) {
                    is AppTimeline.Out.App -> EventLog.activity(LogEvents.app(out.t, out.pkg, out.state))
                    is AppTimeline.Out.Screen -> EventLog.activity(LogEvents.screen(out.t, out.state))
                }
                written++
            }
        }
        prefs.edit().putLong(KEY_PULLED_UNTIL, now).apply()
        return written
    }
}
