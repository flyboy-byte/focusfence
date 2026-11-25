package app.focusfence.data

import android.content.Context
import app.focusfence.dns.CapturePolicy
import app.focusfence.dns.Classification

/**
 * Experimental monitoring configuration: screenshot capture and in-app text
 * capture. All off by default. Stored in the `focusfence` prefs file so the
 * accessibility service reloads on change via its existing listener.
 *
 * Capture is **visible by design**: whenever [screenshotsEnabled] or
 * [textEnabled] is on, the accessibility service posts a persistent, ongoing
 * notification so the person holding the phone can always see it is running.
 * This is what keeps the feature self-accountability rather than covert
 * monitoring, and it is not optional (there is no setting to hide it).
 */
object MonitorSettings {

    const val KEY_SCREENSHOTS = "mon_screenshots"
    const val KEY_TEXT = "mon_text"
    const val KEY_LEVEL = "mon_level"
    const val KEY_RETENTION = "mon_retention"
    const val KEY_THRESHOLD = "mon_threshold"
    const val KEY_REVIEW_ON = "mon_review_on"
    const val KEY_REVIEW_PCT = "mon_review_pct"
    const val KEY_WATCHED = "mon_watched_apps"
    const val KEY_WATCH_MODE = "mon_watch_mode"
    const val KEY_WATCH_EXCLUDED = "mon_watch_excluded"
    const val KEY_PARTNER_KEY = "mon_partner_key_spki"
    const val KEY_AI = "mon_ai"

    fun screenshotsEnabled(c: Context) = prefs(c).getBoolean(KEY_SCREENSHOTS, false)
    fun textEnabled(c: Context) = prefs(c).getBoolean(KEY_TEXT, false)
    fun anyCaptureEnabled(c: Context) = screenshotsEnabled(c) || textEnabled(c)

    fun setScreenshots(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean(KEY_SCREENSHOTS, on).apply()
        app.focusfence.monitor.Monitor.checkIntegrityAsync(c)
    }
    fun setText(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_TEXT, on).apply()

    /**
     * On-device AI flagging of screenshots. Off by default: it's the one
     * monitoring feature with a real CPU cost (~one MobileNet inference per
     * sampled screenshot), so it's opt-in and its cost is logged (diag perf
     * `clf`, `clf_ms`, `clf_load_ms`). Off = screenshots are scored nothing.
     */
    fun aiEnabled(c: Context) = prefs(c).getBoolean(KEY_AI, false)
    fun setAi(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean(KEY_AI, on).apply()
        app.focusfence.monitor.Monitor.checkIntegrityAsync(c)
    }

    fun level(c: Context): CapturePolicy.Level =
        runCatching { CapturePolicy.Level.valueOf(prefs(c).getString(KEY_LEVEL, null) ?: "") }
            .getOrDefault(CapturePolicy.Level.STANDARD)

    fun setLevel(c: Context, level: CapturePolicy.Level) = prefs(c).edit().putString(KEY_LEVEL, level.name).apply()

    fun retention(c: Context): Classification.RetentionMode =
        runCatching { Classification.RetentionMode.valueOf(prefs(c).getString(KEY_RETENTION, null) ?: "") }
            .getOrDefault(Classification.RetentionMode.FLAGGED_ONLY)

    fun setRetention(c: Context, mode: Classification.RetentionMode) =
        prefs(c).edit().putString(KEY_RETENTION, mode.name).apply()

    /** Owner-only local copies of risky screenshots; see [app.focusfence.dns.ReviewPolicy]. */
    fun reviewEnabled(c: Context) = prefs(c).getBoolean(KEY_REVIEW_ON, true)
    fun setReviewEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_REVIEW_ON, on).apply()
    fun reviewPercent(c: Context) = prefs(c).getInt(KEY_REVIEW_PCT, app.focusfence.dns.ReviewPolicy.DEFAULT_PERCENT)
    fun setReviewPercent(c: Context, pct: Int) = prefs(c).edit().putInt(KEY_REVIEW_PCT, pct).apply()

    fun threshold(c: Context): Double =
        prefs(c).getString(KEY_THRESHOLD, null)?.toDoubleOrNull() ?: Classification.DEFAULT_THRESHOLD

    fun watchedApps(c: Context): Set<String> =
        prefs(c).getStringSet(KEY_WATCHED, emptySet())?.toSet() ?: emptySet()

    fun setWatchedApps(c: Context, apps: Set<String>) =
        prefs(c).edit().putStringSet(KEY_WATCHED, apps).apply()

    /**
     * ALL by default. Someone who picked apps before this setting existed keeps
     * their list (CHOSEN), so an update never silently widens what's watched.
     */
    fun watchMode(c: Context): app.focusfence.dns.WatchScope.Mode {
        val raw = prefs(c).getString(KEY_WATCH_MODE, null)
        return when {
            raw != null -> runCatching { app.focusfence.dns.WatchScope.Mode.valueOf(raw) }.getOrDefault(app.focusfence.dns.WatchScope.Mode.ALL)
            watchedApps(c).isNotEmpty() -> app.focusfence.dns.WatchScope.Mode.CHOSEN
            else -> app.focusfence.dns.WatchScope.Mode.ALL
        }
    }

    fun setWatch(c: Context, mode: app.focusfence.dns.WatchScope.Mode, chosen: Set<String>) =
        prefs(c).edit().putStringSet(KEY_WATCHED, chosen).putString(KEY_WATCH_MODE, mode.name).apply()

    /** Apps never captured in ALL mode. */
    fun excludedApps(c: Context): Set<String> =
        prefs(c).getStringSet(KEY_WATCH_EXCLUDED, emptySet())?.toSet() ?: emptySet()

    fun setExcludedApps(c: Context, apps: Set<String>) =
        prefs(c).edit().putStringSet(KEY_WATCH_EXCLUDED, apps).apply()

    /** Base64 SPKI of the partner's P-256 public key; null until the owner sets one. */
    fun partnerKeySpki(c: Context): String? = prefs(c).getString(KEY_PARTNER_KEY, null)?.ifBlank { null }

    fun setPartnerKeySpki(c: Context, b64: String?) =
        prefs(c).edit().putString(KEY_PARTNER_KEY, b64?.trim()).apply()

    private fun prefs(c: Context) = BlocklistStore.prefs(c)
}
