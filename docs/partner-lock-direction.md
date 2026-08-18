# Direction questionnaire — partner-held locks and enforcement hardness

> **Tier:** High-level (decision layer) · **Audience:** @flyboy-byte, who decides scope (D-007) ·
> **Use when:** answering whether the app grows a second person, and how hard it fights removal.

## What this is

A user raised "a shared partner password to lock settings, plus accessibility controls so it can't
be disabled without it." That's two separable features wearing one sentence, and answering it
properly forces a direction call the project hasn't made yet.

**Nothing gets built from this until Q1–Q3 are answered.** Everything below Q3 is mechanism, and
mechanism questions are cheap to revisit; Q1–Q3 are `DECISIONS.md` entries and one of them is close
to a one-way door on what the product *is*.

**How to answer:** write under each `**Answer:**` line. "Don't care, you pick" is a valid answer and
is faster than a paragraph. Prior reasoning is linked so you can disagree with a specific sentence
rather than re-derive it.

---

## §0 — The fork

Three internally coherent directions. They are not points on a slider; each makes different promises
and has a different failure mode.

**A — Self-directed friction (what's built today).** The user locks themselves out, holds their own
key, and the 10-minute delay is calibrated to an impulse (D-012). Removal always works, on purpose.
*Failure mode:* someone whose impulse is stronger than 10 minutes gets nothing from the app.

**B — Two-person accountability.** The key moves off the person holding the phone. The app stays
technically identical — same VPN, same rules, same removal path — but the *unlock* now requires
another human. This is the Ulysses contract with a real second party.
*Failure mode:* it needs a partner to actually respond, and (see Q3) probably needs a way to reach
them, which would be the first outbound data the app ever sends.

**C — Hardened enforcement.** Fight removal: device admin, an accessibility service that navigates
away from its own Settings page, Shizuku self-heal, eventually Device Owner.
*Failure mode:* it reverses the one thing `overview.md` names as the project's distinctive trait
("treating *don't fight the device owner* as a design constraint rather than a weakness"), voids
D-012's stopping rule, un-parks P-005, and makes research Q6 (Play policy on `VpnService` +
`AccessibilityService`) go from a risk to a likely rejection.

**The load-bearing observation: B does not require C.** They get conflated because every commercial
competitor ships them together. A partner-held key raises the cost of unlocking without touching how
hard the app is to remove — and removal-by-uninstall is not what an accountability partner is
protecting against. What they're protecting against is a quiet 11:30pm unlock.

---

## §1 — Direction (these become `DECISIONS.md` entries)

### Q1. Which direction?

- **A only** — close this out, keep the timer, document why. Cheapest honest answer.
- **B** — partner-held key in scope; enforcement hardness unchanged.
- **B + a bounded slice of C** — partner key, plus *detect and report* tampering, but still no
  fighting with Settings (see Q7).
- **C** — reopen P-005 and D-012. Say so explicitly if you want this; it's a rewrite of the
  project's stated identity, not a feature.

*Recommendation:* B + bounded C. It's the only one that raises the ceiling for people the current
10-minute timer doesn't help, without spending the positioning.

**Answer:**

### Q2. Does a partner-held key violate "not a content filter for someone else"?

`overview.md` states plainly: *a content filter for someone else... it is a tool a user points at
themselves. Strict mode is friction the user chose, not enforcement against a third party.* A
partner password is textually close to the thing being ruled out.

The argument it doesn't violate it: the user *delegates their own key*, voluntarily, and can revoke
it by uninstalling. The argument it does: day to day, the experience is someone else controlling
your phone, and the app cannot tell a willing delegation from a coerced one.

Guardrails that keep it self-directed, if you say yes — all three, or the distinction is cosmetic:

1. The uninstall escape hatch always exists **and the UI says so during setup**, in plain language.
2. The app never hides itself, never conceals its icon, never resists uninstall.
3. The partner can see nothing the user can't see on their own screen.

- **Yes, it violates it** — direction A, done.
- **No, with the three guardrails written into the decision entry.**
- **No, and the guardrails are over-thinking it.**

*Recommendation:* no-with-guardrails. Guardrail 3 is what keeps this out of stalkerware territory,
and it costs nothing because there's no server to leak through anyway.

**Answer:**

### Q3. May the app ever send data *out*? (the one-way door)

An accountability partner who can't be reached in the moment is a partner who doesn't work. But
"nothing leaves the phone" is on the README. Note the premise is already asymmetric — the app
downloads filter lists, so egress exists; it's just inbound-only today.

- **Nothing, ever.** Partner mode works offline only: the partner is physically present, or the user
  phones them and reads out a challenge. Keeps the README claim absolute.
- **Hand-off only.** The app composes a message and opens the system share/SMS sheet; the *user*
  taps send. No network permission used, nothing background, nothing automatic. Arguably not egress.
- **Direct notification.** App sends SMS/email to the partner on tamper events. This is the version
  that actually works, and it is a genuine change to what the app claims about itself.

*Recommendation:* hand-off only. It gets most of the value and the README claim stays literally true.

**Answer:**

---

## §2 — Mechanism (cheap to revisit; skip if Q1 = A)

### Q4. What is the key?

- **Static passphrase** the partner sets. Simple, and decays fatally: the user eventually sees it —
  shoulder-surf, or the partner texts it once — and once seen it is burned permanently and silently.
