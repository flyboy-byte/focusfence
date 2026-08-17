# NEXT — the standing queue

> **Tier:** Execution · **Audience:** an agent picking up work with no one steering it turn by
> turn · **Use when:** you're an autorun session (or anyone) asking "what should I do right now."
> **Start here, not at the board below.** This file exists so that question always has an answer.

**Starting a brand-new session cold?** Use [`KICKOFF.md`](KICKOFF.md) instead of reading this file
directly first — it's a paste-able prompt that gets a fresh session oriented in one message.

## How to use this file

Work top to bottom. Each item names a doc to open and execute. When an item is done:

1. Check it off here, with the date and which branch it landed on.
2. If it unblocked something further down (a research item closing, or a work order whose
   dependency just landed), move the now-unblocked item up next to the top ready items.
3. Commit the checkbox update in the same commit as the work, or immediately after.

If every item here is blocked or done, stop and say so rather than inventing new scope — that's a
"come back to me" moment, not a reason to start improvising past the plan.

## Branch discipline (read this before touching git)

- Work on **your own personal branch** (`logandev` or `shelbydev`), never `shareddev` or `main`
  directly.
- When an item is complete and its tests pass, **merge it into `shareddev` yourself.** That branch
  exists precisely so this doesn't need a human in the loop for every item — it's the shared,
  low-stakes integration point.
- **Never merge or push to `main`.** `main` → release only, at a point a human chooses. If you
  think something is release-ready, say so in this file's notes; don't act on it.
- One item, one commit (or a small tight series) — don't bundle unrelated items so a bad one is
  easy to revert without losing good work.
- Run `./gradlew testDebugUnitTest` before every merge into `shareddev`. No exceptions, per
  `docs/work/README.md`.

## Research items — an agent with live web access can execute these directly

`docs/research-handoff.md` was written when this project only had a no-network coding session
available, so it describes a human-in-the-browser procedure. **If you have real web access, skip
the human step and do the research yourself** — same discipline, though: cite sources, save the
raw output to `docs/research/` before touching anything else, and verify any claim the result
makes about *this codebase* against the actual source before acting on it (the project's own
research cycle already caught five wrong claims doing exactly that once).

**Full prompts, already written, one per open question:** [`docs/research/prompts.md`](../research/prompts.md).
Paste directly — don't re-derive the prompt from the table row in `research-handoff.md`.

