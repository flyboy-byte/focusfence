package app.focusfence

import android.content.Intent
import android.os.Bundle
import android.view.View
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
        c.addView(text(getString(R.string.setup_intro), top = 0, style = com.google.android.material.R.style.TextAppearance_Material3_BodyMedium, muted = true))
        val all = layers()
        c.addView(section(R.string.setup_section_recommended))
        all.filter { it.recommended }.forEach { c.addView(card(it)) }
        c.addView(section(R.string.setup_section_optional))
        all.filter { !it.recommended }.forEach { c.addView(card(it)) }
        c.addView(text(getString(R.string.setup_footnote), top = 20, style = com.google.android.material.R.style.TextAppearance_Material3_BodySmall, muted = true))
    }

    private fun section(title: Int) = text(getString(title), top = 24,
        style = com.google.android.material.R.style.TextAppearance_Material3_TitleSmall, color = attr(androidx.appcompat.R.attr.colorPrimary))

    /** One tappable card per layer: name and an On/Off chip on top, what it does, then its cost in small muted type. */
    private fun card(l: Layer): View = com.google.android.material.card.MaterialCardView(this).apply {
        radius = dp(16).toFloat()
        cardElevation = 0f
        isClickable = true
        isFocusable = true
        setContentPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(10) }
        setOnClickListener { if (l.open != MainActivity::class.java) startActivity(Intent(context, l.open)) else finish() }
        contentDescription = getString(l.title) + ", " + getString(if (l.on) R.string.setup_on else R.string.setup_off)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(text(getString(l.title), top = 0, style = com.google.android.material.R.style.TextAppearance_Material3_TitleMedium).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(statusChip(l.on))
            })
            addView(text(getString(l.what), top = 6, style = com.google.android.material.R.style.TextAppearance_Material3_BodyMedium))
            addView(text(getString(l.cost), top = 8, style = com.google.android.material.R.style.TextAppearance_Material3_BodySmall, muted = true))
        })
    }

    private fun statusChip(on: Boolean) = com.google.android.material.chip.Chip(this).apply {
        text = getString(if (on) R.string.setup_on else R.string.setup_off)
        isClickable = false
        isCheckable = false
        isFocusable = false
        if (on) {
            chipBackgroundColor = android.content.res.ColorStateList.valueOf(attr(com.google.android.material.R.attr.colorSecondaryContainer))
            setTextColor(attr(com.google.android.material.R.attr.colorOnSecondaryContainer))
            chipStrokeWidth = 0f
        } else {
            chipBackgroundColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
            setTextColor(attr(com.google.android.material.R.attr.colorOnSurfaceVariant))
            chipStrokeColor = android.content.res.ColorStateList.valueOf(attr(com.google.android.material.R.attr.colorOutline))
            chipStrokeWidth = dp(1).toFloat()
        }
    }

    private fun attr(id: Int): Int = com.google.android.material.color.MaterialColors.getColor(binding.root, id)

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun text(s: CharSequence, top: Int = 8, style: Int = com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
                     muted: Boolean = false, color: Int? = null) = TextView(this).apply {
        text = s
        androidx.core.widget.TextViewCompat.setTextAppearance(this, style)
        setPadding(0, dp(top), 0, 0)
        if (color != null) setTextColor(color)
        else if (muted) setTextColor(attr(com.google.android.material.R.attr.colorOnSurfaceVariant))
    }
}
