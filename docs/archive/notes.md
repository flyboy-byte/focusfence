# Notes

> **Tier:** High-level (working scratchpad) · **Audience:** you only — this is a
> living notes file, not a polished deliverable · **Use when:** ongoing, update as
> real answers replace open questions. Several items here are good deep-research
> handoff candidates — see [`research-handoff.md`](./research-handoff.md).

Working scratchpad. Settled decisions graduate to [`../DECISIONS.md`](../DECISIONS.md); this file
holds what's still open.

## Things to explicitly decide before committing further

~~**O-001 — Which license.**~~ **Resolved 2026-08-15: MIT** (D-015). The candidates considered were
MPL-2.0 (file-level copyleft), Apache-2.0 (patent grant) and GPLv3 (ecosystem alignment); MIT won
on lowest cost to everyone and on matching StevenBlack, our main upstream source. @sidewinderzz's
written acknowledgement is still outstanding — copyright is shared.

**O-002 — Signing key custody.** Blocks the first release, and it's a **one-way door**: the key
becomes the app's permanent identity, and a later key change forces every user to uninstall and
reinstall, losing their rules. To settle: who generates and holds the keystore; whether both of
you hold a copy; where the backup lives; whether CI signs from a GitHub Actions secret or releases
are signed by hand. Worth settling now — losing this key later is unrecoverable.

**O-003 — Should an allowlist override a *user rule*, or only list-sourced blocks?** The
conservative answer is only list-sourced blocks, so an allowlist can never silently defeat a
boundary the user deliberately set. Not yet decided.

**~~O-004 — Is B2 real, or is it B3 wearing a costume?~~ RESOLVED 2026-08-14** — reasoned through
in [`design.md`](design.md). It's real: the current four "modes" are two dimensions
(target × condition) flattened into one enum, and `targets: Set<Target>` spanning apps and domains
is new capability, not new syntax. All six examples fit a fixed four-by-four shape with no policy
language. **But the reasoning reordered the plan** — see B3a below.

**O-005 — Is F-Droid wanted?** It would put a third party in the build path, which is the single
biggest available improvement to the trust problem in `risks.md`. It also imposes reproducible-build
requirements and anti-feature labelling. Not urgent, but it changes how R5 (release workflow)
should be built, so worth deciding before that's written rather than after.

## Open technical questions

Several of these were answered by [`design.md`](design.md); what's left is genuinely open.

- **Where does the front-door check belong** once condition evaluation moves out of
  `DnsVpnService` (B1)? It's currently a DNS-layer concern (`Blocklist.isFrontDoor`) doing work on
  behalf of budget accrual. After the split it may belong to the usage layer instead.
- **Can `UsageStatsManager` and the DNS-inferred estimate coexist coherently**, or does having two
  sources of "time spent" confuse the stats UI? Probably: apps from UsageStats, websites from DNS
  inference, both labelled. A UI decision, not just a data one.
- **How should partial enforcement be surfaced?** `design.md` §4 proposes domain boundaries
  degrading to browser-only when another VPN holds the slot, and §5 notes an `App` target with path
  exceptions can't be enforced at all. Both are silent failures unless the UI says otherwise, and
  `risks.md` rates silent failure as worse than a crash here. Needs a design pass of its own.
- **Is the §4 degradation worth its battery cost?** It needs `TYPE_WINDOW_CONTENT_CHANGED` for
  browsers whenever a domain boundary exists, gated on the VPN being down. That's the high-volume
  event type the service deliberately avoids. This is the one design proposal whose cost genuinely
  can't be argued without B8's benchmark.
- **Should the DoH guard list be user-editable?** Currently hardcoded (`DOH_BYPASS_GUARDS`). Under
  "least invasive mechanism" and "respect the owner," a user who deliberately wants DoH should
  probably be able to say so.

**Answered in `design.md`:** whether a boundary must span mechanisms atomically (yes — that's the
model's main job, §2); how strict mode and commitment periods reconcile (commitment is a
*condition*, not a second lock, §5); and what happens to existing rules on migration (total,
deterministic, lossless, §6).

## Sequencing — a reasonable next stretch of work

Ordered so nothing waits on a decision that hasn't been made.

1. **B1** — move condition evaluation out of `DnsVpnService` into `dns/` behind an injected clock.
   No behaviour change, existing tests to lean on, and the prerequisite for everything below.
2. **B3a** — give apps `Window` and `Commitment` conditions by evaluating them in the accessibility
   service. `design.md` §3 found this to be nearly free: the service already runs the right code at
   the right moment and only lacks access to the evaluator B1 exposes. Closes the
   most-missed gap (example 5) with no new permission.
3. **Track A step 1** — README corrections plus the `DnsVpnService.kt:47-48` KDoc and the
   `AdListStore` provenance comment. No behaviour change, no decision needed, and it removes the
   stale text that misled all five audit documents.
4. **Track A step 3** — CI builds `assembleRelease`. Permanently closes the "nothing watches the
   release build" gap.
5. **Get @sidewinderzz's written acknowledgement of MIT (D-015), and settle O-002 with him.**
   Nothing else in Track A can proceed without both.
6. **Install the R8 release build on a device.** The one check reasoning can't substitute for.
7. **B2** — the full boundary object, once B1 and B3a have proved the split works in both services.

Steps 1–4 can all happen before any decision comes back. **Deferred:** the VPN-coexistence device
test — the code answers it, so it's worth doing before *claiming* it in user-facing docs, not
before building against it.

## Doc-structure note

This packet deviates from the `plan-scaffolding` template in three places, deliberately:

- **`FRAMEWORK.md` at the repo root**, not alongside a packet README — it's the entry point for
  the whole repo, matching the convention in the owner's other projects.
- **No packet-level `README.md`.** The repo root already has a user-facing app README that must
  not be clobbered; [`README.md`](README.md) in this directory serves as the doc index instead.
- **Research intake at [`research/`](research/) inside `docs/`**, because
  [`audits/`](audits/) already holds the first batch of external research output and the two
  belong together.

`CONTEXT.md` and `PLAN.md` existed briefly as working files on 2026-08-14 and were folded into
`overview.md`, `approach.md`, `infrastructure.md` and `FRAMEWORK.md` rather than kept as a second
source of truth. `DECISIONS.md` stayed at the root — the template has no equivalent and a numbered
decision log is worth having.
