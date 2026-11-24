package app.focusfence.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import app.focusfence.CategoriesActivity
import app.focusfence.UninstallProtectionActivity
import app.focusfence.DiagnosticsActivity
import app.focusfence.MonitoringActivity
import app.focusfence.StrictModeActivity
import app.focusfence.HelpActivity
import app.focusfence.R
import app.focusfence.UiUtil
import app.focusfence.data.AdListStore
import app.focusfence.data.BlockScreenSettings
import app.focusfence.dns.BlockScreen
import app.focusfence.data.BlockedAppsStore
import app.focusfence.data.BlocklistStore
import app.focusfence.data.ExcludedAppsStore
import app.focusfence.databinding.FragmentSettingsBinding
import java.io.IOException
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.adSwitch.isChecked = AdListStore.isEnabled(requireContext())
        binding.adSwitch.setOnCheckedChangeListener { _, checked ->
            val context = requireContext()
            AdListStore.setEnabled(context, checked)
            if (checked && !AdListStore.file(context).exists()) {
                updateAdList()
            } else {
                refreshAdStatus()
            }
        }
        binding.adUpdateButton.setOnClickListener { updateAdList() }

        // Not a loosening action (sites stay blocked either way), so no strict guard.
        binding.blockStyleGroup.check(
            when (BlockScreenSettings.style(requireContext())) {
                BlockScreen.Style.NOTIFY -> R.id.blockStyleNotify
                BlockScreen.Style.SCREEN -> R.id.blockStyleScreen
                BlockScreen.Style.OFF -> R.id.blockStyleOff
            }
        )
        binding.blockStyleGroup.setOnCheckedChangeListener { _, id ->
            val style = when (id) {
                R.id.blockStyleScreen -> BlockScreen.Style.SCREEN
                R.id.blockStyleOff -> BlockScreen.Style.OFF
                else -> BlockScreen.Style.NOTIFY
            }
            BlockScreenSettings.setStyle(requireContext(), style)
        }
        binding.blockHighRiskSwitch.isChecked = BlockScreenSettings.noticeHighRisk(requireContext())
        binding.blockHighRiskSwitch.setOnCheckedChangeListener { _, on -> BlockScreenSettings.setNoticeHighRisk(requireContext(), on) }
        // Turning partner alerts off is a loosening: behind the friction guard, like removing a partner.
        binding.blockPartnerSwitch.isChecked = BlockScreenSettings.partnerRepeated(requireContext())
        binding.blockPartnerSwitch.setOnClickListener {
            val ctx = requireContext()
            if (binding.blockPartnerSwitch.isChecked) {
                BlockScreenSettings.setPartnerRepeated(ctx, true)
            } else {
                binding.blockPartnerSwitch.isChecked = true // revert until the guard is satisfied
                UiUtil.guard(ctx, app.focusfence.dns.GuardPolicy.Action.QUIET_BLOCK_ALERTS) {
                    BlockScreenSettings.setPartnerRepeated(ctx, false)
                    _binding?.blockPartnerSwitch?.isChecked = false
                }
            }
        }

        binding.excludedAppsButton.setOnClickListener {
            UiUtil.chooseExcludedApps(requireContext()) { refreshAll() }
        }
        binding.accessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.dnsButton.setOnClickListener { chooseDnsServer() }
        binding.cardCategories.setOnClickListener {
            startActivity(Intent(requireContext(), CategoriesActivity::class.java))
        }
        binding.setupButton.setOnClickListener {
            startActivity(Intent(requireContext(), app.focusfence.SetupActivity::class.java))
        }
        binding.aboutButton.setOnClickListener { showAbout() }
        binding.updateSwitch.isChecked = app.focusfence.update.Updates.isEnabled(requireContext())
        binding.updateSwitch.setOnCheckedChangeListener { _, on -> app.focusfence.update.Updates.setEnabled(requireContext(), on) }
        binding.updateCheckButton.setOnClickListener { checkForUpdate() }
        binding.whatsNewButton.setOnClickListener { startActivity(Intent(requireContext(), app.focusfence.update.WhatsNewActivity::class.java)) }
        // Partners and Monitoring are tabs of their own in the five-tab layout; these buttons are the classic way in.
        val classic = app.focusfence.data.UiPrefs.classic(requireContext())
        binding.cardPartners.visibility = if (classic) View.VISIBLE else View.GONE
        binding.cardMonitoring.visibility = if (classic) View.VISIBLE else View.GONE
        binding.cardCategories.visibility = if (classic) View.VISIBLE else View.GONE
        binding.classicSwitch.isChecked = classic
        binding.classicSwitch.setOnCheckedChangeListener { _, on ->
            app.focusfence.data.UiPrefs.setClassic(requireContext(), on)
            requireActivity().recreate()
        }
        binding.cardPartners.setOnClickListener {
            startActivity(Intent(requireContext(), app.focusfence.AccountabilityActivity::class.java))
        }
        binding.cardMonitoring.setOnClickListener {
            startActivity(Intent(requireContext(), MonitoringActivity::class.java))
        }
        binding.strictButton.setOnClickListener {
            startActivity(Intent(requireContext(), StrictModeActivity::class.java))
        }
        binding.diagnosticsButton.setOnClickListener {
            startActivity(Intent(requireContext(), DiagnosticsActivity::class.java))
        }
        binding.uninstallProtectionButton.setOnClickListener {
            startActivity(Intent(requireContext(), UninstallProtectionActivity::class.java))
        }
        binding.guideButton.setOnClickListener {
            startActivity(Intent(requireContext(), app.focusfence.SetupActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun refreshAll() {
        refreshAdStatus()
        refreshAccessibility()
        val b = _binding ?: return
        b.dnsSummary.text = app.focusfence.dns.Upstreams.describe(app.focusfence.data.DnsUpstreamStore.load(requireContext()))
        b.excludedAppsTitle.text = getString(
            R.string.excluded_apps_button,
            ExcludedAppsStore.get(requireContext()).size,
        )
    }

    /**
     * Resolver picker: Automatic, a preset, or the owner's own server(s). Not a
     * loosening action (filtering happens before forwarding), so no guard.
     */
    private fun chooseDnsServer() {
        val ctx = requireContext()
        val current = app.focusfence.data.DnsUpstreamStore.load(ctx)
        val view = layoutInflater.inflate(R.layout.dialog_dns_server, null)
        val group = view.findViewById<android.widget.RadioGroup>(R.id.dnsChoices)
        val custom = view.findViewById<android.widget.EditText>(R.id.dnsCustom)
        val fallback = view.findViewById<android.widget.CheckBox>(R.id.dnsFallback)
        val presets = app.focusfence.dns.Upstreams.PRESETS
        // Radio ids: 1 = auto, 2.. = presets, 100 = custom.
        fun radio(id: Int, text: String) = group.addView(android.widget.RadioButton(ctx).apply { this.id = id; this.text = text })
        radio(1, getString(R.string.dns_auto))
        presets.forEachIndexed { i, p -> radio(2 + i, p.label) }
        radio(100, getString(R.string.dns_custom))
        group.check(
            when (current.mode) {
                app.focusfence.dns.Upstreams.Mode.AUTO -> 1
                app.focusfence.dns.Upstreams.Mode.PRESET -> 2 + presets.indexOfFirst { it.id == current.presetId }.coerceAtLeast(0)
                app.focusfence.dns.Upstreams.Mode.CUSTOM -> 100
            }
        )
        custom.setText(app.focusfence.data.DnsUpstreamStore.customText(ctx))
        fallback.isChecked = current.fallback
        fun sync() {
            val c = group.checkedRadioButtonId
            custom.visibility = if (c == 100) View.VISIBLE else View.GONE
            fallback.visibility = if (c == 1) View.GONE else View.VISIBLE
        }
        group.setOnCheckedChangeListener { _, _ -> sync() }
        sync()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.dns_title)
            .setView(view)
            .setPositiveButton(R.string.acc_save) { _, _ ->
                val c = group.checkedRadioButtonId
                val text = custom.text.toString()
                val (ok, bad) = app.focusfence.dns.Upstreams.parse(text)
                if (c == 100 && ok.isEmpty()) {
                    android.widget.Toast.makeText(ctx, R.string.dns_need_entry, android.widget.Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                if (c == 100 && bad.isNotEmpty()) {
                    android.widget.Toast.makeText(ctx, getString(R.string.dns_bad_lines, bad.joinToString(", ")), android.widget.Toast.LENGTH_LONG).show()
                }
                val mode = when (c) {
                    1 -> app.focusfence.dns.Upstreams.Mode.AUTO
                    100 -> app.focusfence.dns.Upstreams.Mode.CUSTOM
                    else -> app.focusfence.dns.Upstreams.Mode.PRESET
                }
                val presetId = if (mode == app.focusfence.dns.Upstreams.Mode.PRESET) presets.getOrNull(c - 2)?.id else null
                app.focusfence.data.DnsUpstreamStore.save(ctx, mode, presetId, text, fallback.isChecked)
                refreshAll()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refreshAccessibility() {
        val b = _binding ?: return
        val context = requireContext()
        val configured = BlocklistStore.rules(context).any { it.hasPathExceptions } ||
            BlockedAppsStore.get(context).isNotEmpty()
        val enabled = UiUtil.isUrlFilterEnabled(context)
        b.accessibilityStatusText.text = when {
            enabled -> getString(R.string.section_enabled)
            configured -> getString(R.string.section_disabled)
            else -> getString(R.string.section_idle)
        }
    }

    private fun updateAdList() {
        val b = _binding ?: return
        b.adUpdateButton.isEnabled = false
        b.adStatusText.text = getString(R.string.adlist_downloading)
        val appContext = requireContext().applicationContext
        Thread {
            val error = try {
                AdListStore.download(appContext)
                null
            } catch (e: IOException) {
                e.message ?: "error"
            }
            _binding?.root?.post {
                val binding = _binding ?: return@post
                binding.adUpdateButton.isEnabled = true
                if (error != null && isAdded) {
                    Toast.makeText(requireContext(), R.string.adlist_error, Toast.LENGTH_LONG)
                        .show()
                }
                refreshAdStatus()
            }
        }.start()
    }

    private fun refreshAdStatus() {
        val b = _binding ?: return
        val context = requireContext()
        val count = AdListStore.entryCount(context)
        val updatedAt = AdListStore.updatedAt(context)
        b.adStatusText.text = if (count == 0 || updatedAt == 0L) {
            getString(R.string.adlist_none)
        } else {
            getString(
                R.string.adlist_status,
                NumberFormat.getIntegerInstance().format(count),
                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(updatedAt)),
            )
        }
    }

    /** Short summary that works offline, then an optional hop to the site in the browser (no web view, no network from the app). */
    private fun checkForUpdate() {
        val ctx = requireContext().applicationContext
        binding.updateCheckButton.isEnabled = false
        binding.updateCheckButton.setText(R.string.update_checking)
        Thread {
            val result = runCatching { app.focusfence.update.Updates.fetchOffer(ctx) }
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                binding.updateCheckButton.isEnabled = true
                binding.updateCheckButton.setText(R.string.update_check_now)
                val offer = result.getOrNull()
                when {
                    offer != null -> startActivity(app.focusfence.update.UpdateActivity.intent(ctx, offer))
                    result.isSuccess -> Toast.makeText(ctx, R.string.update_none, Toast.LENGTH_SHORT).show()
                    else -> Toast.makeText(ctx, R.string.update_check_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun showAbout() {
        val url = getString(R.string.about_url)
        val b = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.about_title)
            .setMessage(R.string.about_summary)
            .setNegativeButton(android.R.string.ok, null)
        // No site yet means no button: a dead link is worse than none.
        if (url.isNotBlank()) {
            b.setPositiveButton(R.string.about_open_site) { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                } catch (e: android.content.ActivityNotFoundException) {
                    android.widget.Toast.makeText(requireContext(), getString(R.string.about_no_browser, url), android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
        b.show()
    }
}
