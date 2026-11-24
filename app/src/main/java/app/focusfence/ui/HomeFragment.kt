package app.focusfence.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import app.focusfence.CategoriesActivity
import app.focusfence.UninstallProtectionActivity
import app.focusfence.DiagnosticsActivity
import app.focusfence.MonitoringActivity
import app.focusfence.StrictModeActivity
import app.focusfence.admin.DeviceAdmin
import app.focusfence.data.CategoryStore
import app.focusfence.data.MonitorSettings
import app.focusfence.databinding.RowStatusBinding
import app.focusfence.dns.Categories
import app.focusfence.monitor.UsageCollector
import app.focusfence.dns.GuardPolicy
import app.focusfence.MainActivity
import app.focusfence.R
import app.focusfence.UiUtil
import app.focusfence.data.AdListStore
import app.focusfence.data.BlockedAppsStore
import app.focusfence.data.BlocklistStore
import app.focusfence.data.StrictStore
import app.focusfence.databinding.FragmentHomeBinding
import app.focusfence.dns.StrictLock
import app.focusfence.vpn.DnsVpnService

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val vpnConsentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                startVpn()
            } else {
                binding.vpnSwitch.isChecked = false
                Toast.makeText(requireContext(), R.string.vpn_permission_denied, Toast.LENGTH_LONG)
                    .show()
            }
        }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshState()
            // Health changes arrive as state broadcasts too.
            if (_binding != null && isAdded) refreshStatusRows()
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            refreshStrict()
            refreshState()
            _binding?.root?.postDelayed(this, 1000)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.vpnSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked == DnsVpnService.isRunning) return@setOnCheckedChangeListener
            if (checked) {
                requestVpn()
            } else {
                binding.vpnSwitch.isChecked = true // revert; stop only once the guard is satisfied
                UiUtil.guard(requireContext(), GuardPolicy.Action.TURN_OFF_VPN) { stopVpn() }
            }
        }

        // Strict mode is one line here; turning it off, and the per-action friction, live on its own screen.
        binding.strictCard.setOnClickListener { startActivity(Intent(requireContext(), StrictModeActivity::class.java)) }
        binding.strictActionButton.setOnClickListener {
            val context = requireContext()
            when (StrictStore.state(context)) {
                is StrictLock.State.Off -> StrictStore.setEnabled(context, true)
                is StrictLock.State.Locked -> StrictStore.requestUnlock(context)
                is StrictLock.State.Pending -> StrictStore.cancelUnlock(context)
                is StrictLock.State.Unlocked -> StrictStore.setEnabled(context, true) // relock early
            }
            refreshStrict()
        }
        binding.weekCard.setOnClickListener { startActivity(Intent(requireContext(), app.focusfence.StatsActivity::class.java)) }

        binding.accessibilityActionButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.adlistActionButton.setOnClickListener {
            (activity as? MainActivity)?.selectTab(R.id.nav_settings)
        }
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            requireContext(),
            stateReceiver,
            IntentFilter(DnsVpnService.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // Opening the app after an update that killed the tunnel: bring it back if the owner had it on
        // (only when the one-time consent is still granted; otherwise the switch stays off and the user taps it).
        if (!DnsVpnService.isRunning && app.focusfence.vpn.VpnWanted.get(requireContext()) &&
            VpnService.prepare(requireContext()) == null
        ) startVpn()
        refreshState()
        refreshStrict()
        refreshAttention()
        refreshStatusRows()
        binding.root.postDelayed(ticker, 1000)
    }

    override fun onPause() {
        super.onPause()
        binding.root.removeCallbacks(ticker)
        requireContext().unregisterReceiver(stateReceiver)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun refreshState() {
        val b = _binding ?: return
        val context = context ?: return
        val running = DnsVpnService.isRunning
        if (b.vpnSwitch.isChecked != running) b.vpnSwitch.isChecked = running
        // A filter problem (bypassed, lockdown, failing) shows as "not protected"
        // too: the hero must never look green while filtering isn't working.
        val problem = running && DnsVpnService.health !in setOf("ok", "offline")
        val good = running && !problem
        b.heroTitle.setText(
            when {
                good -> R.string.hero_on
                problem -> R.string.hero_problem
                else -> R.string.hero_off
            }
        )
        b.statusText.text = when {
            !running -> getString(R.string.status_inactive)
            problem -> getString(R.string.home_hero_problem_sub)
            StrictStore.state(context) is StrictLock.State.Off -> getString(R.string.home_hero_on_sub)
            else -> getString(R.string.home_hero_on_strict_sub)
        }
        fun attr(a: Int) = com.google.android.material.color.MaterialColors.getColor(b.heroCard, a)
        fun res(id: Int) = StatusColors.get(context, id)
        val container = if (good) res(R.color.good_container) else attr(com.google.android.material.R.attr.colorErrorContainer)
        val onContainer = if (good) res(R.color.on_good_container) else attr(com.google.android.material.R.attr.colorOnErrorContainer)
        val muted = if (good) res(R.color.good_container_muted) else attr(com.google.android.material.R.attr.colorOnErrorContainer)
        val accent = if (good) res(R.color.good_tile) else attr(com.google.android.material.R.attr.colorError)
        val onAccent = if (good) res(R.color.good) else attr(com.google.android.material.R.attr.colorOnError)
        b.heroCard.setCardBackgroundColor(container)
        b.heroTitle.setTextColor(onContainer)
        b.statusText.setTextColor(muted)
        b.heroIconBg.background.mutate().setTint(accent)
        b.heroIcon.setColorFilter(onAccent)

        b.heroDetail.visibility = if (running) View.VISIBLE else View.GONE
        if (running) {
            val partners = partnerCount(context)
            val lastSync = app.focusfence.sync.SyncStore.lastOk(context)
            val n = DnsVpnService.blockedCount.get().toInt()
            val blocked = resources.getQuantityString(R.plurals.home_detail_blocked, n, n)
            b.heroDetail.text = when {
                partners == 0 -> blocked
                lastSync == 0L -> getString(R.string.home_detail_partner_waiting, blocked)
                else -> getString(
                    R.string.home_detail_partner_synced, blocked,
                    android.text.format.DateUtils.getRelativeTimeSpanString(lastSync, System.currentTimeMillis(), 60_000L).toString().lowercase(),
                )
            }
            b.heroDetail.setTextColor(muted)
        }
    }

    private fun partnerCount(context: Context): Int =
        app.focusfence.sync.SyncStore.ownPairings(context).size +
            (if (app.focusfence.sync.SyncStore.deviceId(context) != null) 1 else 0)

    private fun refreshStrict() {
        val b = _binding ?: return
        val context = context ?: return
        val state = StrictStore.state(context)
        fun attr(a: Int) = com.google.android.material.color.MaterialColors.getColor(b.strictCard, a)
        val on = state !is StrictLock.State.Off
        b.strictIcon.setColorFilter(
            attr(if (on) com.google.android.material.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurfaceVariant)
        )
        when (state) {
            is StrictLock.State.Off -> {
                b.strictStatusText.text = getString(R.string.home_strict_off)
                b.strictActionButton.text = getString(R.string.home_strict_turn_on)
            }
            is StrictLock.State.Locked -> {
                b.strictStatusText.text = getString(R.string.home_strict_locked)
                b.strictActionButton.text = getString(R.string.home_strict_unlock)
            }
            is StrictLock.State.Pending -> {
                b.strictStatusText.text = getString(R.string.home_strict_pending, UiUtil.formatCountdown(state.remainingMs))
                b.strictActionButton.text = getString(android.R.string.cancel)
            }
            is StrictLock.State.Unlocked -> {
                b.strictStatusText.text = getString(R.string.home_strict_unlocked, UiUtil.formatCountdown(state.remainingMs))
                b.strictActionButton.text = getString(R.string.home_strict_relock)
            }
        }
    }

    private enum class Layer { ON, OFF, ATTENTION }

    /**
     * The layers at a glance: one row each, opening the screen that controls it. Rebuilt on resume (states only
     * change in those screens) and on state broadcasts, not on the 1 s ticker.
     */
    private fun refreshStatusRows() {
        val b = _binding ?: return
        val context = requireContext()
        b.statusContainer.removeAllViews()
        var on = 0
        var total = 0

        fun row(labelRes: Int, state: Layer, value: String, target: Class<*>?, onClick: (() -> Unit)? = null) {
            val r = RowStatusBinding.inflate(layoutInflater, b.statusContainer, true)
            val (icon, color) = when (state) {
                Layer.ON -> R.drawable.ic_check_circle to StatusColors.get(context, R.color.good)
                Layer.ATTENTION -> R.drawable.ic_alert_circle to StatusColors.get(context, R.color.warn)
                Layer.OFF -> R.drawable.ic_circle_outline to
                    com.google.android.material.color.MaterialColors.getColor(r.root, com.google.android.material.R.attr.colorOutline)
            }
            r.statusIcon.setImageResource(icon)
            r.statusIcon.setColorFilter(color)
            r.statusLabel.setText(labelRes)
            r.statusValue.text = value
            if (state == Layer.ATTENTION) r.statusValue.setTextColor(color)
            r.root.contentDescription = getString(labelRes) + ": " + value
            r.root.setOnClickListener { if (onClick != null) onClick() else if (target != null) startActivity(Intent(context, target)) }
            total++
            if (state == Layer.ON) on++
        }

        val policies = CategoryStore.policies(context).values
        val bundlesOn = policies.count { it != Categories.Policy.OFF }
        val bundlesBlocking = policies.count { it == Categories.Policy.BLOCK }
        row(
            R.string.home_row_bundles, if (bundlesOn > 0) Layer.ON else Layer.OFF,
            if (bundlesOn == 0) getString(R.string.home_off) else getString(R.string.home_lists_blocking, bundlesBlocking),
            CategoriesActivity::class.java,
        )

        val partners = partnerCount(context)
        val syncError = app.focusfence.sync.SyncStore.lastError(context) != null
        row(
            R.string.home_row_partners,
            when {
                partners == 0 -> Layer.OFF
                syncError -> Layer.ATTENTION
                else -> Layer.ON
            },
            when {
                partners == 0 -> getString(R.string.home_off)
                syncError -> getString(R.string.home_partners_sync_error)
                else -> resources.getQuantityString(R.plurals.home_partners_short, partners, partners)
            },
            app.focusfence.AccountabilityActivity::class.java,
        )

        val shots = MonitorSettings.screenshotsEnabled(context)
        val text = MonitorSettings.textEnabled(context)
        row(
            R.string.home_row_monitoring, if (shots || text) Layer.ON else Layer.OFF,
            getString(
                when {
                    shots && text -> R.string.home_mon_both
                    shots -> R.string.home_mon_shots
                    text -> R.string.home_mon_text
                    else -> R.string.home_off
                }
            ),
            MonitoringActivity::class.java,
        )

        val admin = DeviceAdmin.isDeviceOwner(context) || DeviceAdmin.isAdminActive(context)
        row(
            R.string.home_row_managed, if (admin) Layer.ON else Layer.OFF,
            getString(if (admin) R.string.home_on else R.string.home_off),
            UninstallProtectionActivity::class.java,
        )

        val timeline = UsageCollector.hasAccess(context)
        row(
            R.string.home_row_timeline, if (timeline) Layer.ON else Layer.OFF,
            getString(if (timeline) R.string.home_on else R.string.home_off),
            DiagnosticsActivity::class.java,
        )

        // Battery optimization: the most common reason protection silently stops. Not optional, so "needs you".
        val exempt = UiUtil.isBatteryExempt(context)
        row(
            R.string.home_row_battery, if (exempt) Layer.ON else Layer.ATTENTION,
            getString(if (exempt) R.string.home_battery_ok else R.string.home_battery_fix),
            null,
        ) { if (!exempt) UiUtil.requestBatteryExemption(requireContext()) }

        // Filter health only earns a row when something's wrong; "working" is what the hero already says.
        val health = DnsVpnService.health
        if (DnsVpnService.isRunning && health != "ok") {
            row(
                R.string.home_row_health, if (health == "offline") Layer.OFF else Layer.ATTENTION,
                getString(
                    when (health) {
                        "offline" -> R.string.home_health_offline
                        "degraded" -> R.string.home_health_degraded
                        "upstream_down" -> R.string.home_health_down
                        "silent" -> R.string.home_health_silent
                        "bypassed" -> R.string.home_health_bypassed
                        "lockdown" -> R.string.home_health_lockdown
                        else -> R.string.home_health_off
                    }
                ),
                DiagnosticsActivity::class.java,
            )
        }

        b.layersCount.text = getString(R.string.home_layers_count, on, total)
    }

    private fun refreshAttention() {
        val b = _binding ?: return
        val context = requireContext()
        val needsAccessibility = (
            BlocklistStore.rules(context).any { it.hasPathExceptions } ||
                BlockedAppsStore.get(context).isNotEmpty()
            ) && !UiUtil.isUrlFilterEnabled(context)
        b.accessibilityRow.visibility = if (needsAccessibility) View.VISIBLE else View.GONE

        val needsAdList = AdListStore.isEnabled(context) && AdListStore.entryCount(context) == 0
        b.adlistRow.visibility = if (needsAdList) View.VISIBLE else View.GONE

        b.attentionTitle.setTextColor(StatusColors.get(context, R.color.warn))
        b.attentionCard.visibility =
            if (needsAccessibility || needsAdList) View.VISIBLE else View.GONE
    }

    private fun requestVpn() {
        val consentIntent = VpnService.prepare(requireContext())
        if (consentIntent != null) {
            vpnConsentLauncher.launch(consentIntent)
        } else {
            startVpn()
        }
    }

    private fun startVpn() {
        val context = requireContext()
        ContextCompat.startForegroundService(
            context,
            Intent(context, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_START),
        )
        binding.root.postDelayed({ refreshState() }, 300)
    }

    private fun stopVpn() {
        val context = requireContext()
        context.startService(
            Intent(context, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_STOP),
        )
        binding.root.postDelayed({ refreshState() }, 300)
    }
}
