# Work orders

> **Tier:** Execution · **Audience:** an agent or developer implementing, cold ·
> **Use when:** doing the work.

**If you're deciding what to work on next, read [`NEXT.md`](NEXT.md) first, not this board.**
NEXT.md is the standing, self-refilling queue — it exists so an agent running unsupervised always
has an answer to "what now" without waiting on a human to assign something. This file (and the
board below it) is the reference the queue points into.

Each `W*.md` is a self-contained work order. **You do not need to read the rest of `docs/` to
execute one** — everything required is in the file, including the relevant background restated.
The rest of the packet explains *why*; these say *what*.

## Rules of execution

1. **One work order per commit (or a tight series), on your own personal branch**
   (`logandev` or `shelbydev`) — never `shareddev` or `main` directly. When it's done and tested,
   merge it into `shareddev` yourself; that branch is the shared, low-stakes integration point.
   `main` is releases only, merged from `shareddev` deliberately, by a human, at release points.
   See `DECISIONS.md` D-005.
2. **Do not exceed the stated scope.** Each order has an explicit "Out of scope" section. If you
   find something else wrong, note it in your commit message and add it to `NEXT.md` as a new
   item if it's real — don't fix it inline here.
3. **Acceptance criteria are literal.** Every box must be checkable by running something, not by
   judgement.
4. **`./gradlew testDebugUnitTest` must pass before merging into `shareddev`.** No exceptions.
5. **If a work order is marked BLOCKED, do not start it.** Its blocker is a human decision.
6. If reality contradicts the spec, **stop and report** rather than improvising. The spec was
   written against a real read of the code, so a contradiction means something changed.

## Build environment

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ANDROID_HOME=$HOME/Android/Sdk ./gradlew testDebugUnitTest
```

JDK 26 **fails** with a bare `IllegalArgumentException: 26.0.2` — Kotlin 1.9.24 can't parse the
version string. JDK 17 (CI) and 21 both work.

## Hard constraints — violating any of these fails review

- The VPN routes **DNS only** (`/32` + `/128` for its two synthetic servers). Never widen routes.
- `app/src/main/java/app/focusfence/dns/` imports **no Android types**. Ever.
- Blocked queries answer `0.0.0.0`/`::` **with a TTL**, not NXDOMAIN. A bare NXDOMAIN has no SOA,
  is uncacheable, and caused a measured battery regression that is already fixed.
- `setBlocking(true)` on the tun fd stays. A non-blocking fd busy-spins and pins a core.
- The accessibility service's narrowed `packageNames`/`eventTypes` are a **battery** measure.
  `TYPE_WINDOW_CONTENT_CHANGED` is high-volume and must stay conditional.
- Config reaches the services **only** via `SharedPreferences.OnSharedPreferenceChangeListener` on
  the `focusfence` prefs file. A new runtime-relevant key must be handled in both listeners.
- User input is normalized through `Blocklist.normalize` / `normalizePath` at every boundary.

## The board

| # | Work order | Track | Depends on | Status |
|---|---|---|---|---|
| [W1](W1-doc-corrections.md) | Correct three false documentation claims | Release | — | **Done** |
| [W2](W2-ci-release-build.md) | CI builds and verifies the release APK | Release | — | **Done** |
| [W3](W3-license.md) | Add a `LICENSE` file | Release | D-015 | **Done** — acked 2026-08-15 |
| [W4](W4-release-signing.md) | Release signing config + CI secret | Release | O-002 | **BLOCKED** — human decision |
| [W5](W5-release-workflow.md) | Tag → signed APK → GitHub Release, + `CHANGELOG.md` | Release | W2, W4 | Blocked by W4 |
| [W6](W6-extract-rule-evaluator.md) | Extract rule evaluation into `dns/` | Product | — | **Done** |
| [W7](W7-time-bounded-app-blocking.md) | Time-bounded app blocking | Product | W6 | **Done** |
| [W8](W8-boundary-model.md) | The boundary model | Product | W6, W7 | **Parked** — see `docs/parked.md`, D-016 recommendation |
| [W9](W9-allowlist.md) | User allowlist | Product | W6 | **Core done** (`logandev`, 2026-08-15) — UI open, see file |
| [W10](W10-app-budgets-usagestats.md) | App budgets via `UsageStatsManager` | Product | W7 | Needs research Q4 |
| [W11](W11-source-provenance.md) | Multi-source lists with provenance + categories | Product | W9 | Needs research Q2 |
| [W12](W12-shizuku-enforcement.md) | Shizuku package suspension as preferred app enforcement | Product | W7 | Needs research Q3 |
| [W13](W13-benchmark-harness.md) | Battery/CPU benchmark harness | Product | — | **Ready** |
| [W14](W14-adult-content-category.md) | Adult-content filtering: category lists + DNS SafeSearch | Product | W11 | Needs research Q10 |

**W1, W2, W3, W6 and W7 are done.** For what to pick up next, read
[`NEXT.md`](NEXT.md) — it's the standing, ordered queue and stays current in a way a static
"start here" line in this file won't.

## Suggested parallelisation (historical — kept for the pattern, not the specific items)

W1, W2 and W6 touched disjoint files and ran concurrently in three worktrees:

- W1 → `README.md`, `DnsVpnService.kt` (KDoc only), `AdListStore.kt` (comment only)
- W2 → `.github/workflows/android.yml`
- W6 → `dns/RuleEvaluator.kt` (new), `DnsVpnService.kt` (body), new test file

W6 and W1 both touch `DnsVpnService.kt` but in different regions (KDoc header vs. method bodies);
if running both at once, land W1 first — it's smaller.
