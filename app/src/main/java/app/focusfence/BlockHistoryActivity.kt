package app.focusfence

import android.os.Bundle
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import app.focusfence.data.EventLog
import app.focusfence.databinding.ActivityBlockHistoryBinding
import app.focusfence.databinding.RowPrefBinding
import app.focusfence.dns.BlockHistory
import app.focusfence.dns.Categories
import app.focusfence.dns.LogJson
import app.focusfence.ui.Tiles
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.concurrent.thread

/**
 * Block history (2026-11-25): the sites the filter stopped, newest first, grouped by day, read from this phone's
 * activity log ([BlockHistory] decides what counts). Nothing here leaves the phone.
 */
class BlockHistoryActivity : AppCompatActivity() {
    private lateinit var b: ActivityBlockHistoryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityBlockHistoryBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setTitle(R.string.history_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        val app = applicationContext
        thread(name = "block-history") {
            EventLog.init(app)
            EventLog.flushAsync()
            val records = ArrayList<Map<String, Any?>>()
            EventLog.forEachLine(EventLog.Channel.ACTIVITY, "\"blocked\"") { line -> LogJson.decode(line)?.let(records::add) }
            val entries = BlockHistory.entries(records.asSequence()).take(MAX)
            runOnUiThread { if (!isDestroyed) show(entries) }
        }
    }

    private fun show(entries: List<BlockHistory.Entry>) {
        b.loading.visibility = View.GONE
        b.list.removeAllViews()
        b.empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        for ((day, items) in entries.groupBy { Instant.ofEpochMilli(it.last).atZone(zone).toLocalDate() }) {
            val label = layoutInflater.inflate(R.layout.item_group_label, b.list, false) as TextView
            label.text = when (day) {
                today -> getString(R.string.stats_period_today)
                today.minusDays(1) -> getString(R.string.history_yesterday)
                else -> DateUtils.formatDateTime(this, day.atStartOfDay(zone).toInstant().toEpochMilli(), DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY)
            }
            b.list.addView(label)
            val group = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            for (e in items) {
                val r = RowPrefBinding.inflate(layoutInflater, group, false)
                r.prefTitle.text = e.site
                r.prefSummary.text = why(e.why)
                r.prefSummary.visibility = View.VISIBLE
                r.prefValue.text = DateFormat.getTimeFormat(this).format(e.first)
                r.prefValue.visibility = View.VISIBLE
                r.prefChevron.visibility = View.GONE
                group.addView(r.root)
            }
            b.list.addView(group)
            Tiles.group(group)
        }
    }

    private fun why(id: String): String =
        if (id == "rule") getString(R.string.history_why_rule)
        else getString(R.string.history_why_list, Categories.bundle(id)?.label?.substringBefore(" (")?.substringBefore(" —") ?: id)

    companion object {
        private const val MAX = 300
    }
}
