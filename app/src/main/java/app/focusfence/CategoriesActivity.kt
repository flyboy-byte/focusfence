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
 * Manage content categories (adult, gambling, social) and the allowlist.
 *
 * Each category has a three-way policy — Off / Report / Block — making the
 * detection-vs-enforcement split visible to the user: "Report" records matches
 * without blocking, "Block" also blocks. Removing a category from Block, or
 * removing an allowlist entry, is a loosening action and goes through strict
 * mode like any other.
 */
class CategoriesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCategoriesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCategoriesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        for (source in Categories.DEFAULT_SOURCES) addCategoryRow(source)

        binding.allowlistAddButton.setOnClickListener {
            val raw = binding.allowlistInput.text?.toString().orEmpty()
            val norm = Blocklist.normalize(raw)
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

    private fun addCategoryRow(source: Categories.Source) {
        val row = RowCategoryBinding.inflate(layoutInflater, binding.categoryContainer, true)
        row.categoryLabel.text = source.label
        row.categoryLicense.text = source.license

        val policies = listOf(Categories.Policy.OFF, Categories.Policy.REPORT, Categories.Policy.BLOCK)
        val labels = listOf(getString(R.string.policy_off), getString(R.string.policy_report), getString(R.string.policy_block))
        row.policyDropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, labels))
        fun showPolicy(p: Categories.Policy) = row.policyDropdown.setText(labels[policies.indexOf(p)], false)
        showPolicy(CategoryStore.policy(this, source.id))

        row.policyDropdown.setOnItemClickListener { _, _, pos, _ ->
            val chosen = policies[pos]
            val current = CategoryStore.policy(this, source.id)
            // Off/Report are looser than Block: moving down from Block needs an unlock.
            if (current == Categories.Policy.BLOCK && chosen != Categories.Policy.BLOCK &&
                StrictStore.isLocked(this)
            ) {
                showPolicy(current)
                UiUtil.showStrictLockedDialog(this)
                return@setOnItemClickListener
            }
            CategoryStore.setPolicy(this, source.id, chosen)
            maybeOfferDownload(source, row)
        }

        row.categoryDownload.setOnClickListener { download(source, row) }
        if (source.url.isBlank()) {
            row.categoryDownload.isEnabled = false
            row.categoryStatus.text = getString(R.string.category_no_list)
        } else {
            refreshCategoryStatus(source, row)
        }
    }

    private fun maybeOfferDownload(source: Categories.Source, row: RowCategoryBinding) {
        if (CategoryStore.policy(this, source.id) != Categories.Policy.OFF &&
            source.url.isNotBlank() && !CategoryStore.file(this, source.id).exists()
        ) {
            download(source, row)
        }
    }

    private fun refreshCategoryStatus(source: Categories.Source, row: RowCategoryBinding) {
        val count = CategoryStore.entryCount(this, source.id)
        row.categoryStatus.text = if (count <= 0) {
            getString(R.string.category_not_downloaded)
        } else {
            getString(R.string.category_downloaded, NumberFormat.getIntegerInstance().format(count))
        }
    }

    private fun download(source: Categories.Source, row: RowCategoryBinding) {
        row.categoryDownload.isEnabled = false
        row.categoryStatus.text = getString(R.string.category_downloading)
        val app = applicationContext
        Thread {
            val error = try {
                CategoryStore.download(app, source); null
            } catch (e: IOException) {
                e.message ?: "error"
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                row.categoryDownload.isEnabled = true
                if (error != null) {
                    Toast.makeText(this, getString(R.string.category_download_failed, error), Toast.LENGTH_LONG).show()
                }
                refreshCategoryStatus(source, row)
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
