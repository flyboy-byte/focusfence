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
import app.focusfence.R
import app.focusfence.UiUtil
import app.focusfence.data.AdListStore
import app.focusfence.data.BlockScreenSettings
import app.focusfence.dns.BlockScreen
import app.focusfence.data.ExcludedAppsStore
import app.focusfence.databinding.FragmentSettingsBinding
import app.focusfence.databinding.RowPrefBinding
import app.focusfence.databinding.RowPrefSwitchBinding
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
        val b = binding
        val ctx = requireContext()
        fun open(cls: Class<*>) = startActivity(Intent(requireContext(), cls))

        // Protection
        row(b.rowStrict, R.string.strict_title, R.string.settings_strict_summary) { open(StrictModeActivity::class.java) }
        row(b.rowUninstall, R.string.admin_card_title, R.string.settings_uninstall_summary) { open(UninstallProtectionActivity::class.java) }
        row(b.rowAccessibility, R.string.section_service_label, R.string.settings_accessibility_summary) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // Things with a tab of their own in the five-tab layout; the classic layout reaches them from here.
        row(b.rowPartners, R.string.acc_title, R.string.acc_settings_summary) { open(app.focusfence.AccountabilityActivity::class.java) }
        row(b.rowChecks, R.string.monitor_title, R.string.monitor_settings_summary) { open(MonitoringActivity::class.java) }
        row(b.rowSetup, R.string.setup_title, null) { open(app.focusfence.SetupActivity::class.java) }
        val classic = app.focusfence.data.UiPrefs.classic(ctx)
        b.labelAccountability.visibility = if (classic) View.VISIBLE else View.GONE
        b.groupAccountability.visibility = if (classic) View.VISIBLE else View.GONE
        b.rowCategories.root.visibility = if (classic) View.VISIBLE else View.GONE

        // Blocking
        row(b.rowBlockNotices, R.string.notices_title, R.string.settings_notices_summary) { open(app.focusfence.BlockNoticesActivity::class.java) }
        row(b.rowCategories, R.string.categories_title, null) { open(CategoriesActivity::class.java) }
        switchRow(b.rowAds, R.string.adblock_label)
        b.rowAds.prefSwitch.isChecked = AdListStore.isEnabled(ctx)
        b.rowAds.prefSwitch.setOnCheckedChangeListener { _, checked ->
            val c = requireContext()
            AdListStore.setEnabled(c, checked)
            if (checked && !AdListStore.file(c).exists()) updateAdList() else refreshAdStatus()
        }
        row(b.rowAdUpdate, R.string.settings_ad_update, null) { updateAdList() }
        b.rowAdUpdate.prefChevron.visibility = View.GONE
        row(b.rowExcluded, R.string.settings_excluded_title, R.string.settings_excluded_summary) {
            UiUtil.chooseExcludedApps(requireContext()) { refreshAll() }
        }

        // Updates
        switchRow(b.rowUpdates, R.string.update_check_switch)
        b.rowUpdates.prefSummary.setText(R.string.settings_updates_summary)
        b.rowUpdates.prefSummary.visibility = View.VISIBLE
        b.rowUpdates.prefSwitch.isChecked = app.focusfence.update.Updates.isEnabled(ctx)
        b.rowUpdates.prefSwitch.setOnCheckedChangeListener { _, on -> app.focusfence.update.Updates.setEnabled(requireContext(), on) }
        row(b.rowCheckNow, R.string.update_check_now, null) { checkForUpdate() }
        b.rowCheckNow.prefChevron.visibility = View.GONE
        row(b.rowWhatsNew, R.string.whats_new_title, null) { open(app.focusfence.update.WhatsNewActivity::class.java) }

        // About
        row(b.rowGuide, R.string.settings_guide, null) { open(app.focusfence.SetupActivity::class.java) }
        row(b.rowAbout, R.string.about_title, null) { showAbout() }
        row(b.rowBug, R.string.feedback_title, R.string.feedback_summary) { chooseFeedback() }
        row(b.rowGithub, R.string.github_title, null) {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/" + app.focusfence.dns.UpdateCheck.REPO)))
            }
        }
        b.rowGithub.prefChevron.setImageResource(R.drawable.ic_open_external)

        // Advanced
        row(b.rowDns, R.string.dns_title, null) { chooseDnsServer() }
        row(b.rowDiagnostics, R.string.diag_title, null) { open(DiagnosticsActivity::class.java) }
        switchRow(b.rowClassic, R.string.classic_layout)
        b.rowClassic.prefSummary.setText(R.string.classic_layout_summary)
        b.rowClassic.prefSummary.visibility = View.VISIBLE
        b.rowClassic.prefSwitch.isChecked = classic
        b.rowClassic.prefSwitch.setOnCheckedChangeListener { _, on ->
            app.focusfence.data.UiPrefs.setClassic(requireContext(), on)
            requireActivity().recreate()
        }
    }

    private fun row(r: RowPrefBinding, title: Int, summary: Int?, onClick: () -> Unit) {
        r.prefTitle.setText(title)
        if (summary != null) { r.prefSummary.setText(summary); r.prefSummary.visibility = View.VISIBLE }
        r.root.setOnClickListener { onClick() }
    }

    private fun switchRow(r: RowPrefSwitchBinding, title: Int) {
        r.prefTitle.setText(title)
        r.root.setOnClickListener { r.prefSwitch.toggle() }
    }

    private fun value(r: RowPrefBinding, text: CharSequence?) {
        r.prefValue.text = text
        r.prefValue.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
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
        val b = _binding ?: return
        val ctx = requireContext()
        refreshAdStatus()
        val on = getString(R.string.home_on)
        val off = getString(R.string.home_off)
        value(b.rowStrict, if (app.focusfence.data.StrictStore.isLocked(ctx)) getString(R.string.settings_locked) else off)
        val admin = app.focusfence.admin.DeviceAdmin.isAdminActive(ctx)
        value(b.rowUninstall, if (admin) on else off)
        value(b.rowAccessibility, if (UiUtil.isUrlFilterEnabled(ctx)) on else off)
        value(
            b.rowBlockNotices,
            getString(
                when (BlockScreenSettings.style(ctx)) {
                    BlockScreen.Style.NOTIFY -> R.string.notices_value_notify
                    BlockScreen.Style.OFF -> R.string.notices_value_silent
                }
            ),
        )
        val bundles = app.focusfence.dns.Categories.BUNDLES.count {
            app.focusfence.data.CategoryStore.policy(ctx, it.id) != app.focusfence.dns.Categories.Policy.OFF
        }
        value(b.rowCategories, getString(R.string.blocking_lists_value, app.focusfence.data.CategoryStore.baseTier(ctx).label.substringBefore(" —"), bundles))
        val excluded = ExcludedAppsStore.get(ctx).size
        value(b.rowExcluded, if (excluded == 0) getString(R.string.settings_none) else excluded.toString())
        value(b.rowDns, app.focusfence.dns.Upstreams.describe(app.focusfence.data.DnsUpstreamStore.load(ctx)))
        b.rowCheckNow.prefSummary.text = getString(R.string.update_installed, app.focusfence.dns.UpdateCheck.shortVersion(app.focusfence.update.Updates.installedVersion(ctx)))
        b.rowCheckNow.prefSummary.visibility = View.VISIBLE
        b.rowAdUpdate.root.visibility = if (b.rowAds.prefSwitch.isChecked) View.VISIBLE else View.GONE
        for (g in listOf(b.groupProtection, b.groupAccountability, b.groupBlocking, b.groupUpdates, b.groupAbout, b.groupAdvanced)) Tiles.group(g)
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

    private fun updateAdList() {
        val b = _binding ?: return
        b.rowAdUpdate.root.isEnabled = false
        b.rowAds.prefSummary.text = getString(R.string.adlist_downloading)
        b.rowAds.prefSummary.visibility = View.VISIBLE
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
                binding.rowAdUpdate.root.isEnabled = true
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
        b.rowAds.prefSummary.visibility = View.VISIBLE
        b.rowAds.prefSummary.text = if (count == 0 || updatedAt == 0L) {
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
        binding.rowCheckNow.root.isEnabled = false
        binding.rowCheckNow.prefTitle.setText(R.string.update_checking)
        Thread {
            val result = runCatching { app.focusfence.update.Updates.fetchOffer(ctx) }
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                binding.rowCheckNow.root.isEnabled = true
                binding.rowCheckNow.prefTitle.setText(R.string.update_check_now)
                val offer = result.getOrNull()
                when {
                    offer != null -> startActivity(app.focusfence.update.Updates.homeIntent(ctx))
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

    /**
     * Report a bug (2026-11-25): opens GitHub's issue form filled with versions, and, if ticked, a numbers-only summary
     * shown here first (dns/BugReport). The repo is public, so the dialog says not to paste logs or screenshots.
     */
    /** Bug, feature request or feedback: each opens its own GitHub issue form, with versions filled in. */
    private fun chooseFeedback() {
        val kinds = app.focusfence.dns.BugReport.Kind.values()
        val names = arrayOf(getString(R.string.bug_title), getString(R.string.feature_title), getString(R.string.feedback_general))
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.feedback_title)
            .setItems(names) { _, i -> reportBug(kinds[i]) }
            .show()
    }

    private fun reportBug(kind: app.focusfence.dns.BugReport.Kind = app.focusfence.dns.BugReport.Kind.BUG) {
        val ctx = requireContext()
        val bug = kind == app.focusfence.dns.BugReport.Kind.BUG
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        box.addView(android.widget.TextView(ctx).apply { setText(R.string.bug_body) })
        val check = com.google.android.material.checkbox.MaterialCheckBox(ctx).apply {
            setText(R.string.bug_add_summary)
            visibility = if (bug) View.VISIBLE else View.GONE
        }
        box.addView(check)
        val preview = android.widget.TextView(ctx).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            visibility = View.GONE
            setTextIsSelectable(true)
        }
        box.addView(android.widget.ScrollView(ctx).apply { addView(preview) })
        var summary: String? = null
        check.setOnCheckedChangeListener { _, on ->
            preview.visibility = if (on) View.VISIBLE else View.GONE
            if (on && summary == null) {
                preview.setText(R.string.bug_summary_loading)
                Thread {
                    val s = runCatching { bugNumbers(ctx.applicationContext) }.getOrElse { "summary unavailable: ${it.javaClass.simpleName}" }
                    activity?.runOnUiThread { summary = s; preview.text = s }
                }.start()
            }
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(when (kind) {
                app.focusfence.dns.BugReport.Kind.BUG -> R.string.bug_title
                app.focusfence.dns.BugReport.Kind.FEATURE -> R.string.feature_title
                app.focusfence.dns.BugReport.Kind.FEEDBACK -> R.string.feedback_general
            })
            .setView(box)
            .setPositiveButton(R.string.bug_open) { _, _ ->
                val sync = app.focusfence.sync.SyncStore
                val status = sync.protocolStatus(ctx).name.lowercase()
                val info = app.focusfence.dns.BugReport.Info(
                    app.focusfence.update.Updates.installedVersion(ctx), app.focusfence.dns.Protocol.LEVEL,
                    if (sync.deviceId(ctx) == null) "none" else status,
                    "${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})",
                    "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                )
                val url = app.focusfence.dns.BugReport.url(app.focusfence.dns.UpdateCheck.REPO, info, summary.takeIf { bug && check.isChecked }, kind)
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Numbers only, from the last 3 days of the diagnostics log: no sites, apps, people or addresses. */
    private fun bugNumbers(ctx: android.content.Context): String {
        val days = 3
        val since = System.currentTimeMillis() - days * 86_400_000L
        val s = app.focusfence.dns.LogSummary(since)
        app.focusfence.data.EventLog.init(ctx)
        runCatching { app.focusfence.data.EventLog.flushBlocking() }
        app.focusfence.data.EventLog.scan(app.focusfence.data.EventLog.Channel.DIAG, sinceT = since) { line ->
            app.focusfence.dns.LogJson.decode(line)?.let(s::add); true
        }
        val bat = s.battery()
        return app.focusfence.dns.BugReport.summary(app.focusfence.dns.BugReport.Numbers(
            filterOn = app.focusfence.vpn.DnsVpnService.isRunning,
            alwaysOn = app.focusfence.vpn.DnsVpnService.alwaysOnState,
            accessibility = app.focusfence.UiUtil.isUrlFilterEnabled(ctx),
            strict = app.focusfence.data.StrictStore.isEnabled(ctx),
            screenChecks = app.focusfence.data.MonitorSettings.screenshotsEnabled(ctx),
            ai = app.focusfence.data.MonitorSettings.aiEnabled(ctx),
            onServer = app.focusfence.sync.SyncStore.deviceId(ctx) != null,
            screenOnPctPerHour = bat.screenOn.pctPerHour.takeIf { bat.screenOn.hours > 0 },
            screenOffPctPerHour = bat.screenOff.pctPerHour.takeIf { bat.screenOff.hours > 0 },
            cpuMsPerHourOn = bat.screenOn.cpuMsPerHour.takeIf { bat.screenOn.hours > 0 },
            forwardFailurePct = s.forwardFailureRate()?.let { it * 100 },
            latencyP95Ms = s.latencyP95()?.first,
            errors = s.errors,
            days = days,
        ))
    }
}
