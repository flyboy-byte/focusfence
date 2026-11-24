# Decisions D-017 to D-020 from `shareddev` (2026-08-18), with their status today

> **Why this file:** these four were written on the `shareddev` branch, which never merged into `main` and was
> deleted on 2026-11-23 (everything kept in tag `archive/shareddev-2026-08-18`). They are kept here as reasoning,
> **not** as live decisions: `DECISIONS.md` on `main` now uses D-021 (signing key) and D-022 (collaboration), and
> these numbers are not reused there.

| Original | What it said | Status on `main` (2026-11-23) |
|---|---|---|
| D-017 | An allowlist overrides list-sourced blocks only, never a user rule | `AllowlistStore` exists on `main`; check its precedence matches before relying on this |
| D-018 | Partner-held unlock keys are in scope; fighting removal is not | **Matches what was built**: per-action Partner PIN, a paired partner's rotating code skips the strict wait, uninstall is never blocked |
| D-019 | A partner-held key does not violate "not a content filter for someone else" | Consistent with the accountability work |
| D-020 | Partner notification is hand-off only; no background egress | **Superseded** by the accountability work: phones upload signed activity batches to an optional server and partners get email/app alerts. Still true in spirit for the default: with no server set, nothing leaves the phone |

---

## D-017 — An allowlist overrides list-sourced blocks only, never a user rule

*Decided: 2026-08-15, resolving O-003, while implementing W9.*

An allowlist entry exempts a domain from the ad/tracker list, but **never** from a `BlockRule` the
user deliberately set. The alternative — letting it override anything — would turn the allowlist
into a one-tap bypass of the app's entire purpose, which fails the threat model (D-012): the
adversary is the user's own impulse, and an impulse will absolutely reach for the nearest bypass.

**Consequence for evaluation order:** in `blockReason()`, the user-rule check runs first and is
final; the allowlist check only runs afterward, against the ad-host set and the DoH bypass guards.
Implemented as a pure `Blocklist.blockReason()` so the ordering is unit-tested (`BlocklistTest`)
rather than only living in `DnsVpnService`, which cannot run on the JVM.

**DoH guards:** an allowlist entry *can* exempt a DoH bootstrap host — the app respects device
ownership enough to let a user who deliberately wants DoH say so — but the UI warns at the point of
adding that this disables the filter's own bypass protection, since D-012's "silent non-enforcement
is the worst failure mode" applies here too.

---

## D-018 — Partner-held unlock keys are in scope; fighting removal is not

*Decided: 2026-08-18, resolving Q1 and Q7 of `docs/partner-lock-direction.md`.*

The project adds a second direction alongside self-directed friction (D-012): an accountability
**partner can hold the unlock key**, raising the cost of disabling a lock beyond what the existing
10-minute timer stops. This is direction **B, plus a bounded slice of C** — detect-and-report
tamper evidence only. It is explicitly **not** direction C in full.

**What's in scope:** a partner-held key (mechanism: D-020's egress answer and the questionnaire's
§2), and detecting + reporting when the accessibility service goes down while a lock is active
(a persistent notification, a visible "down N times this week" ledger, cleared only by a partner
code).

**What's permanently out of scope, and why removal-fighting is a different thing than a partner
key:** the app must never navigate away from its own entry in Android Settings, request Device
Admin, or pursue Device Owner to resist being disabled. `overview.md` names *not fighting the
device owner* as this project's distinctive trait, D-012 already decided the threat model is an
impulse, not a determined adversary, and P-005 already parked Device Owner as effectively
permanent. A partner-held key does not touch any of that — the load-bearing observation from the
questionnaire is that raising the cost of an *unlock decision* is orthogonal to raising the cost of
*uninstalling the app*, and only the former is being adopted here. Removal-by-uninstall stays a
clean exit under every configuration this decision authorizes.

**Consequence:** any future work order building tamper-evidence or a partner key that starts
fighting Settings, or that adds Device Admin, contradicts this decision and needs its own new
entry to reopen it — it is not a natural extension of D-018.

---

## D-019 — A partner-held key does not violate "not a content filter for someone else"

*Decided: 2026-08-18, resolving Q2 of `docs/partner-lock-direction.md`.*

`overview.md` rules out the app being "a content filter for someone else" — a tool a user points at
themselves, not enforcement against a third party. A partner-held unlock key is textually close to
that, since day to day the experience is someone else holding a key to your phone. The
distinguishing test is **delegation, not control**: the user chooses to hand the key over and can
revoke that choice by uninstalling at any time.

That distinction only holds if three guardrails are binding, not optional:

1. The uninstall escape hatch always exists, and **the setup UI states this in plain language**
   before a partner key is ever configured.
2. The app never hides itself, never conceals its icon, and never resists uninstall (consistent
   with D-018).
3. The partner can see **nothing** the user can't see on their own screen — no location, no
   browsing history, no content, nothing beyond lock state and tamper events. This is the specific
   guardrail that keeps the feature out of stalkerware territory, and it costs nothing to hold
   because there is no server for extra data to leak through in the first place (D-020).

**Consequence:** any implementation that gives the partner visibility the user doesn't also have —
a dashboard, a history feed, location — violates this decision even if the unlock mechanism itself
is unchanged.

---

## D-020 — Partner notification is hand-off only; no background egress

*Decided: 2026-08-18, resolving Q3 of `docs/partner-lock-direction.md`.*

The README states nothing leaves the phone. Reaching a partner in the moment is what makes a
partner-held key actually work, so this is the one place the project adds a deliberate, narrow
exception — **hand-off only**: the app composes a message (e.g. "requesting unlock, code expires
in N minutes") and opens the system share/SMS sheet; the *user* taps send. No network permission is
added, nothing sends in the background, and nothing is automatic.

**Rejected:** direct app-initiated notification (SMS/email sent by the app itself on tamper events
or unlock requests) — that is a genuine change to what the app claims about itself and was not
adopted. **Also rejected:** an offline-only partner mode (no messaging at all, partner must be
physically present or reached by a separate phone call) — it was on the table but gets less of the
value for the same guardrail cost as hand-off.

**Consequence:** the README's "nothing leaves the phone" claim needs a one-line amendment once this
ships, scoped precisely to "the app never sends anything itself; a user-initiated share/SMS is not
the app talking to a server." No new network permission may be added to support this feature.
