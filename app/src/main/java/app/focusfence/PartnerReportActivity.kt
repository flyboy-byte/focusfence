package app.focusfence

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.appcompat.app.AppCompatActivity
import app.focusfence.databinding.ActivityPartnerReportBinding
import app.focusfence.dns.Digest
import app.focusfence.dns.InviteLink
import app.focusfence.dns.LogJson
import app.focusfence.dns.Pairing
import app.focusfence.sync.Api
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
    private var webShown = false

    // With the server report on screen, the unlock code (and Remove) are one tap away in the toolbar.
    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        if (webShown) menu.add(0, MENU_CODE, 0, R.string.report_unlock_title)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId != MENU_CODE) return super.onOptionsItemSelected(item)
        val w = SyncStore.watch(this, mailbox) ?: return true
        val now = System.currentTimeMillis()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.report_unlock_title)
            .setMessage(Pairing.code(w.keys.otp, now).chunked(4).joinToString(" ") + "\n\n" +
                getString(R.string.report_unlock_hint, (Pairing.codeRemainingMs(now) / 60_000 + 1).toInt()))
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(R.string.acc_remove) { _, _ -> binding.removeWatch.performClick() }
            .show()
        return true
    }

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
                    // Plan 12: tell the server first, so the owner and their other partners hear that this partner left.
                    // Best effort: offline, the server isn't told and still lists this partner.
                    InviteLink.leaveTarget(w.reportUrl)?.let { (origin, path) ->
                        Thread { runCatching { Api(origin).post(path, org.json.JSONObject()) } }.start()
                    }
                    SyncStore.removeWatch(this, w)
                    Sync.schedule(this)
                    finish()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        Sync.kick(this)
        SyncStore.watch(this, mailbox)?.reportUrl?.takeIf { it.isNotEmpty() }?.let(::openReport)
    }

    /**
     * The server's report page, the one an email partner opens: same sealed data, same screenshots. The key is after
     * '#', so the server never gets it; the page's own script opens the report here. Only this server's pages load in
     * the view; any other link goes to the browser.
     */
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun openReport(url: String) {
        val origin = android.net.Uri.parse(url).let { "${it.scheme}://${it.authority}" }
        binding.reportWeb.visibility = android.view.View.VISIBLE
        binding.reportScroll.visibility = android.view.View.GONE
        webShown = true
        invalidateOptionsMenu()
        binding.reportWeb.settings.javaScriptEnabled = true // the page's own report.js opens the sealed report
        binding.reportWeb.settings.domStorageEnabled = true // where it keeps the key
        binding.reportWeb.settings.allowFileAccess = false
        binding.reportWeb.webViewClient = object : android.webkit.WebViewClient() {
            override fun shouldOverrideUrlLoading(view: android.webkit.WebView, req: android.webkit.WebResourceRequest): Boolean {
                val u = req.url
                if ("${u.scheme}://${u.authority}" == origin) return false
                runCatching { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, u)) }
                return true
            }
        }
        binding.reportWeb.loadUrl(url)
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
        if (w.reportUrl.isNotEmpty()) { bindCode(); return } // the server page shows status and report
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
        private const val MENU_CODE = 1
    }
}
