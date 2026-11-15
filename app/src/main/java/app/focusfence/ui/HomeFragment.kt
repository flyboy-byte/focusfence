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
        binding.vpnSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked == DnsVpnService.isRunning) return@setOnCheckedChangeListener
            if (checked) {
                requestVpn()
            } else {
                binding.vpnSwitch.isChecked = true // revert; stop only once the guard is satisfied
                UiUtil.guard(requireContext(), GuardPolicy.Action.TURN_OFF_VPN) { stopVpn() }
            }
        }

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
