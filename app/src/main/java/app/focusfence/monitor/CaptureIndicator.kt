package app.focusfence.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import app.focusfence.R
import app.focusfence.data.MonitorSettings

/**
 * The on-device capture indicator. Whenever screenshot or text capture is
 * enabled, a persistent, ongoing (non-dismissible) notification is shown so the
 * person holding the phone can always see that capture is running. This is a
 * deliberate, non-optional part of the design: it is what makes the feature
 * self-accountability rather than covert monitoring. There is intentionally no
 * setting to hide it.
 *
 * The accessibility service (the component that actually captures) owns the
 * lifecycle: it calls [refresh] whenever capture settings change and when it
 * connects/disconnects.
 */
object CaptureIndicator {

    private const val CHANNEL = "capture_status"
    private const val NOTIFICATION_ID = 42

    fun refresh(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (MonitorSettings.anyCaptureEnabled(context)) show(context, manager) else manager.cancel(NOTIFICATION_ID)
    }

    fun clear(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private fun show(context: Context, manager: NotificationManager) {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.capture_channel), NotificationManager.IMPORTANCE_LOW)
                .apply { description = context.getString(R.string.capture_channel_desc) }
        )
        val shots = MonitorSettings.screenshotsEnabled(context)
        val text = MonitorSettings.textEnabled(context)
        val what = when {
            shots && text -> context.getString(R.string.capture_active_both)
            shots -> context.getString(R.string.capture_active_shots)
            else -> context.getString(R.string.capture_active_text)
        }
        // Tapping it opens the app's monitoring screen, so the indicator is also
        // the way to see and change what is being captured.
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = open?.let {
            android.app.PendingIntent.getActivity(
                context, 0, it,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        val n: Notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(context.getString(R.string.capture_active_title))
            .setContentText(what)
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
        manager.notify(NOTIFICATION_ID, n)
    }
}
