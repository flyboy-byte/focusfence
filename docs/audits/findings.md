# Verification pass over the audits

The four research documents plus the executive summary in this directory were produced
externally, working from a `focus-fence.zip` export of this repository taken a few hours
before the research. Before any of their recommendations become work, every claim they make
**about this codebase** was checked against the actual source at `origin/main`.

The snapshot appears to have been current: audit 03 cites specific line ranges
(`DnsVpnService.kt:180-193`, `Blocklist.kt:156-168`, `AdListStore.kt:14-24`) and every one of
them resolves to exactly the code it describes in this tree. So where a claim below is wrong,
it is an error of reading or synthesis — not a stale copy of the source.

This file is that check. Nothing here is a proposal — see [`../plans/`](../plans/) for those.

Three verdicts are used:

- **Confirmed** — checked against the source, true as written.
- **Wrong** — checked, and false for this tree. Acting on it would waste effort or cause harm.
- **Not verified** — a claim about the outside world (competitors, filter lists, Play policy).
  The verification pass had no network access; these are neither endorsed nor disputed.

---

## 1. Confirmed claims about this codebase

These are accurate, and several are load-bearing enough that future changes should not
break them without deliberate intent.

| Claim | Where it's true |
|---|---|
| The VPN adds only `/32` + `/128` host routes for its synthetic DNS addresses; no `0.0.0.0/0` or `::/0`. General traffic never enters the app. | `DnsVpnService.kt:186-192` |
| Upstream resolver sockets are `protect()`ed, so relayed queries don't loop back through the tunnel. | `DnsVpnService.kt:359` |
| The TUN fd is explicitly put in blocking mode rather than busy-polled. | `DnsVpnService.kt:184` |
| The accessibility service narrows `packageNames` to only the apps it acts on, requests high-volume content-changed events only while section rules exist, batches delivery, throttles URL checks, and bounds its fallback tree walk. | `UrlFilterService.kt:98-115`, `MAX_FALLBACK_NODES = 250` |
| Strict mode is a 10-minute delay followed by a 5-minute edit window; the VPN service refuses its own stop action while locked. | `StrictLock.kt:24-25`, `DnsVpnService.kt:146-152` |
| Website time is inferred from DNS activity with a 3-minute session gap — an estimate, not measured attention. | `UsageTracker.kt:26`, `UsageTracker.kt:44-62` |
| Matching logic is deliberately free of Android types and unit-tested on the JVM. | `dns/` package, 8 test classes in `app/src/test/` |
| The ad list is a single source (StevenBlack), parsed wholesale into one `HashSet<String>` with no category or provenance information retained. | `AdListStore.kt`, `HostsParser.kt:19` |
| Parent-domain walking means a single `example.com` entry already covers unknown subdomains — the app is more expressive than a literal hosts file. | `Blocklist.kt:162-168` |
| **There is no `LICENSE`, `COPYING` or `NOTICE` file.** | repo root |
| Targets/compiles against API 34, `minSdk 26`, declares `FOREGROUND_SERVICE_SPECIAL_USE` with `foregroundServiceType="specialUse"`. | `app/build.gradle.kts`, `AndroidManifest.xml` |
| A fixed list of DoH bootstrap hostnames plus the `use-application-dns.net` canary is hardcoded. | `Blocklist.kt:81-92` |
| No benchmark, Macrobenchmark, Perfetto or battery-measurement harness exists anywhere in the tree. | no `androidTest/`, no benchmark module |

### 1a. The two documentation claims that are actually wrong in the repo

Both audits flagged these independently, and both are correct.

**The README overstates privacy.** It says "nothing about your browsing ever leaves your
phone." Allowed DNS queries are forwarded to Cloudflare `1.1.1.1` and Google `8.8.8.8`
(`DnsVpnService.kt:103-108`). That is ordinary DNS resolution, not a cloud backend — but the
sentence as written is not true, and for a project whose pitch is privacy, the precise version
is stronger than the sweeping one.

**The README states battery impact as fact.** "Battery and throughput impact is effectively
zero" is an inference from the architecture, not a measurement, and no measurement exists in
the tree. The architecture genuinely justifies expecting a low cost; it does not justify the
number.

