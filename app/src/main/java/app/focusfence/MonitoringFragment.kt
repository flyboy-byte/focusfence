package app.focusfence

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import app.focusfence.dns.GuardPolicy
import app.focusfence.data.MonitorSettings
import app.focusfence.data.StrictStore
import app.focusfence.databinding.ActivityMonitoringBinding
import app.focusfence.dns.B64
import app.focusfence.dns.CapturePolicy
import app.focusfence.dns.Classification
import app.focusfence.dns.Ecies
import app.focusfence.monitor.ScreenCapture

/**
 * Experimental monitoring (screenshots + in-app text). Everything here is off by
 * default and, while on, shows the persistent capture indicator that cannot be
 * hidden — see [app.focusfence.monitor.CaptureIndicator]. Turning capture off,
 * widening retention, or narrowing the watched apps is loosening and respects
 * strict mode.
 */
class MonitoringFragment : Fragment() {

    private lateinit var binding: ActivityMonitoringBinding

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = ActivityMonitoringBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // As a tab (the five-tab layout) MainActivity's toolbar is the title; as its own screen this one is.
        if (arguments?.getBoolean(ARG_TAB) == true) {
            (binding.toolbar.parent as View).visibility = View.GONE
        } else {
            (requireActivity() as AppCompatActivity).setSupportActionBar(binding.toolbar)
            (requireActivity() as AppCompatActivity).supportActionBar?.setDisplayHomeAsUpEnabled(true)
            binding.toolbar.setNavigationOnClickListener { requireActivity().finish() }
        }

        if (!ScreenCapture.available) {
            binding.screenshotsSwitch.isEnabled = false
            binding.screenshotsHint.setText(R.string.monitor_shots_unavailable)
        }

        binding.screenshotsSwitch.setOnCheckedChangeListener { _, on ->
            if (!on) {
                bind()
                UiUtil.guard(requireContext(), GuardPolicy.Action.DISABLE_CAPTURE) { MonitorSettings.setScreenshots(requireContext(), false); bind() }
            } else {
                MonitorSettings.setScreenshots(requireContext(), true)
            }
        }
        binding.aiSwitch.setOnCheckedChangeListener { _, on ->
            if (!on) {
                bind()
                UiUtil.guard(requireContext(), GuardPolicy.Action.DISABLE_CAPTURE) { MonitorSettings.setAi(requireContext(), false); bind() }
            } else {
                MonitorSettings.setAi(requireContext(), true)
            }
        }
        binding.textSwitch.setOnCheckedChangeListener { _, on ->
            if (!on) {
                bind()
                UiUtil.guard(requireContext(), GuardPolicy.Action.DISABLE_CAPTURE) { MonitorSettings.setText(requireContext(), false); bind() }
            } else {
                MonitorSettings.setText(requireContext(), true)
            }
        }
        binding.reviewButton.setOnClickListener {
            startActivity(android.content.Intent(requireContext(), ReviewActivity::class.java))
        }
        // What was captured and why lives in the logs; reachable from here as well as Settings → Advanced.
        binding.logsButton.setOnClickListener {
            startActivity(android.content.Intent(requireContext(), DiagnosticsActivity::class.java))
        }
        binding.advancedToggle.setOnClickListener {
            val open = binding.advancedContent.visibility != View.VISIBLE
            binding.advancedContent.visibility = if (open) View.VISIBLE else View.GONE
            binding.advancedChevron.rotation = if (open) 180f else 0f
        }
        binding.watchedAppsButton.setOnClickListener {
            UiUtil.chooseWatchScope(requireContext()) { bind() }
        }

