# Plan 12: partners who can act (started 2026-11-25)

**Why:** after plan 10 the partner side mostly *receives*: a report page, an alerts feed, emails. Nothing a partner
does reaches the owner, and some paths only work for one kind of partner. Logan, 2026-11-25: "Where could we improve
partners still" → "move through it and scope with questions, then more questions if needed as we build".

**How it runs:** the same as plan 10. Trace each section through the code, ask Logan the questions only he can
answer (batched), build in small PRs ranked by value, and mark a section done when its exit condition holds.

## What's there now (traced 2026-11-25, app 2.25.0 / server 1.2.0)

- **Partners:** email partners (`Store.Partner`, confirmed by email; SMS optional) and app partners
  (`Store.AppPartner`), at most 5 of each. Both read the same `/p/{token}` page and sealed data (D-023, D-035).
- **Alerts:** emailed at once to email partners. App partners poll `/p/{token}/alerts` every ~15 min (`SyncWorker`),
  and Doze can stretch that.
- **Strict unlock with a partner PIN** (`GuardPolicy.Level.PARTNER_PIN`): the code comes from the pairing seed
  (`Pairing.code`). Only an **app** partner can show it (`PartnerReportActivity`), so an email-only owner can't use
  the partner tier at all.
- **Nothing records that a partner looked.** `/p/{token}`, `/data` and `/alerts` keep no record of being read, so
  neither side knows whether anyone is watching.
- **A partner who leaves is silent.** `POST /p/{token}/leave` drops the partner and tells nobody. The owner still
  believes someone is watching. (Removal the other way is never silent: `/v1/partners/unlink` tells the partner.)
- Alerts and unlock codes still go through the pairing mailbox; reports use the server route (HANDOFF item 2).

## Sections

| # | Section | Exit condition | Status |
|---|---|---|---|
| 0 | Two-phone field test of what's built | Second phone as an app partner: invite → join → first report → first alert → unlock code, and an email partner in a normal browser; findings recorded here | waiting on Logan 📱 |
| 1 | A partner leaving is never silent | The owner gets a notification and a log record; the other partners are told; an app partner's Remove tells the server; server test | building |
| 2 | "Seen": owners know partners are looking | The owner sees when each partner last opened the report | built 🧪 (PR pending), not yet on a phone |
| 3 | Approve an unlock from the report page | Email or app partner taps Approve/Deny on a pending request; the phone acts on it within a minute while the screen is on | built 🧪 (PR pending), not yet on a phone |
| 4 | Replies to alerts | A partner answers an alert; the owner sees the answer in the app | parked: Logan's "random things to add" list (below) |
| 5 | Partner settings | A partner chooses what reaches them (urgent only / everything / daily digest, quiet hours) | built 🧪 (PR pending), not yet on a phone |
| 6 | Faster alerts for app partners | An urgent alert reaches an app partner in under a minute without Google services | parked (Logan); research Ever Accountable / Accountable2You first |
| 7 | Owner's partner list and keys | Each partner's status (pending / active / last seen); a new report key that cuts off old links | status done with §2; key reset skipped (Logan), reason below |
| 8 | One path | Alerts and digests to app partners only through the server feed once their app reads it | built 🧪 (PR pending), not yet on a phone |

Order: 1 (a bug, small) → 2 → 3 → 4, then 5–8. Section 0 runs whenever Logan has the second phone handy.

## Logan's answers (2026-11-25)

- **Leaving:** the owner and the remaining partners hear about it.
- **Approve:** any one partner, and an approval skips the wait; Deny cancels the request. With nobody answering, the
  normal 10-minute wait still works.
- **What an approval opens:** "Prob what was asked. Or if halfway between, we could do that whole app page." Built as
  the halfway point. An approval covers the `GuardPolicy.Action` that was asked for, for 15 minutes. Those actions are
  already page-sized ("Loosen a filter bundle", "Unblock an app"); nothing else loosens.
- **Deny:** the wait keeps running, and the owner sees "Sam said no". A partner can never lock the owner out.
- **Email partners:** an email saying what's asked, with an Open button to the report page where Approve and Deny
  sit. App partners get a notification that opens the same page.
