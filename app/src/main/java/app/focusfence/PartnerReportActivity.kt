package app.focusfence

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import app.focusfence.databinding.ActivityPartnerReportBinding
import app.focusfence.sync.Accountability
import app.focusfence.sync.Sync
import app.focusfence.sync.SyncStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * A report this user sees (plan 13): the server's report page, the same one a partner in a browser reads, opened
 * with this phone's copy of the report key. Until the owner's phone has sealed that over, it says so and checks again.
 */
class PartnerReportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPartnerReportBinding
    private val handler = Handler(Looper.getMainLooper())
    private val recheck = object : Runnable {
        override fun run() {
            bind()
            if (!webShown) handler.postDelayed(this, 15_000)
        }
    }
    private val seenId: String get() = intent.getStringExtra(EXTRA_SEEN).orEmpty()
    private var webShown = false

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        // Always in the menu: with the report on screen the page's own Leave button is far down, and a report whose
        // owner removed you has nothing else to tap.
        menu.add(0, MENU_REMOVE, 1, R.string.report_stop_seeing)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == MENU_REMOVE) { binding.removeWatch.performClick(); return true }
        return super.onOptionsItemSelected(item)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPartnerReportBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.removeWatch.setOnClickListener {
            val s = SyncStore.seen(this, seenId) ?: return@setOnClickListener finish()
            MaterialAlertDialogBuilder(this)
                .setMessage(getString(R.string.report_stop_confirm, s.owner))
                .setPositiveButton(R.string.report_stop_seeing) { _, _ ->
                    val ctx = applicationContext
                    Thread { Accountability.leave(ctx, s) }.start()
                    finish()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        Sync.kick(this)
    }

    override fun onResume() {
        super.onResume()
        handler.post(recheck)
    }

    override fun onPause() {
        handler.removeCallbacks(recheck)
        super.onPause()
    }

    private fun bind() {
        val s = SyncStore.seen(this, seenId) ?: return finish()
        binding.toolbar.title = getString(R.string.seen_title, s.owner)
        when {
            s.linkGone -> waiting(false, getString(R.string.seen_gone_title), getString(R.string.seen_gone_body, s.owner))
            s.reportKey.isEmpty() -> {
                waiting(true, getString(R.string.seen_waiting_title, s.owner), getString(R.string.seen_waiting_body, s.owner))
                Sync.kick(this)
            }
            !webShown -> openReport(s)
        }
    }

    private fun waiting(busy: Boolean, title: String, body: String) {
        binding.reportScroll.visibility = View.VISIBLE
        binding.reportWeb.visibility = View.GONE
        binding.reportProgress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.reportStatus.text = title
        binding.reportBody.text = body
    }

    /**
     * The key goes after '#', which a browser never sends to a server; the page's own script opens the report here.
     * `app=1` tells it this is the app, so it never offers to add the view as a browser. Only this server's pages load
     * in the view; any other link goes to the browser.
     */
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun openReport(s: SyncStore.Seen) {
        webShown = true
        binding.reportWeb.visibility = View.VISIBLE
        binding.reportScroll.visibility = View.GONE
        binding.reportWeb.settings.javaScriptEnabled = true
        binding.reportWeb.settings.domStorageEnabled = true
        binding.reportWeb.settings.allowFileAccess = false
        binding.reportWeb.webViewClient = object : android.webkit.WebViewClient() {
            override fun shouldOverrideUrlLoading(view: android.webkit.WebView, req: android.webkit.WebResourceRequest): Boolean {
                val u = req.url
                if ("${u.scheme}://${u.authority}" == s.server) return false
                runCatching { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, u)) }
                return true
            }
        }
        binding.reportWeb.loadUrl("${s.reportUrl}#k=${s.reportKey}&app=1")
    }

    companion object {
        const val EXTRA_SEEN = "seen"
        private const val MENU_REMOVE = 2
    }
}
