# Focus Fence — planning and research docs

This directory is **documentation only**. Nothing here changes the app's behaviour, and nothing
here is loaded automatically by any tool — it is read when someone opens it.

**Start at [`../FRAMEWORK.md`](../FRAMEWORK.md)** — the phase-gated status tracker, and the honest
answer to "where is this project actually at." This file is the doc index.

## Contents

| Doc | Tier | Purpose |
| --- | --- | --- |
| [`../FRAMEWORK.md`](../FRAMEWORK.md) | Meta | Phase-gated status tracker — where this is and what's next |
| [`../DECISIONS.md`](../DECISIONS.md) | Meta | Numbered log of *why* (D-001 … D-014) |
| [`work/`](work/) | **Execution** | **The work orders — W1…W14.** For what to do *right now*, go straight to [`work/NEXT.md`](work/NEXT.md). Starting a brand-new session cold? Paste [`work/KICKOFF.md`](work/KICKOFF.md) instead. |
| [`documentation-guide.md`](documentation-guide.md) | Meta | The tier map — who each doc is for and how they fit |
| [`overview.md`](overview.md) | High-level | What it is, who it's for, the recorded product intent |
| [`approach.md`](approach.md) | High-level | Is this worth doing this way — the gap, alternatives, what to validate |
| [`architecture.md`](architecture.md) | Low-level | How the app works **as built today**, and why the odd parts are odd |
| [`design.md`](design.md) | Low-level | The boundary model reasoned through against the six examples — resolves O-004 and reorders Track B |
| [`infrastructure.md`](infrastructure.md) | Low-level | Toolchain, the release-readiness gap, hard constraints, testing |
| [`signing-key-custody.md`](signing-key-custody.md) | Meta | Decision support for O-002 — the one open blocking decision before the first release |
| [`parked.md`](parked.md) | High-level | Ideas considered and set down deliberately, with the reasoning — read before proposing an "obvious" addition |
| [`risks.md`](risks.md) | Mixed | Scope, legal/licensing, technical and operational risk |
| [`notes.md`](notes.md) | High-level | Open decisions and questions — working scratchpad |
| [`partner-lock-direction.md`](partner-lock-direction.md) | High-level | Questionnaire for @flyboy-byte: does the app grow a second person (partner-held unlock), and how hard does it fight removal — awaiting answers |
| [`research-handoff.md`](research-handoff.md) | Meta | Queue of unverified external claims, and how to close them |
| [`research/prompts.md`](research/prompts.md) | Meta | Ready-to-paste, fully self-contained prompts for every open research-handoff item |
| [`reasoning-prompts.md`](reasoning-prompts.md) | Meta | Deep judgment-call prompts for a high-context model — not fact lookup, real trade-off reasoning |
| [`research/`](research/) | — | Intake folder for raw deep-research output |
| [`audits/findings.md`](audits/findings.md) | Source | **Read before the audits.** Every claim they make about this codebase, checked against source |
| [`audits/`](audits/) | Source | Five externally commissioned research documents, delivered 2026-08-13 |
| [`plans/00-roadmap.md`](plans/00-roadmap.md) | Source | Sequencing derived from that research — **superseded as an ordering**, kept for its analysis |

## How to read it

If you're catching up after time away, `../FRAMEWORK.md` then `overview.md` is enough.

If you're about to change code, `architecture.md` and `infrastructure.md` § "Hard constraints" are
the two that will stop you breaking something deliberate. Several odd-looking details exist purely
for battery and have already been regressions once.

If you're reading the research in [`audits/`](audits/): **read
[`audits/findings.md`](audits/findings.md) first.** It's short, and it's the only file here checked
line-by-line against the source. The research is long, opinionated, and written by an outside party
with no stake in the project's history. Some of it is genuinely useful. Some of it argues for
building things that already exist, or describes the code inaccurately — despite having had the
source. Read in the wrong order, it will send you after work that's already done, and in one case
would have you re-introduce a battery regression that's already fixed.

## The two things that need no debate

- **The repo has no `LICENSE` file.** For a project whose identity is FOSS, source visibility
  isn't a license grant — and it becomes a hard blocker the moment a binary is published.
- **The release build is unsigned.** `assembleRelease` produces `app-release-unsigned.apk`, which
  Android will not install. There is no `signingConfig`.

Both are in `infrastructure.md`; both need a decision from the project's owner, and the signing
key is a one-way door.

## What this is not

It isn't a set of requirements or a claim that the current design is wrong. The strongest
conclusion across all four audits is the opposite: the DNS-only routing, the scoped accessibility
service and the pure-logic `dns/` layer are the parts worth protecting, and most proposed additions
should have to prove they're worth their cost first.
