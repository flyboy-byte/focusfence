# Approach

> **Tier:** High-level (decision input) · **Audience:** decision-maker ·
> **Use when:** deciding go/no-go on the approach, and planning what to validate before
> spending real time. Several open items here are flagged for handoff to external deep
> research — see [`research-handoff.md`](./research-handoff.md).

Unusually for this kind of doc, much of the approach is already built and running. The claims
below about *the existing code* have been verified against source. The claims about *what to do
next* are working hypotheses.

## The gap, measured against the project's own examples

[`overview.md`](./overview.md) lists six example boundaries. Scoring the code against them is the
most honest assessment available, because they're the author's own words rather than a competitor
checklist. Verified against the tree 2026-08-14:

| Boundary | State | Why |
|---|---|---|
| Block ads/trackers | ✅ Ships | Single hosts-file toggle |
| Block Reddit until after work | ⚠️ Works, but VPN-dependent | Schedules evaluated in `DnsVpnService.ruleBlocks()` |
| Limit Instagram to 20 minutes | ⚠️ Domain only, VPN-dependent | Domain budgets exist; the *app* has none |
| YouTube but not Shorts | ⚠️ Browser only | Section rules read browser address bars; can't see inside the YouTube app |
| Block adult content always | ❌ Not possible | One ad list, no categories; only manual per-domain entry |
| TikTok during a commitment period | ❌ Not possible | `BlockedAppsStore` is a bare `Set<String>` — app blocking has **no time dimension at all** |

**Two of six are impossible, three are partial.** Three structural findings sit behind that:

**Apps are second-class.** Domains carry four rule modes (always / daily window / daily budget /
section allowlist). Apps carry one: blocked, forever. Since most self-control boundaries aim at
apps rather than websites, most of them cannot be expressed.

**Half the enforcement dies with the VPN.** Schedules and budgets are evaluated inside
`DnsVpnService`. App blocking and section rules run in the accessibility service and survive
independently. So "the rest keeps working when another VPN owns the slot" is currently **only
half true** — and the half that breaks is the half a developer would notice immediately. The
recorded intent (D-013) is what makes this a bug rather than a limitation.

**There is no boundary object.** The intent says boundaries are the product, but the code has no
such thing. "No TikTok during a commitment period" would today be three unlinked settings in
three separate stores with no shared identity, and nothing can answer "what am I currently
committed to?"

## Where the code contradicts its own stated principle

The principle is *use the least invasive mechanism that can enforce a boundary*, and *avoid
observing what you don't need*.

Whole-app blocking is currently enforced by the accessibility service, which means Focus Fence
**reads browser address bars** — full URLs, across ten browsers — and watches window events. That
is the most invasive observation the app performs.

For *section rules* it is genuinely necessary: DNS sees hostnames, TLS hides paths, so nothing
cheaper can see `/shorts`. For *whole-app blocking* it is not. Shizuku package suspension enforces
harder while observing almost nothing.

This reframes Shizuku. [`plans/00-roadmap.md`](plans/00-roadmap.md) files it under Tier 3 as an
*advanced capability*; under the recorded intent it is a **privacy improvement** — less
observation, more enforcement — which is a considerably stronger justification than "more power."

## Alternatives considered

**Do nothing / use an existing tool.** Genuinely realistic, and the research
([`audits/01-product-definition.md`](audits/01-product-definition.md)) argues for it hardest.
RethinkDNS, NetGuard and personalDNSfilter cover DNS filtering well; several wellbeing apps cover
timers. What none of them combine is per-path section rules, budgets, and a stated refusal to
fight the device owner. The counter-argument is real and should stay on the table: if the boundary
model (below) proves to be over-abstraction, "keep it small and personal" is a legitimate landing
place.

**Full-TUN packet filtering.** Rejected. Discards the property that makes the app cheap, and
battery is an explicit product requirement. It would also make Focus Fence a worse NetGuard
without making it a better wellbeing tool.

**HTTPS/TLS interception.** Rejected on principle rather than feasibility. It would let the
process read plaintext no boundary above requires — a direct violation of *avoid observing what
you don't need*. (It also fails technically: apps targeting API 24+ don't trust user CAs, pinning
defeats the rest, and QUIC/ECH removes the metadata passive inspection relied on.)

**Device Owner provisioning.** Rejected as a default. It fights a determined owner; the stated
adversary is an impulse. It also generally requires a factory-reset device. Possibly interesting
as an opt-in dedicated-device experiment, never a default.

**A general policy engine / SDK first.** Rejected as premature (D-014). The abstractions have to
prove their value in the app before being generalised.