**Additionally** (not raised by any audit): `AdListStore.kt:16-18` describes StevenBlack as
"aggregated from the same sources AdGuard/uBlock use." Audit 03 objects, and is right —
StevenBlack is an aggregator of hosts sources, while uBlock Origin's defaults are EasyList-family
lists in a far richer syntax. Not the same pipeline.

---

## 2. Wrong for this tree

Almost all of these come from `00-executive-summary.md`. That document is a synthesis layer
written *across* the other four rather than from the source, and it shows: it repeats
second-hand descriptions of the code, and several are simply wrong about a tree it had in
front of it. The three named audits that actually opened files — 01, 02 and 03 — are markedly
more accurate, and 02 is the most reliable of the five. **Weight them accordingly, and treat
the exec summary's technical assertions as needing a source check before use.**

### 2.1 "A basic openVPN-based DNS blocker"
There is no OpenVPN anywhere. This is a raw `VpnService` with hand-written IPv4/IPv6 + UDP
parsing (`Packets.kt`) and hand-written DNS wire-format handling (`Dns.kt`). No networking
dependency at all — `app/build.gradle.kts` pulls in only AndroidX and Material.

### 2.2 "The engine should be data-driven — load lists from files/downloads, not hardcoded"
Already true. User rules live in `SharedPreferences` (`BlocklistStore`), and the ad list is
downloaded at runtime into private storage (`AdListStore.download`). The only hardcoded domain
sets are the ten social-media presets and the DoH guard list, both intentional.

### 2.3 "Policy — time budgets, categories — should live in a settings database, not in code"
Already true for budgets and schedules; they are encoded rules in prefs, not code. Categories
don't exist yet, which is a real gap, but it is not the gap described.

### 2.4 Roadmap items marked P0/P1 that are already shipped
The exec summary's prioritized table proposes as new work several things that are already in
the app — and were in the snapshot it was given:

| Proposed as new | Actually already implemented |
|---|---|
| P0 "Commitment mode (delayed disable)" | `StrictLock` + `StrictStore`, enforced in service and UI |
| P1 "Basic Accessibility enforcement — detect forbidden URL, block" | `UrlFilterService` + `UrlFilter`, across 10 browsers with a heuristic fallback |
| P0 "App selection … block specific apps" | `BlockedAppsStore` + accessibility home-bounce |
| P0 "Category toggles" (partially) | single ads/trackers toggle exists; multi-category does not |
| P0 "App/time budget" (partially) | per-**domain** daily budgets exist; per-**app** budgets do not |

Roughly half the proposed P0/P1 tier is already in the app. The genuinely-absent items are
UsageStats, Shizuku, Device Owner, multi-source/category lists, and per-app budgets.

### 2.5 "Starting Android 10, apps do not trust user-added CA certificates by default"
Wrong version, and **the audits contradict each other here**. The correct boundary is
Android 7.0 / API 24: apps *targeting* API 24+ stopped trusting the user CA store by default.
`02-android-platform.md` states this correctly ("targeting Android 6/API 23 and lower");
the exec summary says Android 10. Trust the platform audit.

The conclusion both draw — that local HTTPS MITM is not a realistic "just install a
certificate" architecture — is unaffected and correct.

### 2.6 "Android 9+ (supports VpnService)"
`VpnService` has existed since API 14 (Android 4.0). The relevant floor for this project is
its own `minSdk 26`, not a VpnService availability limit.

### 2.7 Every reference to blocking via NXDOMAIN
All five documents describe blocked queries as answered with NXDOMAIN. **The code has not done
that since commit `0552f11`.** `Dns.buildBlockedResponse` returns a real A/AAAA answer of
`0.0.0.0` / `::` carrying an explicit TTL, or NODATA for other query types. `Dns.buildNxDomain`
still exists but is no longer used by the block path.

This is deliberate and important: its KDoc explains that a bare NXDOMAIN without an SOA is
uncacheable, so clients re-asked endlessly and "was waking the VPN thousands of times a day."
Block answers are cached for 1 hour (ads/guards) or 60 seconds (user rules), because user rules
change with schedules and budgets.

