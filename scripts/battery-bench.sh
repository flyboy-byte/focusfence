#!/usr/bin/env bash
# W13 battery/CPU benchmark harness. See docs/work/W13-benchmark-harness.md and
# docs/benchmarks/README.md for the five scenarios, the method, and how to read the output.
#
# This script never ships in the app -- it drives an already-installed debug build over adb.
# It does not modify app code or app data beyond what a normal run of the app would do.
#
# Usage:
#   scripts/battery-bench.sh <scenario-name> <duration-seconds>
#
# What it does:
#   1. Resets batterystats (adb shell dumpsys batterystats --reset)
#   2. Fakes "unplugged" via `dumpsys battery unplug` so batterystats accrues discharge-style
#      stats even though the device stays powered over USB for the adb connection itself.
#      This is the standard technique for on-bench battery measurement without an actual
#      battery drain run. See "Why unplug" in docs/benchmarks/README.md.
#   3. Sleeps for <duration-seconds>, during which you (or a scripted workload) exercise the
#      scenario -- e.g. leave the device idle, or interact with the app.
#   4. Dumps batterystats and a focused extract (wakeup alarms, wakelocks, mobile radio active
#      time) for app.focusfence's UID into docs/benchmarks/raw/<scenario-name>-<date>.txt
#   5. Restores real charging state (`dumpsys battery reset`).
#
# Requires: adb on PATH, exactly one device attached (or ANDROID_SERIAL set), app.focusfence
# already installed in the state the scenario calls for (VPN running, accessibility on, etc --
# this script does not toggle those for you; see docs/benchmarks/README.md).

set -euo pipefail

SCENARIO="${1:?usage: battery-bench.sh <scenario-name> <duration-seconds>}"
DURATION="${2:?usage: battery-bench.sh <scenario-name> <duration-seconds>}"
PKG="app.focusfence"
OUT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/docs/benchmarks/raw"
DATE="$(date +%Y-%m-%d_%H%M)"
OUT_FILE="${OUT_DIR}/${SCENARIO}-${DATE}.txt"

mkdir -p "$OUT_DIR"

if ! command -v adb >/dev/null 2>&1; then
  echo "adb not on PATH" >&2
  exit 1
fi

DEVICE_COUNT="$(adb devices | grep -c 'device$' || true)"
if [ "$DEVICE_COUNT" -ne 1 ]; then
  echo "expected exactly one attached device, found $DEVICE_COUNT (set ANDROID_SERIAL if you have more than one)" >&2
  exit 1
fi

if ! adb shell pm list packages | grep -q "package:${PKG}\$"; then
  echo "${PKG} is not installed on this device" >&2
  exit 1
fi

MODEL="$(adb shell getprop ro.product.model | tr -d '\r')"
RELEASE="$(adb shell getprop ro.build.version.release | tr -d '\r')"
SDK="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"

echo "Scenario: $SCENARIO"
echo "Device:   $MODEL, Android $RELEASE (API $SDK)"
echo "Duration: ${DURATION}s"
echo "Resetting batterystats and faking unplugged state..."

adb shell dumpsys batterystats --reset >/dev/null
adb shell dumpsys battery unplug >/dev/null

echo "Sleeping ${DURATION}s -- exercise the scenario now if it needs interaction..."
sleep "$DURATION"

{
  echo "# Battery bench: $SCENARIO"
  echo "# Date: $(date -Iseconds)"
  echo "# Device: $MODEL, Android $RELEASE (API $SDK)"
  echo "# Duration: ${DURATION}s"
  echo "# Method: dumpsys batterystats --reset, dumpsys battery unplug, sleep, dumpsys batterystats"
  echo "# Package: $PKG"
  echo
  echo "## Full batterystats dump for $PKG"
  adb shell dumpsys batterystats "$PKG"
  echo
  echo "## System alarm wakeups (all apps, for context)"
  adb shell dumpsys alarm | grep -A 5 "$PKG" || echo "(no alarms registered by $PKG)"
} > "$OUT_FILE"

adb shell dumpsys battery reset >/dev/null

echo "Restored real charging state."
echo "Raw output: $OUT_FILE"
echo
echo "Grep this file for 'Wakeup alarm', 'Actual wakeups', 'wake lock' and 'mobile radio active'"
echo "to pull the numbers docs/benchmarks/README.md's table wants."
