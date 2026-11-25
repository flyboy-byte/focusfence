package app.focusfence.monitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import app.focusfence.R
import app.focusfence.dns.AutoShare
import app.focusfence.dns.ShotBlur
import app.focusfence.sync.ShareQueue

/**
 * D-037: tells the owner a flagged screenshot is going to their partners, shows exactly the blurred picture they'll
 * get, and takes a note by replying to the notification. There is deliberately no cancel action.
 */
object AutoShareNotice {
    private const val CHANNEL = "auto_share"
    const val KEY_NOTE = "note"
    const val EXTRA_ID = "share_id"
    const val EXTRA_SEND_AT = "send_at"

    fun post(ctx: Context, id: String, thumb: ShotBlur.Thumb, app: String, label: String, percent: Int, sendAt: Long) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, ctx.getString(R.string.auto_share_channel), NotificationManager.IMPORTANCE_HIGH)
        )
        val reply = PendingIntent.getBroadcast(
            ctx, id.hashCode(),
            Intent(ctx, AutoShareNoteReceiver::class.java).putExtra(EXTRA_ID, id).putExtra(EXTRA_SEND_AT, sendAt),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
        )
        val action = NotificationCompat.Action.Builder(R.drawable.ic_shield, ctx.getString(R.string.auto_share_add_note), reply)
            .addRemoteInput(RemoteInput.Builder(KEY_NOTE).setLabel(ctx.getString(R.string.auto_share_note_hint)).build())
            .setAllowGeneratedReplies(false)
            .build()
        val picture = ShareQueue.preview(thumb, 480)
        val text = ctx.getString(R.string.auto_share_text, app, label, percent, time(ctx, sendAt))
        nm.notify(
            id.hashCode(),
            NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(ctx.getString(R.string.auto_share_title))
                .setContentText(text)
                .setLargeIcon(picture)
                .setStyle(NotificationCompat.BigPictureStyle().bigPicture(picture).setSummaryText(text))
                .addAction(action)
                .setAutoCancel(false)
                .build(),
        )
    }

    /** After a note: confirm it, without the reply field. */
    fun noted(ctx: Context, id: String, sendAt: Long, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.notify(
            id.hashCode(),
            NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(ctx.getString(R.string.auto_share_title))
                .setContentText(text)
                .setTimeoutAfter(60_000)
                .build(),
        )
    }

    fun time(ctx: Context, t: Long): String = android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date(t))

    fun sendAt(now: Long) = AutoShare.sendAt(now, auto = true)
}

class AutoShareNoteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(AutoShareNotice.EXTRA_ID) ?: return
        val note = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(AutoShareNotice.KEY_NOTE)?.toString()?.trim().orEmpty()
        val sendAt = intent.getLongExtra(AutoShareNotice.EXTRA_SEND_AT, 0)
        val at = AutoShareNotice.time(context, sendAt)
        val text = when {
            note.isEmpty() -> context.getString(R.string.auto_share_no_note, at)
            ShareQueue.addNote(context, id, note) -> context.getString(R.string.auto_share_noted, at)
            else -> context.getString(R.string.auto_share_too_late)
        }
        AutoShareNotice.noted(context, id, sendAt, text)
    }
}
