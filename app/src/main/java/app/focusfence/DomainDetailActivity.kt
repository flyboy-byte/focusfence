package app.focusfence

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import app.focusfence.data.BlocklistStore
import app.focusfence.data.StrictStore
import app.focusfence.databinding.ActivityDomainDetailBinding
import app.focusfence.dns.BlockRule
import app.focusfence.dns.Blocklist
import com.google.android.material.chip.Chip
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.util.Locale

/**
 * The add/edit wizard for one domain: pick a mode (always / hours / daily
 * limit / allowed sections) with its controls inline.
 */
class DomainDetailActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_DOMAIN = "domain"
        private val BUDGET_OPTIONS = intArrayOf(5, 10, 15, 20, 30, 45, 60, 90)

        fun intent(context: Context, domain: String): Intent =
            Intent(context, DomainDetailActivity::class.java).putExtra(EXTRA_DOMAIN, domain)
    }

    private lateinit var binding: ActivityDomainDetailBinding
    private var editingDomain: String? = null
    private var startMinute = 9 * 60
    private var endMinute = 18 * 60

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDomainDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        editingDomain = intent.getStringExtra(EXTRA_DOMAIN)
        val editing = editingDomain != null
        supportActionBar?.title =
            editingDomain ?: getString(R.string.add_domain_title)

        buildBudgetChips()
        buildPresetChips()

        binding.domainInputLayout.visibility = if (editing) View.GONE else View.VISIBLE
        binding.presetChips.visibility = if (editing) View.GONE else View.VISIBLE
        binding.presetAllButton.visibility = if (editing) View.GONE else View.VISIBLE
        binding.deleteButton.visibility = if (editing) View.VISIBLE else View.GONE

        binding.modeGroup.setOnCheckedChangeListener { _, _ -> updateModeVisibility() }

        binding.startTimeButton.setOnClickListener {
            pickTime(R.string.picker_start_title, startMinute) {
                startMinute = it
                updateTimeButtons()
            }
        }
        binding.endTimeButton.setOnClickListener {
            pickTime(R.string.picker_end_title, endMinute) {
                endMinute = it
                updateTimeButtons()
            }
        }

        binding.presetAllButton.setOnClickListener {
            BlocklistStore.addAll(this, BlocklistStore.SOCIAL_MEDIA_PRESETS)
            Toast.makeText(this, R.string.presets_added, Toast.LENGTH_SHORT).show()
            finish()
        }

        binding.saveButton.setOnClickListener { save() }
        binding.domainInput.doAfterTextChanged { updateInfraNote() }
        binding.deleteButton.setOnClickListener { delete() }

        val locked = StrictStore.isLocked(this)
        if (editing && locked) {
            // Editing an existing rule can loosen it; lock the whole editor.
            binding.lockedBanner.visibility = View.VISIBLE
            binding.saveButton.isEnabled = false
            binding.deleteButton.isEnabled = false
        }

        val rule = editingDomain?.let { BlocklistStore.rulesByDomain(this)[it] }
        if (rule != null) loadRule(rule) else binding.modeAlways.isChecked = true
        updateModeVisibility()
        updateTimeButtons()
        updateInfraNote()
    }

    /** #18: a rule on google.com and the like skips push, time and connectivity hosts; say so where it's set. */
    private fun updateInfraNote() {
        val domain = editingDomain ?: Blocklist.normalize(binding.domainInput.text?.toString().orEmpty())
        binding.infraNote.visibility = if (domain != null && Blocklist.coversInfrastructure(domain)) View.VISIBLE else View.GONE
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun loadRule(rule: BlockRule) {
        when {
            rule.hasPathExceptions -> {
                binding.modePaths.isChecked = true
                binding.pathsInput.setText(rule.allowPaths.joinToString(", "))
            }
            rule.hasBudget -> {
                binding.modeBudget.isChecked = true
                for (i in 0 until binding.budgetChips.childCount) {
                    val chip = binding.budgetChips.getChildAt(i) as Chip
                    if (chip.tag == rule.budgetMinutes) chip.isChecked = true
                }
            }
            rule.isScheduled -> {
                binding.modeWindow.isChecked = true
                startMinute = rule.startMinute
                endMinute = rule.endMinute
            }
            else -> binding.modeAlways.isChecked = true
        }
    }

    private fun buildBudgetChips() {
        binding.budgetChips.isSingleSelection = true
        for (minutes in BUDGET_OPTIONS) {
            val chip = Chip(this)
            chip.text = getString(R.string.minutes_chip, minutes)
            chip.isCheckable = true
            chip.id = View.generateViewId()
            chip.tag = minutes
            binding.budgetChips.addView(chip)
        }
    }

    private fun buildPresetChips() {
        for (preset in BlocklistStore.SOCIAL_MEDIA_PRESETS) {
            val chip = Chip(this)
            chip.text = preset
            chip.id = View.generateViewId()
            chip.setOnClickListener { binding.domainInput.setText(preset) }
            binding.presetChips.addView(chip)
        }
    }

    private fun updateModeVisibility() {
        binding.windowContainer.visibility =
            if (binding.modeWindow.isChecked) View.VISIBLE else View.GONE
        binding.budgetChips.visibility =
            if (binding.modeBudget.isChecked) View.VISIBLE else View.GONE
        binding.pathsInputLayout.visibility =
            if (binding.modePaths.isChecked) View.VISIBLE else View.GONE
        binding.pathsHelp.visibility =
            if (binding.modePaths.isChecked) View.VISIBLE else View.GONE
    }

    private fun updateTimeButtons() {
        binding.startTimeButton.text = getString(R.string.start_time_fmt, formatMinute(startMinute))
        binding.endTimeButton.text = getString(R.string.end_time_fmt, formatMinute(endMinute))
    }

    private fun formatMinute(m: Int): String =
        String.format(Locale.ROOT, "%02d:%02d", m / 60, m % 60)

    private fun pickTime(titleRes: Int, defaultMinute: Int, onPicked: (Int) -> Unit) {
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(defaultMinute / 60)
            .setMinute(defaultMinute % 60)
            .setTitleText(titleRes)
            .build()
        picker.addOnPositiveButtonClickListener { onPicked(picker.hour * 60 + picker.minute) }
        picker.show(supportFragmentManager, "time-picker")
    }

    private fun save() {
        val domain = editingDomain ?: run {
            val normalized = Blocklist.normalize(binding.domainInput.text?.toString().orEmpty())
            if (normalized == null) {
                binding.domainInputLayout.error = getString(R.string.invalid_domain)
                return
            }
            binding.domainInputLayout.error = null
            normalized
        }

        val rule = when {
            binding.modeWindow.isChecked -> {
                if (startMinute == endMinute) {
                    Toast.makeText(this, R.string.schedule_same_time, Toast.LENGTH_LONG).show()
                    return
                }
                BlockRule(domain, startMinute, endMinute)
            }
            binding.modeBudget.isChecked -> {
                val checkedId = binding.budgetChips.checkedChipId
                if (checkedId == View.NO_ID) {
                    Toast.makeText(this, R.string.error_pick_budget, Toast.LENGTH_LONG).show()
                    return
                }
                val minutes = findViewById<Chip>(checkedId).tag as Int
                BlockRule(domain, budgetMinutes = minutes)
            }
            binding.modePaths.isChecked -> {
                val paths = binding.pathsInput.text?.toString().orEmpty().split(',')
                    .mapNotNull { Blocklist.normalizePath(it) }
                    .distinct()
                if (paths.isEmpty()) {
                    Toast.makeText(this, R.string.paths_invalid, Toast.LENGTH_LONG).show()
                    return
                }
                BlockRule(domain, allowPaths = paths)
            }
            else -> BlockRule(domain)
        }

        BlocklistStore.upsert(this, rule)
        if (rule.hasPathExceptions && !UiUtil.isUrlFilterEnabled(this)) {
            UiUtil.promptEnableUrlFilter(this) { finish() }
        } else {
            finish()
        }
    }

    private fun delete() {
        val domain = editingDomain ?: return
        BlocklistStore.remove(this, domain)
        finish()
    }
}
