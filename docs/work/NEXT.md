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
has two deep-judgment prompts (signing-key custody trade-offs; a re-audit of everything in
`parked.md` against what's actually shipped since) — a better use of a big-context/high-reasoning
session than grinding through Kotlin.

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

- [x] **W9 (core)** — 2026-08-15, `logandev`. Store, pure evaluator (`Blocklist.blockReason`),
      D-017 decision, `DnsVpnService` wiring, and JVM tests are done. **UI still open** —
      `docs/work/W9-allowlist.md` now specs only that half, precisely, for a fresh pickup.
- [ ] **W13** — battery/CPU benchmark harness. `docs/work/W13-benchmark-harness.md`. Unblocks
      every performance argument the project makes about itself; currently there is no
      benchmark anywhere in the tree.
- [ ] **W9 (UI)** — the "Allowed domains" section described in `docs/work/W9-allowlist.md`'s
      "What's left" — small, self-contained, good pickup for a fresh session.

### Research that unblocks a work order

- [ ] **Research Q2** — filter-list licensing (StevenBlack, HaGeZi, OISD, Block List Project,
      EasyList). Unblocks **W11**. This one's a real legal question, not a formality — get it
      right. See `docs/research-handoff.md` row 2.
- [ ] **Research Q4** — `UsageStatsManager` behaviour and grant flow. Unblocks **W10**. Row 4.
- [ ] **Research Q3** — Shizuku non-root capability in 2026. Unblocks **W12**. Row 3.
- [ ] **Research Q10** — current SafeSearch hostnames, plus the YouTube header question (P-002).
      Needed for the second half of **W14**. Row 10.

### Build once the research above lands

- [ ] **W11** — multi-source lists with provenance and categories. After Q2. `docs/work/W11-source-provenance.md`.
- [ ] **W14** — adult-content category + SafeSearch. After W11 and Q10. `docs/work/W14-adult-content-category.md`.
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
