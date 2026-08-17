# Risks

> **Tier:** Mixed — scope/dependency/legal sections are high-level (decision-maker,
> plus a lawyer where relevant); technical/operational sections are low-level
> (implementer) · **Audience:** decision-maker for the whole doc, whoever
> builds/operates for the technical section · **Use when:** before committing to the
> approach (scope/legal section), before shipping (operational section), and as an
> ongoing check during build (technical section). Legal-research candidates are
> flagged in [`research-handoff.md`](./research-handoff.md).

## Scope / dependency risk

**B2 is where scope balloons.** "Boundaries are the product" is a framing, not yet a design, and
it implies the most expensive change proposed: a new data model, a persistence migration for
existing users, and most of the UI rewritten. The specific failure mode is building a general
policy engine when four rule modes applied to apps as well as domains would have closed most of
the coverage gap. D-014 exists to guard this, and `approach.md` recommends sketching all six
boundary examples against the model on paper before writing code. **Mitigation:** if the model
collapses into "the same rules with a wrapper," skip B2 and do B3 alone.

**Browser address-bar view IDs are the least stable dependency in the project.** `URL_BAR_IDS`
maps ten browsers to specific view IDs. Browsers can rename or restructure those nodes in any
release, silently, with no error — section rules just stop working. There is a bounded heuristic
fallback (250 nodes), but it's a heuristic. This is permanent maintenance that scales with the
number of browsers supported, and no test in this repo covers it.

**Filter lists are an external dependency with no pinning.** The ad list is downloaded from
StevenBlack at runtime. If the URL moves, the format changes, or the list's licensing terms
change, the feature breaks or becomes a licensing problem. B6 (multiple sources) multiplies this
by the number of sources.

**Android platform behaviour is outside this project's control.** `VpnService` semantics,
accessibility service policy, and `UsageStatsManager` are all subject to change between platform
versions, and the app's three enforcement layers each depend on one of them.

**Two-person bus factor**, one of whom is the original author and not currently active in
planning. Every unresolved question in `notes.md` that requires @sidewinderzz is blocked on a
single person.

## Legal / licensing risk

**Not legal advice.** Everything below is reasoning that needs real review before anything is
distributed publicly.

**No `LICENSE` file — the largest open item.** Public source is not a license grant. As things
stand nobody may legally use, redistribute or fork this, and every outside contribution so far has
arrived with no terms attached. This becomes acute the moment a binary is published. Candidates
(MPL-2.0, Apache-2.0, GPLv3) and their tradeoffs are in
[`audits/03-foss-ecosystem.md`](audits/03-foss-ecosystem.md); the executive summary's
compatibility table has errors and should not be used (see `audits/findings.md` §2.8). Copyright
is shared, so this needs @sidewinderzz.

**Filter-list licensing — verified 2026-08-15…17 (research Q2), no longer an unknown.** The terms
have now actually been read, from primary sources:
[`research/2026-08-17-filter-list-licensing-claude.md`](research/2026-08-17-filter-list-licensing-claude.md).
Short version: HaGeZi and OISD are GPL-3.0, Block List Project is public domain (Unlicense), and
all three are unambiguous for the app's runtime-download model. StevenBlack is MIT *as a
compilation* but its base unified list carries upstream data under **non-commercial** terms
(MVPS CC BY-NC-SA 4.0; Dan Pollock "non-commercial with attribution") — fine for an end-user's
device downloading it, **not** fine to vendor into the repo or APK; its porn extension's sources
are all MIT/CC BY. EasyList is dual GPLv3/CC BY-SA 3.0 but structurally an ABP filter list, not a
domain list — excluded from B6/W11. **The standing rule survives: keep downloading at runtime,
never vendor a list into the repo without re-reading that file's per-source table first.**

**Distribution-channel policy is unverified.** Google Play has specific and strict policies for
both `VpnService` and accessibility services. This is currently moot — the plan is GitHub
Releases, not Play — but it constrains any future decision to publish there, and F-Droid has its
own requirements including anti-feature labelling. Both queued for research handoff.

**~~The `AdListStore` provenance comment is inaccurate~~ — resolved by W11 (2026-08-17):**
`AdListStore` no longer exists; the per-source `FilterSources` catalog describes each list
accurately (verified against primary sources in research Q2) and the misattributed
"same sources AdGuard/uBlock use" claim is gone from shipped source.

## Technical risk

**The release build has never been run.** It compiles with R8 (verified 2026-08-14) and installs
nowhere, because it's unsigned. R8 can strip things that only fail at runtime. The first person to
install a signed release build will be the first person to find out. **Mitigation:** R4 (CI builds
release) plus a device install, both cheap, both in Phase 2.

**Zero coverage of the two long-running services.** All automated testing is JVM-only over `dns/`.
The VPN service and accessibility service — where every battery regression to date has originated
— have none. Two battery regressions have already shipped and been fixed (`0552f11`, and the
earlier blocking-tun-read fix). Nothing prevents a third.

**Battery is a product requirement with no instrument.** Any change to the DNS hot path or the
accessibility event filter is currently unfalsifiable — it can be argued about but not measured.
This is why B8 exists and why `approach.md` puts the benchmark before anything in Tier 3.

**The purity boundary is soft in exactly one place**, `DnsVpnService.ruleBlocks()`, which mixes
observation, policy evaluation and enforcement. It's also the code most likely to need changing
for B2/B3. B1 addresses it directly.

**Bypass vectors are known and partly unaddressed**, and this is acceptable under the threat
model (D-012) but should be honest in user-facing docs: Android's Private DNS setting bypasses the
filter entirely; DoH guards are a fixed list of bootstrap hostnames that any other host can
substitute for; already-resolved domains work until their TTL expires; apps that hardcode IPs are
unaffected. None of these defeat an impulse; all of them defeat a determined owner, who is
explicitly not the adversary.

## Operational risk

**The trust problem is the biggest operational risk, and it's a communication problem rather than
a technical one.** Focus Fence asks for `VpnService` **and** `AccessibilityService` — the single
most abusable permission pair on Android, and exactly the combination malware wants. A stranger
sideloading an unsigned-origin APK is right to be suspicious, and "it's open source" doesn't clear
it on its own, because almost nobody reads the source and the APK isn't obviously derived from it.

What actually helps, in increasing order of effort:

1. A plain-language statement of what each permission is used for and what it deliberately does
   *not* do. Cheap, and it's the first thing a careful user looks for.
2. CI-built releases, so the binary is traceable to a public commit rather than someone's laptop.
3. Publishing the APK's SHA-256 alongside the release.
4. Reproducible builds, and/or F-Droid, where a third party does the building. Highest effort,
   highest trust payoff.

**Permanent maintenance commitments** the project takes on the moment it has real users: browser
compatibility (above), filter-list availability, Android version churn across three platform APIs,
and support for false positives. A user allowlist exists (W9, D-017), with UI to add/remove
entries in `BlocklistFragment` — a broken site no longer requires disabling the entire ad list.

**Support expectations for a wellbeing tool are unusual.** People rely on this to hold a boundary.
A bug that silently stops enforcement is worse than a crash, because nothing tells the user it
stopped working — and two of the four enforcement paths already fail silently when another VPN
takes the slot.

**Sustainability.** Two people, no revenue, no infrastructure cost. Nothing here needs funding,
which removes a whole class of risk; the constraint is attention, and the honest mitigation is
keeping scope narrow enough that the project survives inattention. The existing architecture is
well suited to that — it does little, cheaply. B2 is the item most likely to compromise it.
