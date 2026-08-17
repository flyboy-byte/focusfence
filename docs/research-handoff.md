# Research Handoff Workflow

> **Tier:** Meta (process doc) · **Audience:** you, during the scoping/validation
> phase · **Use when:** a doc in this packet has a claim marked unverified/inferred that
> needs real citations, current facts, or legal/licensing specifics — things a coding
> agent shouldn't fabricate.

## Why this exists

Claims in this packet **about the codebase** have been verified against source — see
[`audits/findings.md`](audits/findings.md), which checked every code-level assertion in the
external research and found several wrong. Claims about **the outside world** have not. This
session had no network access, so platform policies, licensing terms, competitor behaviour and
library capabilities are recorded, not endorsed.

The right tool for closing those gaps is a deep research engine with live web access — either run
by you in a browser, or, if you're an agent session that genuinely has live web access (not this
one), run directly. Either way it shouldn't be the source of truth for anything that needs to be
traceable and citable later without that discipline: cite sources, save the raw output before
acting on it, and verify anything the result claims about this codebase against the actual source.

This project has already been through one full cycle of this: five documents were commissioned,
delivered, and then verified. **The verification found real errors** — a platform version off by
three releases, half a proposed roadmap already shipped, a misattributed repository, and a license
compatibility table that contradicted its own prose. Treat new research output the same way.

## What qualifies for handoff

Externally verifiable, and not something this session should guess at. Internal decisions (which
architecture, what to name something) are judgment calls and belong in [`notes.md`](notes.md).
Questions needing *primary* research — running it on a real device, asking a real person — belong
in [`../FRAMEWORK.md`](../FRAMEWORK.md) Phase 2 as action items, **not here**. The VPN-coexistence
test is the clearest example: no amount of web research answers it, only a phone does.

## Current handoff queue

Pulled from open items flagged across this packet. Item 2 blocks the release; the rest inform
Track B.

| # | Question | Source doc | Suggested engine | Why that engine |
| - | -------- | ---------- | ---------------- | --------------- |
| ~~1~~ | ~~MPL-2.0 vs Apache-2.0 vs GPLv3 for an app that downloads third-party hosts files~~ — **closed 2026-08-15 without research**: MIT chosen (D-015). | O-001 | — | ✅ Resolved |
| ~~2~~ | ~~StevenBlack/HaGeZi/OISD/Block List Project/EasyList license and redistribution terms~~ — **closed 2026-08-17**, raw output in [`research/2026-08-17-filter-list-licensing-claude.md`](research/2026-08-17-filter-list-licensing-claude.md), merged into `risks.md` and `work/W11-source-provenance.md`. Answer: W11 is a **go** under runtime download; EasyList dropped (ABP syntax, not a domain list); StevenBlack must never be vendored (non-commercial upstreams); HaGeZi/OISD are GPL-3.0 and no longer publish hosts format — their bare-domain variants parse with the existing `HostsParser` unchanged. | `risks.md`, B6 | — | ✅ Resolved |
| ~~3~~ | ~~Shizuku non-root capability in 2026~~ — **closed 2026-08-17**, raw output in [`research/2026-08-17-shizuku-nonroot-claude.md`](research/2026-08-17-shizuku-nonroot-claude.md), merged into `work/W12-shizuku-enforcement.md`. Answer: suspension works on non-root (shell holds `SUSPEND_APPS` per the AOSP Shell manifest); restart-after-reboot still required per the live manual, keeping it friction-not-prevention; one residual spot-check (app release recency) left for W12's implementer. W12 unblocked. | `approach.md` B7 | — | ✅ Resolved |
| ~~4~~ | ~~`UsageStatsManager` behaviour, grant flow, accuracy limitations~~ — **closed 2026-08-17**, raw output in [`research/2026-08-17-usagestatsmanager-claude.md`](research/2026-08-17-usagestatsmanager-claude.md), merged into `work/W10-app-budgets-usagestats.md`. Answer: use `queryEvents` (RESUMED→PAUSED intervals, delta-windowed), not `queryUsageStats` aggregates; PiP doesn't accrue; grant is a Settings deep-link with an AppOps check. W10 unblocked. | `approach.md` B3 | — | ✅ Resolved |
| 5 | What are F-Droid's current requirements for inclusion — reproducible builds, anti-feature labelling for apps using `VpnService` and `AccessibilityService`, and the review process for a first submission? | O-005, `risks.md` | Claude | Policy documents plus community practice; needs sourcing |
| 6 | What is Google Play's current policy for apps that combine `VpnService` and `AccessibilityService`? Is such an app publishable at all, and under what declarations? | `risks.md` | Claude | Play policy is dense, changes often, and heavily penalises getting it wrong |
| 7 | Is the claim that Play will require `targetSdk` 36 from 2026-08-31 accurate? (Flagged unverified in `audits/findings.md` §4, and currently moot — the plan is GitHub Releases.) | `audits/findings.md` | Either | Single factual check |
| 8 | Does Android offer any mechanism for VPN coexistence, or app-specific routing that would let a filtering app cohabit with a user's real VPN? Has anything changed in recent releases? | `approach.md` B4 | Either | If the answer is "no, one slot, period," that confirms the design constraint permanently |
| ~~9~~ | ~~How do comparable FOSS tools (RethinkDNS, NetGuard, personalDNSfilter, AdAway) handle release signing and key custody for GitHub/F-Droid distribution? Any established practice worth copying?~~ — **closed 2026-08-15**, raw output in [`research/2026-08-15-signing-key-custody-prior-art-claude.md`](research/2026-08-15-signing-key-custody-prior-art-claude.md), merged into `signing-key-custody.md`. Answer: 4/4 hand-sign production; none documents escrow. | O-002 | — | ✅ Resolved |
| 10 | What are the current SafeSearch hostnames for Google, Bing, DuckDuckGo and Yandex, and do they still work in 2026? Separately (parked, P-002): does YouTube restricted mode still require the `YouTube-Restrict` HTTP header, which a DNS-layer filter cannot set? How do AdGuard Home and Pi-hole implement the rewrite, specifically the answer-name handling? | `docs/work/W14`, D-016, `parked.md` | Claude | Several vendor behaviours to verify against current docs, plus two mature open-source implementations to read; model priors on this are likely stale |
| 11 | **Investigative, not a build authorization.** What does CloudVeil (and any comparable open-source project) actually do for on-device NSFW image/content classification — model size, battery/CPU cost, false-positive rate, and whether inference genuinely stays on-device with nothing uploaded? This exists to check whether `docs/parked.md` P-003's rejection of any content-inspection engine still holds now that real prior art can be read instead of reasoned about from first principles. Bring findings back to P-003 and D-016 for a human to weigh — a good implementation existing elsewhere is not itself a reason to build one here. | `docs/parked.md` P-003, D-016 | Either | Needs current, specific technical detail (model size, measured battery cost) that general knowledge won't have reliably |

