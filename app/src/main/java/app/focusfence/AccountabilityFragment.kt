package app.focusfence

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import app.focusfence.databinding.ActivityAccountabilityBinding
import app.focusfence.dns.GuardPolicy
import app.focusfence.sync.Accountability
import app.focusfence.sync.SyncStore
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

/**
 * Who can see this phone's reports, and whose reports this user sees.
 * Two partner paths, side by side: email/web (the default) and app-to-app
 * pairing. Adding a partner is immediate; removing one goes through the
 * friction guard, is logged, and the removed partner is told.
 */
class AccountabilityFragment : Fragment() {

    private lateinit var binding: ActivityAccountabilityBinding
    private val io = Executors.newSingleThreadExecutor()
    private var emailPartners: List<Accountability.Partner> = emptyList()
    private var appPartners: List<Accountability.AppPartner> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = ActivityAccountabilityBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // As a tab (the five-tab layout) MainActivity's toolbar is the title; as its own screen this one is.
        if (arguments?.getBoolean(ARG_TAB) == true) {
            (binding.toolbar.parent as View).visibility = View.GONE
        } else {
            (requireActivity() as AppCompatActivity).setSupportActionBar(binding.toolbar)
            (requireActivity() as AppCompatActivity).supportActionBar?.setDisplayHomeAsUpEnabled(true)
            binding.toolbar.setNavigationOnClickListener { requireActivity().finish() }
        }

