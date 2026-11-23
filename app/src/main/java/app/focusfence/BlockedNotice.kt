package app.focusfence

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import app.focusfence.accessibility.UrlFilterService
import app.focusfence.dns.BlockScreen

/**
 * Routes a blocked front-door lookup to the user. Called on the DNS reader
 * thread, so it does nothing heavier than a map lookup there and hands
 * everything else to the main thread.
 *
 * With the accessibility service running, that service checks the browser's
 * address bar first and then posts the notification or opens [BlockedActivity],
 * per the chosen style. Without it there is no way to tell a person opening
 * the site from background traffic, so it's always the notification, on its
 * own low-importance channel and rate-limited per domain.
 */
object BlockedNotice {

    /** One page load fires several lookups (A/AAAA, bare/www, retries). */
    private val signalCooldown = BlockScreen.Cooldown(15_000L)

    /** Background traffic can repeat; a notification every few minutes at most. */
    private val notifyCooldown = BlockScreen.Cooldown(5 * 60_000L)

    private const val CHANNEL = "blocked_sites"
    private const val NOTIFICATION_ID = 4242

    private val main = Handler(Looper.getMainLooper())

    fun onDnsBlock(context: Context, domain: String) {
        val now = SystemClock.elapsedRealtime()
        if (!signalCooldown.accept(domain, now)) return
        if (UrlFilterService.onDnsBlocked(domain)) return
        if (!notifyCooldown.accept(domain, now)) return
        val app = context.applicationContext
        main.post { show(app, domain) }
    }

    /** Posts (or replaces) the "blocked" notification. Tapping it opens [BlockedActivity]. */
    fun show(context: Context, domain: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.block_screen_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        val open = PendingIntent.getActivity(
            context, domain.hashCode(), BlockedActivity.intent(context, domain),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Without the notification permission (Android 13+) this is a no-op;
        // the block itself is unaffected.
        runCatching {
            nm.notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_shield)
                    .setContentTitle(context.getString(R.string.block_screen_notify_title, domain))
                    .setContentText(context.getString(R.string.block_screen_notify_text))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .setTimeoutAfter(60_000L)
                    .build(),
            )
        }
    }
}
