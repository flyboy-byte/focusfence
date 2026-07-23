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
) : ListAdapter<BlockRule, DomainAdapter.Holder>(DIFF) {

    fun submit(rules: List<BlockRule>) = submitList(rules)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemDomainBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(private val binding: ItemDomainBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(rule: BlockRule) {
            binding.domainText.text = rule.domain
            val ctx = binding.root.context
            binding.scheduleText.text = if (rule.isScheduled) {
                ctx.getString(
                    R.string.rule_window,
                    formatMinute(rule.startMinute),
                    formatMinute(rule.endMinute),
                )
            } else {
                ctx.getString(R.string.rule_always)
            }
            binding.scheduleButton.setOnClickListener { onEditSchedule(rule) }
            binding.deleteButton.setOnClickListener { onDelete(rule) }
        }
    }

    private companion object {
        fun formatMinute(m: Int): String =
            String.format(Locale.ROOT, "%02d:%02d", m / 60, m % 60)

        val DIFF = object : DiffUtil.ItemCallback<BlockRule>() {
            override fun areItemsTheSame(oldItem: BlockRule, newItem: BlockRule) =
                oldItem.domain == newItem.domain

            override fun areContentsTheSame(oldItem: BlockRule, newItem: BlockRule) =
                oldItem == newItem
        }
    }
}
