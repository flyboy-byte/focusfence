package app.focusfence

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.focusfence.databinding.ItemDomainBinding
import app.focusfence.dns.BlockRule
import java.util.Locale

class DomainAdapter(
    private val onEditSchedule: (BlockRule) -> Unit,
    private val onDelete: (BlockRule) -> Unit,
) : ListAdapter<DomainAdapter.Row, DomainAdapter.Holder>(DIFF) {

    data class Row(val rule: BlockRule, val usedMillisToday: Long)

    fun submit(rules: List<BlockRule>, usageMillis: Map<String, Long>) =
        submitList(rules.map { Row(it, usageMillis[it.domain] ?: 0L) })

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemDomainBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(private val binding: ItemDomainBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: Row) {
            val rule = row.rule
            binding.domainText.text = rule.domain
            val ctx = binding.root.context
            binding.scheduleText.text = when {
                rule.hasPathExceptions -> ctx.getString(
                    R.string.rule_paths,
                    rule.allowPaths.joinToString(", "),
                )
                rule.hasBudget -> {
                    val usedMinutes = (row.usedMillisToday / 60_000L).toInt()
                    if (usedMinutes >= rule.budgetMinutes) {
                        ctx.getString(R.string.rule_budget_exhausted, rule.budgetMinutes)
                    } else {
                        ctx.getString(R.string.rule_budget, rule.budgetMinutes, usedMinutes)
                    }
                }
                rule.isScheduled -> ctx.getString(
                    R.string.rule_window,
                    formatMinute(rule.startMinute),
                    formatMinute(rule.endMinute),
                )
                else -> ctx.getString(R.string.rule_always)
            }
            binding.root.setOnClickListener { onEditSchedule(rule) }
            binding.scheduleButton.setOnClickListener { onEditSchedule(rule) }
            binding.deleteButton.setOnClickListener { onDelete(rule) }
        }
    }

    private companion object {
        fun formatMinute(m: Int): String =
            String.format(Locale.ROOT, "%02d:%02d", m / 60, m % 60)

        val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(oldItem: Row, newItem: Row) =
                oldItem.rule.domain == newItem.rule.domain

            override fun areContentsTheSame(oldItem: Row, newItem: Row) = oldItem == newItem
        }
    }
}
