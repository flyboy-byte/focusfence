package app.focusfence

import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CompoundButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import app.focusfence.admin.AppSuspension
import app.focusfence.admin.DeviceAdmin
import app.focusfence.data.StrategyStore
import app.focusfence.databinding.ActivityDeviceOwnerBinding
import app.focusfence.dns.GuardPolicy

/**
 * Optional managed ("teeth") mode. The stance is detect-and-notify, not block:
 * no uninstall blocking; removing protection stays possible (behind friction).
 * Device owner buys three things used here — locked Private DNS for the strategy
 * switch, package suspension for stronger app blocking, and network logging for
 * per-app DNS attribution. Provisioning is an adb/Shizuku step, never a button
 * (docs/specs/device-owner.md).
 */
class DeviceOwnerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeviceOwnerBinding

    private val strategies = StrategyStore.Strategy.values()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceOwnerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Full component — the app id can differ from the namespace, so a
        // ".Class" shorthand would resolve to the wrong receiver.
        // Device admin, Ever Accountable's model: the system's own consent screen,
        // no adb. While active, Android won't uninstall the app until admin is
        // turned off first — a second, deliberate step that the receiver logs.
        binding.adminActivateButton.setOnClickListener {
            startActivity(
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, DeviceAdmin.component(this))
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.admin_add_explanation))
            )
        }

        binding.adbCommand.text =
            "adb shell dpm set-device-owner \\\n  " + DeviceAdmin.component(this).flattenToShortString()

        binding.strategyDropdown.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1,
                strategies.map { getString(strategyLabel(it)) })
        )
        binding.strategyDropdown.setOnItemClickListener { _, _, pos, _ ->
            val chosen = strategies[pos]
            if (chosen == StrategyStore.strategy(this)) return@setOnItemClickListener
            if (chosen == StrategyStore.Strategy.PRIVATE_DNS && !DeviceAdmin.isDeviceOwner(this)) {
                bind() // revert
                Toast.makeText(this, R.string.owner_need_owner, Toast.LENGTH_LONG).show()
                return@setOnItemClickListener
            }
            bind() // revert until the guard is satisfied
            UiUtil.guard(this, GuardPolicy.Action.SWITCH_STRATEGY) { applyStrategy(chosen) }
        }

        binding.dotSaveButton.setOnClickListener {
            val host = binding.dotHostInput.text?.toString().orEmpty().trim()
            if (host.isEmpty() || !host.contains('.')) {
                Toast.makeText(this, R.string.owner_dot_invalid, Toast.LENGTH_SHORT).show()
            } else {
                StrategyStore.setPrivateDnsHost(this, host)
                if (StrategyStore.strategy(this) == StrategyStore.Strategy.PRIVATE_DNS) {
                    DeviceAdmin.setPrivateDns(this, host)
                }
                Toast.makeText(this, R.string.owner_dot_saved, Toast.LENGTH_SHORT).show()
            }
        }

        bindSwitch(binding.networkLoggingSwitch) { on -> DeviceAdmin.setNetworkLogging(this, on) }
        binding.suspendAppsSwitch.setOnCheckedChangeListener(null)
        binding.suspendAppsSwitch.setOnClickListener {
            val on = binding.suspendAppsSwitch.isChecked
            // Turning suspension off makes blocked apps openable again — a loosening.
            if (!on) {
                binding.suspendAppsSwitch.isChecked = true
                UiUtil.guard(this, GuardPolicy.Action.UNBLOCK_APP) {
                    StrategyStore.setSuspendBlockedApps(this, false)
                    AppSuspension.reconcile(this)
                    bind()
                }
            } else {
                StrategyStore.setSuspendBlockedApps(this, true)
                AppSuspension.reconcile(this)
            }
        }

        // Each managed-mode protection: turning on is immediate, turning off is a
        // loosening and goes through the friction guard.
        guardedSwitch(
            binding.alwaysOnSwitch,
            enable = {
                StrategyStore.setAlwaysOnVpn(this, true)
                if (StrategyStore.strategy(this) == StrategyStore.Strategy.LOCAL_DNS_VPN) {
                    if (!DeviceAdmin.setAlwaysOnVpn(this, true)) {
                        Toast.makeText(this, R.string.owner_strategy_failed, Toast.LENGTH_LONG).show()
                    }
                }
            },
            disable = {
                // Settings lock without always-on would leave the user unable to
                // fix VPN settings at all; drop both together.
                DeviceAdmin.setNetworkSettingsLocked(this, false)
                StrategyStore.setAlwaysOnVpn(this, false)
                DeviceAdmin.setAlwaysOnVpn(this, false)
            },
        )
        guardedSwitch(
            binding.lockSettingsSwitch,
            enable = {
                if (!StrategyStore.alwaysOnVpn(this)) {
                    Toast.makeText(this, R.string.owner_lock_needs_always_on, Toast.LENGTH_LONG).show()
                } else {
                    DeviceAdmin.setNetworkSettingsLocked(this, true)
                }
            },
            disable = { DeviceAdmin.setNetworkSettingsLocked(this, false) },
        )
        guardedSwitch(
            binding.fallbackSwitch,
            enable = { StrategyStore.setPrivateDnsFallback(this, true) },
            disable = {
                StrategyStore.setPrivateDnsFallback(this, false)
                // Turning the fallback off while it's active undoes it now.
                app.focusfence.admin.DnsFallback.release(this)
            },
        )

        binding.removeButton.setOnClickListener {
            UiUtil.guard(this, GuardPolicy.Action.REMOVE_PROTECTION) {
                if (DeviceAdmin.relinquish(this)) {
                    Toast.makeText(this, R.string.owner_removed, Toast.LENGTH_SHORT).show()
                }
                bind()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        bind()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun applyStrategy(chosen: StrategyStore.Strategy) {
        when (chosen) {
            StrategyStore.Strategy.PRIVATE_DNS -> {
                if (DeviceAdmin.setPrivateDns(this, StrategyStore.privateDnsHost(this))) {
                    // This Private DNS is the chosen strategy, not our fallback.
                    StrategyStore.setFallbackActive(this, false)
                    UiUtil.stopVpn(this) // free the single VPN slot for another VPN
                    StrategyStore.setStrategy(this, chosen)
                } else {
                    Toast.makeText(this, R.string.owner_strategy_failed, Toast.LENGTH_LONG).show()
                }
            }
            StrategyStore.Strategy.LOCAL_DNS_VPN -> {
                DeviceAdmin.clearPrivateDns(this)
                StrategyStore.setStrategy(this, chosen)
                UiUtil.startVpnIfPrepared(this)
            }
        }
        bind()
    }

    private fun guardedSwitch(sw: CompoundButton, enable: () -> Unit, disable: () -> Unit) {
        sw.setOnCheckedChangeListener(null)
        sw.setOnClickListener {
            if (sw.isChecked) {
                enable()
                bind()
            } else {
                sw.isChecked = true // revert until the guard is satisfied
                UiUtil.guard(this, GuardPolicy.Action.LOOSEN_MANAGED) {
                    disable()
                    bind()
                }
            }
        }
    }

    private fun bindSwitch(sw: CompoundButton, apply: (Boolean) -> Unit) {
        sw.setOnCheckedChangeListener { _, checked -> apply(checked) }
    }

    private fun bind() {
        val owner = DeviceAdmin.isDeviceOwner(this)
        val admin = DeviceAdmin.isAdminActive(this)
        binding.statusText.setText(
            when {
                owner -> R.string.owner_status_owner
                admin -> R.string.owner_status_admin
                else -> R.string.owner_status_none
            }
        )
        binding.enableCard.visibility = if (owner) View.GONE else View.VISIBLE
        binding.adminCard.visibility = if (owner || admin) View.GONE else View.VISIBLE

        binding.strategyDropdown.setText(getString(strategyLabel(StrategyStore.strategy(this))), false)
        if (binding.dotHostInput.text.isNullOrEmpty()) {
            binding.dotHostInput.setText(StrategyStore.privateDnsHost(this))
        }

        binding.networkLoggingSwitch.isEnabled = owner
        binding.networkLoggingSwitch.setOnCheckedChangeListener(null)
        binding.networkLoggingSwitch.isChecked = owner && DeviceAdmin.isNetworkLoggingEnabled(this)
        binding.networkLoggingSwitch.setOnCheckedChangeListener { _, on -> DeviceAdmin.setNetworkLogging(this, on) }

        binding.suspendAppsSwitch.isEnabled = owner
        binding.suspendAppsSwitch.isChecked = StrategyStore.suspendBlockedApps(this)
        binding.removeButton.isEnabled = owner || admin

        binding.alwaysOnSwitch.isEnabled = owner
        binding.alwaysOnSwitch.isChecked = owner && StrategyStore.alwaysOnVpn(this)
        binding.lockSettingsSwitch.isEnabled = owner
        binding.lockSettingsSwitch.isChecked = owner && DeviceAdmin.isNetworkSettingsLocked(this)
        binding.fallbackSwitch.isEnabled = owner
        binding.fallbackSwitch.isChecked = StrategyStore.privateDnsFallback(this)
    }

    private fun strategyLabel(s: StrategyStore.Strategy) = when (s) {
        StrategyStore.Strategy.LOCAL_DNS_VPN -> R.string.owner_strategy_vpn
        StrategyStore.Strategy.PRIVATE_DNS -> R.string.owner_strategy_private_dns
    }
}