        val levels = CapturePolicy.Level.values()
        binding.levelDropdown.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1,
            levels.map { getString(levelLabel(it)) }))
        binding.levelDropdown.setOnItemClickListener { _, _, pos, _ ->
            MonitorSettings.setLevel(requireContext(), levels[pos])
        }

        val modes = Classification.RetentionMode.values()
        binding.retentionDropdown.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1,
            modes.map { getString(retentionLabel(it)) }))
        binding.retentionDropdown.setOnItemClickListener { _, _, pos, _ ->
            val chosen = modes[pos]
            // Keeping more imagery is a loosening of privacy protection; gate it.
            if (chosen.ordinal > MonitorSettings.retention(requireContext()).ordinal) {
                bind()
                UiUtil.guard(requireContext(), GuardPolicy.Action.DISABLE_CAPTURE) { MonitorSettings.setRetention(requireContext(), chosen); bind() }
            } else {
                MonitorSettings.setRetention(requireContext(), chosen)
            }
        }

        val sens = app.focusfence.dns.Sensitivity.values()
        binding.sensitivityDropdown.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1,
            sens.map { getString(sensitivityLabel(it)) }))
        binding.sensitivityDropdown.setOnItemClickListener { _, _, pos, _ ->
            val chosen = sens[pos]
            // Less strict is a loosening: guarded, so a partner can require their PIN (GuardPolicy).
            if (MonitorSettings.sensitivity(requireContext()).loosensTo(chosen)) {
                bind()
                UiUtil.guard(requireContext(), GuardPolicy.Action.LOWER_SENSITIVITY) { MonitorSettings.setSensitivity(requireContext(), chosen); bind() }
            } else {
                MonitorSettings.setSensitivity(requireContext(), chosen)
            }
        }

        binding.partnerKeySaveButton.setOnClickListener { savePartnerKey() }
    }

    override fun onResume() {
        super.onResume()
        statusTicker.run()
        bind()
    }

    override fun onPause() {
        statusHandler.removeCallbacks(statusTicker)
        super.onPause()
    }

    // Captures land every minute or two; refresh the status while the screen is open.
    private val statusHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val statusTicker = object : Runnable {
        override fun run() {
            bindCaptureStatus()
            statusHandler.postDelayed(this, 10_000)
        }
    }

    private fun bindCaptureStatus() {
        val snap = app.focusfence.data.CaptureStats.snapshot(requireContext())
        val status = app.focusfence.dns.CaptureStatus.describe(
            app.focusfence.dns.CaptureStatus.Inputs(
                screenshotsOn = MonitorSettings.screenshotsEnabled(requireContext()),
                supported = ScreenCapture.available,
                accessibilityOn = app.focusfence.monitor.Monitor.a11yConnected,
                watchAll = MonitorSettings.watchMode(requireContext()) == app.focusfence.dns.WatchScope.Mode.ALL,
                watchedApps = MonitorSettings.watchedApps(requireContext()).size,
                aiOn = MonitorSettings.aiEnabled(requireContext()),
                partnerKeySet = MonitorSettings.partnerKeySpki(requireContext()) != null,
                lastShotAt = snap.lastAt,
                lastPkg = snap.lastPkg?.let { pkg ->
                    runCatching { requireContext().packageManager.getApplicationLabel(requireContext().packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                },
                lastLabel = snap.lastLabel,
                lastScore = snap.lastScore,
                lastFlagged = snap.lastFlagged,
                taken = snap.taken,
                flagged = snap.flagged,
                failed = snap.failed,
                aiLoadFailed = snap.aiLoadFailed,
            ),
            System.currentTimeMillis(),
        ) { t -> android.text.format.DateUtils.getRelativeTimeSpanString(t).toString() }
        binding.captureStatus.text = status.text
        val ctx = requireContext()
        fun attr(a: Int) = com.google.android.material.color.MaterialColors.getColor(binding.statusCard, a)
        val sc = app.focusfence.ui.StatusColors
        val v = binding.statusCard
        val level = status.level
        binding.statusHeadline.setText(
            when (level) {
                app.focusfence.dns.CaptureStatus.Level.WORKING -> R.string.monitor_head_working
                app.focusfence.dns.CaptureStatus.Level.WARNING -> R.string.monitor_head_warning
                app.focusfence.dns.CaptureStatus.Level.WAITING -> R.string.monitor_head_waiting
                app.focusfence.dns.CaptureStatus.Level.OFF -> R.string.monitor_head_off
                app.focusfence.dns.CaptureStatus.Level.BLOCKED -> R.string.monitor_head_blocked
            }
        )
        // Theme primary while working or ready; error when it needs you; plain surface when off.
        val good = level == app.focusfence.dns.CaptureStatus.Level.WORKING || level == app.focusfence.dns.CaptureStatus.Level.WAITING
        val bad = level == app.focusfence.dns.CaptureStatus.Level.WARNING || level == app.focusfence.dns.CaptureStatus.Level.BLOCKED
        val container = when {
            good -> sc.goodContainer(v)
            bad -> attr(com.google.android.material.R.attr.colorErrorContainer)
            else -> attr(com.google.android.material.R.attr.colorSurfaceContainerHigh)
        }
        val fg = when {
            good -> sc.onGoodContainer(v)
            bad -> attr(com.google.android.material.R.attr.colorOnErrorContainer)
            else -> attr(com.google.android.material.R.attr.colorOnSurface)
        }
        val muted = if (good) sc.goodContainerMuted(v) else fg
        binding.statusCard.setCardBackgroundColor(container)
        binding.statusHeadline.setTextColor(if (good) sc.onGoodContainer(v) else fg)
        binding.captureStatus.setTextColor(fg)
        binding.reviewHint.setTextColor(muted)
        val nf = java.text.NumberFormat.getIntegerInstance()
        binding.statusCounts.visibility = if (snap.taken > 0) View.VISIBLE else View.GONE
        binding.statusCounts.text = getString(R.string.monitor_counts, nf.format(snap.taken), nf.format(snap.flagged))
        binding.statusCounts.setTextColor(muted)
    }


    private fun bind() {
        val toReview = app.focusfence.data.ReviewStore.count(requireContext())
        binding.reviewButton.text = if (toReview > 0) getString(R.string.review_button, toReview) else getString(R.string.review_button_none)
        binding.screenshotsSwitch.isChecked = MonitorSettings.screenshotsEnabled(requireContext()) && ScreenCapture.available
        binding.textSwitch.isChecked = MonitorSettings.textEnabled(requireContext())
        binding.aiSwitch.isEnabled = ScreenCapture.available
        binding.aiSwitch.isChecked = MonitorSettings.aiEnabled(requireContext()) && ScreenCapture.available
        binding.levelDropdown.setText(getString(levelLabel(MonitorSettings.level(requireContext()))), false)
        binding.retentionDropdown.setText(getString(retentionLabel(MonitorSettings.retention(requireContext()))), false)
        binding.sensitivityDropdown.setText(getString(sensitivityLabel(MonitorSettings.sensitivity(requireContext()))), false)
        binding.watchedAppsButton.text = getString(
            R.string.monitor_watching,
            app.focusfence.dns.WatchScope.describe(
                MonitorSettings.watchMode(requireContext()), MonitorSettings.watchedApps(requireContext()).size, MonitorSettings.excludedApps(requireContext()).size,
            ),
        )
        binding.partnerKeyStatus.setText(
            if (MonitorSettings.partnerKeySpki(requireContext()) != null) R.string.monitor_key_set else R.string.monitor_key_unset
        )
    }

    private fun savePartnerKey() {
        val raw = binding.partnerKeyInput.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) {
            MonitorSettings.setPartnerKeySpki(requireContext(), null)
            bind(); return
        }
        // Validate it's a usable EC public key before storing, so capture never
        // silently fails later for a bad key.
        val ok = runCatching { Ecies.publicKeyFromSpki(B64.decode(raw)) }.isSuccess
        if (!ok) {
            Toast.makeText(requireContext(), R.string.monitor_key_invalid, Toast.LENGTH_LONG).show()
            return
        }
        MonitorSettings.setPartnerKeySpki(requireContext(), raw)
        binding.partnerKeyInput.text?.clear()
        bind()
    }

    private fun levelLabel(l: CapturePolicy.Level) = when (l) {
        CapturePolicy.Level.LOW -> R.string.monitor_level_low
        CapturePolicy.Level.STANDARD -> R.string.monitor_level_standard
        CapturePolicy.Level.HIGH -> R.string.monitor_level_high
    }

    private fun sensitivityLabel(s: app.focusfence.dns.Sensitivity) = when (s) {
        app.focusfence.dns.Sensitivity.STRICT -> R.string.monitor_sens_strict
        app.focusfence.dns.Sensitivity.STANDARD -> R.string.monitor_sens_standard
        app.focusfence.dns.Sensitivity.RELAXED -> R.string.monitor_sens_relaxed
    }

    private fun retentionLabel(m: Classification.RetentionMode) = when (m) {
        Classification.RetentionMode.SCORE_ONLY -> R.string.monitor_retain_score
        Classification.RetentionMode.FLAGGED_ONLY -> R.string.monitor_retain_flagged
        Classification.RetentionMode.ALL -> R.string.monitor_retain_all
    }

    companion object {
        const val ARG_TAB = "tab"
        fun asTab() = MonitoringFragment().apply { arguments = Bundle().apply { putBoolean(ARG_TAB, true) } }
    }
}
