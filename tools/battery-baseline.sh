#!/usr/bin/env bash
# External battery measurement with adb, to compare against the app's own diag log.
#
# The in-app diag log can only sample while a Focus Fence service is running and
# only sees whole-device drain plus its own CPU time. Android's batterystats sees
# everything, including per-app attribution, so use it for the baseline and for
# the A/B runs in docs/plans/03-measurement-plan.md.
#
#   tools/battery-baseline.sh start            # unplug-ready: reset stats
#   ... use the phone normally for the run (ideally 8h+, unplugged) ...
#   tools/battery-baseline.sh stop  run-name   # saves reports to measurements/run-name/
#
# Requires adb with the phone connected (USB or wireless debugging). On USB,
# the phone counts as charging; "start" tells batterystats to ignore that, but
# for clean numbers run wirelessly or disconnect between start and stop.
set -euo pipefail

PKG=app.focusfence
cmd=${1:-}

case "$cmd" in
  start)
    adb shell dumpsys batterystats --reset
    adb shell dumpsys battery unplug   # treat as unplugged even on USB
    echo "batterystats reset at $(date). Use the phone, then run: $0 stop <run-name>"
    ;;
  stop)
    name=${2:?usage: $0 stop <run-name>}
    out="measurements/$name"
    mkdir -p "$out"
    adb shell dumpsys battery reset
    adb shell dumpsys batterystats --charged "$PKG" > "$out/batterystats-$PKG.txt"
    adb shell dumpsys batterystats --checkin > "$out/batterystats-checkin.csv"
    adb shell dumpsys batterystats > "$out/batterystats-full.txt"
    adb shell dumpsys battery > "$out/battery.txt"
    adb shell dumpsys deviceidle > "$out/deviceidle.txt" || true
    adb shell dumpsys activity services "$PKG" > "$out/services.txt" || true
    adb shell getprop ro.build.fingerprint > "$out/build.txt"
    # Per-uid estimated power, if this Android version prints it.
    grep -iE "Estimated power use|Uid u0a|$PKG" "$out/batterystats-full.txt" | head -60 > "$out/power-summary.txt" || true
    echo "saved to $out/ — keep the in-app export from the same run next to it."
    echo "For a timeline view, load a bugreport into Battery Historian or Perfetto:"
    echo "  adb bugreport $out/bugreport.zip"
    ;;
  *)
    echo "usage: $0 start | stop <run-name>" >&2
    exit 1
    ;;
esac
