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
import app.focusfence.data.StrictStore
import app.focusfence.data.UsageStore
import app.focusfence.databinding.FragmentBlocklistBinding
import app.focusfence.dns.BlockRule
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
                UiUtil.guard(requireContext(), GuardPolicy.Action.REMOVE_BLOCK_RULE) {
                    BlocklistStore.remove(requireContext(), rule.domain)
                    refresh()
                }
            },
            onAppsEdit = { UiUtil.chooseBlockedApps(requireContext()) { refresh() } },
            onAppClick = { pkg -> UiUtil.editAppRule(requireActivity(), pkg) { refresh() } },
            onAppRemove = { pkg ->
                val context = requireContext()
                UiUtil.guard(context, GuardPolicy.Action.UNBLOCK_APP) {
                    BlockedAppsStore.set(context, BlockedAppsStore.get(context) - pkg)
                    refresh()
                }
            },
            onHintClick = { key ->
                when (key) {
                    GroupedBlocklistAdapter.GROUP_APPS ->
                        UiUtil.chooseBlockedApps(requireContext()) { refresh() }
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
        bindCategories()
    }

    /** Five-tab layout: the categories card leads into the full screen; the classic layout keeps it in Settings. */
    private fun bindCategories() {
        val b = _binding ?: return
        val ctx = requireContext()
        if (app.focusfence.data.UiPrefs.classic(ctx)) { b.categoriesCard.visibility = View.GONE; return }
        b.categoriesCard.visibility = View.VISIBLE
        val on = app.focusfence.dns.Categories.BUNDLES.count { app.focusfence.data.CategoryStore.policy(ctx, it.id) != app.focusfence.dns.Categories.Policy.OFF }
        b.categoriesSummary.text = getString(R.string.categories_card_summary, app.focusfence.data.CategoryStore.baseTier(ctx).label, on)
        b.categoriesCard.setOnClickListener { startActivity(android.content.Intent(ctx, app.focusfence.CategoriesActivity::class.java)) }
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
