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
import app.focusfence.DeviceOwnerActivity
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

        // Per-action friction lives on its own screen; the card links there
        // (it used to be a duplicate "Friction" status row).
        binding.frictionButton.setOnClickListener { startActivity(Intent(requireContext(), StrictModeActivity::class.java)) }
        binding.strictSwitch.setOnCheckedChangeListener { _, checked ->
            val context = requireContext()
            if (checked == StrictStore.isEnabled(context)) return@setOnCheckedChangeListener
            if (checked) {
                StrictStore.setEnabled(context, true)
                refreshStrict()
            } else {
                binding.strictSwitch.isChecked = true // revert; disable only once the guard is satisfied
                UiUtil.guard(context, GuardPolicy.Action.DISABLE_STRICT) {
                    StrictStore.setEnabled(context, false)
                    refreshStrict()
                }
            }
        }

        binding.strictActionButton.setOnClickListener {
            val context = requireContext()
            when (StrictStore.state(context)) {
                is StrictLock.State.Locked -> StrictStore.requestUnlock(context)
                is StrictLock.State.Pending -> StrictStore.cancelUnlock(context)
                else -> {}
            }
            refreshStrict()
        }

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
        val running = DnsVpnService.isRunning
        if (b.vpnSwitch.isChecked != running) b.vpnSwitch.isChecked = running
        b.statusText.text = if (running) {
            getString(R.string.status_active, DnsVpnService.blockedCount.get())
        } else {
            getString(R.string.status_inactive)
        }
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
        fun c(attr: Int) = com.google.android.material.color.MaterialColors.getColor(b.heroCard, attr)
        val container = c(if (good) com.google.android.material.R.attr.colorPrimaryContainer else com.google.android.material.R.attr.colorErrorContainer)
        val onContainer = c(if (good) com.google.android.material.R.attr.colorOnPrimaryContainer else com.google.android.material.R.attr.colorOnErrorContainer)
        val accent = c(if (good) com.google.android.material.R.attr.colorPrimary else com.google.android.material.R.attr.colorError)
        val onAccent = c(if (good) com.google.android.material.R.attr.colorOnPrimary else com.google.android.material.R.attr.colorOnError)
        b.heroCard.setCardBackgroundColor(container)
        b.heroTitle.setTextColor(onContainer)
        b.statusText.setTextColor(onContainer)
        b.heroIconBg.background.mutate().setTint(accent)
        b.heroIcon.setColorFilter(onAccent)
    }

    private fun refreshStrict() {
        val b = _binding ?: return
        val context = context ?: return
        val state = StrictStore.state(context)
        val enabled = state !is StrictLock.State.Off
        if (b.strictSwitch.isChecked != enabled) b.strictSwitch.isChecked = enabled
        when (state) {
            is StrictLock.State.Off -> {
                b.strictStatusText.text = getString(R.string.strict_off)
                b.strictActionButton.visibility = View.GONE
            }
            is StrictLock.State.Locked -> {
                b.strictStatusText.text = getString(R.string.strict_locked)
                b.strictActionButton.visibility = View.VISIBLE
                b.strictActionButton.text = getString(R.string.strict_request_unlock)
            }
            is StrictLock.State.Pending -> {
                b.strictStatusText.text =
                    getString(R.string.strict_pending, UiUtil.formatCountdown(state.remainingMs))
                b.strictActionButton.visibility = View.VISIBLE
                b.strictActionButton.text = getString(android.R.string.cancel)
            }
            is StrictLock.State.Unlocked -> {
                b.strictStatusText.text =
                    getString(R.string.strict_unlocked, UiUtil.formatCountdown(state.remainingMs))
                b.strictActionButton.visibility = View.GONE
            }
        }
    }

    /**
     * Status at a glance: one row per protection component with its current
     * state, each opening the screen that controls it. Rebuilt on resume (states
     * only change in those screens), not on the 1s ticker.
     */
    private fun refreshStatusRows() {
        val b = _binding ?: return
        val context = requireContext()
        b.statusContainer.removeAllViews()

        fun row(labelRes: Int, on: Boolean, value: String, target: Class<*>?, onClick: (() -> Unit)? = null) {
            val r = RowStatusBinding.inflate(layoutInflater, b.statusContainer, true)
            r.statusDot.text = if (on) "\u25CF" else "\u25CB"
            // Shape carries the state (filled/hollow); colour reinforces it.
            r.statusDot.setTextColor(
                com.google.android.material.color.MaterialColors.getColor(
                    r.statusDot,
                    if (on) com.google.android.material.R.attr.colorPrimary else com.google.android.material.R.attr.colorOutline,
                )
            )
            r.statusLabel.setText(labelRes)
            r.statusValue.text = value
            r.root.setOnClickListener { if (onClick != null) onClick() else if (target != null) startActivity(Intent(context, target)) }
        }

        // Battery optimization: the most common reason protection silently stops.
        val exempt = UiUtil.isBatteryExempt(context)
        row(
            R.string.home_row_battery, exempt,
            getString(if (exempt) R.string.home_battery_ok else R.string.home_battery_fix),
            null,
        ) { if (!exempt) UiUtil.requestBatteryExemption(requireContext()) }

        val health = DnsVpnService.health
        row(
            R.string.home_row_health, health == "ok",
            getString(
                when (health) {
                    "ok" -> R.string.home_health_ok
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

        val partnerCount = app.focusfence.sync.SyncStore.ownPairings(context).size +
            (if (app.focusfence.sync.SyncStore.deviceId(context) != null) 1 else 0)
        val lastSync = app.focusfence.sync.SyncStore.lastOk(context)
        row(
            R.string.home_row_partners, partnerCount > 0 && app.focusfence.sync.SyncStore.lastError(context) == null,
            if (partnerCount == 0) getString(R.string.home_partners_none)
            else getString(
                R.string.home_partners_count, partnerCount,
                if (lastSync == 0L) getString(R.string.acc_status_never)
                else android.text.format.DateUtils.getRelativeTimeSpanString(lastSync).toString(),
            ),
            app.focusfence.AccountabilityActivity::class.java,
        )

        val policies = CategoryStore.policies(context).values
        val bundlesOn = policies.count { it != Categories.Policy.OFF }
        val bundlesBlocking = policies.count { it == Categories.Policy.BLOCK }
        row(
            R.string.home_row_bundles, bundlesOn > 0,
            if (bundlesOn == 0) getString(R.string.home_none_on)
            else resources.getQuantityString(R.plurals.home_bundles_on, bundlesOn, bundlesOn, bundlesBlocking),
            CategoriesActivity::class.java,
        )

        val shots = MonitorSettings.screenshotsEnabled(context)
        val text = MonitorSettings.textEnabled(context)
        row(
            R.string.home_row_monitoring, shots || text,
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

        val timeline = UsageCollector.hasAccess(context)
        row(
            R.string.home_row_timeline, timeline,
            getString(if (timeline) R.string.home_timeline_on else R.string.home_timeline_off),
            DiagnosticsActivity::class.java,
        )


        // Device admin is the default protection (EA's model); device owner is the advanced tier.
        val owner = DeviceAdmin.isDeviceOwner(context)
        val admin = owner || DeviceAdmin.isAdminActive(context)
        row(
            R.string.home_row_managed, admin,
            getString(
                when {
                    owner -> R.string.home_managed_on
                    admin -> R.string.home_managed_admin
                    else -> R.string.home_managed_off
                }
            ),
            DeviceOwnerActivity::class.java,
        )

        row(R.string.home_row_log, true, getString(R.string.home_log_value), DiagnosticsActivity::class.java)
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
