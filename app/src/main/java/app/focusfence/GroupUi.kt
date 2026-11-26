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

    /**
     * Shares an invite as its QR code picture, with the code printed under it, and a message that works on its own:
     * the link to tap, or the code to type. Falls back to the message alone if the picture can't be written.
     */
    fun shareCode(ctx: Context, message: String, code: String, qrUrl: String) {
        val send = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, message).setType("text/plain")
        runCatching {
            val qr = qr(qrUrl) ?: error("no qr")
            val pad = 48
            val out = android.graphics.Bitmap.createBitmap(qr.width + pad * 2, qr.height + pad * 2 + 140, android.graphics.Bitmap.Config.RGB_565)
            val c = android.graphics.Canvas(out)
            c.drawColor(android.graphics.Color.WHITE)
            c.drawBitmap(qr, pad.toFloat(), pad.toFloat(), null)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.BLACK; textSize = 96f; textAlign = android.graphics.Paint.Align.CENTER
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
            }
            c.drawText(app.focusfence.dns.InviteLink.showCode(code), out.width / 2f, (qr.height + pad + 110).toFloat(), paint)
            val dir = java.io.File(ctx.cacheDir, "share").apply { mkdirs() }
            val f = java.io.File(dir, "invite.png")
            f.outputStream().use { out.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
            send.setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = android.content.ClipData.newRawUri(null, uri)
        }
        ctx.startActivity(Intent.createChooser(send, null))
    }

    /** The big code on an invite screen: tap to copy it (dash included; typing it without works too). */
    fun copyOnTap(view: TextView, code: String) {
        view.setOnClickListener {
            val cm = view.context.getSystemService(android.content.ClipboardManager::class.java)
            cm?.setPrimaryClip(android.content.ClipData.newPlainText("Focus Fence code", app.focusfence.dns.InviteLink.showCode(code)))
            Toast.makeText(view.context, R.string.acc_code_copied, Toast.LENGTH_SHORT).show()
        }
    }

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
            gravity = Gravity.CENTER
            contentDescription = ctx.getString(R.string.acc_code_tap_copy)
            copyOnTap(this, code.code)
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
                shareCode(ctx, ctx.getString(R.string.grp_share, code.name, code.qrUrl, app.focusfence.dns.InviteLink.showCode(code.code)), code.code, code.qrUrl)
            }
            main.post(poll)
        }
        dialog.setOnDismissListener { main.removeCallbacks(poll); onClosed() }
        dialog.show()
    }
}
