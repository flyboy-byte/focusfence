package app.focusfence

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import app.focusfence.admin.DeviceAdmin
import app.focusfence.data.GroupStore
import app.focusfence.data.LogSettings
import app.focusfence.data.MonitorSettings
import app.focusfence.data.StrictStore
import app.focusfence.databinding.ActivityGroupBinding
import app.focusfence.sync.SyncStore
import app.focusfence.vpn.DnsVpnService

/**
 * "Set up accountability": a checklist of the optional layers, in the order we'd suggest them, each with whether it is
 * on, what it costs (measured on one Pixel 9 over five days, see docs/plans/05-group-and-release.md) and a button to the
 * screen that owns it. Nothing is forced: every layer is independent, and with all of them off Focus Fence is the same
 * light on-device blocker. This screen only reads settings and opens other screens; it changes nothing itself.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGroupBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGroupBinding.inflate(layoutInflater) // same plain scroll-view skeleton as the group screen
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setTitle(R.string.setup_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.title = getString(R.string.setup_title)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private class Layer(val title: Int, val what: Int, val cost: Int, val recommended: Boolean, val on: Boolean, val open: Class<*>)

    private fun layers(): List<Layer> {
        val server = SyncStore.serverUrl(this).startsWith("https://")
        val partners = server && (SyncStore.deviceId(this) != null || SyncStore.ownPairings(this).isNotEmpty() || SyncStore.watches(this).isNotEmpty())
        val admin = DeviceAdmin.isDeviceOwner(this) || DeviceAdmin.isAdminActive(this)
        return listOf(
            Layer(R.string.setup_filter, R.string.setup_filter_what, R.string.setup_filter_cost, true, DnsVpnService.isRunning, MainActivity::class.java),
            Layer(R.string.setup_log, R.string.setup_log_what, R.string.setup_log_cost, true, LogSettings.logAllowedDns(this), DiagnosticsActivity::class.java),
            Layer(R.string.setup_strict, R.string.setup_strict_what, R.string.setup_strict_cost, true, StrictStore.isEnabled(this), StrictModeActivity::class.java),
            Layer(R.string.setup_admin, R.string.setup_admin_what, R.string.setup_admin_cost, true, admin, DeviceOwnerActivity::class.java),
            Layer(R.string.setup_partners, R.string.setup_partners_what, R.string.setup_partners_cost, true, partners, AccountabilityActivity::class.java),
            Layer(R.string.setup_group, R.string.setup_group_what, R.string.setup_group_cost, false, GroupStore.active(this), GroupActivity::class.java),
            Layer(R.string.setup_text, R.string.setup_text_what, R.string.setup_text_cost, false, MonitorSettings.textEnabled(this), MonitoringActivity::class.java),
            Layer(R.string.setup_shots, R.string.setup_shots_what, R.string.setup_shots_cost, false,
                MonitorSettings.screenshotsEnabled(this) && MonitorSettings.aiEnabled(this), MonitoringActivity::class.java),
        )
    }

    private fun render() {
        val c = binding.content
        c.removeAllViews()
        c.addView(text(getString(R.string.setup_intro), top = 0))
        for (l in layers()) c.addView(row(l))
        c.addView(text(getString(R.string.setup_footnote), top = 20))
    }

    private fun row(l: Layer): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(18), 0, 0)
        val tag = getString(if (l.recommended) R.string.setup_recommended else R.string.setup_optional)
        addView(text("${getString(l.title)} · $tag", bold = true, top = 0))
        addView(text(getString(if (l.on) R.string.setup_on else R.string.setup_off), top = 2))
        addView(text(getString(l.what), top = 4))
        addView(text(getString(l.cost), top = 4, small = true))
        addView(Button(context).apply {
            text = getString(R.string.setup_open)
            setOnClickListener { if (l.open != MainActivity::class.java) startActivity(Intent(context, l.open)) else finish() }
        })
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun text(s: CharSequence, top: Int = 8, bold: Boolean = false, small: Boolean = false) = TextView(this).apply {
        text = s
        setPadding(0, dp(top), 0, 0)
        textSize = if (small) 12f else 14f
        if (bold) { setTypeface(typeface, android.graphics.Typeface.BOLD); textSize = 16f }
    }
}
