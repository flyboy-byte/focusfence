# Work orders

> **Tier:** Execution · **Audience:** an agent or developer implementing, cold ·
> **Use when:** doing the work. This is the only directory you need open.

Each `W*.md` is a self-contained work order. **You do not need to read the rest of `docs/` to
execute one** — everything required is in the file, including the relevant background restated.
The rest of the packet explains *why*; these say *what*.

## Rules of execution

1. **One work order per branch, per PR.** Branch off `dev`, name it `w<N>-<slug>`
   (e.g. `w1-doc-corrections`). PR into `main`.
2. **Do not exceed the stated scope.** Each order has an explicit "Out of scope" section. If you
   find something else wrong, note it in the PR body — don't fix it here.
3. **Acceptance criteria are literal.** Every box must be checkable by running something, not by
   judgement.
4. **`./gradlew testDebugUnitTest` must pass before opening the PR.** No exceptions.
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
| [W1](W1-doc-corrections.md) | Correct three false documentation claims | Release | — | **Ready** |
| [W2](W2-ci-release-build.md) | CI builds and verifies the release APK | Release | — | **Ready** |
| [W3](W3-license.md) | Add a `LICENSE` file | Release | O-001 | **BLOCKED** — human decision |
| [W4](W4-release-signing.md) | Release signing config + CI secret | Release | O-002 | **BLOCKED** — human decision |
| [W5](W5-release-workflow.md) | Tag → signed APK → GitHub Release, + `CHANGELOG.md` | Release | W2, W4 | Blocked by W4 |
| [W6](W6-extract-rule-evaluator.md) | Extract rule evaluation into `dns/` | Product | — | **Ready** |
| [W7](W7-time-bounded-app-blocking.md) | Time-bounded app blocking | Product | W6 | Ready after W6 |
| [W8](W8-boundary-model.md) | The boundary model | Product | W6, W7 | Spec draft |
| [W9](W9-allowlist.md) | User allowlist | Product | W6 | Spec draft |
| [W10](W10-app-budgets-usagestats.md) | App budgets via `UsageStatsManager` | Product | W7, W8 | Needs research Q4 |
| [W11](W11-source-provenance.md) | Multi-source lists with provenance + categories | Product | W9 | Needs research Q2 |
| [W12](W12-shizuku-enforcement.md) | Shizuku package suspension as preferred app enforcement | Product | W8 | Needs research Q3 |
| [W13](W13-benchmark-harness.md) | Battery/CPU benchmark harness | Product | — | Spec draft |

**Start with W1, W2 and W6 — all three are ready, independent of each other, and blocked on
nobody.**

## Suggested parallelisation

W1, W2 and W6 touch disjoint files and can run concurrently in three worktrees:

- W1 → `README.md`, `DnsVpnService.kt` (KDoc only), `AdListStore.kt` (comment only)
- W2 → `.github/workflows/android.yml`
- W6 → `dns/RuleEvaluator.kt` (new), `DnsVpnService.kt` (body), new test file

W6 and W1 both touch `DnsVpnService.kt` but in different regions (KDoc header vs. method bodies);
if running both at once, land W1 first — it's smaller.
