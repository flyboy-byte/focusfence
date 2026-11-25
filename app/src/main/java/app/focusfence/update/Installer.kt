package app.focusfence.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import app.focusfence.R
import app.focusfence.data.EventLog
import app.focusfence.dns.LogEvents
import app.focusfence.dns.UpdateCheck
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.concurrent.thread

/**
 * Downloads, checks and installs an update, for the Home banner (2.24: the whole update happens in the banner) and
 * [UpdateActivity]. Process-wide, so leaving Home and coming back shows the same download. Nothing starts until the
 * owner taps. The APK must be this app, newer, and signed by the same key as the installed copy; Android refuses a
 * different signer anyway, and checking first turns that into a clear message instead of a vague "App not installed".
 */
object Installer {
    sealed class State {
        object Idle : State()
        data class Downloading(val done: Long, val total: Long) : State()
        object Verifying : State()
        /** Handed to Android, which shows its own confirmation. */
        object Confirming : State()
        data class Failed(val message: String) : State()
    }

    @Volatile var state: State = State.Idle
        private set
    @Volatile var version: String? = null
        private set
    private val listeners = CopyOnWriteArraySet<(State) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun observe(l: (State) -> Unit) { listeners += l; l(state) }
    fun stopObserving(l: (State) -> Unit) { listeners -= l }

    private fun set(s: State) {
        state = s
        main.post { listeners.forEach { it(s) } }
    }

    val busy: Boolean get() = state is State.Downloading || state is State.Verifying

    /** Starts the update for [offer]. Without install permission it opens that setting and says so instead. */
    fun start(context: Context, offer: UpdateCheck.Offer) {
        val ctx = context.applicationContext
        if (busy) return
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            set(State.Failed(ctx.getString(R.string.update_allow_installs)))
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        // The check already filtered this, but re-check before fetching anything.
        if (!offer.apkUrl.startsWith(UpdateCheck.DOWNLOAD_PREFIX)) return fail(ctx, ctx.getString(R.string.update_failed_source))
        version = offer.version
        Updates.cancelNotification(ctx)
        set(State.Downloading(0, offer.size))
        thread(name = "update-download") {
            val apk = File(ctx.cacheDir, "update.apk")
            val error = try {
                download(offer.apkUrl, apk, offer.size)
                set(State.Verifying)
                verify(ctx, apk)
            } catch (e: Exception) {
                ctx.getString(R.string.update_failed_download)
            }
            if (error != null) {
                apk.delete()
                fail(ctx, error)
                return@thread
            }
            try {
                install(ctx, apk, offer.version)
                set(State.Confirming)
            } catch (e: Exception) {
                fail(ctx, ctx.getString(R.string.update_failed_install, e.message ?: ""))
            }
        }
    }

    /** Back to the start, e.g. after a failure the owner has read. */
    fun reset() { if (!busy) set(State.Idle) }

    private fun download(url: String, to: File, expected: Long) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "FocusFenceAC")
        try {
            check(conn.responseCode == 200)
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: expected
            conn.inputStream.use { input ->
                to.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var shown = -1L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        // About 100 UI updates per download, not one per 64 KB.
                        val step = if (total > 0) done * 100 / total else done / (256 * 1024)
                        if (step != shown) { shown = step; set(State.Downloading(done, total)) }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Null when the APK is this app, newer, and signed like the installed copy; otherwise the message to show. */
    private fun verify(ctx: Context, apk: File): String? {
        val pm = ctx.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val got = pm.getPackageArchiveInfo(apk.path, flags) ?: return ctx.getString(R.string.update_failed_invalid)
        val mine = pm.getPackageInfo(ctx.packageName, flags)
        if (got.packageName != ctx.packageName) return ctx.getString(R.string.update_failed_invalid)
        if (versionCode(got) <= versionCode(mine)) return ctx.getString(R.string.update_failed_not_newer)
        val theirs = signers(got)
        // Some Android versions can't read an archive's signers; Android's installer still enforces the same key.
        if (theirs.isNotEmpty() && !UpdateCheck.sameSigner(signers(mine), theirs)) return ctx.getString(R.string.update_failed_signer)
        return null
    }

    private fun versionCode(p: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()

    private fun signers(p: PackageInfo): List<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) p.signingInfo?.apkContentsSigners else @Suppress("DEPRECATION") p.signatures
        return sigs.orEmpty().map { s ->
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }

    private fun install(ctx: Context, apk: File, version: String) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(ctx.packageName) }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            // Mutable: the installer adds the status and the confirm intent to it.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val result = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallResultReceiver::class.java), flags)
            EventLog.activity(LogEvents.service(System.currentTimeMillis(), "update", "install_started", version), urgent = true)
            session.commit(result.intentSender)
        }
    }

    private fun fail(ctx: Context, message: String) {
        set(State.Failed(message))
        EventLog.activity(LogEvents.service(System.currentTimeMillis(), "update", "failed", message.take(80)))
    }

    /** Android's installer said no after confirmation (see [InstallResultReceiver]). */
    fun installFailed(ctx: Context, message: String) = set(State.Failed(ctx.getString(R.string.update_failed_install, message)))
}
