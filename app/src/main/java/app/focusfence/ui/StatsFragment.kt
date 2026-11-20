package app.focusfence.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import app.focusfence.R
import app.focusfence.data.BlocklistStore
import app.focusfence.data.StatsStore
import app.focusfence.data.UsageStore
import app.focusfence.databinding.FragmentStatsBinding
import app.focusfence.databinding.ItemStatBinding
import app.focusfence.dns.StatsCodec
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** Blocking history: today's counts, budget usage, and a 7-day trend. */
class StatsFragment : Fragment() {

    private var _binding: FragmentStatsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentStatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        populate()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun populate() {
        val context = requireContext()
        val today = LocalDate.now()
        val todayStats = StatsStore.get(context, today.toString())
        val total = StatsCodec.total(todayStats)
        val ads = todayStats[StatsCodec.KEY_ADS] ?: 0
        binding.todaySummary.text = getString(R.string.stats_today_summary, total, ads)

        binding.domainStats.removeAllViews()
        val domainCounts = todayStats
            .filterKeys { it != StatsCodec.KEY_ADS && it != StatsCodec.KEY_GUARD }
            .entries.sortedByDescending { it.value }
            .take(10)
        if (domainCounts.isEmpty()) {
            addRow(binding.domainStats, getString(R.string.stats_none_yet), "", 0, 1)
        } else {
            val max = domainCounts.first().value
            for ((domain, count) in domainCounts) {
                addRow(binding.domainStats, domain, count.toString(), count, max)
            }
        }

        binding.budgetStats.removeAllViews()
        val budgetRules = BlocklistStore.rules(context).filter { it.hasBudget }
        if (budgetRules.isEmpty()) {
            addRow(binding.budgetStats, getString(R.string.stats_no_budgets), "", 0, 1)
        } else {
            for (rule in budgetRules) {
                val usedMin =
                    (UsageStore.usedMillisOn(context, rule.domain, today.toString()) / 60_000L)
                        .toInt()
                addRow(
                    binding.budgetStats,
                    rule.domain,
                    getString(R.string.stats_budget_value, usedMin, rule.budgetMinutes),
                    usedMin.coerceAtMost(rule.budgetMinutes),
                    rule.budgetMinutes,
                )
            }
        }

        binding.weekStats.removeAllViews()
        val days = (6L downTo 0L).map { today.minusDays(it) }
        val totals = days.associateWith { StatsCodec.total(StatsStore.get(context, it.toString())) }
        binding.weekChart.setData(
            days.map { day ->
                if (day == today) getString(R.string.stats_today_label)
                else day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            },
            days.map { totals.getValue(it) },
        )
    }

    private fun addRow(parent: ViewGroup, title: String, value: String, progress: Int, max: Int) {
        val row = ItemStatBinding.inflate(layoutInflater, parent, false)
        row.statTitle.text = title
        row.statValue.text = value
        row.statBar.max = max
        row.statBar.progress = progress
        parent.addView(row.root)
    }
}
