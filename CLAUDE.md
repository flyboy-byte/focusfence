# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> **Start with [`STATE.md`](STATE.md)** (where things stand) and [`AGENTS.md`](AGENTS.md) (how to
> work here — rules for any AI agent). This file is the architecture reference.
>
> It describes the codebase as it exists (architecture, invariants, commands) and deliberately holds
> no plans or opinions about direction. Those live in [`docs/`](docs/) and `DECISIONS.md`.

## Project

Focus Fence AC — an Android app (Kotlin, views + ViewBinding, no DI/Compose) that blocks distracting
websites via a DNS-only `VpnService` and keeps a local activity/diagnostics log. The filter works alone:
no account, no server. Allowed DNS is relayed to the network's own resolver (DoH to public resolvers as
fallback). Optional accountability layers sync to a self-hostable Kotlin/Ktor server in `server/`, which
stores partner reports sealed on the phone (D-023); logs otherwise leave the phone only via user-initiated
export. `applicationId app.focusfence.accountable`, namespace still `app.focusfence`. `minSdk 26`, `compileSdk`/`targetSdk 35`, JDK 17.

## Commands

```sh
./gradlew assembleDebug                 # APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest             # all JVM unit tests
./gradlew testDebugUnitTest --tests 'app.focusfence.dns.BlocklistTest'          # one class
./gradlew testDebugUnitTest --tests 'app.focusfence.dns.BlocklistTest.matches*' # one method
./gradlew lint                          # Android lint
```

CI (`.github/workflows/android.yml`) runs `testDebugUnitTest` then `assembleDebug` on every push
and uploads the APK as artifact `focus-fence-debug-apk`. `.github/workflows/release.yml` builds a
signed release APK on any `v*` tag and publishes a GitHub Release (see `AGENTS.md` → Releases). There are no instrumented tests — the
only automated coverage is the JVM suite in `app/src/test/`.

## Architecture

Three enforcement layers, each covering what the others cannot:

1. **DNS filter** (`vpn/DnsVpnService.kt`) — the main path. The tunnel advertises virtual DNS
   servers (`10.111.222.3`, `fd66:f0cf:0:1::53`) and routes *only those two addresses*, so normal
   traffic never enters the app. That is the entire reason battery/throughput cost is negligible;
   any change that widens the routes breaks the core premise. Blocked queries get a locally
   synthesized cacheable `0.0.0.0`/`::` (NODATA for other types; `Dns.buildBlockedResponse`,
   TTL 1h for ads/guards, 60s for user rules); everything else is relayed upstream (`forward`) in the order `Upstreams.order` gives for the
   owner's choice (Settings → DNS server, `DnsUpstreamStore`; default Automatic): first the
   real network's own DNS servers over UDP on a socket bound to that network (`udpQuery`,
   `refreshUnderlying`), then DoH (RFC 8484, `dohQuery`) to `https://1.1.1.1` / `https://8.8.8.8` via
   `Network.openConnection`, tracking a `preferredUpstream`. Public resolvers alone failed on the
   user's network (~75% plain-UDP failures, then a DoH-only relay with no working DNS). Never
   `disconnect()` a DoH connection — on Android that kills keep-alive. Android's "Block connections
   without VPN" (lockdown) is incompatible with DNS-only routes (it drops all non-tunnel traffic);
   `VpnHealth` flags it as `lockdown`.
2. **Accessibility service** (`accessibility/UrlFilterService.kt`) — enforces per-path "section"
   rules (allow `facebook.com/marketplace`, block the rest) by reading browser address bars and
   pressing Back, and bounces blocked apps to the home screen. DNS sees hostnames only and TLS
   hides paths, so section rules *must* leave the domain resolvable — hence
   `ruleBlocks()` returns false for `hasPathExceptions` rules.
3. **DoH guards** (`Blocklist.DOH_BYPASS_GUARDS`) — blocks browser DoH bootstrap hosts and
   answers the `use-application-dns.net` canary so browsers can't bypass the filter.

### Purity boundary

