# Plan 13: one partner system, designed once (started 2026-11-26)

**Status:** design stage. **Production is paused** (Logan, 2026-11-26: "hard pause the whole app and go through some
iterations and testing with updates before we start back into production"). The live server was wiped the same night
(devices, mailboxes, screenshots; the admin login and enrollment codes kept; root-only backup
`/var/backups/ffbackend-data-20261126.tgz` on the VPS). No code until Logan has seen mockups.

**Why:** Logan, after testing 2.26 on two phones: "its such a convolution of email vs app and routes for both all mixed
and shitty ui", "dont require stupid pasting", "figure out the perfect setup, then fully enforce the new protocol
without any residue and overlap".

## What's wrong today (three systems stacked)

| Kind | Added | Sees the report | Alerts | Key reaches them by |
|---|---|---|---|---|
| Email partner | D-023/D-026 | web page | email, SMS | the invite link, pasted or tapped |
| App partner (invite) | D-035 | the same page in a WebView | feed poll, notification | the invite link, pasted |
| Pairing-key partner | the original | its own digest screen | pairing mailbox | a pasted pairing key |

Plus groups (`GroupSync`, mailboxes), unlock codes from the pairing seed, and a mailbox fallback. Each was right when
added; together they are the mess.

## How the others do it (searched 2026-11-26; their help pages, not tried)

- **Accountable2You:** the owner enters the partner's **name and email** and sends the invite. The partner accepts by
  email and makes a free partner account. The owner sets per-device visibility (Everything / Alerts / Nothing).
  Partners choose their own alerts (summary emails, hourly alerts, texts) and get instant alerts in a "Reports" app.
  [set up partners](https://support.accountable2you.com/article/809-set-up-accountability-partners),
  [alert settings](https://support.accountable2you.com/article/842-manage-alert-settings).
- **Covenant Eyes:** the owner enters the ally's **name and email** in the app. The ally opens the email or text link,
  signs in or makes a free account, and verifies by an emailed code. The ally's app shows an activity feed, and alerts
  go to their phone.
  [add an ally](https://support.covenanteyes.com/hc/en-us/articles/11818732440859),
  [accept an invite](https://support.covenanteyes.com/hc/en-us/articles/11756353979675).
- **Ever Accountable:** its own help pages didn't come up in the search. Not checked.

**The common shape:** nobody pastes anything. You type the partner's email; they tap a link and sign in with their
email. The difference for us: they can read the reports on their servers, and we promised a server operator never can
(D-023). So the report key can't travel in an email the server sends.

## The idea that fits both: approve each partner device on the owner's phone

Like linking a new device in Signal: every place a partner reads from (their app, or one browser) makes its own key
pair. The owner's phone seals the report key to that device's public key (`dns/Ecies` exists). The server only ever
relays public keys and sealed blobs, so it still can't read anything, and nobody pastes a key.

1. **Owner invites by email or by code.** Type the partner's email, and the server emails a plain link with no key
   in it. Or show a 6-character code (or a QR code) for someone standing next to you, or for your own second phone.
2. **Partner opens the link** in a browser, or types the code into the app. That browser or app makes its key pair and
   asks to join.
3. **The owner's phone shows "Sam wants to see your report: Allow?"** with a short check code both screens show. On
   Allow, the phone seals the report key to Sam's device. That's the one tap that keeps a server operator from
   slipping in a fake partner.
4. **Sam's device opens the report.** Alerts come by email or app notification, whichever Sam chose, and the same
   alert settings cover both.
5. **A new browser or phone for Sam** asks the same way. Email links can then always open the report, which also
   solves plan 09's "key is missing", with no password.

**One kind of partner:** a person, with one or more devices, each approved once. Email versus app is only how *alerts*
reach them.

## Stories to check the design against (to finish with Logan)

1. **Logan, owner:** Partners → Add partner → types Sam's email → Sam joins → Logan taps Allow → done.
2. **Sam, app partner:** gets the email on a phone with Focus Fence installed → taps the link → the app opens →
   it's waiting for Logan's OK → then the report and notifications.
3. **Sam, email-only partner:** taps the link on a laptop → the page waits for Logan's OK → the report opens in that
   browser from then on, and every alert email opens it too.
4. **Logan's own second phone:** "Add partner → show code" on the Pixel → the second phone types the code → Allow.
5. **Removing:** Logan removes Sam, so every Sam device loses the report (a new report key from then on). Sam leaves,
   and Logan is told.
6. **Server operator:** sees who partners whom, and when. Never a report, a key or a screenshot.

## The clean cut (when it's built)

- **Protocol level 2, and the server refuses level 1** (`oldestApp = 2`, D-041). That break is deliberate, since
  production is paused.
- **The server drops:** pairing mailboxes, `/i/` invites with keys in fragments, `/v1/invites/accept`, app-partner
  tokens as a separate kind, and the old feeds' second path. One partner store.
- **The app drops on first start of the new version:** own pairings, watches, mailbox keys and the digest screen. A
  one-time "Partners were reset for the new system" notice, logged.
- **Each old path's compat tests are deleted with it.** The level-1 compat suite stays only as long as level 1 is
  served, which it won't be.

## Logan's answers (2026-11-26)

1. **Invites:** all three (email, code, QR code), "but either one is great". **The code is at most 6–8
   characters.**
2. **No approval step:** "trust who uses the invite, more permission is clutter". The first device to use an invite
   becomes that partner.
3. **Groups:** "kept as the idea, but not enforced". Not in the rebuild. The idea is kept in a scope note, like
   F-Droid.
4. **Text alerts:** a scope only, for if Logan moves to Twilio ("that's a commitment"). Not in the rebuild.
5. **Releases:** normal releases while paused.

## The design, revised by those answers

The owner's phone still hands the report key to each partner device, but **automatically**: no Allow tap.

1. **Invite:**
   - **Email:** type their address, and the server emails a plain link with no key in it.
   - **Code:** the app shows a 6–8 character code (e.g. `K7Q4-M2XD`), valid 24 h, used once.
   - **QR code:** the same code as a QR, for someone in the room or your own second phone.
2. **Join:**
   - **Email link** on a phone with the app: opens the app ("Open in the app"), which joins.
   - **Email link** in a browser: the page joins as a browser device.
   - **Code or QR:** the partner types or scans it in the app.
   - Whichever device uses the invite first makes its key pair and sends the public key. The invite is spent.
3. **The key arrives on its own.** The server tells the owner's phone at its next check-in. While an invite is open,
   the phone checks every minute for up to 30 minutes, like an ask, so a partner joining in person waits under a
   minute. The phone seals the report key to the new device and says "Sam joined". The partner's screen shows "Waiting
   for Logan's phone…" until then.
4. **More devices for the same partner** (a laptop browser later): "Add a device" on the partner's side gives a
   one-time code for that same partner, and the key arrives the same way. Email alert links then open the report
   wherever that partner has a device.
5. **Trust, as Logan chose:** whoever uses an invite first is trusted. A server operator could therefore add a device
   to a pending invite. That's accepted, because invites are single-use and short-lived, and the owner sees every
   partner and device in the list ("Sam · phone · browser").

**What's in the rebuild:** partners (one kind), email/code/QR invites, alert delivery by email or app notification
with the plan 12 choices, ask a partner, seen, leaving. **Out:** groups, SMS, pairing keys, the mailbox, unlock codes
from the pairing seed (an ask replaces them).

## Questions for Logan (next)

1. Unlock codes read aloud (from the pairing seed) go away; ask-a-partner replaces them. OK?
2. Mockups next: a page of phone screens to look at on the Pixel, or rough sketches in this file?
