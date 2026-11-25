package app.focusfence

import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import app.focusfence.data.AllowlistStore
import app.focusfence.data.CategoryStore
import app.focusfence.data.ListRefresh
import app.focusfence.databinding.ActivityCategoriesBinding
import app.focusfence.databinding.RowCategoryBinding
import app.focusfence.dns.Blocklist
import app.focusfence.dns.Categories
import app.focusfence.dns.GuardPolicy
import app.focusfence.ui.Tiles
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.IOException
import java.text.NumberFormat

/**
 * Block lists (2.24 rework of "Categories and allowlist"). Each curated list is Off / Report (recorded, not blocked) /
 * Block, shown as three buttons so every choice is visible. Lists sit in two rounded groups, Protection and Content;
 * the base list carries its strength (HaGeZi tier) as a row. Lists refresh weekly in the background
 * ([ListRefresh]); one "Update now" replaces a button per list. Loosening a list (down from Block) or removing an
 * always-allow entry still goes through the strict-mode guard.
 */
class CategoriesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCategoriesBinding
    private val rows = HashMap<String, RowCategoryBinding>()
    private val policies = listOf(Categories.Policy.OFF, Categories.Policy.REPORT, Categories.Policy.BLOCK)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCategoriesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // The app's purpose first (porn accountability and what protects it), then general blocking on top.
        for (bundle in Categories.BUNDLES.filter { it.core }.sortedBy { if (it.id == "adult") 0 else 1 }) addRow(bundle, binding.groupProtection)
        for (id in EXTRA_ORDER) Categories.bundle(id)?.let { addRow(it, binding.groupContent) }
        for (bundle in Categories.BUNDLES.filter { !it.core && it.id !in EXTRA_ORDER }) addRow(bundle, binding.groupContent)
        Tiles.group(binding.groupProtection)
        Tiles.group(binding.groupContent)

        binding.allowlistAddButton.setOnClickListener { addAllowed() }
        binding.allowlistInput.setOnEditorActionListener { _, id, _ -> (id == EditorInfo.IME_ACTION_DONE).also { if (it) addAllowed() } }
        binding.updateAll.setOnClickListener { updateAll() }
    }

    override fun onResume() {
        super.onResume()
        refreshAllowlist()
        refreshUpdated()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun addRow(bundle: Categories.Bundle, group: LinearLayout) {
        val row = RowCategoryBinding.inflate(layoutInflater, group, true)
        rows[bundle.id] = row
        row.categoryLabel.text = bundle.label
        row.categoryLicense.text = bundle.description
        val buttons = listOf(row.policyOff, row.policyReport, row.policyBlock)
        // A core list is Report or Block, never Off.
        if (bundle.core) row.policyOff.visibility = View.GONE
        var showing = CategoryStore.policy(this, bundle.id)
        fun show(p: Categories.Policy) {
            showing = p
            row.policyGroup.check(buttons[policies.indexOf(p)].id)
        }
        show(showing)

        row.policyGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val chosen = policies[buttons.indexOfFirst { it.id == id }]
            if (chosen == showing) return@addOnButtonCheckedListener
            val current = showing
            fun apply() {
                CategoryStore.setPolicy(this, bundle.id, chosen)
                show(chosen)
                refreshRow(bundle)
                if (chosen != Categories.Policy.OFF && !CategoryStore.file(this, bundle.id).exists()) download(listOf(bundle))
            }
            // Off/Report are looser than Block: moving down from Block goes through the guard.
            if (current == Categories.Policy.BLOCK && chosen != Categories.Policy.BLOCK) {
                show(current) // stays on Block until the guard lets it change
                UiUtil.guard(this, GuardPolicy.Action.LOOSEN_BUNDLE) { apply() }
            } else apply()
        }

        if (bundle.tiered) {
            row.categoryStrength.visibility = View.VISIBLE
            row.categoryStrength.setOnClickListener { pickStrength(bundle) }
        }
        refreshRow(bundle)
    }

    private fun refreshRow(bundle: Categories.Bundle) {
        val row = rows[bundle.id] ?: return
        val on = CategoryStore.policy(this, bundle.id) != Categories.Policy.OFF
        if (bundle.tiered) {
            row.categoryStrength.text = getString(R.string.categories_strength, CategoryStore.baseTier(this).label)
            row.categoryStrength.visibility = if (on) View.VISIBLE else View.GONE
        }
        val count = CategoryStore.entryCount(this, bundle.id)
        row.categoryStatus.visibility = if (on) View.VISIBLE else View.GONE
        row.categoryStatus.text = if (count <= 0) getString(R.string.category_not_downloaded)
        else getString(R.string.category_downloaded, NumberFormat.getIntegerInstance().format(count))
    }

    /** Strength of the base list: plain names with one line each, instead of HaGeZi's tier names. */
    private fun pickStrength(bundle: Categories.Bundle) {
        val tiers = Categories.BaseTier.values()
        val current = tiers.indexOf(CategoryStore.baseTier(this))
        val items = tiers.map { "${it.label}\n${it.blurb}" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.base_tier_hint)
            .setSingleChoiceItems(items, current) { d, which ->
                d.dismiss()
                if (which == current) return@setSingleChoiceItems
                CategoryStore.setBaseTier(this, tiers[which])
                refreshRow(bundle)
                // The base list must be fetched again at the new strength.
                if (CategoryStore.policy(this, bundle.id) != Categories.Policy.OFF) download(listOf(bundle))
            }
            .show()
    }

    private fun updateAll() = download(Categories.BUNDLES.filter { CategoryStore.policy(this, it.id) != Categories.Policy.OFF })

    private fun download(bundles: List<Categories.Bundle>) {
        if (bundles.isEmpty()) return
        binding.updateAll.isEnabled = false
        binding.listsUpdated.setText(R.string.categories_updating)
        for (b in bundles) rows[b.id]?.categoryStatus?.setText(R.string.category_downloading)
        val app = applicationContext
        Thread {
            val failed = bundles.filter { b ->
                try { CategoryStore.download(app, b); false } catch (e: IOException) { true }
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                binding.updateAll.isEnabled = true
                if (failed.isNotEmpty()) {
                    Toast.makeText(this, getString(R.string.categories_update_failed, failed.joinToString { it.label }), Toast.LENGTH_LONG).show()
                }
                bundles.forEach(::refreshRow)
                refreshUpdated()
            }
        }.start()
    }

    private fun refreshUpdated() {
        val oldest = ListRefresh.oldestUpdate(this)
        binding.listsUpdated.text = if (oldest == null) getString(R.string.categories_updated_never)
        else getString(R.string.categories_updated, DateUtils.getRelativeTimeSpanString(oldest, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS))
    }

    private fun addAllowed() {
        val norm = Blocklist.normalize(binding.allowlistInput.text?.toString().orEmpty())
        if (norm == null) {
            Toast.makeText(this, R.string.allowlist_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        AllowlistStore.add(this, norm)
        binding.allowlistInput.text?.clear()
        refreshAllowlist()
    }

    /** One tile per always-allowed site, under the add row, in the same rounded group. */
    private fun refreshAllowlist() {
        val group = binding.groupAllow
        while (group.childCount > 1) group.removeViewAt(1)
        for (domain in AllowlistStore.get(this).sorted()) {
            val row = layoutInflater.inflate(R.layout.row_allowed, group, false)
            row.findViewById<TextView>(R.id.allowedDomain).text = domain
            row.findViewById<ImageButton>(R.id.allowedRemove).apply {
                contentDescription = getString(R.string.allowlist_remove, domain)
                setOnClickListener {
                    UiUtil.guard(this@CategoriesActivity, GuardPolicy.Action.REMOVE_ALLOWLIST) {
                        AllowlistStore.remove(this@CategoriesActivity, domain)
                        refreshAllowlist()
                    }
                }
            }
            group.addView(row)
        }
        Tiles.group(group)
    }

    companion object {
        /** Extra blocking, most useful first. */
        private val EXTRA_ORDER = listOf("security", "threat", "scam", "gambling", "social", "piracy")
    }
}
