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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import app.focusfence.data.AdListStore
import app.focusfence.data.BlocklistStore
import app.focusfence.databinding.ActivityMainBinding
import app.focusfence.dns.BlockRule
import app.focusfence.dns.Blocklist
import app.focusfence.vpn.DnsVpnService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.io.IOException
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        adapter = DomainAdapter(
            onEditSchedule = { rule -> editSchedule(rule) },
            onDelete = { rule ->
                BlocklistStore.remove(this, rule.domain)
                refreshList()
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
            if (checked) requestVpn() else stopVpn()
        }

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

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(stateReceiver)
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
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(rule.domain)
            .setItems(options) { _, which ->
                if (which == 0) {
                    BlocklistStore.clearSchedule(this, rule.domain)
                    refreshList()
                } else {
                    pickWindow(rule)
                }
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
        adapter.submit(BlocklistStore.rules(this))
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