**High-context reasoning, not fact lookup:** [`docs/reasoning-prompts.md`](../reasoning-prompts.md)
has two deep-judgment prompts (R1 signing-key custody trade-offs; R2 a re-audit of everything in
`parked.md` against what's actually shipped since) — a better use of a big-context/high-reasoning
session than grinding through Kotlin, and **unlike the research items above, neither needs live
web access.** They're queue items in their own right, below.

## The queue

### Helps unblock the release — do this first

- [x] **Research Q9** — 2026-08-15, `shelbydev`. Signing-key custody prior art. Raw output in
      `docs/research/2026-08-15-signing-key-custody-prior-art-claude.md`, merged into
      `docs/signing-key-custody.md`. Answer: **4 of 4 comparable projects hand-sign production**;
      RethinkDNS's CI signs only a *separate* alpha key under a different `applicationId`. **No
      project documents any backup/escrow practice** — questions 1–3 of O-002 stay blank-page
      decisions that no further research can help with.
      **The ball is now with @sidewinderzz, not with an agent.** O-002 needs a human answer to
      "who holds the key, does the other maintainer hold a copy, where does the backup live."
      Nothing below is blocked on it, so an unsupervised session should skip to the next section
      rather than sit here — but do not start W4/W5, and do not decide O-002 on a human's behalf.

### Ready now, zero dependencies

- [x] **W9 (core + UI)** — core 2026-08-15, UI 2026-08-16, both `logandev`. Store, pure evaluator
      (`Blocklist.blockReason`), D-017 decision, `DnsVpnService` wiring, JVM tests, and the
      "Allowed domains" group in `BlocklistFragment`/`GroupedBlocklistAdapter` (add/list/remove,
      strict-mode gate on add, DoH-guard warning) are all done. `testDebugUnitTest` and
      `assembleDebug` both pass. Manual on-device check still worth doing before release, per
      `docs/work/W9-allowlist.md`.
- [x] **W13** — 2026-08-16, `logandev`. `scripts/battery-bench.sh` built and all five scenarios
      measured for real on a Pixel 7 (VPN consent + accessibility grant driven over
      `adb input tap` through the real system dialogs, not bypassed). Result: **zero wakeup
      events in every scenario**, battery-drain rate flat within noise across all five (~0.0013
      mAh/s regardless of VPN/list/accessibility state). This is the first measured (not
      inferred) support for the README's "battery impact is effectively zero" claim. See
      `docs/benchmarks/README.md`. Follow-up (not blocking): longer 15–30min runs and one
      off-USB-power confirmatory run.

### No web access needed — good pickup for a high-context/no-browsing session

- [x] **R1** — 2026-08-17, `claude/focus-fence-session-s72elg` → `shareddev`. Signing-key custody
      trade-off analysis. Output: `docs/research/2026-08-17-signing-key-custody-reasoning.md`,
      linked from `docs/signing-key-custody.md` § Status. Recommends hand-signing with split
      custody (sealed encrypted copy with the contributor, passphrase escrowed on paper), includes
      paste-able draft resolution text for O-002, and flags that W5's workflow spec silently
      assumed CI signing and needs amending if hand-signing is adopted. **O-002 stays open** — the
      draft decides nothing until @sidewinderzz adopts it; the ball is with him, same as Q9 left it.
- [x] **R2** — 2026-08-17, `claude/focus-fence-session-s72elg` → `shareddev`. Re-audit of
      parked/rejected items against shipped code. Output:
      `docs/research/2026-08-17-parked-reaudit-reasoning.md`. **Clean re-audit — every item still
      holds**; P-003/D-016 and D-001 are *strengthened* (W13 turned their battery premise into a
      measurement) and P-004's reopen condition was tested by W13 and did not fire. One flag for a
      human, not a reopen: W8's cost side has genuinely dropped post-W6/W9 (decision logic now
      flows through two pure choke points), though nothing yet needs it. One follow-up noted on
      the W11 line below.

### Ready to build — research landed

- [x] **W11** — 2026-08-17, `claude/focus-fence-session-s72elg` → `shareddev`. Multi-source lists
      with provenance and categories: 8-source catalog (`dns/FilterSources`), per-source
      files/enable/download (`FilterListStore` + legacy migration), `_src:<id>` stats buckets,
      source-aware `blockReason()` with parent-domain matching (the bare-domain OISD/HaGeZi
      variants require it), category+source UI in Settings with strict-gated disable, stats
      "blocked by list" section. 67 JVM tests pass; `assembleDebug` builds. Memory measured with
      5 real lists: 756,649 entries → 72 MB heap (~100 B/entry) — see the work order's "How it
      landed". **Still needs a device:** the W13 scenario-3 re-run with a combined list, and an
      on-device pass of the new Settings screen — both queued below.

### Field report awaiting a product decision — needs @sidewinderzz or @flyboy-byte

- [ ] **Parent-domain rules can break device-wide push (FCM).** Reported 2026-08-17: Focus Fence
      blocked another PWA's notifications. Verified same day: `mtalk.google.com` (the FCM push
      endpoint every Android push notification rides on) is **not** in any catalog filter list,
      and TCP never transits the DNS-only VPN — so the breakage is only consistent with a *user
      rule* on `google.com` being in force, which subdomain-matches `mtalk.google.com` and kills
      push for every app on the device while active. The allowlist **cannot** fix this, by
      design: D-017 says an allowlist entry never overrides a user rule. The decision needed —
      and it is a semantics change, so a human's: should always/window rules carve out known
      infrastructure subdomains the way budget rules already do (the `isFrontDoor` precedent
      exists because "traffic belonging to unrelated apps must neither drain nor break"), or is
      "block google.com = all of google.com" the honest contract, with a warning in the rule
      editor when a rule's domain has known infra subdomains? Interim workaround for the user:
      narrow or remove the `google.com` rule; allowlisting `mtalk.google.com` does nothing
      against a user rule.