Everything in `dns/` is deliberately free of Android types (`Blocklist`/`BlockRule`, `Dns`,
`Packets`, `UrlFilter`, `UsageTracker`, `StrictLock`, `StatsCodec`, `HostsParser`, and the
logging core `LogJson`, `LogEvents`, `Battery`, `AppTimeline`, `LogSummary`, and `Categories`, `Pin`, `Ecies`, `CapturePolicy`, `Classification`, `GuardPolicy`, `VpnHealth`, `BlockTriage`, `UpdateCheck`, `AppTime`,
`CaptureStatus`, `BlockHistory`, `InviteLink`, `AutoShare`, `GapAccount`, `BodyCheck`, `RiskTerms`, `ImageRegions`, `SecretFields`, `Faults`) so it can be
unit-tested on the JVM. Keep new decision logic there and let the service/UI supply clocks,
timestamps and context. `data/` holds the Android-side SharedPreferences wrappers.

### Rules and persistence

A `BlockRule` is exactly one of four modes, serialized to a single prefs string via
`encode()`/`decode()`: always-block (`domain`), daily window (`domain|start|end`, may wrap
midnight), daily budget (`domain|b|minutes`), section allowlist (`domain|p|/a,/b`). Rules live in
a `StringSet` under `focusfence`/`block_rules`. Both the VPN service and the accessibility service
hold `@Volatile` snapshots refreshed by a `SharedPreferences.OnSharedPreferenceChangeListener` —
**that listener is the only propagation mechanism**, so any new store key the runtime depends on
must be written to the `focusfence` prefs file and handled in those listeners (`AdListStore`
deliberately writes `KEY_COUNT` last as its reload signal). Stats live in a separate
`focusfence_stats` file so their high write volume doesn't churn the rules listeners.

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
requires requesting an unlock, waiting 10 min, then acting within a 15 min window (notification when it opens). Adding blocks is
always allowed. Enforcement is spread across call sites — `DnsVpnService.onStartCommand` refuses
`ACTION_STOP` while locked, and UI paths check `StrictStore.isLocked` and route through
`UiUtil.showStrictLockedDialog`. New destructive UI actions must add their own check.
Loosening actions go through `UiUtil.guard(context, GuardPolicy.Action.X)`,
which applies the per-action friction level (None / Delay / Own PIN / Partner PIN,
`docs/specs/friction-tiers.md`) on top of the strict lock.

### Logging (activity + diag)

`data/EventLog` is a process singleton writing JSON lines to `files/logs/` in two channels:
**activity** (detection record + integrity trail: `dns`, `url`, `app`, `screen`, `enforce`,
`svc`, `integ`) and **diag** (`bat`, `perf`, `err`). The schema lives only in `dns/LogEvents`,
documented in `docs/specs/event-log.md`; change both together. Rules that matter:

- Never write to disk from the DNS reader thread: `EventLog` buffers and flushes on its own
  thread. Lifecycle/integrity records pass `urgent = true` so they hit disk immediately.
- A protection gap must never be silent: a new protection component needs a key in
  `Monitor.integrityState` and its own `svc` lifecycle records.
- `monitor/Monitor` samples battery only from system broadcasts (battery changed, screen
  on/off), never a timer, and closes a `PerfWindow` on each sample. Services count into
  `Monitor.perf`; a new sensor should add counters for its volume and miss rate.
- Recording settings (`LogSettings`) live in the `focusfence` prefs file and are handled in the
  services' prefs listeners, like rules. Turning recording off and clearing logs are loosening
  actions and check strict mode.
- `UsageCollector` *pulls* UsageStats (no wakeups) and turns it into a fg/bg timeline via the
  pure `AppTimeline`.

### Battery-sensitive code

Several non-obvious details exist purely for battery/CPU and should not be "cleaned up" without
understanding them: `setBlocking(true)` on the tunnel builder (a non-blocking fd turns the reader
into a busy spin); the accessibility service narrowing `serviceInfo.packageNames`/`eventTypes` to
only the packages it acts on, requesting the high-volume content-changed events only while
section rules exist (URL recording, text capture and screenshots run on a foreground tick
instead — content events cost ~75% of screen-on CPU in the 2026-11-19 export); batched stats flushes; and split block TTLs (ads cached 1h, user rules 60s).

### Block notices and triage (D-024)