The audits aren't at fault — **the repo told them NXDOMAIN.** Both the README and
`DnsVpnService.kt:47-48` still say it. The docs are stale, not the code. Any plan that
"restores correct NXDOMAIN behaviour" would re-introduce a fixed battery regression.

### 2.8 License-compatibility table errors (`00-executive-summary.md`)
The table contradicts its own prose two paragraphs later, and contradicts audit 03.

- It marks GPL-3.0 ← Apache-2.0 as **✗ "unless dual-licensing."** Apache-2.0 *is* one-way
  compatible into GPLv3; the same document then says so in prose.
- `04-cloudveil-repos.md` states "MPL-2 is not GPL-compatible by default." The opposite is
  true: MPL-2.0 is explicitly GPL-compatible unless the file is marked "Incompatible With
  Secondary Licenses." Audit 03 and the exec summary both describe this correctly elsewhere.

The overall recommendation (MPL-2.0 or Apache-2.0) survives; the table should not be used as a
reference without correction.

### 2.9 Misattributed repository
`04-cloudveil-repos.md` cites "adblock-android by gorhill." gorhill is the author of uBlock
Origin; `adblock-android` is an Adblock Plus / eyeo project. Anyone following that pointer
will not find what the document describes.

### 2.10 The "DNS64 trick" discussion
Muddled, and moot regardless — the app already answers blocked queries with `0.0.0.0`/`::`
directly for both address families.

---

## 3. A gap none of the audits caught

Several documents assume a **user allowlist / local override** already exists — "local
exceptions," "user can whitelist on the fly," "user overrides: 14 allowed."

**There is no allowlist anywhere in this codebase.** `blockReason()` checks user rules, then
the ad-host set, then the DoH guards, and there is no path by which a user can exempt a domain
from a source that blocks it. In practice: a single false positive in a 100k-entry downloaded
list can only be resolved by turning the entire ad list off.

This is a real and cheap-to-fix gap, and it is a prerequisite for every "multiple sources with
provenance" proposal in audits 01 and 03 — the moment more lists exist, false positives stop
being hypothetical.

---

## 4. Not verified

Claims about the world outside this repo. The verification pass had no network access, so
these are recorded, not endorsed. They should be re-checked before any of them justifies a
decision.

- Competitor feature sets and licenses (Mindful, Curbox, DetoxDroid, Freedom, one sec,
  ScreenZen, AdAway, RethinkDNS, personalDNSfilter, NetGuard, PCAPdroid, DNS66).
- Filter-list statistics and licensing (StevenBlack's 97,646 entries as of 2026-08-11,
  HaGeZi tier/category counts, OISD's policy, Block List Project's Unlicense, URLhaus's
  5-minute regeneration and authentication requirement, EasyList's dual GPLv3/CC-BY-SA terms).
- Google Play policy specifics for `VpnService` and accessibility services.
- The claim that Play will require target API 36 from 2026-08-31. Plausible and consistent
  with Google's annual cadence, but unverified — **and currently moot**: this project ships as
  a CI artifact for sideloading, not through Play. It becomes relevant only if Play
  distribution is ever intended.
- Android API behaviours not exercised by this codebase (Device Owner provisioning
  constraints, Shizuku's post-reboot re-initialisation, `UsageStatsManager` semantics). These
  are consistently and carefully sourced in `02-android-platform.md`, which was the most
  reliable of the five documents wherever it *was* checkable — but they remain unverified here.

---

## 5. Editorial changes to the delivered text

The audit files are otherwise exactly as delivered. Two changes were made:

1. **Filenames** normalised (`executive summery.md` → `00-executive-summary.md`, etc.) for
   ordering and to remove spaces/colons from paths.
2. **Two mermaid diagrams** in `00-executive-summary.md` were syntactically invalid and would
   have rendered as error blocks on GitHub: an edge written `A -- label -- B` with no
   arrowhead, a node written `*(User Device)*`, a `%` comment where mermaid requires `%%`, and
   subgraph titles containing unquoted brackets and parentheses. Syntax fixed; the content and
   meaning of both diagrams are unchanged.

No claim, judgement or recommendation in any of the five documents was altered.
