#!/usr/bin/env python3
"""Light-mode check: did an export come from a phone running only the core filter?

Focus Fence's promise (docs/plans/05-group-and-release.md, "layers") is that with every optional layer off it is the
same lightweight on-device blocker: no capture, no AI, no uploads. This reads the diag log of an export (the zip or the
diag .jsonl) and sums the counters those layers would have raised. It prints only counter names and totals, never log
contents. Exit 0 = nothing optional ran; 1 = something did.

    python3 -I tools/check_light_mode.py focusfence-logs-YYYYMMDD-HHMM.zip
"""
import json
import sys
import zipfile

# counter -> the layer that raises it
LAYERS = {
    "shot": "screenshots", "shot_req": "screenshots", "clf": "on-device AI", "clf_ms": "on-device AI",
    "text_captured": "on-screen text", "text_cpu_us": "on-screen text",
    "sync": "server sync (partners / groups)", "sync_cpu_us": "server sync (partners / groups)",
}


def diag_lines(path):
    if path.endswith(".zip"):
        with zipfile.ZipFile(path) as z:
            for name in sorted(z.namelist()):
                if "diag" in name and name.endswith(".jsonl"):
                    yield from z.read(name).decode("utf-8", "replace").splitlines()
    else:
        with open(path, encoding="utf-8", errors="replace") as f:
            yield from f


def main(path):
    totals = {}
    for line in diag_lines(path):
        try:
            rec = json.loads(line)
        except ValueError:
            continue
        if rec.get("type") != "perf":
            continue
        for key in LAYERS:
            v = rec.get(key)
            if isinstance(v, (int, float)):
                totals[key] = totals.get(key, 0) + v
    ran = {k: v for k, v in totals.items() if v}
    if not ran:
        print("PASS: no screenshot, AI, text-capture or sync activity in this export.")
        return 0
    by_layer = {}
    for k, v in ran.items():
        by_layer.setdefault(LAYERS[k], []).append(f"{k}={v:g}")
    print("FAIL: optional layers were active:")
    for layer, items in sorted(by_layer.items()):
        print(f"  {layer}: {', '.join(sorted(items))}")
    return 1


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    sys.exit(main(sys.argv[1]))
