package app.focusfence

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import app.focusfence.dns.InviteLink

/**
 * Opens partner invites in the app instead of the browser, so whoever taps one in a chat app joins as an app partner
 * rather than becoming an email partner by mistake. Two ways in:
 *  - `https://<APP_LINK_HOST>/i/...`: Android App Links, verified against the server's /.well-known/assetlinks.json.
 *    Only the host built into the app (`appLinkHost` in build.gradle.kts) can be verified.
 *  - `focusfence://invite?link=<the invite>`: the "Open in the app" button on any server's invite page, so
 *    self-hosted servers work too.
 * Nothing is joined here: the Join dialog opens filled in, and the person still taps OK.
 */
class LinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val invite = inviteFrom(intent?.data)
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
