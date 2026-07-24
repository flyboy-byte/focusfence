package app.focusfence

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import app.focusfence.data.AdListStore
import app.focusfence.data.BlocklistStore
import androidx.appcompat.app.AlertDialog
import app.focusfence.data.BlockedAppsStore
import app.focusfence.data.StrictStore
import app.focusfence.data.UsageStore
import app.focusfence.databinding.ActivityMainBinding
import app.focusfence.dns.StrictLock
import app.focusfence.accessibility.UrlFilterService
import app.focusfence.dns.BlockRule
import app.focusfence.dns.Blocklist
import app.focusfence.vpn.DnsVpnService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.io.IOException
import java.text.DateFormat
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: DomainAdapter

    private val vpnConsentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                startVpn()
            } else {
                binding.vpnSwitch.isChecked = false
                Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_LONG).show()
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refreshState()
    }

    /** One-second UI tick while visible, for strict-mode countdowns and the block counter. */
    private val ticker = object : Runnable {
        override fun run() {
            refreshStrict()
            refreshState()
            binding.root.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        adapter = DomainAdapter(
            onEditSchedule = { rule ->
                if (StrictStore.isLocked(this)) showStrictLockedDialog() else editSchedule(rule)
            },
            onDelete = { rule ->
                if (StrictStore.isLocked(this)) {
                    showStrictLockedDialog()
                } else {
                    BlocklistStore.remove(this, rule.domain)
                    refreshList()
                }
            },
        )
        binding.domainList.adapter = adapter

        binding.addButton.setOnClickListener { addDomainFromInput() }
        binding.domainInput.setOnEditorActionListener { _, _, _ ->
            addDomainFromInput()
            true
        }

        binding.presetButton.setOnClickListener {
            BlocklistStore.addAll(this, BlocklistStore.SOCIAL_MEDIA_PRESETS)
            refreshList()
        }

        binding.vpnSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked == DnsVpnService.isRunning) return@setOnCheckedChangeListener
            if (checked) {
                requestVpn()
            } else if (StrictStore.isLocked(this)) {
                binding.vpnSwitch.isChecked = true
                showStrictLockedDialog()
            } else {
                stopVpn()
            }
        }

        binding.strictSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked == StrictStore.isEnabled(this)) return@setOnCheckedChangeListener
            if (checked) {
                StrictStore.setEnabled(this, true)
            } else if (StrictStore.isLocked(this)) {
                binding.strictSwitch.isChecked = true
                showStrictLockedDialog()
                return@setOnCheckedChangeListener
            } else {
                StrictStore.setEnabled(this, false)
            }
            refreshStrict()
        }

        binding.strictActionButton.setOnClickListener {
            when (StrictStore.state(this)) {
                is StrictLock.State.Locked -> StrictStore.requestUnlock(this)
                is StrictLock.State.Pending -> StrictStore.cancelUnlock(this)
                else -> {}
            }
            refreshStrict()
        }

        binding.chooseAppsButton.setOnClickListener { chooseBlockedApps() }

        binding.adSwitch.isChecked = AdListStore.isEnabled(this)
        binding.adSwitch.setOnCheckedChangeListener { _, checked ->
            AdListStore.setEnabled(this, checked)
            if (checked && !AdListStore.file(this).exists()) {
                updateAdList()
            } else {
                refreshAdStatus()
            }
        }
        binding.adUpdateButton.setOnClickListener { updateAdList() }
        binding.sectionEnableButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (!BlocklistStore.prefs(this).getBoolean(OnboardingActivity.KEY_ONBOARDED, false)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_help -> {
                startActivity(Intent(this, HelpActivity::class.java))
                true
            }
            R.id.action_stats -> {
                startActivity(Intent(this, StatsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this,
            stateReceiver,
            IntentFilter(DnsVpnService.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        refreshList()
        refreshState()
        refreshAdStatus()
        refreshSectionStatus()
        refreshStrict()
        binding.root.postDelayed(ticker, 1000)
    }

    private fun refreshSectionStatus() {
        val pathRules = BlocklistStore.rules(this).any { it.hasPathExceptions }
        val appCount = BlockedAppsStore.get(this).size
        val configured = pathRules || appCount > 0
        val enabled = isUrlFilterEnabled()
        binding.chooseAppsButton.text = getString(R.string.apps_choose_button, appCount)
        binding.sectionStatusText.text = when {
            !configured -> getString(R.string.section_idle)
            enabled -> getString(R.string.section_enabled)
            else -> getString(R.string.section_disabled)
        }
        binding.sectionEnableButton.visibility =
            if (configured && !enabled) android.view.View.VISIBLE else android.view.View.GONE
    }

    override fun onPause() {
        super.onPause()
        binding.root.removeCallbacks(ticker)
        unregisterReceiver(stateReceiver)
    }

    private fun refreshStrict() {
        val state = StrictStore.state(this)
        val enabled = state !is StrictLock.State.Off
        if (binding.strictSwitch.isChecked != enabled) binding.strictSwitch.isChecked = enabled
        when (state) {
            is StrictLock.State.Off -> {
                binding.strictStatusText.text = getString(R.string.strict_off)
                binding.strictActionButton.visibility = android.view.View.GONE
            }
            is StrictLock.State.Locked -> {
                binding.strictStatusText.text = getString(R.string.strict_locked)
                binding.strictActionButton.visibility = android.view.View.VISIBLE
                binding.strictActionButton.text = getString(R.string.strict_request_unlock)
            }
            is StrictLock.State.Pending -> {
                binding.strictStatusText.text =
                    getString(R.string.strict_pending, formatCountdown(state.remainingMs))
                binding.strictActionButton.visibility = android.view.View.VISIBLE
                binding.strictActionButton.text = getString(android.R.string.cancel)
            }
            is StrictLock.State.Unlocked -> {
                binding.strictStatusText.text =
                    getString(R.string.strict_unlocked, formatCountdown(state.remainingMs))
                binding.strictActionButton.visibility = android.view.View.GONE
            }
        }
    }

    private fun formatCountdown(ms: Long): String {
        val totalSeconds = (ms + 999) / 1000
        return String.format(Locale.ROOT, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }

    private fun showStrictLockedDialog() {
        val state = StrictStore.state(this)
        val message = if (state is StrictLock.State.Pending) {
            getString(R.string.strict_dialog_pending, formatCountdown(state.remainingMs))
        } else {
            getString(R.string.strict_dialog_locked)
        }
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.strict_title)
            .setMessage(message)
            .setNegativeButton(android.R.string.ok, null)
        if (state is StrictLock.State.Locked) {
            builder.setPositiveButton(R.string.strict_request_unlock) { _, _ ->
                StrictStore.requestUnlock(this)
                refreshStrict()
            }
        }
        builder.show()
    }

    private fun chooseBlockedApps() {
        val pm = packageManager
        val launchables = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        )
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .filter { it.first != packageName && it.first != "com.android.settings" }
            .sortedBy { it.second.lowercase(Locale.ROOT) }
        if (launchables.isEmpty()) return

        val current = BlockedAppsStore.get(this)
        val labels = launchables.map { it.second }.toTypedArray()
        val checked = BooleanArray(launchables.size) { launchables[it].first in current }
        val original = checked.copyOf()
        val locked = StrictStore.isLocked(this)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.apps_dialog_title)
            .setMultiChoiceItems(labels, checked) { dialog, index, isChecked ->
                // Strict mode: un-blocking an app is a loosening change.
                if (locked && !isChecked && original[index]) {
                    checked[index] = true
                    (dialog as AlertDialog).listView.setItemChecked(index, true)
                    Toast.makeText(this, R.string.strict_locked_toast, Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val selected = launchables.indices
                    .filter { checked[it] }
                    .map { launchables[it].first }
                    .toSet()
                BlockedAppsStore.set(this, selected)
                refreshSectionStatus()
                if (selected.isNotEmpty() && !isUrlFilterEnabled()) promptEnableUrlFilter()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun addDomainFromInput() {
        val raw = binding.domainInput.text?.toString().orEmpty()
        val domain = Blocklist.normalize(raw)
        if (domain == null) {
            binding.domainInputLayout.error = getString(R.string.invalid_domain)
            return
        }
        binding.domainInputLayout.error = null
        binding.domainInput.setText("")
        BlocklistStore.add(this, domain)
        refreshList()
    }

    private fun editSchedule(rule: BlockRule) {
        val options = arrayOf(
            getString(R.string.schedule_always),
            getString(R.string.schedule_window),
            getString(R.string.schedule_budget),
            getString(R.string.schedule_paths),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(rule.domain)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        BlocklistStore.clearSchedule(this, rule.domain)
                        refreshList()
                    }
                    1 -> pickWindow(rule)
                    2 -> pickBudget(rule)
                    else -> pickAllowedPaths(rule)
                }
            }
            .show()
    }

    private fun pickAllowedPaths(rule: BlockRule) {
        val input = EditText(this).apply {
            hint = getString(R.string.paths_dialog_hint)
            setText(rule.allowPaths.joinToString(", "))
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        val container = FrameLayout(this).apply {
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.paths_dialog_title, rule.domain))
            .setMessage(R.string.paths_dialog_message)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val paths = input.text.toString().split(',')
                    .mapNotNull { Blocklist.normalizePath(it) }
                    .distinct()
                if (paths.isEmpty()) {
                    Toast.makeText(this, R.string.paths_invalid, Toast.LENGTH_LONG).show()
                } else {
                    BlocklistStore.setAllowPaths(this, rule.domain, paths)
                    refreshList()
                    if (!isUrlFilterEnabled()) promptEnableUrlFilter()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun isUrlFilterEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val component = "$packageName/${UrlFilterService::class.java.name}"
        val short = "$packageName/.accessibility.UrlFilterService"
        return enabled.split(':').any { it.equals(component, true) || it.equals(short, true) }
    }

    private fun promptEnableUrlFilter() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.section_service_label)
            .setMessage(R.string.section_enable_message)
            .setPositiveButton(R.string.section_enable_button) { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickBudget(rule: BlockRule) {
        val minutes = intArrayOf(5, 10, 15, 20, 30, 45, 60, 90)
        val labels = minutes
            .map { resources.getQuantityString(R.plurals.budget_minutes, it, it) }
            .toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.budget_dialog_title, rule.domain))
            .setItems(labels) { _, which ->
                BlocklistStore.setBudget(this, rule.domain, minutes[which])
                refreshList()
            }
            .show()
    }

    private fun pickWindow(rule: BlockRule) {
        val startDefault = if (rule.isScheduled) rule.startMinute else 9 * 60
        val endDefault = if (rule.isScheduled) rule.endMinute else 18 * 60
        showTimePicker(R.string.picker_start_title, startDefault) { start ->
            showTimePicker(R.string.picker_end_title, endDefault) { end ->
                if (start == end) {
                    Toast.makeText(this, R.string.schedule_same_time, Toast.LENGTH_LONG).show()
                } else {
                    BlocklistStore.setSchedule(this, rule.domain, start, end)
                    refreshList()
                }
            }
        }
    }

    private fun showTimePicker(titleRes: Int, defaultMinute: Int, onPicked: (Int) -> Unit) {
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(defaultMinute / 60)
            .setMinute(defaultMinute % 60)
            .setTitleText(titleRes)
            .build()
        picker.addOnPositiveButtonClickListener { onPicked(picker.hour * 60 + picker.minute) }
        picker.show(supportFragmentManager, "time-picker")
    }

    private fun updateAdList() {
        binding.adUpdateButton.isEnabled = false
        binding.adStatusText.text = getString(R.string.adlist_downloading)
        Thread {
            val error = try {
                AdListStore.download(this)
                null
            } catch (e: IOException) {
                e.message ?: "error"
            }
            runOnUiThread {
                binding.adUpdateButton.isEnabled = true
                if (error != null) {
                    Toast.makeText(this, R.string.adlist_error, Toast.LENGTH_LONG).show()
                }
                refreshAdStatus()
            }
        }.start()
    }

    private fun refreshList() {
        val rules = BlocklistStore.rules(this)
        val dayKey = LocalDate.now().toString()
        val usage = rules.filter { it.hasBudget }.associate {
            it.domain to UsageStore.usedMillisOn(this, it.domain, dayKey)
        }
        adapter.submit(rules, usage)
    }

    private fun refreshState() {
        val running = DnsVpnService.isRunning
        binding.vpnSwitch.isChecked = running
        binding.statusText.text = if (running) {
            getString(R.string.status_active, DnsVpnService.blockedCount.get())
        } else {
            getString(R.string.status_inactive)
        }
    }

    private fun refreshAdStatus() {
        val count = AdListStore.entryCount(this)
        val updatedAt = AdListStore.updatedAt(this)
        binding.adStatusText.text = if (count == 0 || updatedAt == 0L) {
            getString(R.string.adlist_none)
        } else {
            getString(
                R.string.adlist_status,
                NumberFormat.getIntegerInstance().format(count),
                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(updatedAt)),
            )
        }
    }

    private fun requestVpn() {
        val consentIntent = VpnService.prepare(this)
        if (consentIntent != null) {
            vpnConsentLauncher.launch(consentIntent)
        } else {
            startVpn()
        }
    }

    private fun startVpn() {
        val intent = Intent(this, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
        binding.statusText.postDelayed({ refreshState() }, 300)
    }

    private fun stopVpn() {
        startService(Intent(this, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_STOP))
        binding.statusText.postDelayed({ refreshState() }, 300)
    }
}
