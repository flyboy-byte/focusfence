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
 * Who can see this phone's report, and whose reports this user sees (plan 13: one kind of partner, a person with
 * devices). Adding a partner is an invite (a code, its QR code, or an email); removing one goes through the friction
 * guard, is logged, and the removed partner is told.
 */
class AccountabilityFragment : Fragment() {

    private lateinit var binding: ActivityAccountabilityBinding
    private val io = Executors.newSingleThreadExecutor()
    private var partners: Accountability.Partners? = null

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
            // A phone that only reads others' reports isn't watched itself; this opens the fields to make it watched too.
            if (!editingServer && partnerOnly()) {
                if (binding.serverInput.text.isNullOrBlank()) binding.serverInput.setText(SyncStore.seen(requireContext()).first().server)
                editingServer = true; bindServer(); return@setOnClickListener
            }
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
        binding.inviteButton.setOnClickListener { invite() }
        binding.watchButton.setOnClickListener { join() }
        binding.groupStartButton.setOnClickListener { startGroup() }
        binding.groupJoinButton.setOnClickListener { join() }
    }

    override fun onResume() {
        super.onResume()
        bind()
        refreshPartners()
        // Plan 13: once, after the update that replaced the partner system.
        if (app.focusfence.data.PartnerReset.noticeDue(requireContext())) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.partners_reset_title)
                .setMessage(R.string.partners_reset_body)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
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

    /** Joined someone's report but never connected as a watched phone itself (plan 13: joining doesn't enroll). */
    private fun partnerOnly(): Boolean =
        SyncStore.deviceId(requireContext()) == null && SyncStore.seen(requireContext()).isNotEmpty()

    private fun bindServer() {
        // Rank: before there's a server nothing else works, so its card leads; once connected it's a status line at
        // the bottom and the partners come first.
        val list = binding.partnersList
        val card = binding.serverCard
        val connected = SyncStore.deviceId(requireContext()) != null || partnerOnly()
        val want = if (connected) list.childCount - 1 else 0
        if (list.indexOfChild(card) != want) {
            list.removeView(card)
            list.addView(card, if (connected) list.childCount else 0)
            (card.layoutParams as ViewGroup.MarginLayoutParams).topMargin = if (connected) dp(12) else 0
            (list.getChildAt(if (connected) 0 else 1).layoutParams as ViewGroup.MarginLayoutParams).topMargin = if (connected) 0 else dp(12)
            list.requestLayout()
        }
        val open = editingServer || (SyncStore.deviceId(requireContext()) == null && !partnerOnly())
        val v = if (open) View.VISIBLE else View.GONE
        (binding.serverInput.parent.parent as View).visibility = v
        (binding.enrollInput.parent.parent as View).visibility = v
        binding.serverSave.setText(when {
            open -> R.string.acc_save
            partnerOnly() -> R.string.acc_watch_me_too
            else -> R.string.acc_change_server
        })
        binding.serverLeave.visibility = if (!open && SyncStore.deviceId(requireContext()) != null) View.VISIBLE else View.GONE
    }

    private fun bind() {
        val ok = SyncStore.lastOk(requireContext())
        val configured = SyncStore.deviceId(requireContext()) != null
        binding.syncStatus.text = if (!configured && partnerOnly()) {
            val seen = SyncStore.seen(requireContext())
            resources.getQuantityString(R.plurals.acc_status_partner_only, seen.size, seen.size,
                seen.map { it.server.removePrefix("https://").trimEnd('/') }.distinct().joinToString())
        } else if (!configured) {
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

    // ---- your partners ------------------------------------------------------

    private fun refreshPartners(then: ((Accountability.Partners) -> Unit)? = null) {
        if (SyncStore.serverUrl(requireContext()).isBlank() || SyncStore.deviceId(requireContext()) == null) return
        val ctx = requireContext().applicationContext
        background({ Accountability.partners(ctx) }) {
            partners = it
            renderPartners()
            then?.invoke(it)
        }
        background({ Accountability.groups(ctx) }) { groups = it; renderGroups() }
    }

    // ---- groups (plan 14) ---------------------------------------------------

    private var groups: List<Accountability.Group>? = null

    private fun renderGroups() {
        val c = binding.groupsList
        c.removeAllViews()
        for (g in groups.orEmpty()) {
            val names = g.members.joinToString(", ") { if (it.me) getString(R.string.grp_you) else it.name }
            c.addView(row(g.name, getString(R.string.grp_row, g.members.size, g.max, names), getString(R.string.grp_open)) { openGroup(g.id, g.name) })
        }
    }

    private fun openGroup(id: String, name: String) {
        startActivity(Intent(requireContext(), GroupActivity::class.java).putExtra(GroupActivity.EXTRA_GROUP, id).putExtra(GroupActivity.EXTRA_NAME, name))
    }

    /** Start a group (prototype screen 10): a name, then its code and QR code. Asks what partners call you first, once. */
    private fun startGroup() {
        if (!hasServer()) return
        fun named() = prompt(R.string.grp_name_hint) { name ->
            val ctx = requireContext().applicationContext
            background({ Accountability.createGroup(ctx, name) }) { code ->
                GroupUi.showCode(requireContext(), code, 5) { refreshPartners() }
                refreshPartners()
            }
        }
        if (SyncStore.ownerName(requireContext()).isNotBlank()) return named()
        prompt(R.string.acc_your_name) { me -> SyncStore.setOwnerName(requireContext(), me); named() }
    }

    /** A group's code typed or scanned (screen 11): say which group and who's in it, then join. */
    private fun confirmGroup(found: Accountability.Lookup, code: String, server: String, me: String) {
        if (found.full) {
            Toast.makeText(requireContext(), getString(R.string.grp_full, found.name), Toast.LENGTH_LONG).show(); return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.grp_confirm_title, found.name))
            .setMessage(getString(R.string.grp_confirm_text, found.members.joinToString(", ")))
            .setPositiveButton(R.string.grp_join_button) { _, _ ->
                val ctx = requireContext().applicationContext
                background({ Accountability.joinGroup(ctx, code, server, me) }) { g ->
                    Toast.makeText(requireContext(), getString(R.string.grp_you_joined, g.name), Toast.LENGTH_LONG).show()
                    bind(); refreshPartners()
                    openGroup(g.id, g.name)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Everyone who sees this phone's report, with their devices, and invites not used yet. */
    private fun renderPartners() {
        val c = binding.emailPartners
        c.removeAllViews()
        val all = partners ?: return
        if (all.partners.isEmpty() && all.invites.isEmpty()) {
            c.addView(note(getString(R.string.acc_none)))
            return
        }
        for (p in all.partners) {
            val sub = if (!p.ready) getString(R.string.acc_partner_waiting) else {
                getString(when (p.alertsBy) { "app" -> R.string.acc_by_app; "email" -> R.string.acc_by_email; else -> R.string.acc_by_none }) +
                    (if (p.devices.size > 1) " · " + getString(R.string.acc_devices, p.devices.size) else "") + looked(p.lastViewMs)
            } + (if (p.groups.isNotEmpty()) " · " + p.groups.joinToString(", ") else "")
            c.addView(row(p.name, sub, getString(R.string.acc_details)) { partnerDetails(p) })
        }
        for (i in all.invites) {
            val left = ((i.expiresMs - System.currentTimeMillis()) / 3_600_000L).coerceAtLeast(1)
            val title = if (i.email.isNotEmpty()) getString(R.string.acc_invite_email_row, i.email) else getString(R.string.acc_invite_code_row)
            c.addView(row(title, getString(R.string.acc_invite_ends, left), getString(android.R.string.cancel)) {
                val ctx = requireContext().applicationContext
                background({ Accountability.cancelInvite(ctx, i.id) }) { refreshPartners() }
            })
        }
    }

    /** One partner: their devices (each one opens the report), how alerts reach them, and Remove. */
    private fun partnerDetails(p: Accountability.Partner) {
        // Plan 14: a group made this pair, so it's left through the group, not removed here.
        if (p.groups.isNotEmpty()) {
            MaterialAlertDialogBuilder(requireContext()).setTitle(p.name)
                .setMessage(getString(R.string.grp_partner_note, p.name, p.groups.joinToString(", ")))
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        val lines = p.devices.joinToString("\n") { d ->
            "• ${d.label} (${getString(if (d.kind == "app") R.string.acc_device_app else R.string.acc_device_web)})" +
                if (d.ready) "" else " · " + getString(R.string.acc_device_waiting)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(p.name)
            .setMessage(getString(R.string.acc_partner_details, lines, p.email.ifEmpty { getString(R.string.acc_no_email) }))
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(R.string.acc_remove) { _, _ ->
                UiUtil.guard(requireContext(), GuardPolicy.Action.REMOVE_PARTNER) {
                    val ctx = requireContext().applicationContext
                    background({ Accountability.unlink(ctx, p) }) { refreshPartners() }
                }
            }
            .show()
    }

    /** Plan 12 §2: " · looked 2 hours ago" or " · hasn't opened the report yet". */
    private fun looked(ms: Long): String = when {
        ms <= 0L -> " · " + getString(R.string.acc_not_looked)
        else -> " · " + getString(R.string.acc_looked, DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS))
    }

    /** Plan 13: what partners call this person, asked once, before the first invite. */
    private fun invite() {
        if (!hasServer()) return
        if (SyncStore.ownerName(requireContext()).isNotBlank()) return showInvite()
        prompt(R.string.acc_your_name) { name ->
            SyncStore.setOwnerName(requireContext(), name)
            showInvite()
        }
    }

    /**
     * Add a partner (the prototype's screen 2): a code and its QR code at once, for someone in the room or on the
     * phone, and an email box for someone who isn't. While it's open the list refreshes, and when a new partner's key
     * has gone over this says so and closes.
     */
    private fun showInvite() {
        val ctx = requireContext().applicationContext
        val readyBefore = partners?.partners?.count { it.ready } ?: 0
        background({ Accountability.createInvite(ctx) }) { inv ->
            val box = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(24), dp(8), dp(24), 0)
            }
            box.addView(TextView(requireContext()).apply {
                text = getString(R.string.acc_invite_howto)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            })
            qr(inv.qrUrl)?.let { bmp ->
                box.addView(android.widget.ImageView(requireContext()).apply {
                    setImageBitmap(bmp)
                    contentDescription = getString(R.string.acc_qr_desc)
                    setPadding(0, dp(12), 0, dp(4))
                }, LinearLayout.LayoutParams(dp(200), dp(216)))
            }
            box.addView(TextView(requireContext()).apply {
                text = app.focusfence.dns.InviteLink.showCode(inv.code)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineMedium)
                letterSpacing = 0.12f
                setTextIsSelectable(true)
                gravity = Gravity.CENTER
            })
            box.addView(note(getString(R.string.acc_invite_meta, SyncStore.serverUrl(requireContext()).removePrefix("https://"))).apply { gravity = Gravity.CENTER })
            val email = EditText(requireContext()).apply {
                setHint(R.string.acc_invite_email_hint)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                isSingleLine = true
            }
            box.addView(email, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val dialog = MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.acc_invite)
                .setView(box)
                .setPositiveButton(R.string.acc_send_email, null)
                .setNeutralButton(R.string.acc_share, null)
                .setNegativeButton(R.string.acc_done, null)
                .create()
            // Someone joining now gets the key within about a minute: watch for it while this is open.
            val poll = object : Runnable {
                override fun run() {
                    if (!dialog.isShowing || !isAdded) return
                    app.focusfence.sync.Sync.kick(ctx)
                    refreshPartners { now ->
                        val joined = now.partners.filter { it.ready }
                        if (joined.size > readyBefore && dialog.isShowing) {
                            dialog.dismiss()
                            Toast.makeText(requireContext(), getString(R.string.acc_partner_joined, joined.last().name), Toast.LENGTH_LONG).show()
                        }
                    }
                    handler.postDelayed(this, 15_000)
                }
            }
            dialog.setOnShowListener {
                dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    share(getString(R.string.acc_invite_share, inv.qrUrl))
                }
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val address = email.text.toString().trim()
                    if (address.isEmpty()) { email.requestFocus(); return@setOnClickListener }
                    background({ Accountability.createInvite(ctx, address) }) { sent ->
                        Toast.makeText(requireContext(), getString(if (sent.emailed) R.string.acc_email_sent else R.string.acc_email_not_sent, address), Toast.LENGTH_LONG).show()
                        if (sent.emailed) email.setText("")
                        refreshPartners()
                    }
                }
                handler.postDelayed(poll, 15_000)
            }
            dialog.setOnDismissListener { handler.removeCallbacks(poll); refreshPartners() }
            dialog.show()
            refreshPartners()
        }
    }

    /** The invite's QR code: its /c/ link, so any camera app opens the join page, which offers this app. */
    private fun qr(text: String): android.graphics.Bitmap? = GroupUi.qr(text)

    // ---- reports you see ----------------------------------------------------

    private fun renderWatches() {
        val c = binding.watchList
        c.removeAllViews()
        val seen = SyncStore.seen(requireContext())
        if (seen.isEmpty()) {
            c.addView(note(getString(R.string.acc_none)))
            return
        }
        for (s in seen) {
            val sub = when {
                s.linkGone -> getString(R.string.acc_watch_gone)
                s.reportKey.isEmpty() -> getString(R.string.seen_waiting_title, s.owner)
                else -> getString(R.string.seen_ready_row)
            } + (if (s.group.isNotEmpty()) " · " + s.group else "")
            c.addView(row(s.owner, sub, getString(R.string.acc_open_report)) {
                startActivity(Intent(requireContext(), PartnerReportActivity::class.java).putExtra(PartnerReportActivity.EXTRA_SEEN, s.id))
            })
        }
    }

    /**
     * Join with a code (the prototype's screen 4): the 8 characters from their phone and your name. A bare code needs
     * the server, filled in with this phone's own when it has one; a link opened from the invite page carries its own.
     */
    private fun join(prefill: String? = null) {
        val linked = prefill?.let { app.focusfence.dns.InviteLink.parse(it) as? app.focusfence.dns.InviteLink.Parsed.Join }
        val box = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        fun field(hint: Int, type: Int, value: String = "") = EditText(requireContext()).apply {
            setHint(hint); inputType = type; isSingleLine = true; setText(value)
            box.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        if (linked != null) box.addView(note(getString(R.string.acc_join_from_link, linked.server.removePrefix("https://"))))
        val code = if (linked == null) field(R.string.acc_join_code, android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) else null
        val server = if (linked == null) field(R.string.acc_join_server, android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI,
            SyncStore.serverUrl(requireContext()).removePrefix("https://")) else null
        val name = field(R.string.acc_join_name, android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PERSON_NAME or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        name.filters = arrayOf(android.text.InputFilter.LengthFilter(60))
        if (linked == null) box.addView(note(getString(R.string.acc_join_scan)))
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.acc_watch_add)
            .setView(box)
            .setPositiveButton(R.string.acc_join) { _, _ ->
                val ctx = requireContext().applicationContext
                val text = prefill ?: code?.text.toString()
                val srv = server?.text?.toString().orEmpty()
                val me = name.text.toString()
                // One code box for both kinds (plan 14): a group's code gets its own confirmation, with who's in it.
                val bare = linked?.code?.takeIf { it.isNotEmpty() }
                    ?: app.focusfence.dns.InviteLink.normalizeCode(text).takeIf { it.length == 8 && linked == null }
                val groupServer = linked?.server ?: srv
                if (bare != null) {
                    background({ runCatching { Accountability.lookup(ctx, bare, groupServer) }.getOrNull() }) { found ->
                        if (found?.kind == "group") confirmGroup(found, bare, groupServer, me) else joinPartner(ctx, text, me, srv)
                    }
                    return@setPositiveButton
                }
                joinPartner(ctx, text, me, srv)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun joinPartner(ctx: android.content.Context, text: String, me: String, srv: String) {
                background({ Accountability.join(ctx, text, me, srv) }) { owner ->
                    if (owner.isEmpty()) {
                        // It was the server's message: this phone is now on that server.
                        binding.serverInput.setText(SyncStore.serverUrl(requireContext()))
                        binding.enrollInput.setText(SyncStore.enrollCode(requireContext()))
                        connected(SyncStore.serverUrl(requireContext()))
                        bind(); refreshPartners()
                        return@background
                    }
                    Toast.makeText(requireContext(), getString(R.string.acc_joined, owner), Toast.LENGTH_LONG).show()
                    bind()
                    SyncStore.seen(requireContext()).lastOrNull()?.let {
                        startActivity(Intent(requireContext(), PartnerReportActivity::class.java).putExtra(PartnerReportActivity.EXTRA_SEEN, it.id))
                    }
                }
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
        /** An invite link from [LinkActivity] (focusfence://join…); opens Join, filled in. */
        const val EXTRA_INVITE = "invite"
        fun asTab() = AccountabilityFragment().apply { arguments = Bundle().apply { putBoolean(ARG_TAB, true) } }
    }
}
