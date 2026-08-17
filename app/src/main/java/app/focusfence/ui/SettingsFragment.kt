package app.focusfence.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import app.focusfence.HelpActivity
import app.focusfence.R
import app.focusfence.UiUtil
import app.focusfence.data.BlockedAppsStore
import app.focusfence.data.BlocklistStore
import app.focusfence.data.ExcludedAppsStore
import app.focusfence.data.FilterListStore
import app.focusfence.data.StrictStore
import app.focusfence.databinding.FragmentSettingsBinding
import app.focusfence.databinding.ItemFilterCategoryBinding
import app.focusfence.databinding.ItemFilterSourceBinding
import app.focusfence.dns.FilterSource
import app.focusfence.dns.FilterSources
import java.io.IOException
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.adUpdateButton.setOnClickListener { downloadSources(enabledSources()) }

        binding.excludedAppsButton.setOnClickListener {
            UiUtil.chooseExcludedApps(requireContext()) { refreshAll() }
        }
        binding.accessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.guideButton.setOnClickListener {
            startActivity(Intent(requireContext(), HelpActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun refreshAll() {
        populateFilterSources()
        refreshFilterStatus()
        refreshAccessibility()
        val b = _binding ?: return
        b.excludedAppsButton.text = getString(
            R.string.excluded_apps_button,
            ExcludedAppsStore.get(requireContext()).size,
        )
    }

    private fun enabledSources(): List<FilterSource> =
        FilterListStore.enabledIds(requireContext()).mapNotNull { FilterSources.byId(it) }

    private fun categoryLabel(category: String): String = getString(
        when (category) {
            FilterSources.CATEGORY_ADULT -> R.string.filter_category_adult
            FilterSources.CATEGORY_GAMBLING -> R.string.filter_category_gambling
            FilterSources.CATEGORY_SOCIAL -> R.string.filter_category_social
            else -> R.string.filter_category_ads
        }
    )

    /**
     * One switch per category, one checkbox per source. Enabling is always
     * allowed; disabling a source or category is a loosening action and goes
     * through the strict-mode gate like every other destructive path (D-012 —
     * the gate matters most on the adult category, per D-016).
     */
    private fun populateFilterSources() {
        val b = _binding ?: return
        val context = requireContext()
        val enabled = FilterListStore.enabledIds(context)
        b.filterSourcesContainer.removeAllViews()
        for (category in FilterSources.CATEGORIES) {
            val sources = FilterSources.inCategory(category)
            if (sources.isEmpty()) continue
            val anyEnabled = sources.any { it.id in enabled }

            val header = ItemFilterCategoryBinding.inflate(layoutInflater, b.filterSourcesContainer, false)
            header.categoryName.text = categoryLabel(category)
            header.categorySwitch.isChecked = anyEnabled
            header.categorySwitch.setOnCheckedChangeListener { switch, checked ->
                if (checked == sources.any { FilterListStore.isEnabled(context, it.id) }) {
                    return@setOnCheckedChangeListener
                }
                if (checked) {
                    FilterSources.defaultFor(category)?.let { setSourceEnabled(it, true) }
                } else if (StrictStore.isLocked(context)) {
                    switch.isChecked = true
                    UiUtil.showStrictLockedDialog(context)
                } else {
                    for (source in sources) FilterListStore.setEnabled(context, source.id, false)
                    refreshAll()
                }
            }
            b.filterSourcesContainer.addView(header.root)

            for (source in sources) {
                val row = ItemFilterSourceBinding.inflate(layoutInflater, b.filterSourcesContainer, false)
                row.sourceName.text = source.name
                row.sourceMeta.text = sourceMeta(source)
                row.sourceCheck.isChecked = source.id in enabled
                row.sourceCheck.setOnCheckedChangeListener { check, checked ->
                    if (checked == FilterListStore.isEnabled(context, source.id)) {
                        return@setOnCheckedChangeListener
                    }
                    if (!checked && StrictStore.isLocked(context)) {
                        check.isChecked = true
                        UiUtil.showStrictLockedDialog(context)
                    } else {
                        setSourceEnabled(source, checked)
                    }
                }
                // Attribution: tapping the source opens its license text.
                row.sourceInfo.setOnClickListener {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(source.licenseUrl)))
                }
                b.filterSourcesContainer.addView(row.root)
            }
        }
    }

    private fun sourceMeta(source: FilterSource): String {
        val context = requireContext()
        val count = FilterListStore.entryCount(context, source.id)
        val updatedAt = FilterListStore.updatedAt(context, source.id)
        return if (count == 0 || updatedAt == 0L) {
            getString(R.string.filter_source_not_downloaded, source.license)
        } else {
            getString(
                R.string.filter_source_meta,
                source.license,
                NumberFormat.getIntegerInstance().format(count),
                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(updatedAt)),
            )
        }
    }

    private fun setSourceEnabled(source: FilterSource, enabled: Boolean) {
        val context = requireContext()
        FilterListStore.setEnabled(context, source.id, enabled)
        if (enabled && !FilterListStore.file(context, source.id).exists()) {
            downloadSources(listOf(source))
        } else {
            refreshAll()
        }
    }

    /** Sequential download on one background thread; user-triggered only. */
    private fun downloadSources(sources: List<FilterSource>) {
        if (sources.isEmpty()) return
        val b = _binding ?: return
        b.adUpdateButton.isEnabled = false
        b.adStatusText.text = getString(R.string.adlist_downloading)
        val appContext = requireContext().applicationContext
        Thread {
            var failed = false
            for (source in sources) {
                try {
                    FilterListStore.download(appContext, source)
                } catch (e: IOException) {
                    failed = true
                }
            }
            _binding?.root?.post {
                val binding = _binding ?: return@post
                binding.adUpdateButton.isEnabled = true
                if (failed && isAdded) {
                    Toast.makeText(requireContext(), R.string.adlist_error, Toast.LENGTH_LONG)
                        .show()
                }
                refreshAll()
            }
        }.start()
    }

    private fun refreshFilterStatus() {
        val b = _binding ?: return
        val context = requireContext()
        val enabled = enabledSources()
        b.adStatusText.text = if (enabled.isEmpty()) {
            getString(R.string.filter_status_none)
        } else {
            val domains = enabled.sumOf { FilterListStore.entryCount(context, it.id) }
            getString(
                R.string.filter_status_summary,
                enabled.size,
                NumberFormat.getIntegerInstance().format(domains),
            )
        }
    }

    private fun refreshAccessibility() {
        val b = _binding ?: return
        val context = requireContext()
        val configured = BlocklistStore.rules(context).any { it.hasPathExceptions } ||
            BlockedAppsStore.get(context).isNotEmpty()
        val enabled = UiUtil.isUrlFilterEnabled(context)
        b.accessibilityStatusText.text = when {
            enabled -> getString(R.string.section_enabled)
            configured -> getString(R.string.section_disabled)
            else -> getString(R.string.section_idle)
        }
    }
}