- **Seen:** "Hmm never thot of that". Not decided; the default if asked again is a quiet "looked 2 h ago" in the
  Partners list, with no notification.
- **Replies:** "Interesting… document this for later when I'm looking for random things to add".
- **Seen, asked again:** yes, quietly: "looked 2 h ago" in the Partners list, no notifications, and the partner page
  says the owner can see it.
- **Partner settings:** all three, "different levels of alerts etc is great stuff": urgent only vs everything, a
  daily digest, and quiet hours.
- **Push:** "seems like way too much". Parked. If it's ever pursued, first research how Ever Accountable and
  Accountable2You deliver partner alerts (parity), then decide.
- **Release:** keep building, release later in a batch.

## Ideas for later (Logan's "random things to add" list)

- **Replies to alerts.** Set replies ("All good", "Let's talk", "Call me", "Proud of you"), so nothing personal sits on
  the server. Or free text sealed in the partner's browser to the owner's phone key (P-256 `DeviceKey`; `dns/Ecies`
  exists), or both. The owner sees the reply in the app and in its notification.
- **"How to be a good partner"** page, linked from the welcome email: what each alert means, and how to bring it up
  without shaming.
- **Excluding apps from reports** (Ever Accountable has it); the exclusion itself is reported to partners.

## Notes per section

Notes are added here as each section runs.

### 7. Key reset: skipped (Logan, 2026-11-25: "Skip it and document it")

- **The idea:** "Make a new report key", so that a leaked partner link stops opening the report.
- **Why skipped:**
  - Removing a partner already deletes their `/p/{token}` on the server, so their link dies.
  - Remove and re-invite covers a leaked link.
  - A new key would reach nobody automatically: the server never sees it (D-023), so the owner would have to send
    every partner a fresh link by hand.
- Partner status in the list (pending / in the app / email, looked when) is done by §2.
- Revisit only if a leaked link without a removable partner ever happens.

### 8. One path (built 2026-11-25)

- **Correction to the table's first wording:** unlock codes never travelled through the mailbox. They are TOTP-style
  codes computed on both phones from the pairing seed (`Pairing.code`). What doubled up was **alerts and the 6-hour
  digest**, which the owner's phone still posted to the mailbox while the partner's app read the server feed.
- **How the owner's phone learns:** the server records when an app partner's app reads `/p/{token}/alerts`
  (`AppPartner.lastFeedMs`, written at most hourly), and `/v1/partners/list` says `readsFeed`.
  `Accountability.learnViaServer` asks, once per sync, only while some invite pairing is still unknown, and marks it
  `viaServer` for good.
- **What changes for that pairing:** `sendToPartner` posts nothing more to its mailbox. The partner's app sets
  `Watch.feedOk` the first time the feed answers. After that, its old 14-hour mailbox silence check is off, since the
  server decides silence for these.
- **Still via the mailbox, on purpose:**
  - pairings made with a pairing key rather than an invite (no server link);
  - partners whose app has never read the feed (older app or server);
  - the "you were removed" message (`removePairing`), because the removed partner's feed link is gone by then;
  - groups (`GroupSync`), untouched.

### 5. Partner settings (built 2026-11-25)

- **Logan:** all four groups count as important ("Honestly those are all valid"). New partners get everything at once,
  as today. Quiet hours hold *everything* until 7 am.
- **Rules:** `dns/AlertPrefs` (app and server):
  - `Level` ALL / DAILY / IMPORTANT;
  - quiet hours from 22:00 to 07:00, fixed for now;
  - held alerts go out together once a day at 07:00, never inside quiet hours;
  - `isImportant` marks only housekeeping as minor: partner removed, group changes, a change made with a partner's
    OK. The server also marks "phone is back", answers to asks, and a partner leaving as minor.
- **Email partners:** a "How alerts reach you" form on `/p/{token}` (`POST /p/{token}/prefs`). The time zone comes from
  the browser (`report.js` fills `#tz`), and a form without one keeps the old zone. `Alerts.toPartners` routes each
  line; held lines wait in `Partner.held`; `sweep` sends them as "N alerts held for you".
- **App partners:** the same form, in the report WebView. The feed carries their `level`/`quiet` and each entry's
  `important`, and their own app applies them in its phone's time zone (`Watch.held`, one "N held for you"
  notification at 7).
