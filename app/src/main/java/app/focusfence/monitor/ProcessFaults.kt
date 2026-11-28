package app.focusfence.monitor

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import app.focusfence.data.EventLog
import app.focusfence.dns.Faults
import app.focusfence.dns.LogEvents

/**
 * Why the app stopped. A crash is written by the dying process; everything else (not responding, killed for memory or
 * by a battery saver, force-stopped) only Android knows, and it is asked at the next start. Both go to diag: they are
 * for fixing the app, never partner alerts.
 */
object ProcessFaults {

    private const val KEY_LAST_EXIT = "last_exit_t"
    private const val MAX_EXITS = 10

    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            // Logging must never replace the crash being logged (it may be an out-of-memory one).
            try {
                EventLog.diagBeforeDeath(
                    LogEvents.crash(System.currentTimeMillis(), thread.name, Faults.exceptionChain(e), Faults.stack(e)),
                )
            } catch (ignored: Throwable) {
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /** Writes the exits Android has recorded since the last start (Android 11+; older versions keep no record). */
    fun logPastExits(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val am = context.getSystemService(ActivityManager::class.java) ?: return
            val prefs = EventLog.prefs()
            val seen = prefs.getLong(KEY_LAST_EXIT, 0L)
            val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXITS)
                .filter { it.timestamp > seen }
                .sortedBy { it.timestamp }
            for (x in exits) {
                EventLog.diag(
                    LogEvents.exit(
                        x.timestamp, Faults.exitReason(x.reason), x.status, x.importance, x.rss,
                        Faults.exitNote(x.reason, x.description),
                    ),
                    urgent = true,
                )
            }
            exits.lastOrNull()?.let { prefs.edit().putLong(KEY_LAST_EXIT, it.timestamp).apply() }
        } catch (e: RuntimeException) {
            EventLog.diag(LogEvents.error(System.currentTimeMillis(), "monitor", "exit_reasons", e.javaClass.simpleName))
        }
    }
}