### Needs a physical device — cannot be done from a cloud session

- [ ] **W13 follow-up for W11** — re-run `scripts/battery-bench.sh` scenario 3 with a combined
      multi-source list enabled (P-004's measured-adequate verdict covers 98,950 entries; a
      realistic W11 config can be ~750k). Also worth an on-device sanity pass of the new
      per-source Settings UI while the phone is plugged in anyway.

### Research that unblocks a work order — needs live web access

- [x] **Research Q2** — 2026-08-17, `claude/focus-fence-session-s72elg` → `shareddev`. Filter-list
      licensing. Raw output in `docs/research/2026-08-17-filter-list-licensing-claude.md`, merged
      into `risks.md`, `work/W11-source-provenance.md`, D-016 (format note), handoff queue row 2.
      Answer: **W11 is a go** under runtime download — HaGeZi/OISD GPL-3.0, Block List Project
      public domain, StevenBlack MIT-with-non-commercial-upstreams (never vendor it), EasyList
      dropped (ABP syntax, wrong object). Surprise finding: HaGeZi and OISD no longer publish
      hosts format; their bare-domain variants parse with the existing `HostsParser` unchanged
      (verified against the code). W11 moved up to "Ready to build" above.
- [ ] **Research Q4** — `UsageStatsManager` behaviour and grant flow. Unblocks **W10**. Row 4.
- [ ] **Research Q3** — Shizuku non-root capability in 2026. Unblocks **W12**. Row 3.
- [ ] **Research Q10** — current SafeSearch hostnames, plus the YouTube header question (P-002).
      Needed for the second half of **W14**. Row 10.

### Build once the research above lands

- [ ] **W14** — adult-content category + SafeSearch. After W11 (now ready, above) and Q10.
      `docs/work/W14-adult-content-category.md`.
- [ ] **W10** — app budgets via `UsageStatsManager`. After Q4. `docs/work/W10-app-budgets-usagestats.md`.
- [ ] **W12** — Shizuku enforcement. After Q3. `docs/work/W12-shizuku-enforcement.md`.

### Confirms a permanent constraint — low priority, no rush

- [ ] **Research Q8** — whether Android has any VPN-coexistence mechanism at all. Expected answer
      is "no, one slot, period," which would just confirm a design constraint already assumed
      everywhere. Row 8.

### Investigative — does not authorize building anything

- [ ] **Research Q11** — CloudVeil (and any comparable project) as prior art for on-device NSFW
      image/content classification: what it actually costs in battery, false-positive rate, model
      size, and privacy (does classification happen on-device with no upload?). This exists to
      check whether **P-003** in `docs/parked.md` (content inspection, rejected) still holds up,
      now that real prior art can be read instead of reasoned about from first principles.
      **Finding a good implementation does not mean build it** — it means bring the finding back
      to `docs/parked.md` and `DECISIONS.md` D-016 and let a human decide whether to reopen the
      question. Save findings to `docs/research/`, cite everything, and if the prior art turns out
      to require server-side inference, cloud upload, or heavy device requirements, that's a
      strong signal D-016's reasoning still holds — say so plainly rather than downplaying it.

### Deliberately not on this queue

- **W8** (the unified boundary model) — parked, see `DECISIONS.md` and the 04.3 recommendation in
  the project review. Don't pick it up without a human reopening it.
- **W4 / W5** (signing config, release workflow) — blocked on O-002, a human decision. Research Q9
  above can inform that decision; nothing here should execute W4/W5 before it's made.
- Anything not already speced as a `W*` work order or a numbered research question. This queue is
  deliberately closed — invent nothing. If something new and real comes up while working an item,
  write it up as a new work order or research question and add it here, rather than doing it
  inline under a different item's scope.
