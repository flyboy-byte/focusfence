# W13 — Battery/CPU benchmark harness

**Track:** Product (B8) · **Depends on:** nothing · **Status:** Spec draft
**Size:** ~2–3 days. Can be done at any time; blocks nothing, unblocks every performance argument.

## Why

**Battery life is an explicit product requirement**, not a footnote — and there is no benchmark,
Macrobenchmark, Perfetto trace or measurement of any kind anywhere in the tree.

Consequences today:

- The README's "battery and throughput impact is effectively zero" is an inference, not a result
  (W1 corrects the wording; only this work order can restore the claim honestly).
- Two battery regressions have already shipped and been fixed — the non-blocking tun read that
  pinned a core, and the uncacheable NXDOMAIN that woke the service thousands of times a day. Both
  were found by symptom, not by measurement. Nothing prevents a third.
- Every change to the DNS hot path or the accessibility event filter is currently unfalsifiable.
  `docs/design.md` §4 proposes a change whose cost genuinely cannot be argued without this.

## What to measure

Five scenarios, each a distinct claim the project makes about itself:

1. App installed, VPN off, accessibility off — the floor.
2. DNS VPN running, no lists, no rules — cost of the tunnel itself.
3. DNS VPN with a realistic ad list (~100k entries) and a handful of user rules.
4. Accessibility app-blocking only, VPN off — cost of the event filter with `packageNames` narrowed.
5. Accessibility with section rules — the expensive case, where
   `TYPE_WINDOW_CONTENT_CHANGED` is requested.

The interesting numbers are the deltas between them, not absolutes: 2−1 is the tunnel, 3−2 is the
list, 5−4 is the content-changed event type.

## Approach

A crude, reproducible harness beats a sophisticated one that never runs. Options in ascending
order of effort — **start at the top**:

- **`dumpsys batterystats` + `adb shell dumpsys` deltas** over a fixed interval with a scripted
  workload. Cheap, no new dependencies, runs on any device.
- **Perfetto traces** for wakeup counts, which is the metric that actually caught both prior
  regressions. Wakeups per hour is a better proxy for this app's battery cost than mAh.
- **`androidx.benchmark` Macrobenchmark** for the DNS parse/match hot path specifically. This is
  a real dependency and a new module; justify it before adding.

Whatever is chosen must be **documented well enough that someone else can reproduce it**, including
device model, Android version, screen state and workload. An unreproducible number is worse than no
number, because it will be quoted later.

## Deliverables

- A script or module that runs the five scenarios and emits a comparable result.
- `docs/benchmarks/` with dated results, device details and the exact method.
- A baseline recorded for the current release, so future changes have something to diff against.

## Acceptance criteria

- [ ] All five scenarios are runnable by following written instructions, on a device the author
      does not have.
- [ ] Results are recorded with date, device, Android version and method.
- [ ] Wakeups per hour is among the reported metrics.
- [ ] A baseline for the current version is committed.
- [ ] The harness adds **no** dependency or code to the release APK — it must not ship inside the
      app.
- [ ] If a new module is added, `assembleRelease` output size is unchanged.

## Out of scope

- CI-based benchmarking. Emulator battery numbers are meaningless; this needs real hardware.
- Optimising anything. This work order measures; it does not change the app.
- Restoring the README's battery claim — do that in a follow-up, once there's a number.
