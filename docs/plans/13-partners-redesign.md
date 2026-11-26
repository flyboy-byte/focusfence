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

## Questions for Logan (next)

1. Invite methods: email, code, QR code. All three, or which?
2. Approve each partner device on the owner's phone (safest), or trust the first device to use the invite
   (fewer taps, but the server operator could slip one in)?
3. Groups (up to 4 phones seeing each other): keep, rebuild on this model, or drop for now?
4. SMS alerts: keep or drop?
5. While paused: test builds as GitHub pre-releases so only phones that opt in update, or normal releases?
