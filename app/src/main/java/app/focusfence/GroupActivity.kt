package app.focusfence

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import app.focusfence.data.GroupStore
import app.focusfence.databinding.ActivityGroupBinding
import app.focusfence.dns.Group
import app.focusfence.dns.GroupProtocol
import app.focusfence.dns.GuardPolicy
import app.focusfence.sync.GroupManager
import app.focusfence.sync.SyncStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * The group screen (docs/plans/05-group-and-release.md): start or join a group of up to 4 phones, compare
 * fingerprints and approve members, choose who sees your report, leave. All rules live in `dns/Group*`; every action
 * goes through [GroupManager], which only signs a message and queues it, so nothing here touches the network.
 * Changes that weaken accountability go through the same friction guard as removing a partner.
 */
class GroupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGroupBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGroupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    // ---- small view helpers -----------------------------------------------------------------------------------

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun text(s: CharSequence, style: Int = com.google.android.material.R.attr.textAppearanceBodyMedium, top: Int = 8): TextView =
        TextView(this).apply {
            this.text = s
            setTextAppearance(obtainStyledAttributes(intArrayOf(style)).use { it.getResourceId(0, 0) })
            setPadding(0, dp(top), 0, 0)
        }

    private fun heading(s: String) = text(s, com.google.android.material.R.attr.textAppearanceTitleMedium, top = 20)

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private fun field(hint: String, multiline: Boolean = false): TextInputLayout {
        val layout = TextInputLayout(this).apply { this.hint = hint; setPadding(0, dp(8), 0, 0) }
        layout.addView(TextInputEditText(layout.context).apply { if (multiline) { minLines = 3; isSingleLine = false } else isSingleLine = true })
        return layout
    }

    private fun TextInputLayout.value() = editText?.text?.toString().orEmpty()

    private fun roleChoice(): RadioGroup = RadioGroup(this).apply {
        addView(RadioButton(context).apply { id = View.generateViewId(); text = getString(R.string.group_role_filtered); isChecked = true })
        addView(RadioButton(context).apply { id = View.generateViewId(); text = getString(R.string.group_role_partner) })
    }

    private fun RadioGroup.role() = if (indexOfChild(findViewById(checkedRadioButtonId)) == 0) Group.Role.FILTERED else Group.Role.PARTNER

    private fun share(text: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), getString(R.string.group_share_title)))
    }

    /** Runs [action]; behind the friction guard when the pure rules say it weakens accountability. */
    private fun maybeGuarded(loosens: Boolean, action: () -> Unit) {
        if (loosens) UiUtil.guard(this, GuardPolicy.Action.LEAVE_GROUP) { action() } else action()
    }

    private fun loosens(o: Group.Outcome) = (o as? Group.Outcome.Ok)?.change?.loosens ?: false

    // ---- screens ----------------------------------------------------------------------------------------------

    private fun render() {
        val c = binding.content
        c.removeAllViews()
        val st = GroupStore.load(this)
        if (st == null) renderSetup(c) else renderGroup(c, st)
    }

    private fun renderSetup(c: LinearLayout) {
        c.addView(text(getString(R.string.group_intro)))
        if (!SyncStore.serverUrl(this).startsWith("https://")) {
            c.addView(text(getString(R.string.group_needs_server)))
            c.addView(button(getString(R.string.acc_open)) { startActivity(Intent(this, AccountabilityActivity::class.java)) })
        }

        c.addView(heading(getString(R.string.group_create_title)))
        val createName = field(getString(R.string.group_name_hint)); c.addView(createName)
        val createRole = roleChoice(); c.addView(createRole)
        c.addView(button(getString(R.string.group_create)) {
            when (val r = GroupManager.create(this, createName.value(), createRole.role())) {
                is GroupManager.Result.Error -> toast(r.message)
                is GroupManager.Result.Ok -> { render(); showInvite(r.invite.orEmpty()) }
            }
        })

        c.addView(heading(getString(R.string.group_join_title)))
        val invite = field(getString(R.string.group_invite_hint), multiline = true); c.addView(invite)
        val joinName = field(getString(R.string.group_name_hint)); c.addView(joinName)
        val joinRole = roleChoice(); c.addView(joinRole)
        c.addView(button(getString(R.string.group_join)) {
            when (val r = GroupManager.join(this, invite.value(), joinName.value(), joinRole.role())) {
                is GroupManager.Result.Error -> toast(r.message)
                is GroupManager.Result.Ok -> render()
            }
        })
    }

    private fun showInvite(invite: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.group_share_title)
            .setMessage(getString(R.string.group_invite_made) + "\n\n" + invite)
            .setPositiveButton(R.string.group_share_invite) { _, _ -> share(invite) }
            .setNegativeButton(R.string.group_ok, null)
            .show()
    }

    private fun renderGroup(c: LinearLayout, st: GroupStore.State) {
        val view = GroupManager.view(this)
        val state = view?.state
        val me = st.identity

        if (st.ended) { c.addView(text(getString(R.string.group_ended))); return }
        if (state == null || view == null) { c.addView(text(getString(R.string.group_waiting))); return }
        val mine = state.members.firstOrNull { it.id == me.memberId }
        if (mine == null) { c.addView(text(getString(R.string.group_waiting))); return }

        c.addView(heading(getString(R.string.group_members, state.members.size)))
        for (m in state.members) {
            val self = m.id == me.memberId
            val role = getString(if (m.role == Group.Role.FILTERED) R.string.group_role_filtered_short else R.string.group_role_partner_short)
            c.addView(text((if (self) getString(R.string.group_you, m.name) else m.name) + " · " + role, com.google.android.material.R.attr.textAppearanceTitleSmall, top = 14))
            if (self) continue
            val fp = view.keys[m.id]?.let { GroupProtocol.fingerprint(it) }
            if (fp != null) c.addView(text(getString(R.string.group_fingerprint, fp)))
            if (m.id in st.approved) {
                c.addView(text(getString(R.string.group_approved)))
            } else {
                c.addView(text(getString(R.string.group_not_approved)))
                c.addView(text(getString(R.string.group_fingerprint_hint)))
                c.addView(button(getString(R.string.group_approve)) { GroupManager.approve(this, m.id); render() })
            }
            c.addView(button(getString(R.string.group_remove)) {
                maybeGuarded(loosens(Group.remove(state, me.memberId, m.id))) {
                    MaterialAlertDialogBuilder(this)
                        .setMessage(getString(R.string.group_remove_confirm, m.name))
                        .setPositiveButton(R.string.group_remove) { _, _ -> GroupManager.remove(this, m.id); toastQueued(); render() }
                        .setNegativeButton(R.string.group_cancel, null).show()
                }
            })
        }

        c.addView(heading(getString(R.string.group_my_settings)))
        val toRole = if (mine.role == Group.Role.FILTERED) Group.Role.PARTNER else Group.Role.FILTERED
        c.addView(button(getString(if (toRole == Group.Role.PARTNER) R.string.group_become_partner else R.string.group_become_filtered)) {
            maybeGuarded(loosens(Group.setRole(state, me.memberId, toRole))) { GroupManager.setRole(this, toRole); toastQueued(); render() }
        })
        if (mine.role == Group.Role.FILTERED && state.members.size > 1) {
            c.addView(button(getString(R.string.group_viewers)) { chooseViewers(state, me.memberId) })
        }
        c.addView(button(getString(R.string.group_share_invite)) { GroupManager.inviteText(this)?.let { showInvite(it) } })
        if (view.creatorId == me.memberId) {
            if (view.closed) c.addView(text(getString(R.string.group_closed)))
            else c.addView(button(getString(R.string.group_close)) { GroupManager.close(this); toastQueued(); render() })
        }
        c.addView(button(getString(R.string.group_leave)) {
            maybeGuarded(loosens(Group.leave(state, me.memberId))) {
                MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.group_leave_confirm)
                    .setPositiveButton(R.string.group_leave) { _, _ -> GroupManager.leave(this); render() }
                    .setNegativeButton(R.string.group_cancel, null).show()
            }
        })

        c.addView(heading(getString(R.string.group_activity)))
        val notices = view.notices.takeLast(10).reversed()
        c.addView(text(if (notices.isEmpty()) getString(R.string.group_none_yet) else notices.joinToString("\n")))
        if (view.dropped > 0) c.addView(text(getString(R.string.group_dropped, view.dropped)))
    }

    /** A checklist of the other members; "everyone" is the default and is stored as no restriction. */
    private fun chooseViewers(state: Group.State, myId: String) {
        val others = state.members.filter { it.id != myId }
        val current = Group.viewersOf(state, myId)
        val checked = BooleanArray(others.size) { others[it].id in current }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.group_viewers_title)
            .setMultiChoiceItems(others.map { it.name }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton(R.string.group_ok) { _, _ ->
                val chosen = others.filterIndexed { i, _ -> checked[i] }.map { it.id }.toSet()
                val everyone = chosen.size == others.size
                val outcome = Group.setViewers(state, myId, if (everyone) null else chosen)
                if (outcome is Group.Outcome.Rejected) return@setPositiveButton
                maybeGuarded(loosens(outcome)) { GroupManager.setViewers(this, if (everyone) null else chosen); toastQueued(); render() }
            }
            .setNegativeButton(R.string.group_cancel, null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun toastQueued() = Toast.makeText(this, R.string.group_waiting, Toast.LENGTH_SHORT).show()
}
