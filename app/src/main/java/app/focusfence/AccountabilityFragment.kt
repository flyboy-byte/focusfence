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
            editingServer = false
            SyncStore.setServerUrl(requireContext(), binding.serverInput.text?.toString().orEmpty())
            SyncStore.setEnrollCode(requireContext(), binding.enrollInput.text?.toString().orEmpty())
            bind()
            refreshPartners()
        }
        binding.groupButton.setOnClickListener { startActivity(Intent(requireContext(), GroupActivity::class.java)) }
        binding.inviteButton.setOnClickListener { invite() }
        binding.pairButton.setOnClickListener { pair() }
        binding.watchButton.setOnClickListener { addWatch() }
    }

    override fun onResume() {
        super.onResume()
        bind()
        refreshPartners()
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
    private fun <T> background(work: () -> T, done: (T) -> Unit) {
        io.execute {
            val r = runCatching(work)
            activity?.runOnUiThread {
                if (!isAdded || view == null) return@runOnUiThread
                r.onSuccess(done).onFailure {
                    Toast.makeText(requireContext(), getString(R.string.acc_failed, it.message ?: it.javaClass.simpleName), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private var editingServer = false

    private fun bindServer() {
        val open = editingServer || SyncStore.deviceId(requireContext()) == null
        val v = if (open) View.VISIBLE else View.GONE
        (binding.serverInput.parent.parent as View).visibility = v
        (binding.enrollInput.parent.parent as View).visibility = v
        binding.serverSave.setText(if (open) R.string.acc_save else R.string.acc_change_server)
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
            )
        }
        bindServer()
        renderEmailPartners()
        renderPairings()
        renderWatches()
    }

    // ---- email partners ------------------------------------------------------

    private fun refreshPartners() {
        if (SyncStore.serverUrl(requireContext()).isBlank() || SyncStore.deviceId(requireContext()) == null) return
        background({ Accountability.partners(requireContext()) }) {
            emailPartners = it
            renderEmailPartners()
        }
    }

    private fun renderEmailPartners() {
        val c = binding.emailPartners
        c.removeAllViews()
        if (emailPartners.isEmpty()) {
            c.addView(note(getString(R.string.acc_none)))
            return
        }
        for (p in emailPartners) {
            val sub = p.email + if (p.sms) " · " + getString(R.string.acc_sms_on) else ""
            c.addView(row(p.name, sub, getString(R.string.acc_remove)) {
                UiUtil.guard(requireContext(), GuardPolicy.Action.REMOVE_PARTNER) {
                    background({ Accountability.unlink(requireContext(), p) }) { refreshPartners() }
                }
            })
        }
    }

    private fun invite() {
        if (!hasServer()) return
        background({ Accountability.createInvite(requireContext()) }) { inv ->
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.acc_invite_ready_title)
                .setMessage(getString(R.string.acc_invite_ready, inv.code) + "\n\n" + inv.joinUrl)
                .setPositiveButton(R.string.acc_share) { _, _ -> share(getString(R.string.acc_invite_share, inv.joinUrl, inv.code)) }
                // A partner on a new phone or browser needs the report key again; it never goes through the server.
                .setNeutralButton(R.string.acc_key_link) { _, _ -> share(getString(R.string.acc_key_link_share, Accountability.keyLink(requireContext()))) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            bind()
        }
    }

    // ---- app-to-app ------------------------------------------------------------

    private fun renderPairings() {
        val c = binding.appPartners
        c.removeAllViews()
        val pairings = SyncStore.ownPairings(requireContext())
        if (pairings.isEmpty()) {
            c.addView(note(getString(R.string.acc_none)))
            return
        }
        for (p in pairings) {
            c.addView(row(p.name, p.code.take(9) + "…", getString(R.string.acc_remove)) {
                UiUtil.guard(requireContext(), GuardPolicy.Action.REMOVE_PARTNER) {
                    background({ Accountability.removePairing(requireContext(), p) }) { bind() }
                }
            })
        }
    }

    private fun pair() {
        if (!hasServer()) return
        prompt(R.string.acc_pair_name) { name ->
            val key = Accountability.newPairing(requireContext(), name)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.acc_pair_ready_title)
                .setMessage(getString(R.string.acc_pair_ready, name, key, SyncStore.serverUrl(requireContext())))
                .setPositiveButton(R.string.acc_share) { _, _ -> share(getString(R.string.acc_pair_share, key, SyncStore.serverUrl(requireContext()))) }
                .setNegativeButton(android.R.string.ok, null)
                .show()
            bind()
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
            val sub = if (w.lastMsgMs == 0L) getString(R.string.acc_watch_status_waiting)
            else getString(R.string.acc_watch_status, DateUtils.getRelativeTimeSpanString(w.lastMsgMs))
            c.addView(row(w.name, sub, getString(R.string.acc_open_report)) {
                startActivity(Intent(requireContext(), PartnerReportActivity::class.java).putExtra(PartnerReportActivity.EXTRA_MAILBOX, w.keys.mailboxId))
            })
        }
    }

    private fun addWatch() {
        if (!hasServer()) return
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_two_fields, null)
        val name = view.findViewById<EditText>(R.id.field1)
        val key = view.findViewById<EditText>(R.id.field2)
        name.setHint(R.string.acc_pair_name)
        key.setHint(R.string.acc_watch_key)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.acc_watch_add)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val n = name.text.toString().ifBlank { "Partner" }
                if (!Accountability.addWatch(requireContext(), n, key.text.toString())) {
                    Toast.makeText(requireContext(), R.string.acc_watch_bad_key, Toast.LENGTH_LONG).show()
                }
                bind()
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
        fun asTab() = AccountabilityFragment().apply { arguments = Bundle().apply { putBoolean(ARG_TAB, true) } }
    }
}