`dns/BlockTriage` decides who hears about a block. Ads, trackers, guards and deeper hosts stay silent. A user rule, or the
front door of an adult/gambling/scam list site, is a notification or nothing (`BlockScreen.Style` NOTIFY/OFF; the
full-screen page is gone, D-034), after the accessibility service confirms the browser's address bar shows that site. Three confirmed
high-risk notices in one category within 10 minutes write an urgent `svc block repeated` record, which partners get by
category, never by site name. Without the accessibility service partners are never told (prefetch would cause false
alerts). `dns/BlockHistory` builds the owner's Block history page (sites vs trackers) from the activity log.

### Partners and groups (D-042, plans 13 and 14)

One kind of partner: a person with devices (their app, or a browser), each with its own P-256 key. Every phone on a
server is enrolled (`sync/Accountability.ensureEnrolled`, a `DeviceKey`) with the server's enrollment code, a partner
token (`X-FF-Partner`: a phone that joined a report) or a group code (`X-FF-Group`). Invites are an 8-character code,
its QR code (`/c/CODE`) or an emailed link (`/j/SECRET`); whoever uses one first joins (`/v1/join`, unsigned). The
owner's phone hears of new partner devices in the heartbeat's `seal` list and seals its report key to each one
(`dns/Ecies`, `INFO_KEY`; `SyncWorker.sealKeys` → `/v1/seal`), so the server relays only ciphertext. On the partner's
side `sync/PartnerKey` (software key wrapped by a keystore AES key) opens it, `SyncStore.Seen` holds each report, and
`PartnerReportActivity` opens the server's report page in a WebView with the key in the fragment. Groups (`GroupActivity`,
`GroupUi`; server `Groups.kt`) are partner pairs made both ways, marked with the group (`Partner.groupIds`); the
heartbeat's `groupPairs` tells a member which reports to send a device key for (`/v1/groups/key`). Removing or leaving
makes the owner's phone start a new report key (`rotateKey`). `dns/InviteLink` parses codes, links and the admin
page's Server message.

### Screenshot flagging (D-025, D-037, D-038)

`monitor/ScreenCapture` scores each capture with the GantMan MobileNet (`TfliteClassifier`). A risky one (the owner's
threshold, or at least `BodyCheck.CHECK_FROM`, 0.30) gets a second look from NudeNet's body-part detector
(`monitor/NudeDetector`, `assets/classifier/nudenet_320n_f16.tflite`): a partner flag needs an exposed body part
(`dns/BodyCheck.confirm`), because the first model calls faces and skin "porn". Covered intimate parts (lingerie,
swimwear) make it "suggestive" instead: report-only, a `svc monitor suggestive` line, never urgent. If NudeNet can't run, `dns/FlagPolicy`
decides as before. A flag queues the 48 px blurred thumbnail for partners after a 10 min note window
(`ShareQueue.enqueue(auto = true)`, `monitor/AutoShareNotice`). NudeNet is AGPL-3.0, which is why the project is
AGPL-3.0 (D-038); `tools/convert_nudenet_model.py` rebuilds the model.

Each picture on screen is also checked on its own (plan 17): `ScreenCapture.pictureBoxes` reads image and video views'
bounds from the accessibility tree just before the shot, `dns/ImageRegions.pick` keeps up to 3 (or 3 squares when there
are none and the whole screen scored ≥ 0.10), and the best crop result counts, because a small picture shrunk with the
whole screen to 224/320 px is invisible to both models. For 3 minutes after alert-level words in an app (`FlagPolicy.inContext`), its
pictures face lower bars: body check from 0.25, a flag at 0.35 or when suggestive. A woman in the picture adds 0.10 to that score (`BodyCheck.FEMALE_BOOST`). The owner's
`dns/Sensitivity` (Strict 0.45 / Standard 0.50 / Relaxed 0.70, Screen checks → Flag sensitivity) is the bar at which a
suggestive picture is a partner flag; nudity (`BodyCheck.CONFIRM` 0.30) flags at any setting. Less strict is guarded
(`GuardPolicy.Action.LOWER_SENSITIVITY`) and partners are told at once.

### Screen text (plan 17)

