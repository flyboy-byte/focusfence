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
 * Opened from Settings → Check now. Since 2.24 the update normally happens in Home's banner; both use [Installer],
 * which does the download, the checks and the hand-off to Android's installer.
 */
class UpdateActivity : AppCompatActivity() {
    private lateinit var b: app.focusfence.databinding.ActivityUpdateBinding
    private lateinit var offer: UpdateCheck.Offer
    private val watch: (Installer.State) -> Unit = { show(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val version = intent.getStringExtra(EXTRA_VERSION) ?: return finish()
        offer = UpdateCheck.Offer(
            version, intent.getStringExtra(EXTRA_TAG).orEmpty(), intent.getStringExtra(EXTRA_URL).orEmpty(),
            intent.getLongExtra(EXTRA_SIZE, 0), intent.getStringExtra(EXTRA_PAGE).orEmpty(), intent.getStringExtra(EXTRA_NOTES).orEmpty(),
        )
        b = app.focusfence.databinding.ActivityUpdateBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setTitle(R.string.update_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }

        b.updateVersion.text = getString(R.string.update_version, UpdateCheck.shortVersion(version))
        b.updateInstalled.text = getString(R.string.update_installed_size, UpdateCheck.shortVersion(Updates.installedVersion(this)),
            android.text.format.Formatter.formatShortFileSize(this, offer.size))
        b.updateNotes.text = offer.notes.ifEmpty { getString(R.string.update_no_notes) }
        b.updateInstall.setOnClickListener { Installer.start(this, offer) }
        b.updatePage.visibility = if (offer.pageUrl.isEmpty()) View.GONE else View.VISIBLE
        b.updatePage.setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(offer.pageUrl))) }
        Updates.cancelNotification(this)
    }

    override fun onStart() { super.onStart(); Installer.observe(watch) }
    override fun onStop() { Installer.stopObserving(watch); super.onStop() }

    private fun show(s: Installer.State) {
        b.updateInstall.isEnabled = !Installer.busy
        b.updateProgress.visibility = if (s is Installer.State.Downloading || s is Installer.State.Verifying) View.VISIBLE else View.GONE
        if (s is Installer.State.Downloading && s.total > 0) b.updateProgress.progress = (s.done * 100 / s.total).toInt()
        val text = UpdateText.status(this, s)
        b.updateStatus.text = text
        b.updateStatus.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    companion object {
        private const val EXTRA_VERSION = "version"
        private const val EXTRA_TAG = "tag"
        private const val EXTRA_URL = "url"
        private const val EXTRA_SIZE = "size"
        private const val EXTRA_PAGE = "page"
        private const val EXTRA_NOTES = "notes"

        fun intent(c: Context, o: UpdateCheck.Offer): Intent = Intent(c, UpdateActivity::class.java)
            .putExtra(EXTRA_VERSION, o.version)
            .putExtra(EXTRA_TAG, o.tag)
            .putExtra(EXTRA_URL, o.apkUrl)
            .putExtra(EXTRA_SIZE, o.size)
            .putExtra(EXTRA_PAGE, o.pageUrl)
            .putExtra(EXTRA_NOTES, o.notes)
    }
}

/** What to say for each [Installer.State], shared by the Home banner and [UpdateActivity]. */
object UpdateText {
    fun status(c: Context, s: Installer.State): String = when (s) {
        Installer.State.Idle -> ""
        is Installer.State.Downloading -> if (s.total > 0) c.getString(
            R.string.update_progress,
            android.text.format.Formatter.formatShortFileSize(c, s.done), android.text.format.Formatter.formatShortFileSize(c, s.total),
        ) else c.getString(R.string.update_downloading)
        Installer.State.Verifying -> c.getString(R.string.update_verifying)
        Installer.State.Confirming -> c.getString(R.string.update_installing)
        is Installer.State.Failed -> s.message
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
                Installer.installFailed(context, msg)
                Toast.makeText(context, context.getString(R.string.update_failed_install, msg), Toast.LENGTH_LONG).show()
            }
        }
    }
}