- **The owner's phone** sends `minor` indices with `/v1/alerts`. It's unsigned, because a wrong value only changes
  *when* a partner hears.
- Not done: editable quiet hours; SMS for held alerts (they go by email only).
- Tests: `AlertPrefsTest`, `ServerTest.aPartnersQuietHoursAndDailyMessageFollowTheirOwnClock`.

### 2. Seen (built 2026-11-25)

- **What counts as a look:** a fetch of `/p/{token}/data`, which only the report page's script makes (in a browser or
  the app partner's WebView). Opening the page HTML (what mail link scanners do) and the app partner's background
  `/alerts` checks don't count. `Store.markViewed` writes at most every 5 minutes.
- **Owner side:** `/v1/partners/list` carries `lastViewMs`. Partners shows "looked 2 hours ago" or "hasn't opened the
  report yet", and nothing on an older server. The partner page says: "The phone's owner can see when you last opened
  this report, not what you looked at."
- Test: `ServerTest.theOwnerSeesWhenEachPartnerLastOpenedTheReport`.

### 1. A partner leaving (built 2026-11-25)

- **Server:** `POST /p/{token}/leave` adds an owner notice (`Device.ownerNotices`) and tells the remaining partners
  (`Alerts.toPartners`: email, SMS, the app partners' feed) with "Sam stopped being an accountability partner for this
  phone."
- **Heartbeat:** the response is now `HeartbeatResponse(ok, notices)`, with notices newer than the request's
  unsigned `noticesAfter` cursor. Older apps read only the status code, so this is additive: no protocol level change
  (D-041), and the compat suites are untouched.
- **App:** `SyncWorker.ownerNotices` logs `svc partner partner_left` (urgent) and posts one notification each.
  Tapping it opens Partners. An app partner's Remove (`PartnerReportActivity`) now calls the leave endpoint
  (`InviteLink.leaveTarget`) before forgetting the pairing. Before this, the server kept listing them and nobody was
  told.
- Test: `ServerTest.aPartnerLeavingTellsTheOwnerAndTheOthers`.

### 3. Approve an unlock (built 2026-11-25)

- The phone already signs its requests (P-256 `DeviceKey`). An unlock request would be `POST /v1/unlock/request` with
  the action and its plain-words label. The server stores it on the device, and partners see it on `/p/{token}`
  (email partners also get an email) and in the app partner's alerts.
- Approve and Deny are `POST /p/{token}/unlock/{id}`. The phone reads the answer at its next sync, or polls every
  minute while a request is pending and the screen is on.
- **Trust:** a self-hosted operator who is also the owner could forge an approval. That's the same trust as today's
  code, whose seed also sits on the owner's phone. Write it down; don't build around it.
- Expiry: a request lapses after the existing 15-minute window if nobody answers.

**As built:**
- **Rules:** `dns/UnlockAsk`, compiled by the app and the server. A request lapses after 15 min; an approval covers
  only the asked `GuardPolicy.Action`, for 15 min; partners read plain words.
- **Server:**
  - `POST /v1/unlock/ask`: signed `unlock|id|t|actionKey`; 409 with no partner. Asking again for the same open
    request doesn't tell anyone twice.
  - `POST /p/{token}/unlock/{id}`, `d=approve|deny`: the first answer counts, and everyone is told.
  - The card on `/p/{token}` works without JavaScript or the key. The heartbeat carries `unlock` status.
- **App:**
  - `UiUtil.guard` offers "Ask a partner" before the old ways ("Other ways": wait or PIN), wherever the requirement is
    the timed unlock or the partner PIN and the phone is enrolled.
  - `ApprovalStore` holds the server's answer; `SyncWorker` checks every minute while the request waits, logs and
    notifies the answer.
  - An approved change records `svc strict partner_approval_used`, which partners read as "changed with a partner's
    OK: loosen a filter bundle".
- **Not covered:** the seven direct `StrictStore.isLocked` + `showStrictLockedDialog` call sites with no action, which
  keep only the wait and the code. A server older than this answers 404, and the owner reads "Your server is too old".
- Tests: `UnlockAskTest`, `DigestTest`, `ServerTest.anyOnePartnerAnswersAnAskAndTheFirstAnswerCounts`.
