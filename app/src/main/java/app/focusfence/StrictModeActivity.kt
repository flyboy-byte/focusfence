package app.focusfence

import android.os.Bundle
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import app.focusfence.data.PinStore
import app.focusfence.data.StrictStore
import app.focusfence.databinding.ActivityStrictBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Configure strict mode and how it unlocks (StrongHold-style). Strict mode makes
 * every loosening action wait out a delay; a PIN is an alternative to the wait:
 *
 * - **Timer** (default): request unlock, wait 10 min, act within 5 min.
 * - **Own PIN**: unlock immediately with a PIN you set.
 * - **Partner PIN**: unlock only with a PIN held by your accountability partner.
 *
 * The timed delay always remains as a fallback, so a forgotten PIN can never
 * lock you out permanently. Enabling strict mode is allowed anytime; changing
 * these settings while it is locked is itself gated by the lock.
 */
class StrictModeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStrictBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStrictBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.strictSwitch.setOnCheckedChangeListener { _, on ->
            // Disabling strict mode is the loosening direction.
            if (!on && StrictStore.isLocked(this)) {
                UiUtil.showStrictLockedDialog(this) { bind() }
                bind()
                return@setOnCheckedChangeListener
            }
            StrictStore.setEnabled(this, on)
            bind()
        }

        val modes = PinStore.UnlockMode.values()
        binding.modeDropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1,
            modes.map { getString(modeLabel(it)) }))
        binding.modeDropdown.setOnItemClickListener { _, _, pos, _ ->
            if (guardedByLock()) { bind(); return@setOnItemClickListener }
            when (modes[pos]) {
                // Switching to a PIN mode with no PIN set yet prompts to set one.
                PinStore.UnlockMode.OWNER_PIN -> if (!hasOwnerPin()) {
                    promptSetOwnerPin { PinStore.setMode(this, PinStore.UnlockMode.OWNER_PIN); bind() }
                    return@setOnItemClickListener
                }
                PinStore.UnlockMode.PARTNER_PIN -> if (!PinStore.hasPartnerPin(this)) {
                    promptGeneratePartnerPin { bind() }
                    return@setOnItemClickListener
                }
                PinStore.UnlockMode.TIMER -> Unit
            }
            PinStore.setMode(this, modes[pos])
            bind()
        }

        binding.setOwnerPinButton.setOnClickListener {
            if (guardedByLock()) return@setOnClickListener
            promptSetOwnerPin { bind() }
        }
        binding.partnerPinButton.setOnClickListener {
            if (guardedByLock()) return@setOnClickListener
            promptGeneratePartnerPin { bind() }
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

    private fun guardedByLock(): Boolean {
        if (StrictStore.isLocked(this)) {
            UiUtil.showStrictLockedDialog(this) { bind() }
            return true
        }
        return false
    }

    private fun hasOwnerPin(): Boolean = PinStore.hasOwnerPin(this)

    private fun bind() {
        binding.strictSwitch.isChecked = StrictStore.isEnabled(this)
        binding.modeDropdown.setText(getString(modeLabel(PinStore.mode(this))), false)
        binding.setOwnerPinButton.setText(if (hasOwnerPin()) R.string.strict_change_owner_pin else R.string.strict_set_owner_pin)
        binding.partnerPinButton.setText(if (PinStore.hasPartnerPin(this)) R.string.strict_regen_partner_pin else R.string.strict_gen_partner_pin)
    }

    private fun promptSetOwnerPin(onDone: () -> Unit) {
        val input = pinInput()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.strict_set_owner_pin)
            .setMessage(R.string.strict_owner_pin_message)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val pin = input.text?.toString().orEmpty()
                if (pin.length < 4) {
                    Toast.makeText(this, R.string.strict_pin_too_short, Toast.LENGTH_LONG).show()
                } else {
                    PinStore.setOwnerPin(this, pin)
                    onDone()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun promptGeneratePartnerPin(onDone: () -> Unit) {
        val pin = PinStore.generatePartnerPin(this)
        PinStore.setMode(this, PinStore.UnlockMode.PARTNER_PIN)
        // Shown once: the owner hands this to the partner and doesn't keep it.
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.strict_partner_pin_title)
            .setMessage(getString(R.string.strict_partner_pin_message, pin))
            .setPositiveButton(android.R.string.ok) { _, _ -> onDone() }
            .setCancelable(false)
            .show()
    }

    private fun pinInput() = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        hint = getString(R.string.strict_pin_hint)
    }

    private fun modeLabel(m: PinStore.UnlockMode) = when (m) {
        PinStore.UnlockMode.TIMER -> R.string.strict_mode_timer
        PinStore.UnlockMode.OWNER_PIN -> R.string.strict_mode_owner
        PinStore.UnlockMode.PARTNER_PIN -> R.string.strict_mode_partner
    }
}
