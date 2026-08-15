# Documentation Guide

> **Tier:** Meta (about the docs themselves) · **Audience:** anyone new to this packet,
> including future-you after time away · **Use when:** you don't know which doc to open,
> or you're adding a new doc and need to decide where it fits.

This packet has two altitudes, plus a third layer that sits below both once there's real work to
hand off. Knowing which altitude you're reading (or writing) at prevents the two failure modes
that hit project-planning docs specifically: framing material that's all vision and no way to
actually build the thing, and build docs that bury the "is this worth doing this way" question
under implementation detail.

**Start at [`../FRAMEWORK.md`](../FRAMEWORK.md)** — it's the phase tracker and the honest answer to
"where are we." [`README.md`](README.md) in this directory is the **complete file listing** (every
doc, one line each); this file is the **tier map** — which altitude each doc reads at and how they
relate. If the two ever disagree about whether a doc exists, `README.md` is the one to trust and
this file should be corrected to match — it's the newer, denser one and more likely to drift.

## The two tiers

### High-level — the decision layer

**Question it answers:** *Should this exist, and is this the right way to build it?*
**Written for:** the person deciding whether and how to pursue this.
**Properties:** readable in one sitting, states conclusions and open questions plainly, doesn't
require deep technical literacy.

| Doc | What it's for |
| --- | --- |
| [`overview.md`](overview.md) | What it is, who it's for, why it exists, the recorded product intent |
| [`approach.md`](approach.md) | Is this worth doing this way — the gap measured against the project's own examples, alternatives, validation plan |
| [`notes.md`](notes.md) | Live scratchpad of open decisions |
| [`risks.md`](risks.md) *(scope/legal sections)* | What could derail this and why |
| [`parked.md`](parked.md) | Ideas already considered and deliberately set down, with the reasoning — check before proposing an "obvious" addition |

### Low-level — the build layer

**Question it answers:** *Given we're doing this, how does it actually get built and shipped?*
**Written for:** whoever is implementing. Assumes the high-level case has been read.

| Doc | What it's for |
| --- | --- |
| [`architecture.md`](architecture.md) | How the app works **as built today**, and why the non-obvious parts are the way they are |
| [`design.md`](design.md) | The boundary model: what it is, whether it's worth building, and what it can't do |
| [`infrastructure.md`](infrastructure.md) | Toolchain, the release-readiness gap, hard constraints, testing |
| [`risks.md`](risks.md) *(technical/operational sections)* | What breaks in practice and how it's guarded |

### Execution — the work layer

**Question it answers:** *What do I actually do right now?* Sits below both tiers above: it exists
once there's real, sequenced work to hand off, not while the project is still deciding what it is.

| Doc | What it's for |
| --- | --- |
| [`work/NEXT.md`](work/NEXT.md) | **Start here for "what now."** The standing, ordered queue |
| [`work/KICKOFF.md`](work/KICKOFF.md) | Paste-able prompt to orient a brand-new agent session in one message |
| [`work/README.md`](work/README.md) | Rules of execution and the full work-order board (`W1`…`W14`) |
| `work/W*.md` | One self-contained spec per work order |

### Meta — about the project and the docs

| Doc | What it's for |
| --- | --- |
| [`../FRAMEWORK.md`](../FRAMEWORK.md) | Phase-gated status tracker — the entry point |
| [`../DECISIONS.md`](../DECISIONS.md) | Numbered log of *why* (D-001 … D-017), separate from the narrative |
| [`documentation-guide.md`](documentation-guide.md) | This file |
| [`research-handoff.md`](research-handoff.md) | Queue of unverified external claims and how to close them |
| [`research/prompts.md`](research/prompts.md) | Every open research-handoff item, expanded into a ready-to-paste prompt |
| [`reasoning-prompts.md`](reasoning-prompts.md) | Deep judgment-call prompts for a high-context model — trade-offs, not fact lookup |
| [`signing-key-custody.md`](signing-key-custody.md) | Decision support for O-002, the one open blocking decision |

### Source material this packet was built from

Both live inside this directory, so the links resolve:

| Doc | What it is |
| --- | --- |
| [`audits/`](audits/) | Five externally commissioned research documents, delivered 2026-08-13 |
| [`audits/findings.md`](audits/findings.md) | **Read this before any of the audits.** Every claim they make about this codebase, checked against source: confirmed, wrong, or unverified |
| [`plans/00-roadmap.md`](plans/00-roadmap.md) | The sequencing derived from that research. **Superseded as an ordering** — it predates the recorded product intent. Kept for its analysis |
| [`research/`](research/) | Intake folder for future research output |

## How the tiers relate

Focus Fence is unusual: **the build came first.** The app existed and worked before any of this
packet did. The source material was written next (the audits), then the as-built documentation
(`architecture.md`), and the product intent was recorded last, on 2026-08-14 — which is why
`plans/00-roadmap.md` is sorted by *risk* rather than by *value*, and why it's now reference rather
than plan.

Read **top-down** (`FRAMEWORK.md` → `overview.md` → `approach.md`) if you're deciding what to do
next or catching up after time away. Read **bottom-up** (`architecture.md` → `infrastructure.md`)
if you already know what you're building and need the constraints.

There's one ordering rule worth respecting: **`audits/findings.md` before `audits/`.** Read in the
other order, the research will send you after work that's already done, and in one case would have
you re-introduce a battery regression that's already been fixed.

## Who this packet is actually for, concretely

@flyboy-byte wrote or commissioned all of it; @sidewinderzz is the original author and owner of
the project and has not reviewed most of it. Two decisions in `notes.md` (license, signing key
custody) are explicitly blocked on him.

**None of the forward-looking material has been pressure-tested against reality.** No device test
has been run, no research question in `research-handoff.md` has been answered, and the release
build has never been installed. That's what `FRAMEWORK.md` Phase 2 exists for. The material about
the *existing code* is a different matter — it was verified line-by-line against source, and
`audits/findings.md` records what survived.

## Adding a new doc

Decide which tier it belongs to (does it answer "should we" or "how do we"), add it to the table
above, and give it the same header block used everywhere here (`Tier / Audience / Use when`). If a
section depends on facts nobody in this project actually knows, don't invent them — add a row to
`research-handoff.md` instead, or a Phase 2 item in `FRAMEWORK.md` if it needs a real device.
