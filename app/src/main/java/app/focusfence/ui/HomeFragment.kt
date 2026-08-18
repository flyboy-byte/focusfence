package app.focusfence.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.net.VpnService
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import app.focusfence.MainActivity
import app.focusfence.R
import app.focusfence.UiUtil
import app.focusfence.data.BlockedAppsStore
import app.focusfence.data.BlocklistStore
import app.focusfence.data.FilterListStore
import app.focusfence.data.StatsStore
import app.focusfence.data.StrictStore
import app.focusfence.data.UsageStore
import app.focusfence.databinding.FragmentHomeBinding
import app.focusfence.databinding.ItemUpNextBinding
import app.focusfence.dns.ActivityMeter
import app.focusfence.dns.StatsCodec
import app.focusfence.dns.StrictLock
import app.focusfence.vpn.DnsVpnService
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    /** The meter's bars, created once and then only resized. */
    private val meterBars = mutableListOf<View>()
    private val numberFormat: NumberFormat = NumberFormat.getIntegerInstance()

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
        override fun onReceive(context: Context?, intent: Intent?) = refreshState()
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
        buildMeterBars()

        binding.vpnSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked == DnsVpnService.isRunning) return@setOnCheckedChangeListener
            if (checked) {
                requestVpn()
            } else if (StrictStore.isLocked(requireContext())) {
                binding.vpnSwitch.isChecked = true
                UiUtil.showStrictLockedDialog(requireContext()) { refreshStrict() }
            } else {
                stopVpn()
            }
        }

        binding.strictSwitch.setOnCheckedChangeListener { _, checked ->
            val context = requireContext()
            if (checked == StrictStore.isEnabled(context)) return@setOnCheckedChangeListener
            if (checked) {
                StrictStore.setEnabled(context, true)
            } else if (StrictStore.isLocked(context)) {
                binding.strictSwitch.isChecked = true
                UiUtil.showStrictLockedDialog(context) { refreshStrict() }
                return@setOnCheckedChangeListener
            } else {
                StrictStore.setEnabled(context, false)
            }
            refreshStrict()
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
        binding.root.postDelayed(ticker, 1000)
    }

    override fun onPause() {
        super.onPause()
        binding.root.removeCallbacks(ticker)
        requireContext().unregisterReceiver(stateReceiver)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        meterBars.clear()
        _binding = null
    }

    private fun buildMeterBars() {
        val container = binding.meterContainer
        val gap = dp(3)
        repeat(ActivityMeter.DEFAULT_MINUTES) { index ->
            val bar = View(requireContext())
            bar.setBackgroundResource(R.drawable.bg_meter_bar)
            val params = LinearLayout.LayoutParams(0, dp(2), 1f)
            params.gravity = Gravity.BOTTOM
            if (index < ActivityMeter.DEFAULT_MINUTES - 1) params.marginEnd = gap
            container.addView(bar, params)
            meterBars.add(bar)
        }
    }

    private fun refreshState() {
        val b = _binding ?: return
        val context = context ?: return
        val running = DnsVpnService.isRunning
        if (b.vpnSwitch.isChecked != running) b.vpnSwitch.isChecked = running

        b.heroCard.setBackgroundResource(
            if (running) R.drawable.bg_card_accent else R.drawable.bg_card
        )
        b.statusLabel.setText(
            if (running) R.string.status_label_on else R.string.status_label_off
        )
        b.statusLabel.setTextColor(color(if (running) R.color.accent else R.color.text_disabled))
        b.statusText.setText(
            if (running) R.string.status_sub_active else R.string.status_inactive
        )
        b.counterUnit.setText(
            if (running) R.string.counter_unit else R.string.counter_unit_off
        )

        val today = LocalDate.now().toString()
        val todayStats = StatsStore.get(context, today)
        // The stored total lags by up to one batched flush; add what the service
        // is still holding so the counter is exact rather than stepping in 25s.
        val total = StatsCodec.total(todayStats) +
            if (running) DnsVpnService.unflushedBlocks.get().toInt() else 0
        b.counterText.text = numberFormat.format(total)

        val listBlocks = todayStats.entries.sumOf {
            if (StatsCodec.isListBucket(it.key) && it.key != StatsCodec.KEY_GUARD) it.value else 0
        }
        b.tileLists.text = numberFormat.format(listBlocks)
        b.tileRules.text = (
            BlocklistStore.rules(context).size + BlockedAppsStore.get(context).size
            ).toString()
        b.tileUptime.text = formatUptime(running)

        refreshMeter(running)
        refreshUpNext(running, today)
    }

    private fun refreshMeter(running: Boolean) {
        val b = _binding ?: return
        if (meterBars.isEmpty()) return
        val counts = DnsVpnService.activityMeter.snapshot(SystemClock.elapsedRealtime())
        val peak = (counts.maxOrNull() ?: 0).coerceAtLeast(1)
        val height = b.meterContainer.height.takeIf { it > 0 } ?: dp(54)
        val floor = dp(2)
        // Off, the meter stays legible but drains of colour rather than
        // disappearing — the shape is still the last 20 minutes of history.
        val liveTint = color(if (running) R.color.accent else R.color.text_disabled)
        val pastTint = color(if (running) R.color.accent_meter else R.color.fill_high)

        for ((index, bar) in meterBars.withIndex()) {
            val fraction = counts[index].toFloat() / peak
            val params = bar.layoutParams as LinearLayout.LayoutParams
            val target = (height * fraction).toInt().coerceAtLeast(floor)
            if (params.height != target) {
                params.height = target
                bar.layoutParams = params
            }
            bar.backgroundTintList = ColorStateList.valueOf(
                if (index == meterBars.lastIndex) liveTint else pastTint
            )
        }
        b.meterPeak.text = getString(R.string.meter_peak, counts.maxOrNull() ?: 0)
    }

    /**
     * The next thing a rule will actually do: budgets counting down, windows
     * reopening. Anything blocked around the clock has no "next", so it is left
     * out rather than padding the card with rows that never change.
     */
    private fun refreshUpNext(running: Boolean, dayKey: String) {
        val b = _binding ?: return
        val context = context ?: return
        val container = b.upNextContainer
        container.removeAllViews()

        if (!running) {
            addUpNext(
                container,
                getString(R.string.up_next_off),
                getString(R.string.up_next_off_detail),
                R.color.text_tertiary,
            )
            return
        }

        val rules = BlocklistStore.rules(context)
        var added = 0
        for (rule in rules.filter { it.hasBudget }.sortedBy { it.domain }) {
            val usedMinutes =
                (UsageStore.usedMillisOn(context, rule.domain, dayKey) / 60_000L).toInt()
            val left = rule.budgetMinutes - usedMinutes
            val detail = if (left > 0) {
                getString(R.string.up_next_minutes_left, left)
            } else {
                getString(R.string.up_next_used_up)
            }
            addUpNext(
                container,
                rule.domain,
                detail,
                if (left > 0) R.color.mode_budget else R.color.danger,
            )
            added++
        }
        for (rule in rules.filter { it.isScheduled }.sortedBy { it.domain }) {
            addUpNext(
                container,
                rule.domain,
                getString(R.string.up_next_opens, formatMinute(rule.endMinute)),
                R.color.mode_window,
            )
            added++
        }
        if (added == 0) {
            addUpNext(
                container,
                getString(R.string.up_next_idle),
                getString(R.string.up_next_idle_detail),
                R.color.text_tertiary,
            )
        }
    }

    private fun addUpNext(parent: ViewGroup, domain: String, detail: String, colorRes: Int) {
        val row = ItemUpNextBinding.inflate(layoutInflater, parent, false)
        val tint = ColorStateList.valueOf(color(colorRes))
        row.upNextDot.backgroundTintList = tint
        row.upNextDomain.text = domain
        row.upNextDetail.text = detail
        row.upNextDetail.setTextColor(tint)
        parent.addView(row.root)
    }

    private fun formatUptime(running: Boolean): String {
        if (!running) return getString(R.string.uptime_none)
        val startedAt = DnsVpnService.startedAtMillis
        if (startedAt == 0L) return getString(R.string.uptime_none)
        val minutes = ((SystemClock.elapsedRealtime() - startedAt) / 60_000L).coerceAtLeast(0L)
        return if (minutes >= 60) {
            getString(R.string.uptime_hours, minutes / 60, minutes % 60)
        } else {
            getString(R.string.uptime_minutes, minutes)
        }
    }

    private fun refreshStrict() {
        val b = _binding ?: return
        val context = context ?: return
        val state = StrictStore.state(context)
        val enabled = state !is StrictLock.State.Off
        if (b.strictSwitch.isChecked != enabled) b.strictSwitch.isChecked = enabled
        b.strictIcon.imageTintList = ColorStateList.valueOf(
            color(if (enabled) R.color.accent else R.color.text_disabled)
        )
        var statusColor = R.color.text_secondary
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
                statusColor = R.color.warn
            }
            is StrictLock.State.Unlocked -> {
                b.strictStatusText.text =
                    getString(R.string.strict_unlocked, UiUtil.formatCountdown(state.remainingMs))
                b.strictActionButton.visibility = View.GONE
                statusColor = R.color.accent
            }
        }
        b.strictStatusText.setTextColor(color(statusColor))
    }

    private fun refreshAttention() {
        val b = _binding ?: return
        val context = requireContext()
        val needsAccessibility = (
            BlocklistStore.rules(context).any { it.hasPathExceptions } ||
                BlockedAppsStore.get(context).isNotEmpty()
            ) && !UiUtil.isUrlFilterEnabled(context)
        b.accessibilityRow.visibility = if (needsAccessibility) View.VISIBLE else View.GONE

        val needsAdList = FilterListStore.enabledButMissing(context).isNotEmpty()
        b.adlistRow.visibility = if (needsAdList) View.VISIBLE else View.GONE

        b.attentionCard.visibility =
            if (needsAccessibility || needsAdList) View.VISIBLE else View.GONE
    }

    private fun color(res: Int): Int = ContextCompat.getColor(requireContext(), res)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private fun formatMinute(m: Int): String =
        String.format(Locale.ROOT, "%02d:%02d", m / 60, m % 60)

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
