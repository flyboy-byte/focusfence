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
   ├─ Categories.decide(qname)          first match wins
   │     ├─ user rule matches and is in force   → block (rule)
   │     ├─ qname is a DoH bootstrap host       → block (guard)
   │     ├─ qname on the user allowlist         → allow
   │     ├─ a bundle with policy BLOCK matches  → block (category)
   │     ├─ qname in downloaded ad hosts        → block (ads)
   │     └─ otherwise                           → allow (labelled with any REPORT bundles)
   │
   ├─ blocked  → Dns.buildBlockedResponse, written straight back to the tun
   └─ allowed  → handed to a 12-thread pool and relayed (forward), in Upstreams.order
                 for the owner's choice (Automatic below; or a preset / own server first,
                 optionally falling back to Automatic):
                   1. the real network's own DNS servers, UDP, socket bound to that network
                   2. DoH (RFC 8484) to https://1.1.1.1 then https://8.8.8.8, via
                      Network.openConnection, keep-alive (never disconnect())
                 the upstream that last answered is tried first; reply written back to the tun
```

Two details in there are easy to "fix" into a regression:

**The tun fd is explicitly blocking.** `establish()` returns a non-blocking fd by default,
which turns the reader loop into a busy spin that pins a core.

**Blocked queries are not answered NXDOMAIN** (the README and KDoc said so until the logging
branch corrected them). A bare NXDOMAIN carries no SOA, so clients treat it as uncacheable and re-ask
continuously — this was measured waking the service thousands of times a day. Blocked A/AAAA
queries now get a real answer of `0.0.0.0` / `::` with an explicit TTL; other query types get
NODATA. TTLs are split deliberately: **1 hour** for ad and DoH-guard blocks, which are stable,
and **60 seconds** for user rules, which change as schedules and budgets do.
`Dns.buildNxDomain` still exists but is no longer on the block path.

**Why the relay prefers the network's own resolver.** Relaying only to public resolvers failed
on the owner's test phone twice: plain UDP to 1.1.1.1/8.8.8.8 failed ~75% of the time, and the
DoH-only relay that replaced it (v2.1.1) left the phone with no working DNS. Every network allows
its own resolver, which is what DNS66 and similar apps relay to. DoH stays as the fallback.

**Never enable lockdown.** Android's "Block connections without VPN" drops every packet that
isn't routed into the tunnel. With DNS-only routes that is all real traffic: the phone keeps DNS
and loses the internet. `VpnHealth` reports it as `lockdown` and the alert opens VPN settings;
in managed mode the app re-applies always-on without lockdown.

**Filter health.** `dns/VpnHealth` classifies the running filter (ok, offline, degraded,
upstream_down, silent, bypassed by strict Private DNS, lockdown) from relay outcomes, query
arrivals, screen state and system settings, with no timers. See
[`specs/device-owner.md`](specs/device-owner.md#health-detection).

## Layering

```
dns/     pure Kotlin, no Android imports, unit-tested on the JVM
data/    thin SharedPreferences wrappers
vpn/     the VpnService — packet loop, upstream relay, health, stats, budget accrual
accessibility/  the AccessibilityService — section rules, app blocking, URL/text capture
monitor/ Monitor (battery, perf, integrity trail), usage timeline, text/screen capture
admin/   Device Owner wrapper, app suspension, Private DNS fallback
ui/      fragments + the domain editor
```

The `dns/` boundary is real and worth keeping: `Blocklist`, `BlockRule`, `Dns`, `Packets`,
`UrlFilter`, `UsageTracker`, `StrictLock`, `StatsCodec` and `HostsParser` take no Android
types, which is why the test suite runs as plain JUnit with no instrumentation. New decision
logic belongs there, with the caller supplying clocks and timestamps.

The one place that boundary is soft is `DnsVpnService.ruleBlocks()`, which holds the
"is this rule in force right now" logic — schedule evaluation, budget accrual, the
front-door check — inline in the service rather than in `dns/`.

## Rules

One `BlockRule` per domain, in exactly one of four modes, serialised into a single
`StringSet` preference:

| Mode | Encoding | Enforced by |
|---|---|---|
| Always | `reddit.com` | DNS |
| Daily window (may wrap midnight) | `reddit.com\|540\|1080` | DNS |
| Daily budget in minutes | `reddit.com\|b\|30` | DNS, once exhausted |
| Allowed section paths | `facebook.com\|p\|/marketplace` | accessibility service only |

Rules apply to subdomains: matching walks up parent domains (`a.b.example.com` →
`b.example.com` → `example.com`), so one entry covers unknown subdomains without enumerating
them.

Section rules are the exception that shapes the rest. DNS sees hostnames and TLS hides paths,
so a domain with allowed sections **must stay resolvable** — `ruleBlocks()` returns false for
those, and enforcement moves entirely to the accessibility service reading the browser's
address bar. That is also why they can't work inside native apps.

## Configuration propagation

There is exactly one mechanism. Both long-running services hold `@Volatile` snapshots of the
rules and refresh them from a `SharedPreferences.OnSharedPreferenceChangeListener`:

```
UI writes prefs → listener fires → service swaps its snapshot
```

Anything the runtime depends on must therefore be written to the **`focusfence`** prefs file
and handled in those listeners. `BlockedAppsStore` and `ExcludedAppsStore` live in that file
for exactly this reason even though they aren't rules. `AdListStore.download()` writes its
entry count *last*, so that write doubles as the reload signal. Stats are the deliberate
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
requires requesting an unlock, waiting 10 minutes, then acting inside a 15-minute window (a notification announces when it opens).
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
| `filesDir/adblock_hosts.txt` | downloaded hosts file, replaced transactionally via a `.tmp` rename |

## Known limits

Worth knowing before proposing changes, because several are structural rather than bugs:

- DNS-level blocking only. A domain already resolved and cached may load until its TTL
  expires; apps that hardcode IPs or ship their own DoH stack bypass it.
- DNS over TCP is dropped rather than relayed. Mobile clients essentially always use UDP.
- The DoH guard list is a fixed set of known bootstrap hostnames. It is a tactical measure,
  not a boundary — any other host can offer the same service.
- Android's Private DNS setting bypasses the filter entirely when enabled.
- Only one VPN can be active per user, so this cannot run alongside another VPN.
- **There is no allowlist.** Nothing can exempt a domain from the downloaded ad list; a false
  positive can only be resolved by disabling the whole list.
- Battery cost is unmeasured. The architecture justifies expecting it to be low; no benchmark
  in the tree establishes it.
