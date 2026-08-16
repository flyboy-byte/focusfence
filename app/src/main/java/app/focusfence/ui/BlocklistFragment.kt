package app.focusfence.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.fragment.app.Fragment
import app.focusfence.DomainDetailActivity
import app.focusfence.R
import app.focusfence.UiUtil
import app.focusfence.data.AllowlistStore
import app.focusfence.data.BlockedAppsStore
import app.focusfence.data.BlocklistStore
import app.focusfence.data.StrictStore
import app.focusfence.data.UsageStore
import app.focusfence.databinding.FragmentBlocklistBinding
import app.focusfence.dns.BlockRule
import app.focusfence.dns.Blocklist
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.time.LocalDate
import java.util.Locale

class BlocklistFragment : Fragment() {

    companion object {
        /** Collapse state survives tab switches for the life of the process. */
        private val collapsedGroups = mutableSetOf<String>()
    }

    private var _binding: FragmentBlocklistBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: GroupedBlocklistAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentBlocklistBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = GroupedBlocklistAdapter(
            onHeaderClick = { key ->
                if (!collapsedGroups.remove(key)) collapsedGroups.add(key)
                refresh()
            },
            onDomainClick = { rule ->
                startActivity(DomainDetailActivity.intent(requireContext(), rule.domain))
            },
            onDomainDelete = { rule ->
                if (StrictStore.isLocked(requireContext())) {
                    UiUtil.showStrictLockedDialog(requireContext())
                } else {
                    BlocklistStore.remove(requireContext(), rule.domain)
                    refresh()
                }
            },
            onHeaderAdd = { key ->
                when (key) {
                    GroupedBlocklistAdapter.GROUP_APPS ->
                        UiUtil.chooseBlockedApps(requireContext()) { refresh() }
                    GroupedBlocklistAdapter.GROUP_ALLOWED -> promptAddAllowlistDomain(requireContext())
                }
            },
            onAppClick = { pkg -> UiUtil.editAppRule(requireActivity(), pkg) { refresh() } },
            onAppRemove = { pkg ->
                val context = requireContext()
                if (StrictStore.isLocked(context)) {
                    UiUtil.showStrictLockedDialog(context)
                } else {
                    BlockedAppsStore.set(context, BlockedAppsStore.get(context) - pkg)
                    refresh()
                }
            },
            onAllowedRemove = { domain ->
                AllowlistStore.remove(requireContext(), domain)
                refresh()
            },
            onHintClick = { key ->
                when (key) {
                    GroupedBlocklistAdapter.GROUP_APPS ->
                        UiUtil.chooseBlockedApps(requireContext()) { refresh() }
                    GroupedBlocklistAdapter.GROUP_ALLOWED -> promptAddAllowlistDomain(requireContext())
                    else -> startActivity(
                        Intent(requireContext(), DomainDetailActivity::class.java)
                    )
                }
            },
        )
        binding.blockList.adapter = adapter
        binding.addFab.setOnClickListener {
            startActivity(Intent(requireContext(), DomainDetailActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun refresh() {
        adapter.submitList(buildRows())
    }

    private fun buildRows(): List<Row> {
        val context = requireContext()
        val rules = BlocklistStore.rules(context)
        val dayKey = LocalDate.now().toString()

        val groups = listOf(
            Triple("always", R.string.group_always) { r: BlockRule ->
                !r.isScheduled && !r.hasBudget && !r.hasPathExceptions
            },
            Triple("window", R.string.group_window) { r: BlockRule -> r.isScheduled },
            Triple("budget", R.string.group_budget) { r: BlockRule -> r.hasBudget },
            Triple("paths", R.string.group_paths) { r: BlockRule -> r.hasPathExceptions },
        )

        val rows = mutableListOf<Row>()
        if (rules.isEmpty()) rows.add(Row.Hint(getString(R.string.blocklist_empty), "domains"))

        for ((key, titleRes, predicate) in groups) {
            val members = rules.filter(predicate)
            if (members.isEmpty()) continue
            val expanded = key !in collapsedGroups
            rows.add(
                Row.Header(
                    key,
                    getString(R.string.group_title_fmt, getString(titleRes), members.size),
                    expanded,
                )
            )
            if (expanded) {
                members.forEach { rows.add(Row.Domain(it, summaryFor(it, dayKey))) }
            }
        }

        val apps = BlockedAppsStore.rules(context)
        val appsExpanded = GroupedBlocklistAdapter.GROUP_APPS !in collapsedGroups
        rows.add(
            Row.Header(
                GroupedBlocklistAdapter.GROUP_APPS,
                getString(R.string.group_title_fmt, getString(R.string.group_apps), apps.size),
                appsExpanded,
                showAdd = true,
            )
        )
        if (appsExpanded) {
            if (apps.isEmpty()) {
                rows.add(
                    Row.Hint(
                        getString(R.string.apps_empty_hint),
                        GroupedBlocklistAdapter.GROUP_APPS,
                    )
                )
            } else {
                apps.forEach {
                    rows.add(
                        Row.App(
                            it.domain,
                            UiUtil.appLabel(context, it.domain),
                            UiUtil.appRuleSummary(context, it),
                        )
                    )
                }
            }
        }
        val allowed = AllowlistStore.get(context).sorted()
        val allowedExpanded = GroupedBlocklistAdapter.GROUP_ALLOWED !in collapsedGroups
        rows.add(
            Row.Header(
                GroupedBlocklistAdapter.GROUP_ALLOWED,
                getString(R.string.group_title_fmt, getString(R.string.group_allowed), allowed.size),
                allowedExpanded,
                showAdd = true,
            )
        )
        if (allowedExpanded) {
            if (allowed.isEmpty()) {
                rows.add(
                    Row.Hint(
                        getString(R.string.allowed_empty_hint),
                        GroupedBlocklistAdapter.GROUP_ALLOWED,
                    )
                )
            } else {
                allowed.forEach { rows.add(Row.Allowed(it)) }
            }
        }
        return rows
    }

    private fun promptAddAllowlistDomain(context: Context) {
        if (StrictStore.isLocked(context)) {
            UiUtil.showStrictLockedDialog(context)
            return
        }
        val input = EditText(context).apply { hint = getString(R.string.allow_domain_hint) }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.allow_domain_title)
            .setView(input)
            .setPositiveButton(R.string.menu_add) { _, _ ->
                addAllowlistDomain(context, Blocklist.normalize(input.text?.toString().orEmpty()))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun addAllowlistDomain(context: Context, normalized: String?) {
        if (normalized == null) {
            MaterialAlertDialogBuilder(context)
                .setMessage(R.string.invalid_domain)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        if (Blocklist.matches(normalized, Blocklist.DOH_BYPASS_GUARDS)) {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.allow_doh_warning_title)
                .setMessage(R.string.allow_doh_warning_message)
                .setPositiveButton(R.string.menu_add) { _, _ ->
                    AllowlistStore.add(context, normalized)
                    refresh()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            AllowlistStore.add(context, normalized)
            refresh()
        }
    }

    private fun summaryFor(rule: BlockRule, dayKey: String): String {
        val context = requireContext()
        return when {
            rule.hasPathExceptions ->
                getString(R.string.rule_paths, rule.allowPaths.joinToString(", "))
            rule.hasBudget -> {
                val usedMinutes =
                    (UsageStore.usedMillisOn(context, rule.domain, dayKey) / 60_000L).toInt()
                if (usedMinutes >= rule.budgetMinutes) {
                    getString(R.string.rule_budget_exhausted, rule.budgetMinutes)
                } else {
                    getString(R.string.rule_budget, rule.budgetMinutes, usedMinutes)
                }
            }
            rule.isScheduled -> getString(
                R.string.rule_window,
                formatMinute(rule.startMinute),
                formatMinute(rule.endMinute),
            )
            else -> getString(R.string.rule_always)
        }
    }

    private fun formatMinute(m: Int): String =
        String.format(Locale.ROOT, "%02d:%02d", m / 60, m % 60)
}
