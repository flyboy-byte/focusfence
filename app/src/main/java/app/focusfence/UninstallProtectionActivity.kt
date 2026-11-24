package app.focusfence

import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import app.focusfence.admin.DeviceAdmin
import app.focusfence.dns.GuardPolicy
import com.google.android.material.appbar.MaterialToolbar

/**
 * Uninstall protection, Ever Accountable's model: device admin with no policies. While it's on, Android won't
 * uninstall the app until admin is turned off first, a second deliberate step that the receiver logs and partners
 * hear about. It never blocks anything.
 *
 * Device owner ("managed mode") was removed on 2026-11-24: it needed adb and a phone with no accounts, and nobody
 * would set that up. A phone still provisioned from an older version gets one button to leave it cleanly.
 */
class UninstallProtectionActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var turnOn: Button
    private lateinit var turnOff: Button
    private lateinit var leaveOwner: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val toolbar = MaterialToolbar(this)
        status = TextView(this).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
        }
        val body = TextView(this).apply {
            text = getString(R.string.admin_card_body)
            setPadding(0, pad / 2, 0, pad)
        }
        turnOn = Button(this).apply {
            text = getString(R.string.admin_activate)
            setOnClickListener {
                startActivity(
                    Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, DeviceAdmin.component(this@UninstallProtectionActivity))
                        .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.admin_add_explanation))
                )
            }
        }
        turnOff = Button(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.admin_turn_off)
            setOnClickListener {
                // Turning it off is a loosening: same friction as before; the receiver logs it and partners hear.
                UiUtil.guard(this@UninstallProtectionActivity, GuardPolicy.Action.REMOVE_PROTECTION) {
                    DeviceAdmin.deactivate(this@UninstallProtectionActivity)
                    bind()
                }
            }
        }
        leaveOwner = Button(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.owner_remove)
            setOnClickListener {
                UiUtil.guard(this@UninstallProtectionActivity, GuardPolicy.Action.REMOVE_PROTECTION) {
                    DeviceAdmin.relinquish(this@UninstallProtectionActivity)
                    bind()
                }
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(toolbar, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                addView(status)
                addView(body)
                addView(turnOn)
                addView(turnOff)
                addView(leaveOwner)
            })
        })
        setSupportActionBar(toolbar)
        supportActionBar?.setTitle(R.string.admin_card_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        bind()
    }

    private fun bind() {
        val owner = DeviceAdmin.isDeviceOwner(this)
        val on = owner || DeviceAdmin.isAdminActive(this)
        status.setText(if (on) R.string.admin_status_on else R.string.admin_status_off)
        turnOn.visibility = if (on) View.GONE else View.VISIBLE
        turnOff.visibility = if (on && !owner) View.VISIBLE else View.GONE
        leaveOwner.visibility = if (owner) View.VISIBLE else View.GONE
    }
}
