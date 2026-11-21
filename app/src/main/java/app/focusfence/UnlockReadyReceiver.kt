package app.focusfence

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.focusfence.data.StrictStore
import app.focusfence.dns.StrictLock

/**
 * Tells the owner the moment a strict-mode unlock opens. Without this the
 * 10-minute wait ends silently and the window can pass unnoticed; a
 * notification is the "now's your chance". Uses an inexact (no special
 * permission) idle-allowed alarm: it may fire a little late in Doze, never early.
 */
class UnlockReadyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // The request may have been cancelled or replaced since the alarm was set.
        val state = StrictStore.state(context)
        if (state !is StrictLock.State.Unlocked) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (!nm.areNotificationsEnabled()) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.unlock_channel), NotificationManager.IMPORTANCE_HIGH)
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val minutes = (state.remainingMs / 60_000).coerceAtLeast(1)
        nm.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(context.getString(R.string.unlock_ready_title))
                .setContentText(context.getString(R.string.unlock_ready_text, minutes))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setTimeoutAfter(state.remainingMs)
                .build(),
        )
    }

    companion object {
        private const val CHANNEL = "unlock_ready"
        private const val NOTIFICATION_ID = 7001

        private fun pending(context: Context) = PendingIntent.getBroadcast(
            context, NOTIFICATION_ID, Intent(context, UnlockReadyReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun schedule(context: Context, readyAt: Long) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, readyAt, pending(context))
        }

        fun cancel(context: Context) {
            context.getSystemService(AlarmManager::class.java)?.cancel(pending(context))
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }
    }
}
