# Architecture as built

A description of how Focus Fence works today, aimed at someone about to change it. No
proposals here — those live in [`plans/`](plans/). Where a design looks odd, the reason is
given, because several of the odd-looking parts are deliberate fixes for problems that
already happened.

## The central bet

Focus Fence registers a `VpnService`, but it is not a VPN in any useful sense. The tunnel
advertises two synthetic DNS servers and routes **only those two addresses**:

```
addAddress 10.111.222.1/24        addAddress fd66:f0cf:0:1::1/64
addDnsServer 10.111.222.3         addDnsServer fd66:f0cf:0:1::53
addRoute 10.111.222.3/32          addRoute fd66:f0cf:0:1::53/128
```

Everything that is not a DNS packet addressed to those two hosts takes the ordinary network
path and never enters the process. That single decision is what makes the app cheap to run,
and it is the constraint every other part of the design is shaped around. Widening those
routes — to `0.0.0.0/0`, or to intercept anything other than DNS — changes the app into a
different kind of program with a different cost profile.

The IPv6 half is wrapped in a `try`/`catch`: some devices reject the v6 tunnel config, and
the service continues IPv4-only rather than failing to start.

## Request path

```
tun fd (blocking)
   │
   ├─ Packets.parseUdp        IPv4/IPv6 + UDP, rejects fragments and v6 extension headers
   ├─ dstPort != 53           → dropped
   ├─ Dns.parseQuestion       single-question queries only; responses and malformed → null
   │
   ├─ blockReason(qname)  (Blocklist.blockReason — pure, dns/)
   │     ├─ user rule matches and is in force   → rule.domain   (final — see D-017)
   │     ├─ qname in the user's allowlist       → null, regardless of what follows
   │     ├─ qname in downloaded ad hosts        → "_ads"
   │     ├─ qname is a DoH bootstrap host       → "_guard"
   │     └─ otherwise                           → null
   │
   ├─ blocked  → Dns.buildBlockedResponse, written straight back to the tun
   └─ allowed  → handed to a 4-thread pool, relayed to a real resolver over a
                 protect()ed socket, reply written back to the tun
```

Two details in there are easy to "fix" into a regression:

**The tun fd is explicitly blocking.** `establish()` returns a non-blocking fd by default,
which turns the reader loop into a busy spin that pins a core.

**Blocked queries are not answered NXDOMAIN**, despite what the README and one KDoc comment
still say. A bare NXDOMAIN carries no SOA, so clients treat it as uncacheable and re-ask
continuously — this was measured waking the service thousands of times a day. Blocked A/AAAA
queries now get a real answer of `0.0.0.0` / `::` with an explicit TTL; other query types get
NODATA. TTLs are split deliberately: **1 hour** for ad and DoH-guard blocks, which are stable,
and **60 seconds** for user rules, which change as schedules and budgets do.
`Dns.buildNxDomain` still exists but is no longer on the block path.

## Layering

```
dns/     pure Kotlin, no Android imports, unit-tested on the JVM
data/    thin SharedPreferences wrappers
vpn/     the VpnService — packet loop, upstream relay, stats, budget accrual
accessibility/  the AccessibilityService — section rules and app blocking
ui/      fragments + the domain editor
```

The `dns/` boundary is real and worth keeping: `Blocklist`, `BlockRule`, `RuleEvaluator`, `Dns`,
`Packets`, `UrlFilter`, `UsageTracker`, `StrictLock`, `StatsCodec` and `HostsParser` take no
Android types, which is why the test suite runs as plain JUnit with no instrumentation. New
decision logic belongs there, with the caller supplying clocks and timestamps.

**`RuleEvaluator`** (added in W6, extended in W7) is the actual "is this rule in force right
now" decision — `blocks()` for domains, `blocksApp()` for app targets — and it's pure: the
caller supplies a `Now` (minute of day, epoch millis, day key, screen-on) and any accrued
usage. `DnsVpnService.ruleBlocks()` is a thin wrapper that only *assembles* those inputs
(reads the clock, looks up accrued usage) and calls out to `RuleEvaluator.blocks()`; it does
not hold decision logic itself. `Blocklist.blockReason()` (added in W9) is the next layer up
— it takes the rule-evaluation result plus the allowlist and ad-host sets and applies the
final ordering (D-017), and is itself pure and unit-tested.

## Rules

One `BlockRule` per domain (or, for app rules — W7 — per package name, using the same
encoding), in exactly one of five modes, serialised into a single `StringSet` preference:

| Mode | Encoding | Enforced by |
|---|---|---|
| Always | `reddit.com` | DNS (or accessibility, for an app target) |
| Daily window (may wrap midnight) | `reddit.com\|540\|1080` | DNS / accessibility |
| Daily budget in minutes | `reddit.com\|b\|30` | DNS, once exhausted (domains only — see W10) |
| Allowed section paths | `facebook.com\|p\|/marketplace` | accessibility service only |
| Commitment (blocks until a fixed instant) | `reddit.com\|c\|<epochMillis>` | DNS / accessibility |

Rules apply to subdomains: matching walks up parent domains (`a.b.example.com` →
`b.example.com` → `example.com`), so one entry covers unknown subdomains without enumerating
them.

Section rules are the exception that shapes the rest. DNS sees hostnames and TLS hides paths,
so a domain with allowed sections **must stay resolvable** — `RuleEvaluator.blocks()` returns
false for those, and enforcement moves entirely to the accessibility service reading the
browser's address bar. That is also why they can't work inside native apps.

