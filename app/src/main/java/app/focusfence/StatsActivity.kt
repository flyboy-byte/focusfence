package app.focusfence

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import app.focusfence.data.BlocklistStore
import app.focusfence.data.StatsStore
import app.focusfence.data.UsageStore
import app.focusfence.databinding.ActivityStatsBinding
import app.focusfence.databinding.ItemStatBinding
import app.focusfence.dns.StatsCodec
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** Blocking history: today's counts, budget usage, and a 7-day trend. */
class StatsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStatsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStatsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onResume() {
        super.onResume()
        populate()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun populate() {
        val today = LocalDate.now()
        val todayStats = StatsStore.get(this, today.toString())
        val total = StatsCodec.total(todayStats)
        val ads = todayStats[StatsCodec.KEY_ADS] ?: 0
        binding.todaySummary.text = getString(R.string.stats_today_summary, total, ads)

        // Top blocked domains today (user rules only).
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

        // Budget usage today.
        binding.budgetStats.removeAllViews()
        val budgetRules = BlocklistStore.rules(this).filter { it.hasBudget }
        if (budgetRules.isEmpty()) {
            addRow(binding.budgetStats, getString(R.string.stats_no_budgets), "", 0, 1)
        } else {
            for (rule in budgetRules) {
                val usedMin =
                    (UsageStore.usedMillisOn(this, rule.domain, today.toString()) / 60_000L).toInt()
                addRow(
                    binding.budgetStats,
                    rule.domain,
                    getString(R.string.stats_budget_value, usedMin, rule.budgetMinutes),
                    usedMin.coerceAtMost(rule.budgetMinutes),
                    rule.budgetMinutes,
                )
            }
        }

        // Last 7 days.
        binding.weekStats.removeAllViews()
        val days = (6L downTo 0L).map { today.minusDays(it) }
        val totals = days.associateWith { StatsCodec.total(StatsStore.get(this, it.toString())) }
        val weekMax = (totals.values.maxOrNull() ?: 0).coerceAtLeast(1)
        for (day in days) {
            val label = if (day == today) {
                getString(R.string.stats_today_label)
            } else {
                day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            }
            val count = totals.getValue(day)
            addRow(binding.weekStats, label, count.toString(), count, weekMax)
        }
    }

    private fun addRow(parent: ViewGroup, title: String, value: String, progress: Int, max: Int) {
        val row = ItemStatBinding.inflate(LayoutInflater.from(this), parent, false)
        row.statTitle.text = title
        row.statValue.text = value
        row.statBar.max = max
        row.statBar.progress = progress
        parent.addView(row.root)
    }
}
