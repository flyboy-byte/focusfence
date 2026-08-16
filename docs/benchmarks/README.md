# Battery/CPU benchmarks (W13)

> **Tier:** Low-level (implementer/reproducer) · **Audience:** anyone re-running or extending
> these numbers · **Use when:** arguing about the battery cost of a change to the DNS hot path
> or the accessibility event filter — this is the only place in the repo with a measured number
> instead of an inference.

## Method

`scripts/battery-bench.sh <scenario-name> <duration-seconds>`, run against a real device over
`adb`. It:

1. `adb shell dumpsys batterystats --reset` — zeroes accumulated stats.
2. `adb shell dumpsys battery unplug` — see "Why unplug" below.
3. Sleeps for the given duration (exercise the scenario during this window).
4. `adb shell dumpsys batterystats app.focusfence` plus a system-wide alarm dump, saved to
   `docs/benchmarks/raw/<scenario>-<timestamp>.txt`.
5. `adb shell dumpsys battery reset` — restores real charging state.

**Why unplug.** `dumpsys batterystats` only accrues discharge-style stats while Android believes
the device is on battery. A benchmark run tethered over USB (which `adb` requires) would
otherwise report zero for everything. `dumpsys battery unplug` tells the framework to *report*
unplugged without physically disconnecting power — the standard technique for on-bench battery
measurement. `dumpsys battery reset` at the end undoes this; don't skip it, or the device will
report a fake battery state until it's rebooted or physically unplugged and replugged.

**No dependency ships in the app.** The script only calls `adb shell` against an already-built
debug APK; nothing it does touches `app/` source or `app/build.gradle.kts`.

## Reproducing

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk   # once per build
scripts/battery-bench.sh <scenario-name> <seconds>
```

Read the result with:

```sh
grep -iE 'wakeup|wake lock|mobile radio active|cellular battery drain|screen on:' \
  docs/benchmarks/raw/<file>.txt
```

The interesting section per scenario is `## Full batterystats dump for app.focusfence` (app-level
cost) and `Recent waking activity` / `Wakeup events` under `CPU wakeup stats` (system-wide, for
spotting whether Focus Fence is one of the processes causing a wakeup — this is the metric that
would have caught the two prior battery regressions, since both were "wakes far more often than
it needs to," not "uses more mAh per wake").

## Scenarios and status

| # | Scenario | Status |
|---|---|---|
| 1 | Installed, VPN off, accessibility off (the floor) | ✅ Measured 2026-08-16 |
| 2 | DNS VPN running, no lists, no rules | Harness ready — needs one-time VPN consent grant, see below |
| 3 | DNS VPN with a realistic ad list + user rules | Harness ready — same as above, plus list import |
| 4 | Accessibility app-blocking only, VPN off | Harness ready — needs one-time accessibility grant, see below |
| 5 | Accessibility with section rules (`TYPE_WINDOW_CONTENT_CHANGED`) | Harness ready — same as above, plus a section rule configured |

**Why 2–5 aren't measured yet, stated plainly rather than papered over:** `VpnService.prepare()`
shows a system consent dialog that requires a physical tap — there is no `adb`-only way to grant
it non-interactively, by design (it's the same protection that stops malware from silently
routing your traffic). `AccessibilityService` grants are similarly gated behind Settings UI. Both
are one-time, ~10-second manual steps; **grant them once and every future run of this script for
scenarios 2–5 is fully automated.** Exact steps:

- **VPN (scenarios 2–3):** open Focus Fence, tap the enable toggle, tap "OK" on the system VPN
  consent dialog. Stays granted across app restarts; only needs redoing after `adb uninstall`.
- **Accessibility (scenarios 4–5):** Settings → Accessibility → Focus Fence → enable. Or:
  `adb shell settings put secure enabled_accessibility_services app.focusfence/app.focusfence.accessibility.UrlFilterService`
  followed by `adb shell settings put secure accessibility_enabled 1` (verify it actually took —
  some OEM builds ignore this and require the UI toggle).

## Results

### Scenario 1 — floor (installed, VPN off, accessibility off)

- **Date:** 2026-08-16
- **Device:** Pixel 7, Android 16 (API 36)
- **Duration:** 60s, screen on, device otherwise idle
- **Raw:** [`raw/scenario1-floor-2026-08-16_1215.txt`](raw/scenario1-floor-2026-08-16_1215.txt)

| Metric | Value |
|---|---|
| Wakeup events attributed to `app.focusfence` | **0** |
| Alarms registered by `app.focusfence` | 0 |
| Cellular battery drain (system-wide, for scale) | 0.0793 mAh over 60s |
| Full wakelock time held by `app.focusfence` | 0ms |

This is the expected result for the floor case — the app was installed but not running either
enforcement mechanism, so it should be indistinguishable from "not installed." It is. This
number is the zero that scenarios 2–5 get compared against once they're run; a floor scenario
that already showed nonzero wakeups would itself be a finding.

**60 seconds is a smoke-test interval, not a real baseline window.** It's enough to prove the
harness end-to-end and to show the floor really is zero, but "wakeups per hour" wants a run of at
least 15–30 minutes to be a number worth quoting in the README. Re-run scenario 1 at that length
before treating it as final.

## What this doesn't establish yet

Per `docs/work/W13-benchmark-harness.md`'s scope: this is a measurement harness, not an
optimization pass, and it does not yet restore the README's "battery/throughput impact is
effectively zero" claim — that needs scenarios 2, 3 and 5 actually run (the deltas 2−1, 3−2 and
5−4 are the numbers that would substantiate or contradict it), at a longer duration, ideally with
the device actually off USB power for at least one confirmatory run since `dumpsys battery
unplug` is a reporting trick, not physical validation.
