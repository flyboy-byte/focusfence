# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> **Contributed by @flyboy-byte, not the maintainer.** It describes the codebase as it exists —
> architecture, invariants, commands — and deliberately contains no proposals or opinions about
> where the project should go. Those live in [`docs/`](docs/), which nothing auto-loads.
> Rewrite or delete this file freely; it is a starting point, not a standard.

## Project

Focus Fence — an Android app (Kotlin, views + ViewBinding, no DI/Compose) that blocks distracting
websites via a DNS-only `VpnService`. Fully on-device: no accounts, no servers, nothing leaves the
phone. `minSdk 26`, `compileSdk`/`targetSdk 34`, JDK 17.

## Commands

```sh
./gradlew assembleDebug                 # APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest             # all JVM unit tests
./gradlew testDebugUnitTest --tests 'app.focusfence.dns.BlocklistTest'          # one class
./gradlew testDebugUnitTest --tests 'app.focusfence.dns.BlocklistTest.matches*' # one method
./gradlew lint                          # Android lint
```

CI (`.github/workflows/android.yml`) runs `testDebugUnitTest` then `assembleDebug` on every push
and uploads the APK as artifact `focus-fence-debug-apk`. There are no instrumented tests — the
only automated coverage is the JVM suite in `app/src/test/`.

## Architecture

Three enforcement layers, each covering what the others cannot:

1. **DNS filter** (`vpn/DnsVpnService.kt`) — the main path. The tunnel advertises virtual DNS
   servers (`10.111.222.3`, `fd66:f0cf:0:1::53`) and routes *only those two addresses*, so normal
   traffic never enters the app. That is the entire reason battery/throughput cost is negligible;
   any change that widens the routes breaks the core premise. Blocked queries get a locally
   synthesized NXDOMAIN; everything else is relayed over a `protect()`ed socket to
   1.1.1.1/8.8.8.8 (+ v6), tracking a `preferredUpstream` so dead resolvers aren't re-probed.
2. **Accessibility service** (`accessibility/UrlFilterService.kt`) — enforces per-path "section"
   rules (allow `facebook.com/marketplace`, block the rest) by reading browser address bars and
   pressing Back, and bounces blocked apps to the home screen. DNS sees hostnames only and TLS
   hides paths, so section rules *must* leave the domain resolvable — hence
   `ruleBlocks()` returns false for `hasPathExceptions` rules.
3. **DoH guards** (`Blocklist.DOH_BYPASS_GUARDS`) — NXDOMAINs browser DoH bootstrap hosts and
   answers the `use-application-dns.net` canary so browsers can't bypass the filter.

### Purity boundary

Everything in `dns/` is deliberately free of Android types (`Blocklist`/`BlockRule`,
`RuleEvaluator`, `Dns`, `Packets`, `UrlFilter`, `UsageTracker`, `StrictLock`, `StatsCodec`,
`HostsParser`) so it can be unit-tested on the JVM. Keep new decision logic there and let the
service/UI supply clocks, timestamps and context. `data/` holds the Android-side
SharedPreferences wrappers, including `AllowlistStore` — domains exempted from list-sourced
blocks only, never from a user rule in force (D-017).

### Rules and persistence

A `BlockRule` is exactly one of five modes, serialized to a single prefs string via
`encode()`/`decode()`: always-block (`domain`), daily window (`domain|start|end`, may wrap
midnight), daily budget (`domain|b|minutes`), section allowlist (`domain|p|/a,/b`), or a
commitment blocking until a fixed instant (`domain|c|<epochMillis>`, used by app rules — W7).
`RuleEvaluator.blocks()`/`blocksApp()` in `dns/` is the pure decision of whether a rule is in
force right now; the service only supplies the clock and accrued usage. Rules live in a
`StringSet` under `focusfence`/`block_rules`. Both the VPN service and the accessibility service
hold `@Volatile` snapshots refreshed by a `SharedPreferences.OnSharedPreferenceChangeListener` —
**that listener is the only propagation mechanism**, so any new store key the runtime depends on
must be written to the `focusfence` prefs file and handled in those listeners (`FilterListStore`
deliberately writes `KEY_REV` last as its reload signal). Filter lists (W11) are per-source:
`FilterSources` in `dns/` is the catalog (id, URL, category, license), `FilterListStore` keeps
one downloaded file per source, and the VPN service holds a `host → source id` map so stats can
say *which* list blocked (`_src:<id>` buckets; `_ads` is the pre-W11 aggregate, still decoded).
Lists are downloaded at runtime only, never bundled — licensing (research Q2) makes that
distinction load-bearing. Stats live in a separate `focusfence_stats` file so their high write
volume doesn't churn the rules listeners.

