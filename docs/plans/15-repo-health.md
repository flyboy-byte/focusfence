# Plan 15: repo health, from the 2026-11-26 assessment

**Status:** scoped, not started (2026-11-26). Logan: "scope out the assessment as a fix". Each item stands alone and can
be picked in any order; the order below is the recommended one.

**Why:** the assessment found strong architecture, diagnostics and release automation, and three weak spots: features
ship before anyone runs them on a phone, the Android side has no automated tests, and docs drift when a system is
replaced (four public docs still described plan 12's partners two days after plan 13 removed them).

| # | Fix | Size | Needs Logan |
|---|---|---|---|
| 1 | ~~Issue #18: a google.com rule stops all push notifications~~ DONE (2026-11-26) | ~1 h | no |
| 2 | ~~Docs drift guard in CI~~ DONE (2026-11-26) | ~1 h | no |
| 3 | ~~Partner flow test across app and server (JVM)~~ DONE (2026-11-26) | ~half a day | no |
| 4 | Emulator smoke test in CI | ~half a day | parked (2026-11-26) |
| 5 | ~~Lint triage (130 warnings)~~ DONE (2026-11-26) | ~1 h | no |
| 6 | Split the biggest files | as they're touched | no |
| 7 | targetSdk 35 | ~2 h + a phone check | the phone check |
| 8 | Server lookups by index | only past ~100 devices | parked |

## 1. ~~Issue #18: shared infrastructure under a site rule~~ DONE (2026-11-26)

The DNS side had already shipped in #22 (2026-11-24, `RuleEvaluator.blocks` + `Blocklist.isInfrastructure`, with a
wider list than below: FCM `googleapis.com` hosts too); only the issue was left open. This pass added the rule editor
line (`Blocklist.coversInfrastructure`, `infraNote`) and closed #18.


A rule on `google.com` blocks `mtalk.google.com` (Firebase Cloud Messaging), so push stops for every app, silently.
The issue's option 2: rules keep meaning "the whole site", but a short list of shared infrastructure hosts is never
blocked by a user rule.

- `dns/Blocklist`: `INFRASTRUCTURE` (`mtalk.google.com`, `mtalk4.google.com`, `alt*-mtalk.google.com`,
  `android.clients.google.com`, `time.android.com`, `connectivitycheck.gstatic.com`), checked before user rules only
  (the adult and bypass lists are unaffected).
- Rule editor: one line under a rule that covers one of them ("Push notifications keep working").
- Tests in `BlocklistTest`; close #18 with the PR.

## 2. ~~Docs drift guard~~ DONE (2026-11-26)

Built as below, plus `double opt-in`; history lines opt out with `<!-- docs: past -->` (one, in `server/README.md`).
AGENTS.md → "How to add or change a feature" step 8.


`tools/check_docs.py`, run in `android.yml`: fails when a public doc (README, SECURITY, CONTRIBUTING, `docs/site/`,
`server/README.md`, CLAUDE.md) names something removed. The list lives in the script, one line per term with the plan
that removed it: `mailbox`, `SMS`, `pairing key`, `/i/`, `X-FF-Invite`, `GroupSync`, `unlock code`. Dated history docs
(AGENTS.md status log, DECISIONS.md, `docs/plans/`) are exempt. Rule added to AGENTS.md: a plan that removes a feature
lists the docs to update in its clean-cut section.

## 3. ~~Partner flow test across app and server~~ DONE (2026-11-26)

`server/src/test/.../PartnerFlowTest.kt`, two tests. (1) Invite: QR link read by `InviteLink`, join, enroll with the
partner token, seal, report opens; a second partner by an undashed code; removing one rotates the key, the other still
reads and the removed one's old key can't open the new report. (2) A group of three: everyone reads everyone, one
leaves, the other two still read each other, the leaver's old key opens nothing new. Checked by breaking it: a phone
that ignores `rotateKey`, or seals with the screenshot label instead of `INFO_KEY`, fails both.


