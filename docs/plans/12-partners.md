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
| 2 | "Seen": owners know partners are looking | The owner sees when each partner last opened the report or an alert | Logan hadn't thought of it (2026-11-25); ask again before building |
| 3 | Approve an unlock from the report page | Email or app partner taps Approve/Deny on a pending request; the phone acts on it within a minute while the screen is on | next |
| 4 | Replies to alerts | A partner answers an alert; the owner sees the answer in the app | parked: Logan's "random things to add" list (below) |
| 5 | Partner settings | A partner chooses what reaches them (urgent only / everything / daily digest, quiet hours) | later batch of questions |
| 6 | Faster alerts for app partners | An urgent alert reaches an app partner in under a minute without Google services | later; UnifiedPush is the candidate |
| 7 | Owner's partner list and keys | Each partner's status (pending / active / last seen); a new report key that cuts off old links | after 2 |
| 8 | One path | Alerts and unlock codes off the pairing mailbox, onto the server route | after 3 |

Order: 1 (a bug, small) → 2 → 3 → 4, then 5–8. Section 0 runs whenever Logan has the second phone handy.

## Logan's answers (2026-11-25)

- **Leaving:** the owner and the remaining partners hear about it.
- **Approve:** any one partner, and an approval skips the wait; Deny cancels the request. With nobody answering, the
  normal 10-minute wait still works.
- **Seen:** "Hmm never thot of that". Not decided; the default if asked again is a quiet "looked 2 h ago" in the
  Partners list, with no notification.
- **Replies:** "Interesting… document this for later when I'm looking for random things to add".

## Ideas for later (Logan's "random things to add" list)

- **Replies to alerts.** Set replies ("All good", "Let's talk", "Call me", "Proud of you"), so nothing personal sits on
  the server. Or free text sealed in the partner's browser to the owner's phone key (P-256 `DeviceKey`; `dns/Ecies`
  exists), or both. The owner sees the reply in the app and in its notification.
- **"How to be a good partner"** page, linked from the welcome email: what each alert means, and how to bring it up
  without shaming.
- **Excluding apps from reports** (Ever Accountable has it); the exclusion itself is reported to partners.

## Notes per section

Notes are added here as each section runs.

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

### 3. Approve an unlock (design notes before the questions)

- The phone already signs its requests (P-256 `DeviceKey`). An unlock request would be `POST /v1/unlock/request` with
  the action and its plain-words label. The server stores it on the device, and partners see it on `/p/{token}`
  (email partners also get an email) and in the app partner's alerts.
- Approve and Deny are `POST /p/{token}/unlock/{id}`. The phone reads the answer at its next sync, or polls every
  minute while a request is pending and the screen is on.
- **Trust:** a self-hosted operator who is also the owner could forge an approval. That's the same trust as today's
  code, whose seed also sits on the owner's phone. Write it down; don't build around it.
- Expiry: a request lapses after the existing 15-minute window if nobody answers.
