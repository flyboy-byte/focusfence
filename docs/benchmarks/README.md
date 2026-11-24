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

All five scenarios are now measured for real, on the same device in one sitting (driven over
`adb` via `uiautomator`/`input tap` — VPN consent and the accessibility grant each needed one
scripted tap through the real system dialogs, not a bypass).

| # | Scenario | Status |
|---|---|---|
| 1 | Installed, VPN off, accessibility off (the floor) | ✅ Measured 2026-08-16, 60s |
| 2 | DNS VPN running, no lists, no rules | ✅ Measured 2026-08-16, 120s |
| 3 | DNS VPN with a realistic ad list (98,950 domains) + a section rule + an app rule | ✅ Measured 2026-08-16, 120s |
| 4 | Accessibility app-blocking only, VPN off | ✅ Measured 2026-08-16, 120s |
| 5 | Accessibility with section rules (`TYPE_WINDOW_CONTENT_CHANGED`) | ✅ Measured 2026-08-16, 120s |

**How VPN/accessibility were granted:** `VpnService.prepare()`'s consent dialog and the
`AccessibilityService` Settings toggle both require a real tap on system UI — there is no
`adb`-only bypass, by design (it's the same protection that stops malware from silently routing
traffic). Both were granted with one scripted `adb shell input tap` on the actual system dialog
during this session; they stay granted across app restarts, so a future run only needs to repeat
this if the app is reinstalled. `adb shell settings put secure enabled_accessibility_services
app.focusfence/app.focusfence.accessibility.UrlFilterService` (plus `accessibility_enabled 1`)
works as a non-interactive alternative for accessibility specifically.

## Results

| Scenario | Duration | Cellular battery drain | Rate (mAh/s) | Wakeup events (`app.focusfence`) | Alarms registered |
|---|---|---|---|---|---|
| 1 — floor | 60s | 0.0793 mAh | 0.00132 | **0** | 0 |
| 2 — VPN, no lists | 120s | 0.157 mAh | 0.00131 | **0** | 0 |
| 3 — VPN, full list (98,950 domains) + rules | 120s | 0.157 mAh | 0.00131 | **0** | 0 |
| 4 — accessibility, app-blocking | 120s | 0.160 mAh | 0.00133 | **0** | 0 |
| 5 — accessibility, section rules | 120s | 0.157 mAh | 0.00131 | **0** | 0 |

Raw dumps: [`raw/`](raw/) — one file per scenario, named `<scenario>-<timestamp>.txt`, each
carrying its own date/device/method header.

**Reading this table.** The rate column is the number that matters — it's flat across all five
scenarios to within measurement noise (~0.0013 mAh/s regardless of whether the VPN, the ad list,
or the accessibility service is doing anything). None of the four enforcement configurations
moved the needle relative to the floor, and **zero wakeup events were attributed to
`app.focusfence` in any scenario**, including the two that would have caught the app's prior two
battery regressions (the non-blocking tun read and the uncacheable NXDOMAIN both would have shown
up here as nonzero wakeups). That's a real result, not an inference — the README's "battery
impact is effectively zero" claim now has a measurement behind it for the first time.

**Caveats, stated plainly rather than smoothed over:**
- **120 seconds is still a short window.** It's long enough to be more than a smoke test and to
  show the rate is stable across scenarios, but "wakeups per hour" as a quotable number wants
  15–30 minutes per scenario. Worth re-running at that length before this goes in the README
  verbatim — the direction of the result is unlikely to change, but the precision would improve.
- **`dumpsys battery unplug` is a reporting trick, not physical validation.** The device was on
  USB power (required for `adb`) throughout. A confirmatory run with the device actually
  unplugged, phone-only, would close the one remaining gap between "batterystats reports this"
  and "the battery actually drains this slowly."
- **Scenario 3's list load happened once, before the timed window started** — the 60s the
  benchmark measures is steady-state cost with the list already resident, not list-download or
  first-parse cost. That's the right thing to measure (matches the scenario's own definition,
  "with a realistic ad list"), but it's worth being explicit that a one-time parse cost exists and
  isn't captured here.
