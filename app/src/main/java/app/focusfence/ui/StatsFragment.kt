package app.focusfence.ui

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import app.focusfence.R
import app.focusfence.data.BlocklistStore
import app.focusfence.data.StatsStore
import app.focusfence.data.UsageStore
import app.focusfence.databinding.FragmentStatsBinding
import app.focusfence.databinding.ItemAppTimeBinding
import app.focusfence.databinding.ItemStatBinding
import app.focusfence.dns.AppTime
import app.focusfence.dns.StatsCodec
import app.focusfence.monitor.AppCatalog
import app.focusfence.monitor.UsageCollector
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.Executors

/**
 * This week: screen time and the apps it went to, then what was blocked, then budgets. Today or the last 7 days.
 *
 * App time is read from UsageStats only while this screen is open (one query of a week's events, off the main
 * thread), so it costs nothing in the background; the activity log keeps its own timeline separately.
 */
class StatsFragment : Fragment() {

    private var _binding: FragmentStatsBinding? = null
    private val binding get() = _binding!!
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private class AppRow(val pkg: String, val label: String, val icon: Drawable?, val ms: Long)
    private class Usage(val perDay: List<Long>, val today: List<AppRow>, val week: List<AppRow>)

    private var usage: Usage? = null
    private var week = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentStatsBinding.inflate(inflater, container, false)
        week = savedInstanceState?.getBoolean(KEY_WEEK) ?: false
        binding.periodToggle.check(if (week) R.id.periodWeek else R.id.periodToday)
        binding.periodToggle.addOnButtonCheckedListener { _, id, checked ->
            if (checked) { week = id == R.id.periodWeek; render() }
        }
        binding.usageGrant.setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        }
        return binding.root
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_WEEK, week)
    }

    override fun onResume() {
        super.onResume()
        render()
        val ctx = requireContext().applicationContext
        if (UsageCollector.hasAccess(ctx)) {
            worker.execute {
                val u = runCatching { loadUsage(ctx) }.getOrNull()
                main.post { if (_binding != null) { usage = u; render() } }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
    }

    private fun days(): List<LocalDate> = LocalDate.now().let { today -> (6L downTo 0L).map { today.minusDays(it) } }

    private fun dayLabels(days: List<LocalDate>): List<String> {
        val today = days.last()
        return days.map {
            if (it == today) getString(R.string.stats_today_label)
            else it.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        }
    }

    /** Runs on [worker]. */
    private fun loadUsage(ctx: Context): Usage {
        val zone = ZoneId.systemDefault()
        val days = days()
        val from = days.first().atStartOfDay(zone).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()
        val pm = ctx.packageManager
        val home = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet()
        val time = AppTime(zone, ignoredPackages = AppCatalog.overlays(ctx), hiddenPackages = home)
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
        usm?.queryEvents(from, now)?.let { events ->
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                time.feed(e.timeStamp, e.eventType, e.packageName)
            }
        }
        time.finish(now)
        fun rows(list: List<Pair<String, Long>>) = list.filter { it.second >= 60_000 }.take(TOP).map { (pkg, ms) ->
            val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
            AppRow(
                pkg,
                info?.let { pm.getApplicationLabel(it).toString() } ?: pkg,
                info?.let { runCatching { pm.getApplicationIcon(it) }.getOrNull() },
                ms,
            )
        }
        return Usage(days.map { time.total(it) }, rows(time.apps(days.last())), rows(time.apps(days)))
    }

    private fun render() {
        val b = _binding ?: return
        val ctx = requireContext()
        val days = days()
        val today = days.last()
        val access = UsageCollector.hasAccess(ctx)
        val u = usage

        // Screen time
        b.usageMissing.visibility = if (access) View.GONE else View.VISIBLE
        b.screenChart.visibility = if (access && u != null) View.VISIBLE else View.GONE
        b.appsHeader.visibility = if (access) View.VISIBLE else View.GONE
        b.appStats.removeAllViews()
        when {
            !access -> { b.screenTime.text = "–"; b.screenTimeSub.text = "" }
            u == null -> { b.screenTime.text = "…"; b.screenTimeSub.text = "" }
            else -> {
                val apps = if (week) u.week else u.today
                if (week) {
                    val total = u.perDay.sum()
                    b.screenTime.text = AppTime.format(total)
                    b.screenTimeSub.text = getString(R.string.stats_week_average, AppTime.format(total / 7))
                } else {
                    b.screenTime.text = AppTime.format(u.perDay.last())
                    b.screenTimeSub.text = resources.getQuantityString(R.plurals.stats_apps_used, apps.size, apps.size)
                }
                b.screenChart.setData(dayLabels(days), u.perDay.map { (it / 60_000).toInt() }, ::shortMinutes)
                if (apps.isEmpty()) {
                    addRow(b.appStats, getString(R.string.stats_apps_none), "", 0, 1)
                } else {
                    val max = apps.first().ms
                    for (a in apps) addApp(b.appStats, a, max)
                }
            }
        }

        // Blocked
        val counts = HashMap<String, Int>()
        for (d in if (week) days else listOf(today)) {
            StatsStore.get(ctx, d.toString()).forEach { (k, v) -> counts[k] = (counts[k] ?: 0) + v }
        }
        val total = StatsCodec.total(counts)
        val ads = counts[StatsCodec.KEY_ADS] ?: 0
        b.blockSummary.text = getString(
            if (week) R.string.stats_blocked_week else R.string.stats_blocked_today, total, ads,
        )
        b.domainStats.removeAllViews()
        val sites = counts.filterKeys { it != StatsCodec.KEY_ADS && it != StatsCodec.KEY_GUARD }
            .entries.sortedByDescending { it.value }.take(TOP)
        if (sites.isEmpty()) {
            addRow(b.domainStats, getString(R.string.stats_none_yet), "", 0, 1)
        } else {
            val max = sites.first().value
            for ((domain, n) in sites) addRow(b.domainStats, domain, n.toString(), n, max)
        }
        b.weekChart.setData(dayLabels(days), days.map { StatsCodec.total(StatsStore.get(ctx, it.toString())) })

        // Budgets (today only: they reset daily)
        val budgetRules = BlocklistStore.rules(ctx).filter { it.hasBudget }
        b.budgetSection.visibility = if (budgetRules.isEmpty()) View.GONE else View.VISIBLE
        b.budgetStats.removeAllViews()
        for (rule in budgetRules) {
            val usedMin = (UsageStore.usedMillisOn(ctx, rule.domain, today.toString()) / 60_000L).toInt()
            addRow(
                b.budgetStats, rule.domain, getString(R.string.stats_budget_value, usedMin, rule.budgetMinutes),
                usedMin.coerceAtMost(rule.budgetMinutes), rule.budgetMinutes,
            )
        }
    }

    /** Bar labels have little room: "45m", "2.5h". */
    private fun shortMinutes(min: Int): String =
        if (min < 60) "${min}m" else String.format(Locale.getDefault(), "%.1fh", min / 60.0)

    private fun addApp(parent: ViewGroup, a: AppRow, max: Long) {
        val row = ItemAppTimeBinding.inflate(layoutInflater, parent, false)
        row.appName.text = a.label
        row.appTime.text = AppTime.format(a.ms)
        if (a.icon != null) row.appIcon.setImageDrawable(a.icon) else row.appIcon.setImageResource(R.drawable.ic_circle_outline)
        row.appBar.max = 1000
        row.appBar.progress = ((a.ms * 1000) / max.coerceAtLeast(1)).toInt()
        parent.addView(row.root)
    }

    private fun addRow(parent: ViewGroup, title: String, value: String, progress: Int, max: Int) {
        val row = ItemStatBinding.inflate(layoutInflater, parent, false)
        row.statTitle.text = title
        row.statValue.text = value
        row.statBar.max = max
        row.statBar.progress = progress
        parent.addView(row.root)
    }

    companion object {
        private const val TOP = 10
        private const val KEY_WEEK = "week"
    }
}
