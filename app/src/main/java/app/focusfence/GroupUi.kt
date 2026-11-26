package app.focusfence

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.focusfence.sync.Accountability
import app.focusfence.sync.SyncStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Plan 14: the pieces the Partners screen and a group's page share. */
object GroupUi {

    private val main = Handler(Looper.getMainLooper())

    /** Runs [work] off the main thread; [done] gets the result, a failure shows as a toast. */
    fun <T> background(ctx: Context, work: () -> T, done: (T) -> Unit) {
        Thread {
            val r = runCatching(work)
            main.post {
                r.onSuccess(done).onFailure { Toast.makeText(ctx, it.message ?: it.javaClass.simpleName, Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    /** A code's QR code: its /c/ link, so any camera app opens the page that offers this app. */
    fun qr(text: String): android.graphics.Bitmap? = runCatching {
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0,
            mapOf(com.google.zxing.EncodeHintType.MARGIN to 2))
        val scale = 8
        android.graphics.Bitmap.createBitmap(m.width * scale, m.height * scale, android.graphics.Bitmap.Config.RGB_565).apply {
            for (y in 0 until height) for (x in 0 until width) setPixel(x, y, if (m.get(x / scale, y / scale)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }.getOrNull()

    fun levelName(ctx: Context, level: String): String = ctx.getString(when (level) {
        "all" -> R.string.grp_level_all
        "daily" -> R.string.grp_level_daily
        else -> R.string.grp_level_important
    })

    /**
     * The group's code and QR code (prototype screen 10). While it's open this phone checks in every minute and the
     * member list is re-read, so a joiner is announced here; [onClosed] runs when it's dismissed.
     */
    fun showCode(ctx: Context, code: Accountability.GroupCode, spots: Int, onClosed: () -> Unit) {
        val dp = ctx.resources.displayMetrics.density
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((24 * dp).toInt(), (8 * dp).toInt(), (24 * dp).toInt(), 0)
        }
        box.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.grp_code_howto)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        })
        qr(code.qrUrl)?.let { bmp ->
            box.addView(android.widget.ImageView(ctx).apply {
                setImageBitmap(bmp)
                contentDescription = ctx.getString(R.string.acc_qr_desc)
                setPadding(0, (12 * dp).toInt(), 0, (4 * dp).toInt())
            }, LinearLayout.LayoutParams((200 * dp).toInt(), (216 * dp).toInt()))
        }
        box.addView(TextView(ctx).apply {
            text = app.focusfence.dns.InviteLink.showCode(code.code)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineMedium)
            letterSpacing = 0.12f
            setTextIsSelectable(true)
            gravity = Gravity.CENTER
        })
        box.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.grp_code_meta, spots, SyncStore.serverUrl(ctx).removePrefix("https://"))
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            gravity = Gravity.CENTER
        })
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(code.name)
            .setView(box)
            .setNeutralButton(R.string.acc_share, null)
            .setPositiveButton(R.string.acc_done, null)
            .create()
        var known: Set<String>? = null
        val appCtx = ctx.applicationContext
        val poll = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                app.focusfence.sync.Sync.kick(appCtx)
                background(ctx, { Accountability.groups(appCtx).firstOrNull { it.id == code.groupId } }) { g ->
                    if (g == null || !dialog.isShowing) return@background
                    val names = g.members.filter { !it.me }.map { it.name }.toSet()
                    known?.let { before -> (names - before).forEach { Toast.makeText(ctx, ctx.getString(R.string.grp_member_joined, it, g.name), Toast.LENGTH_LONG).show() } }
                    known = names
                    if (g.spots <= 0) dialog.dismiss()
                }
                main.postDelayed(this, 15_000)
            }
        }
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, ctx.getString(R.string.grp_share, code.name, code.qrUrl)), null))
            }
            main.post(poll)
        }
        dialog.setOnDismissListener { main.removeCallbacks(poll); onClosed() }
        dialog.show()
    }
}
