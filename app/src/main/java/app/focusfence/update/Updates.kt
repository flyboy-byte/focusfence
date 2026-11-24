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

    /** Asks GitHub for the latest release. Blocking; call off the main thread. Null when nothing newer; throws when GitHub can't be reached. */
    fun fetchOffer(c: Context): UpdateCheck.Offer? {
        val conn = URL(UpdateCheck.LATEST_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "FocusFenceAC")
        val body = try {
            check(conn.responseCode == 200) { "GitHub answered ${conn.responseCode}" }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect() // a one-off request; nothing to keep alive
        }
        prefs(c).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        return UpdateCheck.offer(parse(body), installedVersion(c))
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
                .setContentTitle(c.getString(R.string.update_available_title, offer.version))
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