- **TOTP (RFC 6238).** App generates a secret at setup and shows a QR; partner scans it into any
  authenticator app. To unlock, the user asks the partner, who reads six digits over the phone. The
  code is time-boxed and single-use, so hearing it teaches the user nothing. No account, no server,
  works offline on both ends. Roughly 40 lines over `javax.crypto`, no new dependency, pure enough
  to live in `dns/` under D-002 and testable against the RFC's own published vectors.
- **Both**, with TOTP recommended in the UI.

*Recommendation:* TOTP, static passphrase as the low-ceremony fallback. Rare case where the more
secure option is also the better UX and the easier thing to unit-test.

**Answer:**

### Q5. Does the partner key replace the 10-minute delay, or stack with it?

- **Replace** — the partner *is* the friction. Fast when they answer, useless at 3am.
- **Stack** — partner code *and* the wait. Strongest, most annoying.
- **User's choice at setup:** Timer / Partner / Both.

Shape either way: `StrictLock` gains a pluggable key rather than a parallel system. `State` barely
changes, `Pending` gains a second precondition, and no existing `isLocked` call site moves.

*Recommendation:* user's choice, defaulting to Both.

**Answer:**

### Q6. Build tamper-evidence?

Since removal is always possible (and under D-012 it *should* be), make it loud and countable rather
than blocked: detect the accessibility service going down while a lock is active, show a persistent
"protection reduced since 11:34 PM" notification, keep a visible ledger on Home (*down 3 times this
week, 46 minutes*), and require a partner code to clear the alert.

This beats the 11:30pm impulse specifically because **the impulse wants to be quiet.** Needs no new
permissions and picks no fight with Settings.

- **Yes, all of it.** · **Notification only**, no ledger. · **No** — the app doesn't keep score.

*Recommendation:* yes. Highest value-per-line item on the page, and the part that still works if Q1
comes back as A.

**Answer:**

### Q7. Ceiling on accessibility self-defense?

- **Nothing.** The service goes down, it goes down.
- **Detect and report** (feeds Q6). Observes only its own liveness.
- **Navigate away from Settings** — watch for the Settings page listing our own service and press
  Back. What the commercial blockers do. Stops the lazy attempt; walked around by Safe Mode, ADB and
  plain uninstall regardless. Also the single fastest way to fail research Q6.

*Recommendation:* detect and report, as a hard ceiling written into the decision so it can't creep.

**Answer:**

### Q8. Spend the friction budget on legacy Device Admin?

An active device admin can't be uninstalled until deactivated, and the deactivation dialog carries
our own warning text — roughly four extra taps and one scary screen. The only cheap uninstall
friction Android still offers a non-DPC app.

Against it: the API is deprecated, Play restricts it to genuine enterprise use, and its exact 2026
status is something to **verify before building, not assume**.

- **No** (uninstall stays a clean exit, consistent with D-012).
- **Yes, but only after a research question confirms it's still viable and Play-safe.**
- **Yes.**

*Recommendation:* no. Four taps doesn't change an impulse's mind, and it costs the clean-exit
promise that Q2's guardrail 1 depends on.

**Answer:**

### Q9. Take the two free composition wins?

Neither needs code beyond a nudge and a doc section:

1. **Always-on VPN + "Block connections without VPN"** — the user enables it in Settings. Makes
   stopping the tunnel materially harder *and* fails closed. The app can read `isAlwaysOn()` /
   `isLockdownEnabled()` (API 29+) to nudge during onboarding and to notice when it's turned off.
2. **Play Store parental-controls PIN**, set by the partner — blocks *reinstalling* apps above a
   content rating. Blunt (rating-based, not per-app), but it is the one partner-holds-a-PIN
   primitive stock Android actually gives us.

Worth stating outright, since "why not just use Android's built-in child controls" is the obvious
question: **there is no door in.** Family Link, Digital Wellbeing, Focus Mode and app timers expose
no third-party API — you cannot register policy with them, read their state, or extend them. Family
Link is also account-bound with a cloud policy server, which D-008 and `overview.md` rule out. What's
available is composition, documented in the README, not integration.

- **Both.** · **Always-on nudge only.** · **Neither.**

*Recommendation:* both.

**Answer:**

---

## §3 — Sequencing

### Q10. Where does this sit relative to the release?

The ready-to-build queue today is W10 (app budgets), W12 (Shizuku), W14 (SafeSearch), plus the
polish pass. O-002 (signing key) still blocks the release itself and is yours, not an agent's.

- **After the release** — ship what exists first.
- **Before W10/W12/W14.**
- **Slot it after W14.**
- **Research Q6 first, then decide** — Q6 (Play policy on the a11y + VPN combination) is already
  flagged in `work/NEXT.md` as a real risk and gates how assertive Q7 is allowed to be.

*Recommendation:* run research Q6 now (cheap, and blocking-shaped), build after the release.

**Answer:**

---

## §4 — Not being asked

Deliberately excluded so this doesn't reopen settled ground:

- **Device Owner provisioning** — P-005, parked as effectively permanent. Only Q1 = C touches it.
- **Content inspection of any kind** — P-003, re-confirmed by research Q11 on 2026-08-17.
- **Widening the VPN routes** — D-001.
- **An account, a server, or sync** — not on the table under any answer above. A partner-held key
  does not imply a backend; every option in Q4 works with the network off.

## §5 — What gets built if you answer nothing

So that silence has a defined meaning: Q6's tamper-evidence ledger and Q9's two composition wins,
because both are worth doing under direction A and neither presumes a second person. Everything
requiring a partner waits on Q1.