### Time budgets

`UsageTracker` infers usage from DNS activity: consecutive lookups within
`DEFAULT_SESSION_GAP_MILLIS` (3 min) count the gap as usage. Two guards matter and both have
tests — accrual only happens while `PowerManager.isInteractive` (screen on), and only for
"front door" hostnames (`Blocklist.isFrontDoor`: bare, `www.`, `m.`, …), so SDK subdomains like
`graph.facebook.com` from unrelated apps neither drain the budget nor break when it runs out.
Usage is written through to `UsageStore` throttled (15s), and saving a budget rule resets the
day's count.

### Strict mode

`StrictLock` is a pure state machine (Off / Locked / Pending / Unlocked): loosening anything
requires requesting an unlock, waiting 10 min, then acting within a 5 min window. Adding blocks is
always allowed. Enforcement is spread across call sites — `DnsVpnService.onStartCommand` refuses
`ACTION_STOP` while locked, and UI paths check `StrictStore.isLocked` and route through
`UiUtil.showStrictLockedDialog`. New destructive UI actions must add their own check.

### Battery-sensitive code

Several non-obvious details exist purely for battery/CPU and should not be "cleaned up" without
understanding them: `setBlocking(true)` on the tunnel builder (a non-blocking fd turns the reader
into a busy spin); the accessibility service narrowing `serviceInfo.packageNames`/`eventTypes` to
only the packages it acts on, requesting the high-volume content-changed events only while
section rules exist; batched stats flushes; and split block TTLs (ads cached 1h, user rules 60s).

### UI

`MainActivity` is a bottom-nav host swapping four fragments (`ui/HomeFragment`,
`BlocklistFragment`, `StatsFragment`, `SettingsFragment`); `DomainDetailActivity` is the per-domain
rule editor and `OnboardingActivity`/`HelpActivity` are standalone. Shared dialogs, permission
prompts and app pickers live in `UiUtil`. The VPN service signals the UI with the
`ACTION_STATE_CHANGED` package-scoped broadcast plus a 1s ticker for counters/countdowns.

The app ships **one fixed dark theme**, not `DayNight` and not Material You — the fence metaphor
rests on a single unmistakable "armed" green (`@color/accent`), and a wallpaper-derived palette
would have recoloured the one signal that must read identically on every device. Consequences:
nothing may read `?attr/isLightTheme`, there is no `values-night/`, and a new colour belongs in
`res/values/colors.xml` rather than being picked from a Material role. The layered `hairline*`
and `fill*` values are translucent whites composited over `surface_card`, not separate greys, so
they stay correct if that card colour is ever retuned.

Typography is IBM Plex, bundled — see [`docs/fonts.md`](docs/fonts.md) for provenance and for why
weights are referenced as concrete font files instead of a `<font-family>`. `res/values/type.xml`
is the only place that names a font file; layouts use its `FF.Text.*` styles, and
`res/values/themes.xml` maps them onto the Material type-scale attributes so components inherit
Plex too. Mono carries anything numeric or label-like so figures stay aligned while they tick.

Home's hero reads three things the service exposes purely in memory and resets on every start:
`blockedCount`, `startedAtMillis` (monotonic — it renders as a duration), and `activityMeter`, a
`dns/ActivityMeter` ring of per-minute counts. It also adds `unflushedBlocks` to the stored day
total, because stats are flushed in batches and the counter would otherwise step in 25s rather
than tick. All four are deliberately process-lifetime state, not persistence.

The blocklist is a flat `RecyclerView` drawn as grouped cards: `RowPos` tells each row whether it
owns the top corners, the bottom ones, both or neither, and `BlocklistFragment` assigns it. Each
blocking mode has one colour (`@color/mode_*`) reused by its group dot, its row tag and its Up
next row, so a mode is recognisable anywhere it appears.

## Conventions

- User input is normalized through `Blocklist.normalize` (domains) and `Blocklist.normalizePath`
  (paths) — never trust raw input at a store or matcher boundary.
- All user-visible strings go in `res/values/strings.xml` in sentence case — the all-caps look
  is applied by the type scale, so the strings stay readable to screen readers and translatable
  into languages without case.
- Comments in this codebase explain *why* a non-obvious constraint exists (battery, Android API
  quirks, bypass vectors). Match that when touching those areas.
- The README's "Limitations" section documents deliberate non-goals (no TCP DNS relay, DNS-level
  blocking only, budgets are estimates) — check it before "fixing" one.
