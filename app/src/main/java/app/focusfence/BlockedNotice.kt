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
import app.focusfence.data.EventLog
import app.focusfence.dns.BlockScreen
import app.focusfence.dns.BlockTriage
import app.focusfence.dns.Categories
import app.focusfence.dns.LogEvents

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

    /** Repeated confirmed high-risk notices -> one partner alert (D-024 "Partner" tier). */
    private val escalation = BlockTriage.Escalation()

    /**
     * [category] is set for an adult/gambling/scam list block, null for the owner's own rule. [tellMe]: announce it
     * to the owner in the chosen style. [tellPartner]: count it toward the repeated-tries partner alert.
     */
    fun onDnsBlock(context: Context, domain: String, category: String?, tellMe: Boolean, tellPartner: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!signalCooldown.accept(domain, now)) return
        if (UrlFilterService.onDnsBlocked(domain, category, tellMe, tellPartner)) return
        // Without the accessibility service nothing confirms a person opened the site (browsers prefetch while you
        // type), so partners are never told from here: a wrong partner alert is worse than a missed one.
        if (!tellMe) return
        if (!notifyCooldown.accept(domain, now)) return
        val app = context.applicationContext
        main.post { show(app, domain, category) }
    }

    /** The address bar confirmed a high-risk site was opened. Tells partners if it keeps happening. */
    fun onConfirmedHighRisk(context: Context, site: String, category: String) {
        val now = System.currentTimeMillis()
        val n = escalation.hit(category, site, now) ?: return
        EventLog.activity(LogEvents.service(now, "block", "repeated", "cat=$category,n=$n"), urgent = true)
        app.focusfence.sync.Sync.kick(context)
    }

    /** Posts (or replaces) the "blocked" notification. Tapping it opens [BlockedActivity]. */
    fun show(context: Context, domain: String, category: String? = null) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.block_screen_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        val open = PendingIntent.getActivity(
            context, domain.hashCode(), BlockedActivity.intent(context, domain, category),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Without the notification permission (Android 13+) this is a no-op;
        // the block itself is unaffected.
        runCatching {
            nm.notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_shield)
                    // A list block stays generic, so a site name never sits in the notification shade.
                    .setContentTitle(
                        if (category != null) {
                            context.getString(R.string.block_notify_category_title, Categories.bundle(category)?.label ?: category)
                        } else {
                            context.getString(R.string.block_screen_notify_title, domain)
                        }
                    )
                    .setContentText(context.getString(R.string.block_screen_notify_text))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .setTimeoutAfter(60_000L)
                    .build(),
            )
        }
    }
}
