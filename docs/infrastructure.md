# Infrastructure

> **Tier:** Low-level (build detail) · **Audience:** whoever is actually building this ·
> **Use when:** setting up tools and scaffolding before building the first real
> version. Assumes the reader has already read [`overview.md`](./overview.md) and doesn't
> need the framing re-explained.

The app already builds. What's missing is everything between "builds" and "a stranger can install
it." That gap is the content of this doc.

## Core tools / platforms

| Thing | Version / detail | Why |
|---|---|---|
| Kotlin | 1.9.24 | Pinned; JDK 26 breaks it — see below |
| AGP | 8.5.2 | |
| JDK | 17 in CI (temurin) | `sourceCompatibility`/`jvmTarget` are 17 |
| `compileSdk` / `targetSdk` | 34 | Platform 34 must be installed locally |
| `minSdk` | 26 | Android 8.0 |
| Dependencies | AndroidX + Material only | **No networking library at all** — IPv4/IPv6, UDP and DNS wire format are hand-parsed in `Packets.kt` and `Dns.kt` |
| CI | GitHub Actions, `.github/workflows/android.yml` | Runs `testDebugUnitTest` then `assembleDebug` |

### Local build environment

Not a repo problem, but it stopped the first build attempt on 2026-08-14:

- The system JDK is **26**. Kotlin 1.9.24 cannot parse that version string and the build dies with
  a bare `IllegalArgumentException: 26.0.2` that names no cause. **JDK 21 works.** CI is
  unaffected — it pins temurin 17.
- `ANDROID_HOME` is unset and there is no `local.properties`. The SDK is at `~/Android/Sdk`.