        binding.serverInput.setText(SyncStore.serverUrl(requireContext()))
        binding.enrollInput.setText(SyncStore.enrollCode(requireContext()))
        binding.serverSave.setOnClickListener {
            // Once connected, the server fields fold away behind "Change server": set once, rarely touched.
            if (!editingServer && SyncStore.deviceId(requireContext()) != null) { editingServer = true; bindServer(); return@setOnClickListener }
            val url = binding.serverInput.text?.toString().orEmpty().trim().trimEnd('/')
            val code = binding.enrollInput.text?.toString().orEmpty().trim()
            val ctx = requireContext().applicationContext
            // Save connects at once and says how it went, instead of leaving it to the next background sync.
            fun connect() {
                binding.serverSave.isEnabled = false
                background({
                    if (url != SyncStore.serverUrl(ctx).trimEnd('/')) Accountability.leaveServer(ctx, movingTo = url)
                    SyncStore.setServerUrl(ctx, url)
                    SyncStore.setEnrollCode(ctx, code)
                    Accountability.ensureEnrolled(ctx)
                }, always = { binding.serverSave.isEnabled = true; bind() }) {
                    editingServer = false
                    connected(url)
                    bind()
                    refreshPartners()
                }
            }
            // Moving an enrolled phone to another server cuts off its partners there: same guard as leaving.
            val moving = SyncStore.deviceId(ctx) != null && url != SyncStore.serverUrl(ctx).trimEnd('/')
            if (moving) UiUtil.guard(requireContext(), GuardPolicy.Action.LEAVE_SERVER) { connect() } else connect()
        }
        binding.serverLeave.setOnClickListener { leaveServer() }
        binding.groupButton.setOnClickListener { startActivity(Intent(requireContext(), GroupActivity::class.java)) }
        binding.inviteButton.setOnClickListener { invite() }
        binding.watchButton.setOnClickListener { join() }
    }

    override fun onResume() {
        super.onResume()
        bind()
        refreshPartners()
        // An invite opened from a link (LinkActivity): the Join dialog, filled in, once.
        requireActivity().intent?.let { i ->
            i.getStringExtra(EXTRA_INVITE)?.let { invite -> i.removeExtra(EXTRA_INVITE); join(invite) }
        }
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun hasServer(): Boolean {
        if (SyncStore.serverUrl(requireContext()).isNotBlank()) return true
        Toast.makeText(requireContext(), R.string.acc_need_server, Toast.LENGTH_LONG).show()
        return false
    }

    /** Runs [work] off the main thread; failures become a toast, not a crash. */
    private fun <T> background(work: () -> T, always: () -> Unit = {}, done: (T) -> Unit) {
        io.execute {
            val r = runCatching(work)
            activity?.runOnUiThread {
                if (!isAdded || view == null) return@runOnUiThread
                always()
                r.onSuccess(done).onFailure {
                    Toast.makeText(requireContext(), getString(R.string.acc_failed, Accountability.friendly(it)), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private var editingServer = false

    private fun bindServer() {
        // Rank: before there's a server nothing else works, so its card leads; once connected it's a status line at
        // the bottom and the partners come first.
        val list = binding.partnersList
        val card = binding.serverCard
        val connected = SyncStore.deviceId(requireContext()) != null
        val want = if (connected) list.childCount - 1 else 0
        if (list.indexOfChild(card) != want) {
            list.removeView(card)
            list.addView(card, if (connected) list.childCount else 0)
            (card.layoutParams as ViewGroup.MarginLayoutParams).topMargin = if (connected) dp(12) else 0
            (list.getChildAt(if (connected) 0 else 1).layoutParams as ViewGroup.MarginLayoutParams).topMargin = if (connected) 0 else dp(12)
            list.requestLayout()
        }
        val open = editingServer || SyncStore.deviceId(requireContext()) == null
        val v = if (open) View.VISIBLE else View.GONE
        (binding.serverInput.parent.parent as View).visibility = v
        (binding.enrollInput.parent.parent as View).visibility = v
        binding.serverSave.setText(if (open) R.string.acc_save else R.string.acc_change_server)
        binding.serverLeave.visibility = if (!open && SyncStore.deviceId(requireContext()) != null) View.VISIBLE else View.GONE
    }

    private fun bind() {
        val ok = SyncStore.lastOk(requireContext())
        val configured = SyncStore.deviceId(requireContext()) != null || SyncStore.ownPairings(requireContext()).isNotEmpty() || SyncStore.watches(requireContext()).isNotEmpty()
        binding.syncStatus.text = if (!configured) {
            getString(R.string.acc_status_none)
        } else {
            getString(
                R.string.acc_status,
                if (ok == 0L) getString(R.string.acc_status_never) else DateUtils.getRelativeTimeSpanString(ok).toString(),
                SyncStore.lastError(requireContext())?.let { getString(R.string.acc_status_error, it) } ?: "",
            ) + when (SyncStore.protocolStatus(requireContext())) {
                // D-041: say which side is behind, instead of leaving odd failures to guess at. Syncing keeps trying.
                app.focusfence.dns.Protocol.Status.SERVER_TOO_OLD -> "\n" + getString(R.string.acc_protocol_server_old)
                app.focusfence.dns.Protocol.Status.APP_TOO_OLD -> "\n" + getString(R.string.acc_protocol_app_old)
                else -> ""
            }
        }
        bindServer()
        renderPartners()
        renderWatches()
    }

    // ---- email partners ------------------------------------------------------

    private fun refreshPartners() {
        if (SyncStore.serverUrl(requireContext()).isBlank() || SyncStore.deviceId(requireContext()) == null) return
        val ctx = requireContext().applicationContext
        background({ Accountability.resolvePending(ctx); Accountability.partners(ctx) }) {
            emailPartners = it.email
            appPartners = it.app
            renderPartners()
        }
    }

    /** Everyone who sees this phone's reports, email and app alike (D-035), plus invites not used yet. */
    private fun renderPartners() {
        val c = binding.emailPartners
        c.removeAllViews()
        val pairings = SyncStore.ownPairings(requireContext())
        if (emailPartners.isEmpty() && pairings.isEmpty()) {
            c.addView(note(getString(R.string.acc_none)))
            return
        }
        for (p in pairings) {
            val looked = appPartners.firstOrNull { p.inviteId.isNotEmpty() && it.inviteId == p.inviteId }?.lastViewMs
            val sub = (if (p.pending) getString(R.string.acc_pending) else getString(R.string.acc_in_app)) +
                if (p.pending) "" else looked(looked)
            c.addView(row(p.name, sub, getString(R.string.acc_remove)) {
                UiUtil.guard(requireContext(), GuardPolicy.Action.REMOVE_PARTNER) {
                    background({ Accountability.removePairing(requireContext(), p) }) { bind() }
                }
            })
        }
        for (p in emailPartners) {
            val sub = p.email + (if (p.sms) " · " + getString(R.string.acc_sms_on) else "") + looked(p.lastViewMs)
            c.addView(row(p.name, sub, getString(R.string.acc_remove)) {
                UiUtil.guard(requireContext(), GuardPolicy.Action.REMOVE_PARTNER) {
                    background({ Accountability.unlink(requireContext(), p) }) { refreshPartners() }
                }
            })
        }
    }

    /** Plan 12 §2: " · looked 2 hours ago", " · hasn't opened the report yet", or nothing when the server can't say. */
    private fun looked(ms: Long?): String = when {
        ms == null || ms < 0 -> ""
        ms == 0L -> " · " + getString(R.string.acc_not_looked)
        else -> " · " + getString(R.string.acc_looked, android.text.format.DateUtils.getRelativeTimeSpanString(
            ms, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS))
    }

    private fun invite() {
        if (!hasServer()) return
        prompt(R.string.acc_invite_for) { name ->
            val ctx = requireContext().applicationContext
            background({ Accountability.createInvite(ctx, name) }) { inv ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.acc_invite_ready_title)
                    .setMessage(getString(R.string.acc_invite_ready, name) + "\n\n" + inv.link)
                    .setPositiveButton(R.string.acc_share) { _, _ -> share(getString(R.string.acc_invite_share, inv.link)) }
                    // A partner on a new phone or browser needs the report key again; it never goes through the server.
                    .setNeutralButton(R.string.acc_key_link) { _, _ -> share(getString(R.string.acc_key_link_share, Accountability.keyLink(requireContext()))) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                bind()
            }
        }
    }

    private fun renderWatches() {
        val c = binding.watchList
        c.removeAllViews()
        val watches = SyncStore.watches(requireContext())
        if (watches.isEmpty()) {
            c.addView(note(getString(R.string.acc_none)))
            return
        }
        for (w in watches) {
            val sub = if (w.reportUrl.isNotEmpty()) getString(R.string.acc_watch_status_server)
            else if (w.lastMsgMs == 0L) getString(R.string.acc_watch_status_waiting)
            else getString(R.string.acc_watch_status, DateUtils.getRelativeTimeSpanString(w.lastMsgMs))
            c.addView(row(w.name, sub, getString(R.string.acc_open_report)) {
                startActivity(Intent(requireContext(), PartnerReportActivity::class.java).putExtra(PartnerReportActivity.EXTRA_MAILBOX, w.keys.mailboxId))
            })
        }
    }

    /** D-035: one paste box for every invite: a partner's invite link, or the admin page's server message. */
    private fun join(prefill: String? = null) {
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_two_fields, null)
        val text = view.findViewById<EditText>(R.id.field1)
        val name = view.findViewById<EditText>(R.id.field2)
        text.setHint(R.string.acc_join_paste)
        name.setHint(R.string.acc_join_name)
        // The shared layout caps field 1 at 40 characters for names: an invite is ~150, so lift it, and let it wrap.
        text.filters = arrayOf()
        text.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        text.maxLines = 4
        if (prefill != null) text.setText(prefill)
        name.filters = arrayOf(android.text.InputFilter.LengthFilter(60))
        name.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PERSON_NAME or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.acc_watch_add)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val ctx = requireContext().applicationContext
                val pasted = text.text.toString()
                val me = name.text.toString()
                background({ Accountability.join(ctx, pasted, me) }) { owner ->
                    if (owner.isEmpty()) connected(SyncStore.serverUrl(requireContext()))
                    else Toast.makeText(requireContext(), getString(R.string.acc_joined, owner), Toast.LENGTH_LONG).show()
                    binding.serverInput.setText(SyncStore.serverUrl(requireContext()))
                    binding.enrollInput.setText(SyncStore.enrollCode(requireContext()))
                    bind()
                    refreshPartners()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Connected: say so, and the first time on this server, what its operator can see (journey 3). */
    private fun connected(server: String) {
        val ctx = requireContext()
        if (Accountability.operatorNoteDue(ctx, server)) {
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.acc_connected)
                .setMessage(getString(R.string.acc_operator_note, server.removePrefix("https://")))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        } else Toast.makeText(ctx, R.string.acc_connected, Toast.LENGTH_LONG).show()
    }

    private fun leaveServer() {
        val ctx = requireContext().applicationContext
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.acc_leave_server)
            .setMessage(R.string.acc_leave_confirm)
            .setPositiveButton(R.string.acc_leave_server) { _, _ ->
                UiUtil.guard(requireContext(), GuardPolicy.Action.LEAVE_SERVER) {
                    background({ Accountability.leaveServer(ctx) }) {
                        Toast.makeText(requireContext(), R.string.acc_left_server, Toast.LENGTH_LONG).show()
                        binding.serverInput.setText("")
                        binding.enrollInput.setText("")
                        bind()
                        refreshPartners()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- small view helpers -------------------------------------------------

    private fun prompt(hintRes: Int, onOk: (String) -> Unit) {
        val input = EditText(requireContext()).apply { setHint(hintRes); isSingleLine = true }
        val box = LinearLayout(requireContext()).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(hintRes)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ -> onOk(input.text.toString().ifBlank { "Partner" }) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun share(text: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
    }

    private fun note(text: String) = TextView(requireContext()).apply {
        this.text = text
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun row(title: String, subtitle: String, action: String, onAction: () -> Unit): View =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = title
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                })
                addView(TextView(context).apply {
                    text = subtitle
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(MaterialButton(context, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                text = action
                setOnClickListener { onAction() }
            })
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val ARG_TAB = "tab"
        /** An invite's text from [LinkActivity]; opens Join with an invite, filled in. */
        const val EXTRA_INVITE = "invite"
        fun asTab() = AccountabilityFragment().apply { arguments = Bundle().apply { putBoolean(ARG_TAB, true) } }
    }
}
