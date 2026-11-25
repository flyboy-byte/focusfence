package app.focusfence

import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import app.focusfence.admin.DeviceAdmin
import app.focusfence.dns.GuardPolicy

/**
 * Uninstall protection: device admin with no policies. While it's on, Android asks for it to be deactivated before
 * the app can be uninstalled. That is one extra confirm, not a lock: App info offers "Deactivate & uninstall" in one
 * go (Logan pointed this out 2026-11-25, and the old copy claimed more). The receiver logs the deactivation; if the
 * app is then removed, the record can't sync, so what partners actually see is the "phone went silent" alert.
 *
 * Device owner ("managed mode") was removed on 2026-11-24: it needed adb and a phone with no accounts, and nobody
 * would set that up. A phone still provisioned from an older version gets one button to leave it cleanly.
 */
class UninstallProtectionActivity : AppCompatActivity() {
    private lateinit var b: app.focusfence.databinding.ActivityUninstallProtectionBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = app.focusfence.databinding.ActivityUninstallProtectionBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setTitle(R.string.admin_card_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }
        app.focusfence.ui.Tiles.style(b.card, app.focusfence.ui.Tiles.Pos.SINGLE, clickable = false)

        b.turnOn.setOnClickListener {
            startActivity(
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, DeviceAdmin.component(this))
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.admin_add_explanation))
            )
        }
        b.turnOff.setOnClickListener {
            // Turning it off is a loosening: same friction as before; the receiver logs it and partners hear.
            UiUtil.guard(this, GuardPolicy.Action.REMOVE_PROTECTION) {
                DeviceAdmin.deactivate(this)
                // removeActiveAdmin is asynchronous: re-read once Android has applied it.
                b.status.postDelayed({ if (!isFinishing) bind() }, 800)
            }
        }
        b.leaveOwner.setOnClickListener {
            UiUtil.guard(this, GuardPolicy.Action.REMOVE_PROTECTION) {
                DeviceAdmin.relinquish(this)
                bind()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        bind()
    }

    private fun bind() {
        val owner = DeviceAdmin.isDeviceOwner(this)
        val on = owner || DeviceAdmin.isAdminActive(this)
        b.status.setText(if (on) R.string.admin_status_on else R.string.admin_status_off)
        b.turnOn.visibility = if (on) View.GONE else View.VISIBLE
        b.turnOff.visibility = if (on && !owner) View.VISIBLE else View.GONE
        b.leaveOwner.visibility = if (owner) View.VISIBLE else View.GONE
    }
}
