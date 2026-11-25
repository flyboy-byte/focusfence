package app.focusfence

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import app.focusfence.data.BlocklistStore
import app.focusfence.data.MonitorSettings
import app.focusfence.databinding.ActivityOnboardingBinding
import app.focusfence.ui.StatusColors
import app.focusfence.vpn.DnsVpnService

/**
 * First launch (plan 10, journeys 1 and 5, 2026-11-25): five short steps that end with the phone protected, instead of
 * seven pages of reading. What it's for (porn accountability), turn the filter on (Android's VPN prompt, then
 * notifications), screen checks (screenshots + AI, which need the accessibility permission), strict mode (changes that
 * loosen anything wait 10 minutes), then just me or with a partner. Every
 * step can be skipped; the long guide stays under Help (SetupActivity).
 */
class OnboardingActivity : AppCompatActivity() {

    companion object {
        const val KEY_ONBOARDED = "onboarded"
        private const val STEPS = 5
    }

    private lateinit var binding: ActivityOnboardingBinding
    private var index = 0

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) startVpn()
        else Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_LONG).show()
        render()
    }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        index = savedInstanceState?.getInt("page") ?: 0

        binding.skipButton.setOnClickListener { done(partner = false) }
        binding.backButton.setOnClickListener { if (index > 0) { index--; render() } }
        binding.nextButton.setOnClickListener { if (index >= STEPS - 1) done(partner = false) else { index++; render() } }
        render()
    }

    override fun onResume() {
        super.onResume()
        render() // back from Android's accessibility settings
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("page", index)
    }

    private fun render() {
        val b = binding
        b.pageIndicator.text = getString(R.string.onboarding_progress, index + 1, STEPS)
        b.backButton.visibility = if (index == 0) View.INVISIBLE else View.VISIBLE
        b.nextButton.visibility = if (index == STEPS - 1) View.GONE else View.VISIBLE
        b.nextButton.setText(R.string.onboarding_next)
        b.skipButton.visibility = if (index == STEPS - 1) View.INVISIBLE else View.VISIBLE
        b.stepAction.visibility = View.GONE
        b.stepAction2.visibility = View.GONE
        b.stepStatus.visibility = View.GONE
        when (index) {
            0 -> {
                b.pageTitle.setText(R.string.onb_welcome_title)
                b.pageBody.setText(R.string.onb_welcome_body)
            }
            1 -> {
                b.pageTitle.setText(R.string.onb_protect_title)
                b.pageBody.setText(R.string.onb_protect_body)
                val on = DnsVpnService.isRunning
                action(b.stepAction, R.string.onb_protect_action, !on) { requestVpn() }
                status(if (on) R.string.onb_protect_on else null, ok = true)
                if (on) b.nextButton.setText(R.string.onboarding_next)
            }
            2 -> {
                b.pageTitle.setText(R.string.onb_checks_title)
                b.pageBody.text = getString(R.string.onb_checks_body) + "\n\n" + getString(R.string.setup_shots_cost)
                val wanted = MonitorSettings.screenshotsEnabled(this) && MonitorSettings.aiEnabled(this)
                val a11y = UiUtil.isUrlFilterEnabled(this)
                when {
                    wanted && a11y -> status(R.string.onb_checks_on, ok = true)
                    wanted -> {
                        action(b.stepAction, R.string.onb_checks_permission, true) { UiUtil.promptEnableUrlFilter(this) }
                        status(R.string.onb_checks_needs_permission, ok = false)
                    }
                    else -> action(b.stepAction, R.string.onb_checks_action, true) { enableChecks() }
                }
            }
            3 -> {
                b.pageTitle.setText(R.string.onb_strict_title)
                b.pageBody.setText(R.string.onb_strict_body)
                val on = app.focusfence.data.StrictStore.isEnabled(this)
                action(b.stepAction, R.string.onb_strict_action, !on) {
                    app.focusfence.data.StrictStore.setEnabled(this, true)
                    render()
                }
                status(if (on) R.string.onb_strict_on else null, ok = true)
            }
            else -> {
                b.pageTitle.setText(R.string.onb_partner_title)
                b.pageBody.setText(R.string.onb_partner_body)
                action(b.stepAction, R.string.onb_partner_add, true) { done(partner = true) }
                action(b.stepAction2, R.string.onb_partner_me, true) { done(partner = false) }
            }
        }
        b.bodyScroll.scrollTo(0, 0)
    }

    private fun action(button: android.widget.Button, text: Int, show: Boolean, onClick: () -> Unit) {
        button.visibility = if (show) View.VISIBLE else View.GONE
        button.setText(text)
        button.setOnClickListener { onClick() }
    }

    private fun status(text: Int?, ok: Boolean) {
        val s = binding.stepStatus
        if (text == null) { s.visibility = View.GONE; return }
        s.visibility = View.VISIBLE
        s.setText(text)
        s.setTextColor(if (ok) StatusColors.good(s) else StatusColors.warn(s))
    }

    private fun requestVpn() {
        val consent = VpnService.prepare(this)
        if (consent != null) vpnConsent.launch(consent) else { startVpn(); render() }
    }

    private fun startVpn() {
        ContextCompat.startForegroundService(this, Intent(this, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_START))
        // Notifications matter from here on (unlock windows, flagged screenshots, updates): ask now, with a reason on screen.
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        binding.root.postDelayed({ render() }, 500)
    }

    private fun enableChecks() {
        MonitorSettings.setScreenshots(this, true)
        MonitorSettings.setAi(this, true)
        if (!UiUtil.isUrlFilterEnabled(this)) UiUtil.promptEnableUrlFilter(this)
        render()
    }

    private fun done(partner: Boolean) {
        BlocklistStore.prefs(this).edit().putBoolean(KEY_ONBOARDED, true).apply()
        if (partner) startActivity(Intent(this, AccountabilityActivity::class.java))
        finish()
    }
}