**Full prompts already written:** [`research/prompts.md`](research/prompts.md) has a
ready-to-paste, fully self-contained prompt for every open row below — heavier than the generic
template further down, because each states what decision the answer feeds and what would change
as a result. Use those instead of writing your own from the table row.

**Q9 sharpened row 5 (2026-08-15).** F-Droid's default is to generate and hold *its own* per-app
signing key, so an F-Droid listing without reproducible builds creates a second, permanently
un-migratable app identity. Whoever runs row 5 should treat "what does reproducible-builds
eligibility actually require for this build?" as the load-bearing half of that question, not a
footnote to the anti-feature labelling — and it wants answering *before* the first signed APK
exists, not at submission time. See `signing-key-custody.md` §"A fifth question the research
surfaced".

## Handoff procedure

1. **Pick one row** — don't batch unrelated questions into one session; it dilutes the result and
   makes sourcing harder to track.
2. **Write the prompt** using the template below and run it in the browser per the suggested
   engine. Ask for citations explicitly — an answer without a source is not more trustworthy than
   what's already in these docs.
3. **Save the raw output** into [`research/`](research/) before doing anything else with it.
4. **Merge findings back** into the originating doc: replace the unverified language with the
   finding and add a citation line pointing at the saved file. Don't delete the fact that it was
   once unverified — the audit trail of assumed vs. confirmed is useful later.
5. **Verify anything it says about this codebase** before acting on it. The last research cycle
   got the block path wrong in all five documents, and acting on it would have re-introduced a
   fixed battery regression.
6. **Update this queue** — mark the row resolved and add any new questions it surfaced.

## Prompt template

```
I'm scoping a project: Focus Fence, an open-source Android app that blocks distracting
websites and apps using a DNS-only local VpnService plus an accessibility service. Fully
on-device — no accounts, no servers, no telemetry. It's distributed by sideloading from
GitHub Releases. Kotlin, minSdk 26, targetSdk 34.

Research question: {{paste the question from the handoff queue table}}

Please answer with current, sourced information — include links or citations for every
claim, note publication/last-verified dates where relevant, and flag anything that's
opinion or estimate rather than sourced fact.
```

## `research/` folder structure

```
research/
  YYYY-MM-DD-<short-topic-slug>-<engine>.md
```

Each file starts with the exact prompt used, the engine and the date, then the raw response.
Don't edit the raw response after saving — corrections go in the doc that consumes it.

The five documents in [`audits/`](audits/) are the output of an earlier cycle, saved before this
convention existed. They stay where they are.
