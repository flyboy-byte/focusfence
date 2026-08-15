package app.focusfence.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.focusfence.R
import app.focusfence.databinding.ItemDomainCompactBinding
import app.focusfence.databinding.ItemGroupHeaderBinding
import app.focusfence.databinding.ItemHintBinding
import app.focusfence.dns.BlockRule

sealed class Row {
    data class Header(val key: String, val title: String, val expanded: Boolean) : Row()
    data class Domain(val rule: BlockRule, val summary: String) : Row()
    data class App(val pkg: String, val label: String, val summary: String) : Row()
    data class Hint(val text: String) : Row()
}

class GroupedBlocklistAdapter(
    private val onHeaderClick: (String) -> Unit,
    private val onDomainClick: (BlockRule) -> Unit,
    private val onDomainDelete: (BlockRule) -> Unit,
    private val onAppsEdit: () -> Unit,
    private val onAppClick: (String) -> Unit,
    private val onAppRemove: (String) -> Unit,
) : ListAdapter<Row, RecyclerView.ViewHolder>(DIFF) {

    companion object {
        const val GROUP_APPS = "apps"
        private const val TYPE_HEADER = 0
        private const val TYPE_DOMAIN = 1
        private const val TYPE_APP = 2
        private const val TYPE_HINT = 3

        private val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(oldItem: Row, newItem: Row): Boolean = when {
                oldItem is Row.Header && newItem is Row.Header -> oldItem.key == newItem.key
                oldItem is Row.Domain && newItem is Row.Domain ->
                    oldItem.rule.domain == newItem.rule.domain
                oldItem is Row.App && newItem is Row.App -> oldItem.pkg == newItem.pkg
                oldItem is Row.Hint && newItem is Row.Hint -> true
                else -> false
            }

            override fun areContentsTheSame(oldItem: Row, newItem: Row) = oldItem == newItem
        }
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is Row.Header -> TYPE_HEADER
        is Row.Domain -> TYPE_DOMAIN
        is Row.App -> TYPE_APP
        is Row.Hint -> TYPE_HINT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderHolder(ItemGroupHeaderBinding.inflate(inflater, parent, false))
            TYPE_HINT -> HintHolder(ItemHintBinding.inflate(inflater, parent, false))
            else -> EntryHolder(ItemDomainCompactBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is Row.Header -> (holder as HeaderHolder).bind(row)
            is Row.Domain -> (holder as EntryHolder).bindDomain(row)
            is Row.App -> (holder as EntryHolder).bindApp(row)
            is Row.Hint -> (holder as HintHolder).bind(row)
        }
    }

    inner class HeaderHolder(private val binding: ItemGroupHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(row: Row.Header) {
            binding.headerTitle.text = row.title
            binding.expandIcon.rotation = if (row.expanded) 180f else 0f
            binding.root.setOnClickListener { onHeaderClick(row.key) }
        }
    }

    inner class EntryHolder(private val binding: ItemDomainCompactBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bindDomain(row: Row.Domain) {
            binding.entryTitle.text = row.rule.domain
            binding.entrySummary.text = row.summary
            binding.root.setOnClickListener { onDomainClick(row.rule) }
            binding.moreButton.setOnClickListener { view ->
                PopupMenu(view.context, view).apply {
                    menu.add(R.string.menu_edit).setOnMenuItemClickListener {
                        onDomainClick(row.rule); true
                    }
                    menu.add(R.string.menu_delete).setOnMenuItemClickListener {
                        onDomainDelete(row.rule); true
                    }
                    show()
                }
            }
        }

        fun bindApp(row: Row.App) {
            binding.entryTitle.text = row.label
            binding.entrySummary.text = row.summary
            binding.root.setOnClickListener { onAppClick(row.pkg) }
            binding.moreButton.setOnClickListener { view ->
                PopupMenu(view.context, view).apply {
                    menu.add(R.string.menu_edit).setOnMenuItemClickListener {
                        onAppClick(row.pkg); true
                    }
                    menu.add(R.string.menu_remove).setOnMenuItemClickListener {
                        onAppRemove(row.pkg); true
                    }
                    show()
                }
            }
        }
    }

    inner class HintHolder(private val binding: ItemHintBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(row: Row.Hint) {
            binding.hintText.text = row.text
        }
    }
}
