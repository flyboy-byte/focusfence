package app.focusfence

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import app.focusfence.data.AllowlistStore
import app.focusfence.data.CategoryStore
import app.focusfence.data.StrictStore
import app.focusfence.databinding.ActivityCategoriesBinding
import app.focusfence.databinding.RowCategoryBinding
import app.focusfence.dns.Blocklist
import app.focusfence.dns.Categories
import com.google.android.material.chip.Chip
import java.io.IOException
import java.text.NumberFormat

/**
 * Manage filter bundles and the allowlist. The owner toggles curated bundles
 * Off / Report / Block rather than editing domains; the allowlist is only a
 * false-positive escape hatch.
 *
 * Each bundle's policy is three-way — Off / Report (recorded, not blocked) /
 * Block — making the detect-vs-enforce split visible. Loosening a bundle (down
 * from Block) or removing an allowlist entry is a strict-mode-gated action.
 * The base tier sets the Security bundle's strictness.
 */
class CategoriesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCategoriesBinding
    private val rows = HashMap<String, RowCategoryBinding>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCategoriesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        setupTierDropdown()
        for (bundle in Categories.BUNDLES) addBundleRow(bundle)

        binding.allowlistAddButton.setOnClickListener {
            val norm = Blocklist.normalize(binding.allowlistInput.text?.toString().orEmpty())
            if (norm == null) {
                Toast.makeText(this, R.string.allowlist_invalid, Toast.LENGTH_SHORT).show()
            } else {
                AllowlistStore.add(this, norm)
                binding.allowlistInput.text?.clear()
                refreshAllowlist()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAllowlist()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun setupTierDropdown() {
        val tiers = Categories.BaseTier.values().toList()
        val labels = tiers.map { it.label }
        binding.baseTierDropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, labels))
        binding.baseTierDropdown.setText(CategoryStore.baseTier(this).label, false)
        binding.baseTierDropdown.setOnItemClickListener { _, _, pos, _ ->
            CategoryStore.setBaseTier(this, tiers[pos])
            // The security list must be re-fetched at the new tier if it's on.
            rows["security"]?.let { row ->
                if (CategoryStore.policy(this, "security") != Categories.Policy.OFF) {
                    download(Categories.bundle("security")!!, row)
                }
            }
        }
    }

    private fun addBundleRow(bundle: Categories.Bundle) {
        val row = RowCategoryBinding.inflate(layoutInflater, binding.categoryContainer, true)
        rows[bundle.id] = row
        row.categoryLabel.text = bundle.label
        row.categoryLicense.text = bundle.description

        val policies = listOf(Categories.Policy.OFF, Categories.Policy.REPORT, Categories.Policy.BLOCK)
        val labels = listOf(getString(R.string.policy_off), getString(R.string.policy_report), getString(R.string.policy_block))
        row.policyDropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, labels))
        fun showPolicy(p: Categories.Policy) = row.policyDropdown.setText(labels[policies.indexOf(p)], false)
        showPolicy(CategoryStore.policy(this, bundle.id))

        row.policyDropdown.setOnItemClickListener { _, _, pos, _ ->
            val chosen = policies[pos]
            val current = CategoryStore.policy(this, bundle.id)
            // Off/Report are looser than Block: moving down from Block needs an unlock.
            if (current == Categories.Policy.BLOCK && chosen != Categories.Policy.BLOCK &&
                StrictStore.isLocked(this)
            ) {
                showPolicy(current)
                UiUtil.showStrictLockedDialog(this)
                return@setOnItemClickListener
            }
            CategoryStore.setPolicy(this, bundle.id, chosen)
            maybeOfferDownload(bundle, row)
        }

        row.categoryDownload.setOnClickListener { download(bundle, row) }
        refreshBundleStatus(bundle, row)
    }

    private fun maybeOfferDownload(bundle: Categories.Bundle, row: RowCategoryBinding) {
        if (CategoryStore.policy(this, bundle.id) != Categories.Policy.OFF &&
            CategoryStore.url(this, bundle).isNotBlank() && !CategoryStore.file(this, bundle.id).exists()
        ) {
            download(bundle, row)
        }
    }

    private fun refreshBundleStatus(bundle: Categories.Bundle, row: RowCategoryBinding) {
        val count = CategoryStore.entryCount(this, bundle.id)
        row.categoryStatus.text = if (count <= 0) {
            getString(R.string.category_not_downloaded)
        } else {
            getString(R.string.category_downloaded, NumberFormat.getIntegerInstance().format(count))
        }
    }

    private fun download(bundle: Categories.Bundle, row: RowCategoryBinding) {
        row.categoryDownload.isEnabled = false
        row.categoryStatus.text = getString(R.string.category_downloading)
        val app = applicationContext
        Thread {
            val error = try {
                CategoryStore.download(app, bundle); null
            } catch (e: IOException) {
                e.message ?: "error"
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                row.categoryDownload.isEnabled = true
                if (error != null) {
                    Toast.makeText(this, getString(R.string.category_download_failed, error), Toast.LENGTH_LONG).show()
                }
                refreshBundleStatus(bundle, row)
            }
        }.start()
    }

    private fun refreshAllowlist() {
        binding.allowlistChips.removeAllViews()
        val entries = AllowlistStore.get(this).sorted()
        binding.allowlistChips.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        for (domain in entries) {
            val chip = Chip(this).apply {
                text = domain
                isCloseIconVisible = true
                setOnCloseIconClickListener {
                    if (StrictStore.isLocked(this@CategoriesActivity)) {
                        UiUtil.showStrictLockedDialog(this@CategoriesActivity)
                    } else {
                        AllowlistStore.remove(this@CategoriesActivity, domain)
                        refreshAllowlist()
                    }
                }
            }
            binding.allowlistChips.addView(chip)
        }
    }
}