An **allowlist** (W9, D-017) sits alongside rules, not inside `BlockRule` — it's a plain set
of domains in `AllowlistStore`, checked in `Blocklist.blockReason()` after the rule check.
It exempts a domain from the ad list and the DoH bypass guards, but a user rule that is
currently in force always wins; see the request-path diagram above.

## Configuration propagation

There is exactly one mechanism. Both long-running services hold `@Volatile` snapshots of the
rules and refresh them from a `SharedPreferences.OnSharedPreferenceChangeListener`:

```
UI writes prefs → listener fires → service swaps its snapshot
```

Anything the runtime depends on must therefore be written to the **`focusfence`** prefs file
and handled in those listeners. `BlockedAppsStore` and `ExcludedAppsStore` live in that file
for exactly this reason even though they aren't rules. `FilterListStore` writes its revision
counter (`KEY_REV`) *last*, so that write doubles as the reload signal. Stats are the deliberate
exception: they live in a separate `focusfence_stats` file so their high write volume doesn't
churn the rule listeners.

## Time budgets

`UsageTracker` infers usage from DNS activity — while a site is actively used the device keeps
re-resolving its domains, so lookups arrive a few minutes apart at most. Each lookup within
3 minutes of the previous one counts the gap as usage; a longer gap starts a fresh session and
contributes nothing.

This is an estimate and should be described as one. Long-lived DNS caches undercount; a site
left open in the foreground still accrues.

Two guards keep it honest, and both have tests:

- **Screen-on only** — accrual is gated on `PowerManager.isInteractive`, so background
  refreshes don't drain a budget. A screen-off lookup also breaks the session rather than
  extending it.
- **Front doors only** — budgets accrue and enforce only on a site's user-facing hostnames
  (bare, `www.`, `m.`, `web.`, `mobile.`, `touch.`). Hidden subdomains like
  `graph.facebook.com` carry other apps' SDK traffic; counting them drained budgets from the
  background, and blocking them broke unrelated apps.

Usage is written through to prefs at most every 15 seconds per domain, and saving a budget
rule resets that day's count — which doubles as the escape hatch when tracking miscounts.

**App targets (W7) reuse windows and commitments, not budgets.** `RuleEvaluator.blocksApp()`
handles daily windows and commitment rules for a package the same way it does for a domain,
but deliberately returns `false` (permissive, not blocked) for a budget rule on an app target
— there is no DNS activity to infer foreground time from for a native app, and treating an
unsatisfiable budget as always-blocked would be a silent trap. Real app budgets need
`UsageStatsManager` (W10), not usage inference.

## Accessibility service

Handles the two things DNS cannot: per-path section rules, and whole-app blocking (bounce to
home). Both work by reacting to UI events, not by OS-level blocking.

The event filter is narrowed aggressively, and this is a battery matter rather than a
stylistic one — the service tells the system to wake it for as little as possible:

- `packageNames` is restricted to the browsers it knows plus the user's blocked apps; with
  nothing configured it listens only to its own package, i.e. effectively nothing.
- `TYPE_WINDOW_CONTENT_CHANGED` — the high-volume one — is requested **only** while at least
  one section rule exists.
- `notificationTimeout` batches delivery; URL checks are throttled; Back and Home actions are
  debounced.
- The fallback hierarchy search for unknown browsers is bounded to 250 nodes.

Address bars are located by known view ID per browser, with a heuristic fallback. This is a
compatibility surface, not a stable API: browsers can rename or restructure those nodes at any
release.

## Strict mode

`StrictLock` is a pure state machine — Off / Locked / Pending / Unlocked. Loosening anything
requires requesting an unlock, waiting 10 minutes, then acting inside a 5-minute window.
Adding new blocks is always allowed.

Enforcement is **distributed across call sites**, not centralised: `DnsVpnService` refuses
`ACTION_STOP` while locked, and each UI path that could loosen a rule checks
`StrictStore.isLocked` and routes through `UiUtil.showStrictLockedDialog`. Any new destructive
action has to add its own check — nothing will catch a missing one.

It is friction, not a security boundary. Android Settings remains authoritative: the user can
always revoke the VPN, disable the accessibility service, clear data or uninstall. The 10/5
minute values are a chosen default, not a validated one.

## Storage

| File | Contents |
|---|---|
| `focusfence` | rules, blocked apps, excluded apps, ad-list state, strict state, per-domain usage |
| `focusfence_stats` | `stats_<yyyy-mm-dd>` → compact `domain=count;_ads=count` strings, pruned to 14 days |
| `filesDir/filter_<sourceId>.txt` | downloaded filter lists (one per source since W11), each replaced transactionally via a `.tmp` rename |

## Known limits

Worth knowing before proposing changes, because several are structural rather than bugs:

- DNS-level blocking only. A domain already resolved and cached may load until its TTL
  expires; apps that hardcode IPs or ship their own DoH stack bypass it.
- DNS over TCP is dropped rather than relayed. Mobile clients essentially always use UDP.
- The DoH guard list is a fixed set of known bootstrap hostnames. It is a tactical measure,
  not a boundary — any other host can offer the same service.
- Android's Private DNS setting bypasses the filter entirely when enabled.
- Only one VPN can be active per user, so this cannot run alongside another VPN.
- An allowlist exists (W9, D-017) but only its logic and storage are done — no UI yet to add
  or remove an entry from the app itself (see `docs/work/W9-allowlist.md`).
- Battery cost is unmeasured. The architecture justifies expecting it to be low; no benchmark
  in the tree establishes it.
