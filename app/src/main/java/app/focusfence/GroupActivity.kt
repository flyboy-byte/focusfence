package app.focusfence

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import app.focusfence.databinding.ActivityGroupPageBinding
import app.focusfence.dns.GuardPolicy
import app.focusfence.sync.Accountability
import app.focusfence.sync.SyncStore
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Plan 14: one group's page (prototype screen 12). Who's in it and when each last looked at this phone's report, each
 * member's report to open, how alerts from them arrive, a code for one more person, and Leave group.
 */
class GroupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGroupPageBinding
    private val groupId: String get() = intent.getStringExtra(EXTRA_GROUP).orEmpty()
    private var group: Accountability.Group? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGroupPageBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        intent.getStringExtra(EXTRA_NAME)?.let { title = it }
        binding.levelButton.setOnClickListener { pickLevel() }
        binding.addButton.setOnClickListener { addSomeone() }
        binding.leaveButton.setOnClickListener { leave() }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        val ctx = applicationContext
        GroupUi.background(this, { Accountability.groups(ctx).firstOrNull { it.id == groupId } }) { g ->
            if (isFinishing) return@background
            if (g == null) { finish(); return@background }
            group = g
            render(g)
        }
    }

    private fun render(g: Accountability.Group) {
        title = g.name
        binding.groupSummary.text = getString(R.string.grp_page_sub, g.members.size, g.max)
        val list = binding.membersList
        list.removeAllViews()
        val seen = SyncStore.seen(this)
        for (m in g.members) {
            if (m.me) { list.addView(row(getString(R.string.grp_you), "", null)); continue }
            val mine = seen.firstOrNull { it.token == m.theirToken && m.theirToken.isNotEmpty() }
            val looked = if (m.lastViewMs > 0) getString(R.string.grp_member_looked,
                DateUtils.getRelativeTimeSpanString(m.lastViewMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)) else getString(R.string.grp_member_not_looked)
            val sub = if (mine == null || mine.reportKey.isEmpty()) "$looked · ${getString(R.string.grp_member_waiting)}" else looked
            list.addView(row(m.name, sub, mine?.takeIf { it.reportKey.isNotEmpty() }?.let { s -> {
                startActivity(Intent(this, PartnerReportActivity::class.java).putExtra(PartnerReportActivity.EXTRA_SEEN, s.id))
            } }))
        }
        binding.levelButton.text = getString(R.string.grp_level, GroupUi.levelName(this, g.level))
        binding.addButton.isEnabled = g.spots > 0
        binding.addButton.text = if (g.spots > 0) getString(R.string.grp_add, g.spots) else getString(R.string.grp_is_full)
    }

    private fun pickLevel() {
        val g = group ?: return
        val keys = arrayOf("all", "important", "daily")
        val names = keys.map { GroupUi.levelName(this, it) }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.grp_alerts_title)
            .setSingleChoiceItems(names, keys.indexOf(g.level).coerceAtLeast(0)) { d, which ->
                d.dismiss()
                val ctx = applicationContext
                GroupUi.background(this, { Accountability.setGroupLevel(ctx, g, keys[which]) }) { load() }
            }
            .show()
    }

    private fun addSomeone() {
        val g = group ?: return
        val ctx = applicationContext
        GroupUi.background(this, { Accountability.newGroupCode(ctx, g.id) }) { code -> GroupUi.showCode(this, code, g.spots) { load() } }
    }

    /** Leaving stops others seeing this phone's report, so it's a loosening action like removing a partner. */
    private fun leave() {
        val g = group ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.grp_leave)
            .setMessage(R.string.grp_leave_text)
            .setPositiveButton(R.string.grp_leave) { _, _ ->
                UiUtil.guard(this, GuardPolicy.Action.REMOVE_PARTNER) {
                    val ctx = applicationContext
                    GroupUi.background(this, { Accountability.leaveGroup(ctx, g) }) {
                        Toast.makeText(this, getString(R.string.grp_left, g.name), Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun row(title: String, subtitle: String, open: (() -> Unit)?): View {
        val dp = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply { text = title; setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge) })
                if (subtitle.isNotEmpty()) addView(TextView(context).apply { text = subtitle; setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall) })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (open != null) addView(MaterialButton(context, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                text = getString(R.string.grp_open)
                setOnClickListener { open() }
            })
        }
    }

    companion object {
        const val EXTRA_GROUP = "group"
        const val EXTRA_NAME = "name"
    }
}