`monitor/TextCapture` reads watched apps' text and content descriptions every 30 s from the foreground tick. `dns/RiskTerms`
is a word index: words are normalized (NFKC, accents, invisible characters), disguises undone per word (look-alike
digits, `*` for a vowel, plurals, spaced letters) and every 1-3 word run is one hash lookup, so lists can grow cheaply.
A strong hit (`RiskTerms.alertable`: a strong word or two weak ones, and not a page about quitting, `aboutQuitting`)
writes an urgent `svc monitor text` record whose detail is **the app only**: alert text is server-readable, so the words
stay in the sealed report. Adult words or a risky screenshot make the app *hot* (`TextCapture.markHot`, 2 min): text
every 5 s and deeper, screenshots every 10 s (`ScreenCapture.hotCap`). Tuned on a real export replayed locally; never
commit export content. `UrlFilterService.watchingAnything` re-checks the front app every 15 s while it's one we don't
read, because Android doesn't always send a window event when the app underneath comes back.

### Protocol versions (D-041)

`dns/Protocol` (compiled by app and server) holds `LEVEL` and `WINDOW` (2). The app sends `X-FF-Protocol` on every
request (`sync/Api`) and records its own server's `X-FF-Server-Protocol`/`X-FF-Server-Oldest`
(`SyncStore.protocolStatus`); Partners says which side to update when out of range. Server compat suites live in
`server/src/test/.../compat/`; see AGENTS.md → Releases.

### Self-update (D-029, D-031)

`update/Updates` checks `releases/latest` every time the app comes to the foreground (`FocusFenceApp` counts started activities; the VPN keeps the process alive, so neither process start nor `MainActivity.onCreate` means "opened") and otherwise at most every 20 minutes (Home, screen on, WorkManager backstop), remembers a newer offer for Home's banner and posts
one notification per version. Since 2.24 the update happens in Home's banner (size, progress, errors, retry); the
notification and Settings → Check now open Home. `update/Installer` (shared with the fallback `update/UpdateActivity`)
downloads only on tap, checks source URL, package, version code and signing certificate, then hands the APK to
`PackageInstaller`. Release notes come from the release body's
`## What's new` section (written by `release.yml`), cleaned by `UpdateCheck.userNotes`. The repo slug is
`UpdateCheck.REPO`: a fork that publishes its own releases changes that one line.

### UI (D-032)

`MainActivity` is a bottom-nav host with labelled tabs. The default **five-tab layout** swaps `ui/HomeFragment`,
`BlocklistFragment` (Blocking, with a Categories card), `AccountabilityFragment` (Partners), `MonitoringFragment` (shown
as "Screen checks" / tab "Checks") and `SettingsFragment`. Settings → Classic layout (`data/UiPrefs`, own prefs file)
restores the old four tabs. `AccountabilityActivity`/`MonitoringActivity` are thin hosts for their fragments so
notifications and links still open them alone. `UninstallProtectionActivity` turns device admin on and off,
`CategoriesActivity` is "Block lists": the core lists (`Bundle.core`: adult and bypass, Report or Block only, never Off)
under "Accountability · always on", then "Extra blocking" (Off/Report/Block, the base list's strength row), Always
allow; `data/ListRefresh` refreshes lists weekly on Wi-Fi), `StatsActivity` is "This week", `DomainDetailActivity` the per-domain rule editor, `DiagnosticsActivity` the logs,
`SetupActivity` the one Guide (`HelpActivity` forwards to it), `update/WhatsNewActivity` the release notes. Shared
dialogs, permission prompts and app pickers live in `UiUtil`. The VPN service signals the UI with the
`ACTION_STATE_CHANGED` package-scoped broadcast plus a 1s ticker for counters/countdowns.

Visual rules: colour only for state, taken from the theme through `ui/StatusColors` (primary, tertiary, error; never fixed colours);
plain lists with dividers rather than nested cards; each screen ordered by rank (D-032).

## Conventions

- User input is normalized through `Blocklist.normalize` (domains) and `Blocklist.normalizePath`
  (paths) — never trust raw input at a store or matcher boundary.
- All user-visible strings go in `res/values/strings.xml`; Material 3 theming with dynamic color.
- Comments in this codebase explain *why* a non-obvious constraint exists (battery, Android API
  quirks, bypass vectors). Match that when touching those areas.
- The README's "Limitations" section documents deliberate non-goals (no TCP DNS relay, DNS-level
  blocking only, budgets are estimates) — check it before "fixing" one.
