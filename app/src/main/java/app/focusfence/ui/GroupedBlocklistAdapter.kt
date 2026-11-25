package app.focusfence.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.focusfence.R
import app.focusfence.databinding.ItemActionBinding
import app.focusfence.databinding.ItemDomainCompactBinding
import app.focusfence.databinding.ItemGroupHeaderBinding
import app.focusfence.databinding.ItemHintBinding
import app.focusfence.databinding.ItemLinkBinding
import app.focusfence.databinding.ItemSectionBinding
import app.focusfence.dns.BlockRule

sealed class Row {
    /** A top-level section ("Your sites"); [addLabel] shows its Add button ("Add site"). */
    data class Section(val key: String, val title: String, val addLabel: String?) : Row()

    /** A row that opens another screen (Categories). */
    data class Link(val key: String, val title: String, val value: String) : Row()

    /** A full-width button (Block history). */
    data class Action(val key: String, val label: String) : Row()

    /** A collapsible group inside a section ("Always blocked (3)"). */
    data class Header(val key: String, val title: String, val expanded: Boolean) : Row()

    data class Domain(val rule: BlockRule, val summary: String) : Row()
    data class App(val pkg: String, val label: String, val summary: String) : Row()

    /** Plain help text under an empty section; [key] only keeps rows apart. */
    data class Hint(val text: String, val key: String) : Row()
}

class GroupedBlocklistAdapter(
    private val onSectionAdd: (String) -> Unit,
    private val onLinkClick: (String) -> Unit,
    private val onHeaderClick: (String) -> Unit,
    private val onDomainClick: (BlockRule) -> Unit,
    private val onDomainDelete: (BlockRule) -> Unit,
    private val onAppClick: (String) -> Unit,
    private val onAppRemove: (String) -> Unit,
) : ListAdapter<Row, RecyclerView.ViewHolder>(DIFF) {

    companion object {
        const val GROUP_APPS = "apps"
        private const val TYPE_HEADER = 0
        private const val TYPE_DOMAIN = 1
        private const val TYPE_APP = 2
        private const val TYPE_HINT = 3
        private const val TYPE_SECTION = 4
        private const val TYPE_LINK = 5
        private const val TYPE_ACTION = 6

        private val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(oldItem: Row, newItem: Row): Boolean = when {
                oldItem is Row.Header && newItem is Row.Header -> oldItem.key == newItem.key
                oldItem is Row.Domain && newItem is Row.Domain ->
                    oldItem.rule.domain == newItem.rule.domain
                oldItem is Row.App && newItem is Row.App -> oldItem.pkg == newItem.pkg
                oldItem is Row.Hint && newItem is Row.Hint -> oldItem.key == newItem.key
                oldItem is Row.Section && newItem is Row.Section -> oldItem.key == newItem.key
                oldItem is Row.Link && newItem is Row.Link -> oldItem.key == newItem.key
                oldItem is Row.Action && newItem is Row.Action -> oldItem.key == newItem.key
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
        is Row.Section -> TYPE_SECTION
        is Row.Link -> TYPE_LINK
        is Row.Action -> TYPE_ACTION
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderHolder(ItemGroupHeaderBinding.inflate(inflater, parent, false))
            TYPE_HINT -> HintHolder(ItemHintBinding.inflate(inflater, parent, false))
            TYPE_SECTION -> SectionHolder(ItemSectionBinding.inflate(inflater, parent, false))
            TYPE_LINK -> LinkHolder(ItemLinkBinding.inflate(inflater, parent, false))
            TYPE_ACTION -> ActionHolder(ItemActionBinding.inflate(inflater, parent, false))
            else -> EntryHolder(ItemDomainCompactBinding.inflate(inflater, parent, false))
        }
    }

    /** Rows between two headings form one rounded group (ui/Tiles). */
    private fun isTile(r: Row?) = r is Row.Domain || r is Row.App || r is Row.Link || r is Row.Hint

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = getItem(position)
        if (isTile(row)) {
            val first = !isTile(if (position > 0) getItem(position - 1) else null)
            val last = !isTile(if (position < itemCount - 1) getItem(position + 1) else null)
            val pos = when {
                first && last -> Tiles.Pos.SINGLE
                first -> Tiles.Pos.TOP
                last -> Tiles.Pos.BOTTOM
                else -> Tiles.Pos.MIDDLE
            }
            Tiles.style(holder.itemView, pos, clickable = row !is Row.Hint)
        }
        when (row) {
            is Row.Header -> (holder as HeaderHolder).bind(row)
            is Row.Domain -> (holder as EntryHolder).bindDomain(row)
            is Row.App -> (holder as EntryHolder).bindApp(row)
            is Row.Hint -> (holder as HintHolder).bind(row)
            is Row.Section -> (holder as SectionHolder).bind(row)
            is Row.Link -> (holder as LinkHolder).bind(row)
            is Row.Action -> (holder as ActionHolder).bind(row)
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

    inner class SectionHolder(private val binding: ItemSectionBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: Row.Section) {
            binding.sectionTitle.text = row.title
            binding.sectionAction.visibility = if (row.addLabel != null) View.VISIBLE else View.GONE
            binding.sectionAction.text = row.addLabel
            binding.sectionAction.setOnClickListener { onSectionAdd(row.key) }
        }
    }

    inner class ActionHolder(private val binding: ItemActionBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: Row.Action) {
            binding.actionButton.text = row.label
            binding.actionButton.setOnClickListener { onLinkClick(row.key) }
        }
    }

    inner class LinkHolder(private val binding: ItemLinkBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: Row.Link) {
            binding.linkTitle.text = row.title
            binding.linkValue.text = row.value
            binding.root.setOnClickListener { onLinkClick(row.key) }
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
