package app.focusfence.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import app.focusfence.R
import app.focusfence.data.EventLog
import app.focusfence.dns.LogEvents
import app.focusfence.dns.UpdateCheck
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * Opened from the update notification or Settings → Check now. Downloads the release APK only when the owner taps,
 * checks it is this app, newer, and signed by the same key as the installed copy, then hands it to Android's
 * installer, which shows its own confirmation. Android refuses a different signer anyway; checking first turns that
 * into a clear message instead of a vague "App not installed".
 */
class UpdateActivity : AppCompatActivity() {
    private lateinit var b: app.focusfence.databinding.ActivityUpdateBinding
    @Volatile private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val version = intent.getStringExtra(EXTRA_VERSION) ?: return finish()
        b = app.focusfence.databinding.ActivityUpdateBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setTitle(R.string.update_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }

        b.updateVersion.text = getString(R.string.update_version, UpdateCheck.shortVersion(version))
        b.updateInstalled.text = getString(R.string.update_installed, UpdateCheck.shortVersion(Updates.installedVersion(this)))
        val notes = intent.getStringExtra(EXTRA_NOTES).orEmpty()
        b.updateNotes.text = notes.ifEmpty { getString(R.string.update_no_notes) }
        b.updateInstall.setOnClickListener { start() }
        val page = intent.getStringExtra(EXTRA_PAGE).orEmpty()
        b.updatePage.visibility = if (page.isEmpty()) View.GONE else View.VISIBLE
        b.updatePage.setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(page))) }
        Updates.cancelNotification(this)
    }

    private fun say(text: String) {
        b.updateStatus.text = text
        b.updateStatus.visibility = View.VISIBLE
    }

    private fun start() {
        if (busy) return
        if (!packageManager.canRequestPackageInstalls()) {
            say(getString(R.string.update_allow_installs))
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        val url = intent.getStringExtra(EXTRA_URL) ?: return
        // The worker already filtered this, but the intent is ours to re-check before fetching anything.
        if (!url.startsWith(UpdateCheck.DOWNLOAD_PREFIX)) return fail(getString(R.string.update_failed_source))
        busy = true
        b.updateInstall.isEnabled = false
        b.updateProgress.visibility = View.VISIBLE
        say(getString(R.string.update_downloading))
        thread(name = "update-download") {
            val apk = File(cacheDir, "update.apk")
            val error = try {
                download(url, apk)
                verify(apk)
            } catch (e: Exception) {
                getString(R.string.update_failed_download)
            }
            runOnUiThread {
                if (error != null) {
                    apk.delete()
                    fail(error)
                } else {
                    say(getString(R.string.update_installing))
                    try {
                        installApk(apk)
                    } catch (e: Exception) {
                        fail(getString(R.string.update_failed_install, e.message ?: ""))
                    }
                }
            }
        }
    }

    private fun download(url: String, to: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "FocusFenceAC")
        try {
            check(conn.responseCode == 200)
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: intent.getLongExtra(EXTRA_SIZE, 0)
            conn.inputStream.use { input ->
                to.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) runOnUiThread { b.updateProgress.progress = (done * 100 / total).toInt() }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Null when the APK is this app, newer, and signed like the installed copy; otherwise the message to show. */
    private fun verify(apk: File): String? {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val got = packageManager.getPackageArchiveInfo(apk.path, flags) ?: return getString(R.string.update_failed_invalid)
        val mine = packageManager.getPackageInfo(packageName, flags)
        if (got.packageName != packageName) return getString(R.string.update_failed_invalid)
        if (versionCode(got) <= versionCode(mine)) return getString(R.string.update_failed_not_newer)
        val theirs = signers(got)
        // Some Android versions can't read an archive's signers; Android's installer still enforces the same key.
        if (theirs.isNotEmpty() && !UpdateCheck.sameSigner(signers(mine), theirs)) {
            return getString(R.string.update_failed_signer)
        }
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

    private fun installApk(apk: File) {
        val installer = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(packageName) }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            // Mutable: the installer adds the status and the confirm intent to it.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val result = PendingIntent.getBroadcast(this, id, Intent(this, InstallResultReceiver::class.java), flags)
            EventLog.activity(LogEvents.service(System.currentTimeMillis(), "update", "install_started", intent.getStringExtra(EXTRA_VERSION)), urgent = true)
            session.commit(result.intentSender)
        }
    }

    private fun fail(message: String) {
        busy = false
        b.updateInstall.isEnabled = true
        b.updateProgress.visibility = View.GONE
        say(message)
        EventLog.activity(LogEvents.service(System.currentTimeMillis(), "update", "failed", message.take(80)))
    }

    companion object {
        private const val EXTRA_VERSION = "version"
        private const val EXTRA_URL = "url"
        private const val EXTRA_SIZE = "size"
        private const val EXTRA_PAGE = "page"
        private const val EXTRA_NOTES = "notes"

        fun intent(c: Context, o: UpdateCheck.Offer): Intent = Intent(c, UpdateActivity::class.java)
            .putExtra(EXTRA_VERSION, o.version)
            .putExtra(EXTRA_URL, o.apkUrl)
            .putExtra(EXTRA_SIZE, o.size)
            .putExtra(EXTRA_PAGE, o.pageUrl)
            .putExtra(EXTRA_NOTES, o.notes)
    }
}

/** Android's answer to the install: ask the owner to confirm, or say why it failed. Success replaces the app. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // the "app updated" startup record covers it
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown"
                EventLog.activity(LogEvents.service(System.currentTimeMillis(), "update", "failed", msg.take(80)))
                Toast.makeText(context, context.getString(R.string.update_failed_install, msg), Toast.LENGTH_LONG).show()
            }
        }
    }
}
