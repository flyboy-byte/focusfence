# Suggested sequencing

**These are suggestions, not decisions.** They follow from
[`../audits/findings.md`](../audits/findings.md) — only from claims that survived
verification — and every one of them is separable. Take any, reorder them, drop the rest.

Nothing here has been implemented. This PR adds documentation only.

The ordering principle is boring on purpose: fix things that are *wrong* before adding things
that are *new*, and keep anything that would touch the DNS hot path or the accessibility
event filter behind a measurement.

---

## Tier 0 — free, and true regardless of direction

Small, uncontroversial, and independent of any architectural argument.

**Add a `LICENSE` file.** The repo has none. Public source is not a license grant, so as
things stand nobody can legally reuse this, and outside contributions have no terms. The
audits converge on **MPL-2.0** or **Apache-2.0**; audit 03 §"Licensing" lays out the tradeoff
properly (roughly: MPL keeps improvements to the engine open while allowing embedding;
Apache maximises reuse and adds a patent grant; GPLv3 aligns with most of the surrounding
filtering ecosystem but constrains reuse as a library). Note that the exec summary's
compatibility table has errors — see findings §2.8 — so don't decide from that table.

**Correct three stale statements in the repo's own docs.** All three are checked and
confirmed, and the accurate versions are more convincing than the current ones:

- README: "nothing about your browsing ever leaves your phone" → allowed DNS queries do go to
  1.1.1.1 / 8.8.8.8. The precise claim is that *policy evaluation and filtering are local,
  there is no account, no policy server and no telemetry, and ordinary DNS resolution still
  reaches the configured resolver.*
- README: "battery and throughput impact is effectively zero" → true as an expectation,
  unmeasured as a fact. Say why the architecture is cheap; don't state a result.
- README and `DnsVpnService.kt:47-48`: blocked queries are described as NXDOMAIN, but have
  been cacheable `0.0.0.0`/`::` since `0552f11`. This one actively misleads — it is what led
  all five audit documents to describe the block path wrongly.

**Fix the `AdListStore` provenance comment.** StevenBlack is an aggregator of hosts sources;
it is not "the same sources AdGuard/uBlock use."

---

## Tier 1 — the gap the audits assumed was already filled

**A user allowlist.** Multiple documents reason as though local overrides exist. They don't
(findings §3). Today a single false positive in a ~100k-entry downloaded list can only be
resolved by disabling the entire ad list.

This is small, it is the highest-value-per-line change available, and it is a hard
prerequisite for every "multiple sources and categories" proposal — the moment a second list
exists, false positives stop being hypothetical. It fits the existing model directly: another
prefs-backed set consulted at the top of `blockReason()`, ahead of the ad hosts and guards.

Worth deciding explicitly: should an allowlist entry override a *user rule* too, or only
list-sourced blocks? The conservative answer is only list-sourced blocks, so an allowlist can
never silently defeat a deliberate boundary.

---

## Tier 2 — structural, and genuinely contested

Each of these is defensible and each has a real cost. They are listed in dependency order,
not priority order.

**Move `ruleBlocks()` out of the VPN service.** The one place the `dns/` purity boundary is
soft. Schedule evaluation, budget accrual and the front-door check live inline in
`DnsVpnService`, which means they can only be tested through the service. Moving the decision
into `dns/` behind an injected clock would make the whole rule-evaluation surface JVM-testable
with no behaviour change. This is the cheapest step toward everything below it, and it is
worth doing on its own merits even if nothing below happens.

**Filter sources with provenance and categories.** Audits 01 and 03 make the strongest
version of this argument, and it isn't primarily about performance: flattening every source
into one `HashSet<String>` destroys the information needed to answer "why was this blocked?"
The payoff is explainability — *blocked because HaGeZi NSFW → Adult policy* rather than
*blocked* — plus per-source enable/disable and honest license tracking per source. The cost
is real: a source model, update scheduling per source, storage, and UI. Audit 03's scale
analysis concludes the current `HashSet` is entirely defensible at ~100k entries, so **do
this for provenance, not for speed**, and don't let it become a rewrite of the matcher.

**UsageStats for app-level time.** Audit 02's strongest recommendation. DNS-inferred timing
is a genuinely clever low-observation trick, but it cannot measure app time, and it stops
working when the VPN is off. `UsageStatsManager` gives real foreground/background timing,
needs no VPN, and observes package/time rather than content. It would make per-app budgets
possible — which the exec summary lists as P0 and which genuinely does not exist. Requires
`PACKAGE_USAGE_STATS`, which is a user-granted special access.

**Keep the DNS-inferred estimate for domains**, where nothing cheaper can work, and label it
as an estimate in the UI.

---

## Tier 3 — needs a measurement first, by its own logic

Every audit lands on the same principle, and it is the most useful thing they collectively
produced:

> A capability earns inclusion when it demonstrates value against its privacy, battery,
> complexity and interoperability cost.

Applying that honestly means the project cannot argue about battery at all right now, because
there is no benchmark in the tree. Before any of the following, there should be a
reproducible baseline — even a crude one — so claims can be checked rather than asserted:
Focus Fence installed but idle; DNS VPN with no lists; DNS VPN with realistic lists;
accessibility app-blocking only; accessibility with browser path rules.

That harness is itself a defensible piece of work, and it is what would let the README make a
battery claim as a *result*.

Behind it, in descending order of how well the audits support them:

- **Shizuku package suspend/disable** — stronger enforcement with *less* observation than the
  accessibility bounce it would replace, and naturally reversible because non-root Shizuku
  must be restarted after each reboot. Audit 02 rates this the most promising advanced
  capability.
- **Making the VPN properly disposable** — the developer-facing promise that schedules,
  budgets, app rules and accessibility enforcement all keep working while PCAPdroid or another
  VPN owns Android's single VPN slot. Mostly already true; needs stating and testing.
- **Multi-category sources** (adult, gambling, social as distinct toggles) — depends on the
  source model above.
- **Native-app UI rules** (e.g. Shorts inside the YouTube app) — explicitly experimental, and
  a per-app-version compatibility burden.

---

## Explicitly not recommended

All four audits independently reach these, and they agree:

- **HTTPS/TLS interception.** Apps targeting API 24+ don't trust user-added CAs, pinning
  defeats what remains, and QUIC/ECH removes the metadata passive inspection relied on. It
  would also give the process the ability to read plaintext the rest of the product has no use
  for. Audit 02's framing is the right one: don't ask what it could block, ask which validated
  boundary is impossible without it — and until there's an answer, it stays out.
- **Full-TUN packet filtering.** Would make Focus Fence a worse NetGuard/Rethink and a better
  firewall, without making it a better wellbeing tool. It also discards the one property that
  makes the current design cheap.
- **DNS-level Shorts blocking.** Wrong abstraction — the path isn't visible to a resolver.
  The existing accessibility path rule is the correct mechanism.
- **Competing with uBlock Origin or AdAway on ad blocking.** Domain filtering is a cheap
  byproduct of machinery that exists for other reasons; it shouldn't become an identity.
- **Device Owner as a normal "strict mode."** Provisioning generally requires a factory-reset
  device. Interesting as a deliberate dedicated-device experiment, wrong as a product default
  — and squarely against the developer-friendliness the project cares about.
