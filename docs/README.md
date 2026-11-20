# Focus Fence — research and design docs

This directory is **documentation only**. Nothing here changes the app's behaviour, and
nothing here is loaded automatically by any tool — it is read when someone opens it.

It exists because a batch of external research was commissioned about Focus Fence's
product direction, the Android platform's real limits, and the surrounding FOSS filtering
ecosystem. Rather than hand over raw research output, it has been checked against the actual
code and organised so it can be used, argued with, or ignored a piece at a time.

## What's here

**Current status lives in [`../STATE.md`](../STATE.md)**; rules for AI agents in
[`../AGENTS.md`](../AGENTS.md). The audits below predate most of the implementation work.

| Path | What it is |
|---|---|
| [`architecture.md`](architecture.md) | How the app works as built today, and why the non-obvious parts are the way they are. Descriptive — no proposals. |
| [`audits/findings.md`](audits/findings.md) | **Start here.** Every claim the research makes about this codebase, checked against the source: confirmed, wrong, or unverified. |
| [`audits/00-executive-summary.md`](audits/00-executive-summary.md) | A self-audit written across the other four documents, plus product principles, a capability classification and a draft roadmap. **The least reliable of the five** — see findings §2. |
| [`audits/01-product-definition.md`](audits/01-product-definition.md) | Adversarial review of the product thesis against existing FOSS competitors. Concludes the broad thesis is falsified and a narrower one survives. |
| [`audits/02-android-platform.md`](audits/02-android-platform.md) | What Android actually permits: VPN, accessibility, UsageStats, Shizuku, Device Owner, and why HTTPS interception fails a cost/benefit test. **The most reliable of the five.** |
| [`audits/03-foss-ecosystem.md`](audits/03-foss-ecosystem.md) | Survey of uBlock Origin, Rethink, NetGuard, AdAway, PCAPdroid, DNS66 and the filter-list ecosystem, plus matching/storage strategy at scale and licensing analysis. |
| [`audits/04-cloudveil-repos.md`](audits/04-cloudveil-repos.md) | Repository-by-repository review of CloudVeil's open-source filtering stack: what's worth studying, what to avoid. |
| [`plans/00-roadmap.md`](plans/00-roadmap.md) | Suggested sequencing derived from the audits. **Historical** — Tiers 0–1 were largely done on this branch; see `STATE.md`. |
| [`plans/01-base-assessment.md`](plans/01-base-assessment.md) | Is Focus Fence the right base for an Ever Accountable–class accountability app? (Yes, with two conditions.) |
| [`plans/02-ever-accountable-parity.md`](plans/02-ever-accountable-parity.md) | Feature-by-feature gap matrix against Ever Accountable on Android, and a phased build order with measurement gates. |
| [`plans/03-measurement-plan.md`](plans/03-measurement-plan.md) | How to use the new logs to measure reliability and battery cost, and what each result decides. |
| [`research/ai-detection.md`](research/ai-detection.md) | On-device AI: what runs, the first false positive, options to reduce false alarms without battery cost. |
| [`research/ui-design.md`](research/ui-design.md) | Material 3 guidance applied to this Views app, plus an ordered UI to-do list. |
| [`specs/event-log.md`](specs/event-log.md) | **Implemented.** Schema and guarantees of the activity and diagnostics logs, export format. |
| [`specs/accountability-architecture.md`](specs/accountability-architecture.md) | Component specs: categories, capture, screenshots, integrity/heartbeat, backend (incl. partner linking by invite code, email + SMS), settings lock, Device Owner. Device side mostly built; backend parts proposed. Open decisions listed. |
| [`specs/bundles.md`](specs/bundles.md) | **Implemented.** Curated filter-bundle set, strictness tier, bypass guard, defaults. |
| [`specs/friction-tiers.md`](specs/friction-tiers.md) | **Implemented.** Per-action guard levels (None/Delay/Own PIN/Partner PIN). |
| [`specs/device-owner.md`](specs/device-owner.md) | **Implemented, not device-tested.** Managed mode (detect-not-block), always-on VPN (no lockdown), settings lock, Private DNS fallback, the strategy switch, package suspension, network logging, filter health detection. |
| [`../server/`](../server) | **Prototype** Kotlin/Ktor accountability backend (enroll, signed ingest, heartbeat, Resend reports, E2EE screenshots). Not CI-built. |

## How to read it

The research is long, opinionated, and written by an outside party with no stake in the
project's history. Some of it is genuinely useful. Some of it argues for building things that
already exist, or describes the code inaccurately — despite having had the source.

`audits/findings.md` separates those. It is the only file here that has been checked line by
line against the source, and it is short. Read it before reading any of the audits, or the
audits will send you after work that is already done.

Two things in it need no debate and cost nothing:

- **The repo has no `LICENSE` file.** For a project whose identity is FOSS, source
  visibility isn't a license grant. This blocks nothing today and is a five-minute fix.
- ~~Two README claims are not accurate~~ — fixed on this branch: the README now says where
  DNS goes and points to the measured battery numbers instead of asserting them.

Everything else is a judgement call and belongs to the maintainer.

## What this is not

It isn't a plan of record, a set of requirements, or a claim that the current design is
wrong. The strongest conclusion across all four audits is the opposite: the DNS-only routing,
the scoped accessibility service, and the pure-logic `dns/` layer are the parts worth
protecting, and most proposed additions should have to prove they're worth their cost first.
