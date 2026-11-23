package app.focusfence

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
class MonitoringActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMonitoringBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMonitoringBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        if (!ScreenCapture.available) {
            binding.screenshotsSwitch.isEnabled = false
            binding.screenshotsHint.setText(R.string.monitor_shots_unavailable)
        }

        binding.screenshotsSwitch.setOnCheckedChangeListener { _, on ->
            if (!on) {
                bind()
                UiUtil.guard(this, GuardPolicy.Action.DISABLE_CAPTURE) { MonitorSettings.setScreenshots(this, false); bind() }
            } else {
                MonitorSettings.setScreenshots(this, true)
            }
        }
        binding.aiSwitch.setOnCheckedChangeListener { _, on ->
            if (!on) {
                bind()
                UiUtil.guard(this, GuardPolicy.Action.DISABLE_CAPTURE) { MonitorSettings.setAi(this, false); bind() }
            } else {
                MonitorSettings.setAi(this, true)
            }
        }
        binding.textSwitch.setOnCheckedChangeListener { _, on ->
            if (!on) {
                bind()
                UiUtil.guard(this, GuardPolicy.Action.DISABLE_CAPTURE) { MonitorSettings.setText(this, false); bind() }
            } else {
                MonitorSettings.setText(this, true)
            }
        }
        binding.reviewButton.setOnClickListener {
            startActivity(android.content.Intent(this, ReviewActivity::class.java))
        }
        binding.watchedAppsButton.setOnClickListener {
            UiUtil.chooseWatchScope(this) { bind() }
        }

        val levels = CapturePolicy.Level.values()
        binding.levelDropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1,
            levels.map { getString(levelLabel(it)) }))
        binding.levelDropdown.setOnItemClickListener { _, _, pos, _ ->
            MonitorSettings.setLevel(this, levels[pos])
        }

        val modes = Classification.RetentionMode.values()
        binding.retentionDropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1,
            modes.map { getString(retentionLabel(it)) }))
        binding.retentionDropdown.setOnItemClickListener { _, _, pos, _ ->
            val chosen = modes[pos]
            // Keeping more imagery is a loosening of privacy protection; gate it.
            if (chosen.ordinal > MonitorSettings.retention(this).ordinal) {
                bind()
                UiUtil.guard(this, GuardPolicy.Action.DISABLE_CAPTURE) { MonitorSettings.setRetention(this, chosen); bind() }
            } else {
                MonitorSettings.setRetention(this, chosen)
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
        val snap = app.focusfence.data.CaptureStats.snapshot(this)
        val status = app.focusfence.dns.CaptureStatus.describe(
            app.focusfence.dns.CaptureStatus.Inputs(
                screenshotsOn = MonitorSettings.screenshotsEnabled(this),
                supported = ScreenCapture.available,
                accessibilityOn = app.focusfence.monitor.Monitor.a11yConnected,
                watchAll = MonitorSettings.watchMode(this) == app.focusfence.dns.WatchScope.Mode.ALL,
                watchedApps = MonitorSettings.watchedApps(this).size,
                aiOn = MonitorSettings.aiEnabled(this),
                partnerKeySet = MonitorSettings.partnerKeySpki(this) != null,
                lastShotAt = snap.lastAt,
                lastPkg = snap.lastPkg?.let { pkg ->
                    runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
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
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun bind() {
        binding.reviewButton.text = getString(R.string.review_button, app.focusfence.data.ReviewStore.count(this))
        binding.screenshotsSwitch.isChecked = MonitorSettings.screenshotsEnabled(this) && ScreenCapture.available
        binding.textSwitch.isChecked = MonitorSettings.textEnabled(this)
        binding.aiSwitch.isEnabled = ScreenCapture.available
        binding.aiSwitch.isChecked = MonitorSettings.aiEnabled(this) && ScreenCapture.available
        binding.levelDropdown.setText(getString(levelLabel(MonitorSettings.level(this))), false)
        binding.retentionDropdown.setText(getString(retentionLabel(MonitorSettings.retention(this))), false)
        binding.watchedAppsButton.text = getString(
            R.string.monitor_watching,
            app.focusfence.dns.WatchScope.describe(
                MonitorSettings.watchMode(this), MonitorSettings.watchedApps(this).size, MonitorSettings.excludedApps(this).size,
            ),
        )
        binding.partnerKeyStatus.setText(
            if (MonitorSettings.partnerKeySpki(this) != null) R.string.monitor_key_set else R.string.monitor_key_unset
        )
    }

    private fun savePartnerKey() {
        val raw = binding.partnerKeyInput.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) {
            MonitorSettings.setPartnerKeySpki(this, null)
            bind(); return
        }
        // Validate it's a usable EC public key before storing, so capture never
        // silently fails later for a bad key.
        val ok = runCatching { Ecies.publicKeyFromSpki(B64.decode(raw)) }.isSuccess
        if (!ok) {
            Toast.makeText(this, R.string.monitor_key_invalid, Toast.LENGTH_LONG).show()
            return
        }
        MonitorSettings.setPartnerKeySpki(this, raw)
        binding.partnerKeyInput.text?.clear()
        bind()
    }

    private fun levelLabel(l: CapturePolicy.Level) = when (l) {
        CapturePolicy.Level.LOW -> R.string.monitor_level_low
        CapturePolicy.Level.STANDARD -> R.string.monitor_level_standard
        CapturePolicy.Level.HIGH -> R.string.monitor_level_high
    }

    private fun retentionLabel(m: Classification.RetentionMode) = when (m) {
        Classification.RetentionMode.SCORE_ONLY -> R.string.monitor_retain_score
        Classification.RetentionMode.FLAGGED_ONLY -> R.string.monitor_retain_flagged
        Classification.RetentionMode.ALL -> R.string.monitor_retain_all
    }
}
