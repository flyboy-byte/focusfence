package app.focusfence

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.appcompat.app.AppCompatActivity
import app.focusfence.databinding.ActivityPartnerReportBinding
import app.focusfence.dns.Digest
import app.focusfence.dns.LogJson
import app.focusfence.dns.Pairing
import app.focusfence.sync.Sync
import app.focusfence.sync.SyncStore
import app.focusfence.sync.SyncWorker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Date

/**
 * What a partner sees for a phone they watch, app to app: whether it's
 * reporting, the latest decrypted [Digest], recent alerts, and the current
 * unlock code for anything that phone set to need a partner PIN.
 */
class PartnerReportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPartnerReportBinding
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            bindCode()
            handler.postDelayed(this, 30_000)
        }
    }

    private val mailbox: String get() = intent.getStringExtra(EXTRA_MAILBOX).orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPartnerReportBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.removeWatch.setOnClickListener {
            val w = SyncStore.watch(this, mailbox) ?: return@setOnClickListener finish()
            MaterialAlertDialogBuilder(this)
                .setMessage(getString(R.string.acc_remove) + " " + w.name + "?")
                .setPositiveButton(R.string.acc_remove) { _, _ ->
                    SyncStore.removeWatch(this, w)
                    Sync.schedule(this)
                    finish()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        Sync.kick(this)
    }

    override fun onResume() {
        super.onResume()
        bind()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun bind() {
        val w = SyncStore.watch(this, mailbox) ?: return finish()
        binding.toolbar.title = w.name
        val now = System.currentTimeMillis()
        binding.reportStatus.text = when {
            w.lastMsgMs == 0L -> getString(R.string.acc_watch_status_waiting)
            now - w.lastMsgMs > SyncWorker.STALE_AFTER_MS ->
                getString(R.string.partner_stale_alert, (now - w.lastMsgMs) / 3_600_000)
            else -> getString(R.string.acc_watch_status, DateUtils.getRelativeTimeSpanString(w.lastMsgMs))
        }
        val digest = w.digest?.let(LogJson::decode)
        binding.reportBody.text = if (digest == null) {
            getString(R.string.report_no_digest)
        } else {
            Digest.render(digest) { t -> DateFormat.getMediumDateFormat(this).format(Date(t)) + " " + DateFormat.getTimeFormat(this).format(Date(t)) }
        }
        binding.alertsBody.text = w.alerts.lines().filter { it.isNotBlank() }.reversed().joinToString("\n") { line ->
            val t = line.substringBefore('\t').toLongOrNull() ?: 0L
            DateUtils.getRelativeTimeSpanString(t).toString() + " — " + line.substringAfter('\t')
        }.ifBlank { getString(R.string.acc_none) }
        bindCode()
    }

    private fun bindCode() {
        val w = SyncStore.watch(this, mailbox) ?: return
        val now = System.currentTimeMillis()
        val code = Pairing.code(w.keys.otp, now)
        binding.unlockCode.text = code.chunked(4).joinToString(" ")
        binding.unlockHint.text = getString(R.string.report_unlock_hint, (Pairing.codeRemainingMs(now) / 60_000 + 1).toInt())
    }

    companion object {
        const val EXTRA_MAILBOX = "mailbox"
    }
}
