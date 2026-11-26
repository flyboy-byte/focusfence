package app.focusfence

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import app.focusfence.dns.InviteLink

/**
 * Opens partner invites in the app instead of the browser, so whoever taps one in a chat app joins as an app partner
 * rather than becoming an email partner by mistake. The way in is the "Open in the app" button on every server's
 * invite page (`focusfence://invite?link=<the invite>`). Android App Links were left out on purpose (Logan,
 * 2026-11-25): they verify only hosts built into the app, which would make one server special.
 * Nothing is joined here: the Join dialog opens filled in, and the person still taps OK.
 */
class LinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val invite = inviteFrom(intent?.data)
        // Logged without the link itself: it carries the invite secret and the report key.
        app.focusfence.data.EventLog.init(this)
        app.focusfence.data.EventLog.diag(app.focusfence.dns.LogEvents.service(System.currentTimeMillis(), "link",
            if (invite == null) "not_invite" else "invite_opened"))
        if (invite == null) {
            Toast.makeText(this, R.string.link_not_invite, Toast.LENGTH_LONG).show()
        } else {
            startActivity(Intent(this, AccountabilityActivity::class.java)
                .putExtra(AccountabilityFragment.EXTRA_INVITE, invite)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
        finish()
    }

    companion object {
        fun inviteFrom(uri: Uri?): String? = uri?.toString()?.let(InviteLink::fromOpened)
    }
}
