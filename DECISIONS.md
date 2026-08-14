# DECISIONS

Numbered log of *why*. [`FRAMEWORK.md`](FRAMEWORK.md) carries the status; this carries the reasoning, so it doesn't
have to be re-derived or re-argued.

Format: decision, date, reasoning, and what would reopen it.

---

## D-001 — The VPN routes DNS only, and never widens

*Decided: 2026-07-23 (in code, by @sidewinderzz). Recorded: 2026-08-14.*

The tunnel adds `/32` and `/128` routes for its own two synthetic DNS servers. All other traffic
takes the ordinary network path and never enters the process.

**Why:** it is the entire reason battery and throughput cost are negligible, and it means the app
never has the *ability* to see user traffic — a privacy property enforced by architecture rather
than by promise.

**Reopens if:** a validated requirement is impossible without it. Not before. "It would let us
block more" is not sufficient — all four audits independently reject full-TUN filtering.

## D-002 — `dns/` stays free of Android types

*Decided: 2026-07-23 (in code). Recorded: 2026-08-14.*

`Blocklist`, `BlockRule`, `Dns`, `Packets`, `UrlFilter`, `UsageTracker`, `StrictLock`,
`StatsCodec`, `HostsParser` import nothing from Android; callers inject clocks and timestamps.

**Why:** the whole decision surface is testable as plain JUnit with no emulator. This is the only
reason there is any automated coverage at all.

**Reopens if:** never, as stated. New decision logic goes in `dns/`.

## D-003 — Blocked queries are answered `0.0.0.0`/`::`, not NXDOMAIN

*Decided: 2026-08-13, commit `0552f11`. Recorded: 2026-08-14.*

**Why:** a bare NXDOMAIN carries no SOA record, so clients treat it as uncacheable and re-ask
continuously — measured waking the service thousands of times a day. Block answers now carry an
explicit TTL: 1 hour for ads and DoH guards, 60 seconds for user rules (which change with
schedules and budgets).

**Note:** the README and `DnsVpnService.kt:47-48` still say NXDOMAIN. That stale text is what led
all five external audit documents to describe the block path wrongly. **Any proposal to "restore
correct NXDOMAIN behaviour" is re-introducing a fixed battery regression.**

## D-004 — Research and opinion live in `docs/`, never in auto-loaded files

*Decided: 2026-08-13, PR #9.*

`CLAUDE.md` is descriptive only — architecture, commands, invariants, no proposals. All findings,
audits and plans live under `docs/`, which no tool loads automatically.

**Why:** `CLAUDE.md` loads into every Claude session in this repo, including @sidewinderzz's. A
contributor's opinions about direction shouldn't silently steer the maintainer's sessions.

## D-005 — Development happens on branches; `main` is never committed to directly

*Decided: 2026-08-14, by @flyboy-byte.*

`dev` branches off `main`; feature branches off `dev`; merge into `main` by PR. `dev` is
deliberately left with **no upstream**, so a bare `git push` cannot target `main`.

## D-006 — "Finished" means a public GitHub Release

*Decided: 2026-08-14, by @flyboy-byte.*

A signed, installable APK attached to a GitHub Release that a stranger can download and use —
not a CI artifact for the two of us.

**Why it matters beyond packaging:** it changes the standard of proof for the docs. Inaccurate
README claims stop being stale comments and become claims made to strangers about an app that
asks for VPN and Accessibility permissions. See `docs/infrastructure.md` R6 and `docs/risks.md`.

## D-007 — @flyboy-byte decides scope; the original idea is preserved

*Decided: 2026-08-14.*

Focus Fence is @sidewinderzz's project and idea. @flyboy-byte decides direction and scope going
forward, and the original premise — a self-control tool for user-chosen sites, fully on-device,
cheap to run — is kept rather than replaced.

**Consequence:** proposals that redefine what the app *is* need an explicit decision entry here,
not just a roadmap line. `docs/audits/01-product-definition.md` proposes exactly such a
redefinition ("the broad thesis is falsified, a narrower one survives"); that is an outside
reviewer's preference, and it has **not** been adopted.

## D-008 — General FOSS tool, opinionated by one use case

*Decided: 2026-08-14, by @flyboy-byte. Full text in `docs/overview.md` § Product intent.*

Focus Fence is a general-purpose FOSS self-control tool whose philosophy and constraints come
from a specific power-user/developer use case — not a tool for one person, and not a general
digital wellbeing app chasing competitor parity.

**Why the middle position is stated explicitly:** each collapse has a distinct failure mode.
"It's for me" overfits every abstraction to one workflow. "General wellbeing app" imports every
feature a competitor has. The use case sets the constraints; the implementation generalises them.

**Consequence:** feature proposals are judged against the boundary examples in `docs/overview.md`, not
against a competitor matrix.

## D-009 — Boundaries are the product; mechanisms are implementation

