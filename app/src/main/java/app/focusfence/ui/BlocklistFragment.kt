package app.focusfence.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import app.focusfence.dns.GuardPolicy
import app.focusfence.DomainDetailActivity
import app.focusfence.R
import app.focusfence.UiUtil
import app.focusfence.data.BlockedAppsStore
import app.focusfence.data.BlocklistStore
import app.focusfence.data.UsageStore
import app.focusfence.databinding.FragmentBlocklistBinding
import app.focusfence.dns.BlockRule
import java.time.LocalDate
import java.util.Locale

class BlocklistFragment : Fragment() {

    companion object {
        private const val SECTION_SITES = "sites"
        private const val SECTION_APPS = "apps"

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
            onSectionAdd = { key ->
                if (key == SECTION_APPS) UiUtil.chooseBlockedApps(requireContext()) { refresh() }
                else startActivity(Intent(requireContext(), DomainDetailActivity::class.java))
            },
            onLinkClick = { key ->
                val target = if (key == "history") app.focusfence.BlockHistoryActivity::class.java else app.focusfence.CategoriesActivity::class.java
                startActivity(Intent(requireContext(), target))
            },
            onHeaderClick = { key ->
                if (!collapsedGroups.remove(key)) collapsedGroups.add(key)
                refresh()
            },
            onDomainClick = { rule ->
                startActivity(DomainDetailActivity.intent(requireContext(), rule.domain))
            },
            onDomainDelete = { rule ->
                UiUtil.guard(requireContext(), GuardPolicy.Action.REMOVE_BLOCK_RULE) {
                    BlocklistStore.remove(requireContext(), rule.domain)
                    refresh()
                }
            },
            onAppClick = { pkg -> UiUtil.editAppRule(requireActivity(), pkg) { refresh() } },
            onAppRemove = { pkg ->
                val context = requireContext()
                UiUtil.guard(context, GuardPolicy.Action.UNBLOCK_APP) {
                    BlockedAppsStore.set(context, BlockedAppsStore.get(context) - pkg)
                    refresh()
                }
            },
        )
        binding.blockList.adapter = adapter
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
        // A row's corners depend on its neighbours, so reshape every row once the list has changed.
        adapter.submitList(buildRows()) { adapter.notifyItemRangeChanged(0, adapter.itemCount) }
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

        // Block lists first: they do most of the blocking. The classic layout keeps them in Settings instead.
        if (!app.focusfence.data.UiPrefs.classic(context)) {
            val on = app.focusfence.dns.Categories.BUNDLES.count {
                app.focusfence.data.CategoryStore.policy(context, it.id) != app.focusfence.dns.Categories.Policy.OFF
            }
            rows.add(Row.Section("lists", getString(R.string.blocking_lists), addLabel = null))
            rows.add(
                Row.Link(
                    "categories", getString(R.string.categories_title),
                    getString(R.string.blocking_lists_value, app.focusfence.data.CategoryStore.baseTier(context).label.substringBefore(" —"), on),
                )
            )
        }

        // Obvious on purpose (Logan, 2026-11-25): what got blocked, sites and trackers.
        rows.add(Row.Action("history", getString(R.string.history_open)))

        rows.add(Row.Section(SECTION_SITES, getString(R.string.blocking_sites), getString(R.string.blocking_add_site)))
        if (rules.isEmpty()) rows.add(Row.Hint(getString(R.string.blocking_sites_empty), "sites_empty"))
        // Group headings only help when there's more than one kind of rule.
        val kinds = groups.count { (_, _, p) -> rules.any(p) }

        for ((key, titleRes, predicate) in groups) {
            val members = rules.filter(predicate)
            if (members.isEmpty()) continue
            if (kinds == 1) {
                members.forEach { rows.add(Row.Domain(it, summaryFor(it, dayKey))) }
                continue
            }
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
        rows.add(Row.Section(SECTION_APPS, getString(R.string.blocking_apps), getString(R.string.blocking_add_app)))
        run {
            if (apps.isEmpty()) {
                rows.add(Row.Hint(getString(R.string.blocking_apps_empty), "apps_empty"))
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
        return rows
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
