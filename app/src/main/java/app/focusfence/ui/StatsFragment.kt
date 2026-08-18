package app.focusfence.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import app.focusfence.R
import app.focusfence.data.BlocklistStore
import app.focusfence.data.StatsStore
import app.focusfence.data.UsageStore
import app.focusfence.databinding.FragmentStatsBinding
import app.focusfence.databinding.ItemStatBinding
import app.focusfence.databinding.ItemWeekBarBinding
import app.focusfence.dns.FilterSources
import app.focusfence.dns.StatsCodec
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** Blocking history: today's counts, budget usage, and a 7-day trend. */
class StatsFragment : Fragment() {

    private companion object {
        /** Neutral bar fills, brightest first, so rank reads without colour. */
        private val RANK_TINTS = intArrayOf(
            R.color.bar_strong, R.color.bar_mid, R.color.bar_low, R.color.bar_faint,
        )

        /** A day with no blocks still shows a sliver, so the week reads as seven. */
        private const val MIN_BAR_WEIGHT = 0.02f
    }

    private var _binding: FragmentStatsBinding? = null
    private val binding get() = _binding!!
    private val numberFormat: NumberFormat = NumberFormat.getIntegerInstance()

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
        val listBlocks = todayStats.entries.sumOf {
            if (StatsCodec.isListBucket(it.key) && it.key != StatsCodec.KEY_GUARD) it.value else 0
        }
        binding.todayCount.text = numberFormat.format(total)
        binding.todaySummary.text = getString(R.string.stats_blocked_lookups) + "\n" +
            getString(R.string.stats_from_lists, numberFormat.format(listBlocks))

        binding.domainStats.removeAllViews()
        val domainCounts = todayStats
            .filterKeys { !StatsCodec.isListBucket(it) }
            .entries.sortedByDescending { it.value }
            .take(10)
        if (domainCounts.isEmpty()) {
            addRow(binding.domainStats, getString(R.string.stats_none_yet), "", 0, 1, rank = 0)
        } else {
            val max = domainCounts.first().value
            domainCounts.forEachIndexed { index, (domain, count) ->
                addRow(
                    binding.domainStats,
                    domain,
                    numberFormat.format(count),
                    count,
                    max,
                    rank = index,
                )
            }
        }

        // Provenance (W11): which filter list did the blocking. "_ads" is the
        // pre-W11 aggregate bucket and still shows for days recorded before it.
        binding.listStats.removeAllViews()
        val listCounts = todayStats.entries
            .filter { StatsCodec.isListBucket(it.key) && it.key != StatsCodec.KEY_GUARD }
            .sortedByDescending { it.value }
        if (listCounts.isEmpty()) {
            addRow(binding.listStats, getString(R.string.stats_none_yet), "", 0, 1, rank = 0)
        } else {
            val max = listCounts.first().value
            listCounts.forEachIndexed { index, (bucket, count) ->
                val sourceId = StatsCodec.sourceId(bucket)
                val name = sourceId?.let { FilterSources.byId(it)?.name ?: it }
                    ?: getString(R.string.stats_legacy_list)
                addRow(
                    binding.listStats,
                    name,
                    numberFormat.format(count),
                    count,
                    max,
                    rank = index,
                )
            }
        }

        binding.budgetStats.removeAllViews()
        val budgetRules = BlocklistStore.rules(context).filter { it.hasBudget }
        if (budgetRules.isEmpty()) {
            addRow(binding.budgetStats, getString(R.string.stats_no_budgets), "", 0, 1, rank = 0)
        } else {
            for (rule in budgetRules) {
                val usedMin =
                    (UsageStore.usedMillisOn(context, rule.domain, today.toString()) / 60_000L)
                        .toInt()
                val exhausted = usedMin >= rule.budgetMinutes
                addRow(
                    binding.budgetStats,
                    rule.domain,
                    if (exhausted) {
                        getString(R.string.stats_used_up)
                    } else {
                        getString(R.string.stats_budget_value, usedMin, rule.budgetMinutes)
                    },
                    usedMin.coerceAtMost(rule.budgetMinutes),
                    rule.budgetMinutes,
                    // A spent budget is the one thing on this screen worth
                    // colouring: it's the state that changes what happens next.
                    tintRes = if (exhausted) R.color.danger else R.color.warn,
                    valueTintRes = if (exhausted) R.color.danger else R.color.text_secondary,
                )
            }
        }

        populateWeek(today)
    }

    private fun populateWeek(today: LocalDate) {
        val context = requireContext()
        val chart = binding.weekChart
        chart.removeAllViews()
        val days = (6L downTo 0L).map { today.minusDays(it) }
        val totals = days.associateWith { StatsCodec.total(StatsStore.get(context, it.toString())) }
        val weekMax = (totals.values.maxOrNull() ?: 0).coerceAtLeast(1)

        for (day in days) {
            val isToday = day == today
            val column = ItemWeekBarBinding.inflate(layoutInflater, chart, false)
            val fraction = (totals.getValue(day).toFloat() / weekMax)
                .coerceIn(MIN_BAR_WEIGHT, 1f)
            setWeight(column.weekBar, fraction)
            setWeight(column.weekSpace, 1f - fraction)
            column.weekBar.backgroundTintList = ColorStateList.valueOf(
                color(if (isToday) R.color.accent else R.color.bar_track)
            )
            column.weekLabel.text =
                day.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault())
            column.weekLabel.setTextColor(
                color(if (isToday) R.color.accent else R.color.text_tertiary)
            )
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            params.marginEnd = if (day == days.last()) 0 else dp(9)
            chart.addView(column.root, params)
        }
    }

    private fun addRow(
        parent: ViewGroup,
        title: String,
        value: String,
        progress: Int,
        max: Int,
        rank: Int = 0,
        tintRes: Int? = null,
        valueTintRes: Int = R.color.text_secondary,
    ) {
        val row = ItemStatBinding.inflate(layoutInflater, parent, false)
        row.statTitle.text = title
        row.statValue.text = value
        row.statValue.setTextColor(color(valueTintRes))
        val safeMax = max.coerceAtLeast(1)
        val filled = progress.coerceIn(0, safeMax).toFloat() / safeMax
        setWeight(row.statBarFill, filled)
        setWeight(row.statBarRest, 1f - filled)
        row.statBarFill.backgroundTintList = ColorStateList.valueOf(
            color(tintRes ?: RANK_TINTS[rank.coerceAtMost(RANK_TINTS.lastIndex)])
        )
        parent.addView(row.root)
    }

    private fun setWeight(view: View, weight: Float) {
        val params = view.layoutParams as LinearLayout.LayoutParams
        params.weight = weight
        view.layoutParams = params
    }

    private fun color(res: Int): Int = ContextCompat.getColor(requireContext(), res)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)
}
