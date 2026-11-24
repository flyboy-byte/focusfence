# R2 — Re-audit of parked/rejected ideas against what has actually shipped

**Engine:** Claude (high-reasoning Claude Code session — executed per `docs/reasoning-prompts.md`
R2; no web access used, only this repo's docs and source)
**Date:** 2026-08-17
**Inputs read:** `docs/parked.md`, `DECISIONS.md`, `docs/work/README.md` (board),
`docs/work/NEXT.md`, and the shipped code the verdicts depend on:
`dns/RuleEvaluator.kt`, `dns/Blocklist.kt` (`blockReason`, `BlockRule` incl. the W7 commitment
mode), `accessibility/UrlFilterService.kt` (the `blocksApp` call site), `data/` stores,
`docs/benchmarks/README.md` (W13 results).
**Feeds:** sanity check only. **Nothing here reopens anything** — "reopen-worthy" rows (there are
none) and the one "newly cheaper" flag are for a human to weigh.

---

## Verdict table

| Item | Verdict | Reasoning (one line each) |
|---|---|---|
| P-001 mixed-content platforms | **Still holds** | DNS still sees hostnames only and the accessibility layer still reads only browser address bars; W6/W7/W9/W13 added no mechanism that can split same-hostname content, and the only mechanisms that could are the ones D-016 rejects permanently. |
| P-002 YouTube restricted mode | **Still holds** (pending Q10) | Its sole reopen condition is research Q10 answering the `YouTube-Restrict` header question; nothing shipped bears on it. Q10 is queued and unblocked for a web-capable session, so this may resolve soon — by its own stated path, not by drift. |
| P-003 content inspection | **Still holds — strengthened** | W13 turned the battery premise from an inference into a measurement (~0.0013 mAh/s flat across all five scenarios, zero wakeups); content inspection means carrying real traffic, which now forfeits a *measured* claim rather than an assumed one. Q11 remains the defined pressure test. |
| P-004 matcher rewrite | **Still holds — reopen condition tested, did not fire** | P-004 says reopen only if W13 shows a real problem; W13's scenario 3 ran the real matcher with a 98,950-domain list and was indistinguishable from the no-VPN floor (same rate, zero wakeups). The `HashSet` is now measured-adequate, not assumed-adequate. |
| P-005 Device Owner | **Still holds** | D-012's threat model is unchanged, and W7's commitment mode is the friction philosophy working as designed (a promise until a fixed instant; unsatisfiable app budgets deliberately fail *permissive* in `blocksApp`). Harder enforcement already has its sanctioned path (W12/Shizuku), which needs no ownership takeover. |
| D-001 DNS-only routing | **Still holds — strengthened** | The measured zero cost exists *because* of the `/32`+`/128` route trick; any widening proposal now has to answer to a number, not a premise. |
| D-003 no bare NXDOMAIN | **Still holds — now validated** | Zero wakeup events in every W13 scenario is exactly what the cacheable-answer fix predicts; the regression it fixed would have shown up as nonzero wakeups in scenarios 2–3. |
| D-010 least-invasive mechanism | **Still holds** | The known failure it names (whole-app blocking via the address-bar-reading service instead of Shizuku) is unchanged and still tracked as W12; W7 built on the accessibility mechanism but through one pure call site (`RuleEvaluator.blocksApp`, one enforcement point in `UrlFilterService`), so the promised swap stays a swap. |
| D-012 impulse threat model | **Still holds** | Nothing shipped fights a determined owner; W7's commitment mode is a direct expression of the model (calibrated friction, permissive failure). |
| D-014 no premature SDK/policy engine | **Still holds** | Five rule modes, app rules, and an allowlist all landed as small additions to concrete code without a general engine — the "abstractions prove value in the app first" bet keeps paying. See the W8 note below for the honest flip side. |
| D-016 category list + SafeSearch, no content engine | **Still holds — strengthened** | Same W13 strengthening as P-003; its two open pressure tests (Q10 for SafeSearch details, Q11 for classifier prior art) are both queued and neither has produced a reason to revisit. |
| D-015 MIT / D-017 allowlist-never-overrides-user-rules | **Still hold (trivially)** | Included for completeness: D-017 shipped *as specified* — `blockReason()` checks the user rule first and final, unit-tested in `BlocklistTest` — and nothing shipped touches licensing. |

**Bottom line: a clean re-audit.** Every parked and rejected item survives contact with the code
that has shipped since it was written. Two rejections (P-003/D-016, and D-001) are *stronger* now
than when made, because W13 converted their battery premise from inference to measurement; one
(P-004) had its explicit reopen condition tested and the condition did not fire.

## Question 2 — is any parked idea newly cheaper post-W6/W7/W9?

**One, and it is worth a human's attention without being worth reopening: W8, the boundary
model.** When W8 was parked, rule evaluation lived inside `DnsVpnService` and there was no single
place a "boundary" abstraction could attach. That premise has genuinely changed: W6 put all
in-force decisions through pure `RuleEvaluator`, W9 put the block/allow ordering through pure
`Blocklist.blockReason()`, and W7 extended `BlockRule` to apps — so the decision surface W8 would
unify now already flows through two small, tested, Android-free choke points. The *cost* side of
W8's cost/benefit has dropped.

What has **not** changed is the benefit side: the seams D-009 admits to are still visible (apps
reuse `BlockRule` keyed by package name via the bare-string decode trick; app budgets are
silently permissive until W10 exists; domains and apps live in unrelated stores) — but none of
them has yet caused a bug, blocked a work order, or forced a workaround in W7/W9/W10-prep. Parked
items reopen when cost *changes the decision*, and cheaper-but-still-unneeded doesn't. Flagged
here so that whichever human eventually weighs W8 knows the ledger moved; per this audit's own
rules, nothing is being reopened.

No other parked item got cheaper: P-001/P-003/P-005 are rejected on principle (privacy, threat
model), not on cost, and no amount of shipped code moves a principle; P-002 waits on a fact; and
P-004's cost was never the issue — its *need* was, and W13 measured the need away.

## Question 3 — where I am genuinely uncertain

- **P-004 at W11 scale.** The measurement covers 98,950 entries. W11 (multi-source lists) could
  combine sources well past that. I can't tell from here whether 300k+ entries stays free —
  probably, `HashSet` lookup is O(1) and the cost is memory, not CPU — but the honest answer is
  "re-run scenario 3 with the combined list when W11 lands," which costs minutes with the
  existing harness. This is a *measurement to schedule*, not a doubt about the verdict above.
- **D-010/D-011's "swap not rewrite" claim for Shizuku.** The current one-call-site shape
  supports it, but it can't be confirmed until Q3/W12 actually attempt the swap. If W12 turns
  out to need more than replacing the enforcement action at that call site, D-011's pluggability
  claim should be revisited *as a claim* (the decision itself would still stand).
- **W13's caveats propagate here.** Two verdicts above lean on W13's numbers, and W13 itself
  flags 120-second windows and the `dumpsys battery unplug` reporting trick. The direction of
  those results is very unlikely to flip, but "strengthened" above inherits exactly the
  confidence W13's own README claims — no more.

## Status

Done as a sanity check; nothing reopened, nothing built. The single follow-up worth a queue line
when W11 lands: re-run the W13 scenario-3 measurement with the combined multi-source list, per
the P-004 uncertainty above.