The bugs that reach phones live between the two halves (signatures, field names, the seal round trip). A JVM test in
`server/src/test` that drives the server the way the app does, with the app's pure code (`dns/Ecies`, `ReportSeal`,
`InviteLink`, `Protocol`, already compiled into the server): enroll → invite → join by code → heartbeat `seal` list →
seal → partner opens the key → report decrypts; the same for a group of three. `CompatLevel2Test` covers single calls;
this covers the sequence. No Android needed.

## 4. Emulator smoke test (parked 2026-11-26)

**Parked:** Logan tests every build on two phones before a tag, and that caught the one Android-side partner bug so far
("second phone not registered"). The cost is half a day plus ~8 min of flaky-prone CI per sync PR. **Build it when:**
Android-side partner bugs reach a phone more than once, or someone else starts changing `sync/`.


What item 3 can't reach: `SyncWorker`, `Accountability`, `PartnerKey` (keystore), the Partners screen.
`reactivecircus/android-emulator-runner` in a new `smoke.yml` (on PRs touching `sync/` or `server/`), the server started
in the same job on the emulator's host address. One instrumented test: enroll, create an invite, join it from the same
phone as a partner (allowed: one phone can be both), run the worker twice, assert the report key arrived. Slow (~8 min),
so not on every push.

## 5. ~~Lint triage~~ DONE (2026-11-26)

130 → 0 new, 37 kept in `app/lint-baseline.xml` (`./gradlew updateLintBaseline` after fixing one).

| Warning | Done |
|---|---|
| UnusedResources 79 | Deleted (77 strings and plurals from removed screens, 2 icons; no `getIdentifier` lookups, no translations) |
| PluralsCandidate 11 | The budget block message is a plural now ("your 1 minute"); `acc_devices` shows only for 2+; the rest read right in English ("1 blocked", "1 left"): baseline |
| ApplySharedPref 9 | All deliberate (sync worker; losing the report key or enrollment to a kill is unrecoverable): `@SuppressLint` on `SyncStore` and `ApprovalStore` with the reason |
| StaticFieldLeak 2 | Real: `ScreenCapture` kept the accessibility service and its timer after the service died. `onServiceGone()` from `UrlFilterService.onDestroy`. Lint still flags the field: baseline |
| GradleDependency 7 | All need compileSdk 35 or newer (core-ktx 1.15+, activity 1.10+, work 2.10+, material 1.13+): item 7 |
| Also fixed | Remove button on an allowed site had no label for screen readers; Android 8 logged every network as roaming (`NOT_ROAMING` is API 28) |
| Kept, cosmetic | Compat drawable attributes, overdraw, a useless parent, RTL padding, button-bar style, `BatteryLife` (the battery prompt is deliberate; not on Play) |


130 warnings: 79 `UnusedResources` (mostly strings from removed features: delete), 11 `PluralsCandidate` (use plurals
where a count leads), 9 `ApplySharedPref` (check each `commit()` is deliberate, comment it), 7 `GradleDependency`
(record which are pinned by compileSdk 34, see item 7), 2 `StaticFieldLeak` (look). Then a lint baseline so new
warnings show.

## 6. Split the biggest files (as they're touched)

`DnsVpnService` 1050 lines, server `Main.kt` 836, `UiUtil` 664, `SyncWorker` 639, `AccountabilityFragment` 582. No
rewrite: when a change touches one, move the part it touches out first (`Main.kt` routes into `Routes*.kt`,
`SyncWorker` partner side into `PartnerSync`, the fragment's invite and join dialogs into their own files).

## 7. targetSdk 35 (~2 h + a phone check)

Step 1 done (2026-11-27): compileSdk 35, AGP 8.5.2 → 8.7.3 (Gradle 8.9 already). Next: library updates, then targetSdk.


Not needed for GitHub installs; needed for F-Droid (plan 11) or Play. `work-runtime` is pinned at 2.9.x by compileSdk 34.
Raise compileSdk/targetSdk to 35, then WorkManager 2.10, and check Android 15 changes that bite this app: edge-to-edge
by default (every screen's insets), foreground service types for the VPN, and background activity starts from the
accessibility service. Logan checks every screen on the Pixel.

## 8. Server lookups by index (parked)

`partnerByToken`, `inviteByHash` and the group lookups read every device or group file per request. Fine under the
100-device cap. If the cap is raised: an in-memory token → device index rebuilt at start and updated on writes.