## The proposed direction

In dependency order. Each item is justified by a named line of the recorded intent, not by the
external research.

**B1 — Split policy out of the VPN service.** Move schedule evaluation, budget accrual and the
front-door check from `DnsVpnService.ruleBlocks()` into the pure `dns/` package behind an injected
clock. Directly implements *separate observation, classification, policy and enforcement*; makes
the whole rule surface JVM-testable with no behaviour change; and is the prerequisite for B2–B4.
Cheapest structural step available and worth doing on its own merits.

**B2 — A boundary as a real object.** One user-visible boundary spanning domains and apps,
carrying a time dimension, replacing three parallel stores. The change that makes the six examples
expressible. Design it around those six examples, not around a general policy language.

**B3 — Time rules for apps.** Windows and budgets for apps, not just domains. Falls out of B1+B2
almost for free. Needs `UsageStatsManager` for honest app timing — real foreground time, no VPN
required, observes package and duration rather than content, which fits the least-invasive rule
well.

**B4 — Enforcement that survives without the VPN.** Make the promise true and *test* it.

**B5 — Allowlist.** Escape hatch for false positives; prerequisite for B6. Core (store, pure
evaluator, D-017) shipped 2026-08-15 (`docs/work/W9-allowlist.md`); UI still open.

**B6 — Categories with provenance.** Multiple sources retaining which source blocked what, so
"block adult content" becomes expressible and the app can answer *why*. Justified by the boundary
table, not by performance — audit 03 found the current `HashSet` adequate at ~100k entries.

**B7 — Shizuku as the preferred app-blocking mechanism**, accessibility as fallback.

**B8 — Benchmark harness.** Battery is a product requirement and nothing measures it.

## What needs to be validated before investing real time

In rough priority order:

1. **The VPN-coexistence test.** Install PCAPdroid or WireGuard, let it take the VPN slot, see
   what Focus Fence still enforces. One afternoon on a real device. It tests the product's own
   stated promise, and the result sets the priority of B1 and B4. Predicted to partially fail.
2. **Whether the boundary model is real or over-abstraction.** B2 touches persistence, migration
   and most of the UI — the most expensive item proposed here. Before building it, sketch all six
   examples against the proposed model on paper and check it doesn't collapse into "the same
   rules with a wrapper."
3. **Shizuku's actual current capability** on a non-root device — whether package suspension works
   as assumed, and what it costs the user in setup friction. Queued for research handoff; the
   answer decides whether B7 is a real option or a footnote.
4. **That the R8-minified release build actually runs.** It compiles; it has never been installed.

## Cost side

Estimates, stated as such.

- **Track A (release):** small and mostly mechanical. Eight steps, most of them one file each.
  The real cost is two decisions requiring @sidewinderzz, not engineering time.
- **B1:** small. A refactor with no behaviour change and existing test coverage to lean on.
- **B2:** by far the largest item. Data model, persistence migration for existing users, and most
  of the UI. Weeks rather than days, and the one place scope could run away.
- **B3–B8:** individually moderate; each depends on B1 or B2 landing first.
- **Ongoing:** browser address-bar view IDs are a compatibility surface that breaks whenever a
  browser restructures. Filter lists need update scheduling. Both are permanent maintenance, not
  one-time cost.
- **Monetary:** none. No servers, no accounts, no infrastructure. CI is free on a public repo.

## Time-to-first-real-signal

Two days, and neither day requires writing app code:

**Day one** — the VPN-coexistence test plus installing the R8 release build on a device. Both
answer questions currently being reasoned about rather than known.

**Day two** — Track A steps 1 and 3 (README corrections, CI builds the release). No decisions
needed, no behaviour change, and step 3 permanently closes the "the release build is never
exercised" gap.

## Bottom line (working hypothesis, not a conclusion)

The architecture is sound and the philosophy is unusually coherent — the DNS-only routing, the
pure `dns/` layer and the scoped accessibility service are the parts worth protecting, and all
four audits independently agree. The recorded intent is a genuinely good sorting key: applied
honestly, it rejects three proposals on principle and promotes one (Shizuku) for a better reason
than the research had.

The biggest unresolved question is **B2**. "Boundaries are the product" is a compelling framing,
but it is currently a framing rather than a design, and it implies the most expensive change on
the list. If it turns out to be a wrapper around the rules that already exist, the honest move is
to skip it and just give apps the four rule modes domains already have (B3), which closes most of
the coverage gap for a fraction of the cost.

Validate that before building it.
