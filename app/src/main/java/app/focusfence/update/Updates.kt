package app.focusfence.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import app.focusfence.R
import app.focusfence.data.EventLog
import app.focusfence.dns.LogEvents
import app.focusfence.dns.UpdateCheck
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Self-update, notify-and-tap (decided 2026-11-24): once a day ask GitHub for the latest release; if it's newer,
 * show one notification. Nothing downloads until the owner taps it ([UpdateActivity]), and Android's own install
 * screen still asks. On by default with a Settings switch: it is the one request the app makes that the owner didn't
 * set up, and GitHub sees the phone's IP.
 *
 * Own prefs file: nothing in the VPN or accessibility listeners depends on it.
 */
object Updates {
    private const val PREFS = "focusfence_update"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_NOTIFIED_TAG = "notified_tag"
    private const val KEY_OFFER = "offer"
    private const val KEY_DISMISSED_TAG = "dismissed_tag"
    private const val WORK = "focusfence_update_check"
    private const val CHANNEL = "app_updates"
    private const val NOTIFICATION_ID = 0x0FF0_0001

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(c: Context): Boolean = prefs(c).getBoolean(KEY_ENABLED, true)

    fun setEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean(KEY_ENABLED, on).apply()
        schedule(c)
    }

    fun schedule(c: Context) {
        val wm = WorkManager.getInstance(c)
        if (!isEnabled(c)) {
            wm.cancelUniqueWork(WORK)
            return
        }
        // The worker itself enforces once a day (UpdateCheck.isDue); the period is only how often it wakes to ask.
        wm.enqueueUniquePeriodicWork(
            WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }

    fun installedVersion(c: Context): String = try {
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    /**
     * Asks GitHub for the latest release. Blocking; call off the main thread. Null when nothing newer; throws when
     * GitHub can't be reached. The answer is remembered for Home's update banner ([pendingOffer]).
     */
    fun fetchOffer(c: Context): UpdateCheck.Offer? {
        val offer = UpdateCheck.offer(parse(get(UpdateCheck.LATEST_URL)), installedVersion(c))
        prefs(c).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).putString(KEY_OFFER, offer?.let { toJson(it) }).apply()
        return offer
    }

    /** The last few releases, newest first, for the What's new screen: (version, plain notes). Blocking. */
    fun recentReleases(count: Int = 6): List<Pair<String, String>> {
        val arr = org.json.JSONArray(get(UpdateCheck.RELEASES_URL + "?per_page=$count"))
        return (0 until arr.length()).map { parse(arr.getJSONObject(it).toString()) }
            .filter { !it.draft && !it.prerelease && UpdateCheck.isAppTag(it.tag) }
            .map { UpdateCheck.versionOf(it.tag) to UpdateCheck.userNotes(it.body) }
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "FocusFenceAC")
        return try {
            check(conn.responseCode == 200) { "GitHub answered ${conn.responseCode}" }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect() // a one-off request; nothing to keep alive
        }
    }

    /** The newer version found by the last check, unless the owner dismissed its banner or already has it. */
    fun pendingOffer(c: Context): UpdateCheck.Offer? {
        if (!isEnabled(c)) return null
        val o = prefs(c).getString(KEY_OFFER, null)?.let { runCatching { fromJson(it) }.getOrNull() } ?: return null
        if (app.focusfence.dns.Digest.compareVersions(o.version, installedVersion(c)) <= 0) return null
        if (prefs(c).getString(KEY_DISMISSED_TAG, null) == o.tag) return null
        return o
    }

    fun dismissBanner(c: Context, tag: String) {
        prefs(c).edit().putString(KEY_DISMISSED_TAG, tag).apply()
    }

    private fun toJson(o: UpdateCheck.Offer) = JSONObject()
        .put("version", o.version).put("tag", o.tag).put("apk", o.apkUrl).put("size", o.size)
        .put("page", o.pageUrl).put("notes", o.notes).toString()

    private fun fromJson(s: String): UpdateCheck.Offer = JSONObject(s).let {
        UpdateCheck.Offer(it.getString("version"), it.getString("tag"), it.getString("apk"), it.optLong("size"), it.optString("page"), it.optString("notes"))
    }

    private fun parse(body: String): UpdateCheck.Release {
        val o = JSONObject(body)
        val arr = o.optJSONArray("assets")
        val assets = (0 until (arr?.length() ?: 0)).map { i ->
            val a = arr!!.getJSONObject(i)
            UpdateCheck.Asset(a.optString("name"), a.optString("browser_download_url"), a.optLong("size"))
        }
        return UpdateCheck.Release(
            o.optString("tag_name"), o.optBoolean("draft"), o.optBoolean("prerelease"), assets, o.optString("html_url"),
            o.optString("body"),
        )
    }

    fun isDue(c: Context): Boolean = UpdateCheck.isDue(prefs(c).getLong(KEY_LAST_CHECK, 0), System.currentTimeMillis())

    /** One notification per new version, so a dismissed one doesn't come back every day. */
    fun notifyOnce(c: Context, offer: UpdateCheck.Offer) {
        val p = prefs(c)
        if (p.getString(KEY_NOTIFIED_TAG, null) == offer.tag) return
        p.edit().putString(KEY_NOTIFIED_TAG, offer.tag).apply()
        EventLog.activity(LogEvents.service(System.currentTimeMillis(), "update", "available", offer.version))
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, c.getString(R.string.update_channel), NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            c, 0, UpdateActivity.intent(c, offer),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(c.getString(R.string.update_available_title, UpdateCheck.shortVersion(offer.version)))
                .setContentText(c.getString(R.string.update_available_text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    fun cancelNotification(c: Context) {
        c.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }
}

class UpdateWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val c = applicationContext
        if (!Updates.isEnabled(c) || !Updates.isDue(c)) return Result.success()
        try {
            Updates.fetchOffer(c)?.let { Updates.notifyOnce(c, it) }
        } catch (e: Exception) {
            // Offline, rate-limited or GitHub down: try again at the next wake, no noise.
        }
        return Result.success()
    }
}