*Decided: 2026-08-14.*

The app is not fundamentally an ad blocker, porn blocker, app timer or VPN. The user expresses a
boundary; the app chooses the mechanism.

**Consequence, and it's a real one:** the code has no boundary object. Domains carry four rule
modes, apps carry none, and the two live in unrelated stores. `docs/approach.md` B2 exists because of this
decision, and two of the six boundaries in `docs/overview.md` are currently inexpressible.

## D-010 — Least invasive mechanism wins; privacy includes not observing

*Decided: 2026-08-14.*

Privacy is not only "we don't upload data" — the app should avoid observing information it does
not need. Where two mechanisms enforce the same boundary, the one that observes less wins, even
if the other is more powerful. More privilege is not automatically better.

**Consequence:** this is a *test* proposals must pass, and the current code fails it in one place.
Whole-app blocking is enforced by the accessibility service, which reads browser address bars;
Shizuku package suspension would enforce harder while observing almost nothing (`docs/approach.md` B7).
Reading address bars remains justified for section rules, where nothing cheaper can see a path.

It also settles HTTPS interception permanently: it is rejected on principle, not on feasibility.

## D-011 — Separate observation, classification, policy and enforcement

*Decided: 2026-08-14.*

Four distinct concerns, four distinct layers.

**Consequence:** `DnsVpnService.ruleBlocks()` currently mixes all four — it observes DNS packets,
evaluates schedules, accrues budgets and synthesises the block answer. `docs/approach.md` B1 splits it.
This decision also means enforcement mechanisms are pluggable by design, which is what makes B7
(Shizuku) a swap rather than a rewrite.

## D-012 — Threat model is impulsive circumvention, not a determined owner

*Decided: 2026-08-14.*

Target: "I decided at 9 PM I don't want this available, and at 11:30 PM I impulsively try to undo
it." Explicitly **not**: an owner with ADB, recovery, root, factory reset and unlimited effort.

**Why it matters:** it gives a stopping rule. Friction is calibrated to the impulse, so
"make bypass harder" is not self-justifying — it has to beat the impulse threshold, and past that
it costs device ownership for nothing. Strict mode's existing 10-minute delay and 5-minute window
are a reasonable expression of this (still an unvalidated default). Device Owner is rejected under
it: it fights an adversary this project doesn't have.

## D-013 — The VPN is optional; the rest of the app survives without it

*Decided: 2026-08-14.*

Focus Fence must keep working when PCAPdroid, WireGuard, another VPN or a development experiment
holds Android's single VPN slot. The VPN must stay easy to stop.

**Consequence:** currently only half true, and this decision is what makes that a bug rather than
a limitation. Section rules and app blocking run in the accessibility service and survive; domain
schedules and budgets are evaluated inside `DnsVpnService` and stop dead. `docs/approach.md` B1 and B4.

**Interaction with strict mode:** strict lock refusing `ACTION_STOP` is *not* a violation — it's
the impulse filter from D-012, and the 10-minute unlock path keeps the owner in control. The line
is friction, not prevention.

## D-014 — No premature SDK or general policy engine

*Decided: 2026-08-14.*

Longer term the policy/filtering logic should be clean enough for another developer to use or
extend. But abstractions have to prove their value in the app first.

**Consequence:** B2 designs a boundary model around the six concrete examples in `docs/overview.md`, not
around a general policy language. Guards the source-model work (B6) from becoming a matcher
rewrite — audit 03 found the existing `HashSet` adequate at ~100k entries.

---

# Open — decided before the first release

## O-001 — Which license

Blocks the first release (`docs/infrastructure.md` R1). Candidates from audit 03: **MPL-2.0** (file-level
copyleft — improvements to the engine stay open, embedding still allowed), **Apache-2.0**
(maximum reuse, explicit patent grant), **GPLv3** (aligns with most of the surrounding filtering
ecosystem, constrains reuse as a library).

Do **not** decide from the exec summary's compatibility table — it contains errors, documented in
`docs/audits/findings.md` §2.8.

Copyright is shared, so this needs @sidewinderzz's agreement.

## O-002 — Signing key custody

Blocks the first release (`docs/infrastructure.md` R2), and it is a **one-way door**: the key becomes the app's
permanent identity. Signing a later update with a different key forces every user to uninstall
and reinstall, losing their rules.

To settle: who generates and holds the keystore; whether both of us hold a copy; where the backup
lives; and whether CI signs from a GitHub Actions secret or releases are signed by hand.

Worth settling now — losing this key later is unrecoverable.

## O-003 — Whether an allowlist overrides user rules

Scoped in `docs/plans/00-roadmap.md` Tier 1. The conservative answer is that an allowlist should
override only list-sourced blocks, never a rule the user deliberately set — so it can't silently
defeat a boundary someone chose. Not yet decided.