Working invocation:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ANDROID_HOME=$HOME/Android/Sdk ./gradlew assembleRelease
```

## Release infrastructure — the actual gap

Verified against the tree on 2026-08-14 rather than assumed. This is Track A.

| # | Item | State | Blocks release? |
|---|---|---|---|
| R1 | `LICENSE` file | **Absent** | **Yes — hard** |
| R2 | Release signing | **Absent** — output is `app-release-unsigned.apk`; `apksigner` reports `DOES NOT VERIFY` | **Yes — hard** |
| R3 | `assembleRelease` succeeds with R8 | ✅ Verified, 1m59s, no proguard rules needed | No |
| R4 | Release build exercised in CI | **No** — debug only | Yes |
| R5 | Release workflow (tag → build → attach) | **Absent** | Yes |
| R6 | README claims accurate | **No** — three known-false statements | Yes, for an honest release |
| R7 | Changelog | **Absent** | No, but expected |
| R8 | Permission justification for strangers | **Thin** | Practically, yes |

**R1 — no LICENSE.** Publishing a binary without a license grant means nobody legally may use,
redistribute or fork it. Candidates and their tradeoffs are in
[`audits/03-foss-ecosystem.md`](audits/03-foss-ecosystem.md). Do **not** decide from the executive
summary's compatibility table — it contains errors, documented in
[`audits/findings.md`](audits/findings.md) §2.8. Copyright is shared with @sidewinderzz, so this
was decided as **MIT** on 2026-08-15 (D-015); `LICENSE` now exists. Because copyright is shared, @sidewinderzz's written acknowledgement is still required before publishing a binary.

**R2 — no signing.** There is no `signingConfig` in `app/build.gradle.kts`. **This is a one-way
door:** the key becomes the app's permanent identity, and signing a later update with a different
key forces every user to uninstall and reinstall, losing their rules. Settle custody before the
first release, not after (O-002).

**R4 — nothing watches the release build.** `proguard-rules.pro` is a single comment claiming "no
special rules needed." That claim happens to be true, but it had never been tested, because CI
only builds debug. A future dependency using reflection would break the release build silently
until release day. `assembleRelease` belongs in CI.

**R6 — three inaccurate README claims.** All verified in `audits/findings.md`. They matter more
now: in a repo they're stale comments; on a release page they're claims made to strangers about a
security-sensitive app.

1. "answered locally with `NXDOMAIN`" — untrue since `0552f11`; blocked queries get cacheable
   `0.0.0.0`/`::`. Also stale at `DnsVpnService.kt:47-48`.
2. "nothing about your browsing ever leaves your phone" — allowed DNS goes to 1.1.1.1 / 8.8.8.8.
3. "battery and throughput impact is effectively zero" — an inference, not a measurement.

The accurate versions are more persuasive than the sweeping ones.

## Data / storage

All on-device `SharedPreferences` plus one file. No database, no server, no sync.

| File | Contents |
|---|---|
| `focusfence` | rules, blocked apps, excluded apps, ad-list state, strict state, per-domain usage |
| `focusfence_stats` | `stats_<yyyy-mm-dd>` → compact `domain=count;_ads=count`, pruned to 14 days |
| `filesDir/adblock_hosts.txt` | downloaded hosts file, replaced transactionally via `.tmp` rename |

Stats live in a separate prefs file **on purpose** — their high write volume would otherwise churn
the rule-change listeners.

## Hard constraints (do not violate)

Established in `DECISIONS.md`; repeated here because they're easy to break while building.

- **The VPN routes DNS only** (D-001). `/32` and `/128` routes for its two synthetic servers.
  Widening them changes what the program is.
- **`dns/` imports no Android types** (D-002). It's the only reason there's automated coverage.
- **Blocked queries answer `0.0.0.0`/`::` with a TTL, not NXDOMAIN** (D-003). A bare NXDOMAIN has
  no SOA, is uncacheable, and was measured waking the service thousands of times a day. Any plan
  to "restore correct NXDOMAIN behaviour" is re-introducing a fixed battery regression.
- **`setBlocking(true)` on the tun fd.** `establish()` returns a non-blocking fd; the reader loop
  then busy-spins and pins a core.
- **The accessibility service's narrowed `packageNames`/`eventTypes`** are a battery measure, not
  a style choice. `TYPE_WINDOW_CONTENT_CHANGED` is requested only while section rules exist.
- **The `SharedPreferences` listener is the only propagation mechanism.** Any new runtime-relevant
  key must be written to the `focusfence` prefs file and handled in both services' listeners.

## Security-relevant infrastructure

- **The signing keystore** is the one real secret this project has. Losing it is unrecoverable;
  leaking it lets anyone ship a "Focus Fence update." If CI signs, it lives in a GitHub Actions
  secret and the workflow must never echo it.
- **No other secrets exist** — no API keys, no accounts, no backend. This is a genuine advantage
  and worth keeping.
- **The permission surface is the security story.** `VpnService` + `AccessibilityService` is the
  most abusable pair on Android. See `risks.md`.

## Testing infrastructure

**What exists:** 8 JVM test classes covering the pure `dns/` package — matching, rule encoding,
URL filtering, usage accrual, strict-lock transitions, stats codec, hosts parsing. Runs as plain
JUnit, no emulator.

**What doesn't:** anything touching Android. No `androidTest/`, no instrumented tests. The VPN
service, the accessibility service, and all the UI have **zero automated coverage**. That's a
consequence of the purity boundary working well for `dns/` and not being extended anywhere else.

**What can't be verified in this environment at all**, and must be named rather than assumed:

- Behaviour of the DNS filter against a real network with real resolvers.
- The accessibility service against real browsers — address-bar view IDs are a compatibility
  surface that no test in this repo touches.
- Battery cost. There is no benchmark, Macrobenchmark or Perfetto harness anywhere in the tree, so
  the README's battery claim cannot currently be made as a *result*.
- Whether the R8-minified release APK runs at all. It compiles; it has never been installed.

## What's notably absent (gaps to fill)

- `LICENSE` exists (MIT, D-015, W3). No `CHANGELOG.md`, no release workflow, no tags, no
  releases — `CHANGELOG.md` is created by W5, itself blocked on signing (W4/O-002).
- No signing configuration.
- No instrumented or on-device testing of any kind.
- No benchmark harness, while battery is an explicit product requirement.
- An allowlist exists (W9 core, D-017) but has no UI yet, so in practice a false positive in
  the ~100k-entry hosts file still can only be resolved by disabling the entire list until
  the UI half lands — see `docs/work/W9-allowlist.md`.
- No `local.properties` guidance or documented JDK constraint for contributors — the JDK 26
  failure mode above is opaque enough that a new contributor would likely just give up.
