#!/usr/bin/env python3
"""Summarize a Focus Fence log export (the .zip from Logs & diagnostics → Export).

    python3 tools/analyze_logs.py focusfence-logs-20261114-1830.zip
    python3 tools/analyze_logs.py export.zip --since 2026-11-14 --csv out/

Prints the same figures as the on-phone diagnostics report, plus what only
makes sense off-device: per-hour activity, protection-coverage gaps, battery
drain per screen state, and resolver health. With --csv, also writes one CSV
per record type for spreadsheets/pandas. Standard library only.

Schema: docs/specs/event-log.md. Exit status 0 on success, 2 on unreadable input.
"""
import argparse
import collections
import csv
import datetime as dt
import io
import json
import os
import statistics
import sys
import zipfile

HOUR = 3_600_000


def load(path):
    """Returns (meta, activity_records, diag_records), each list oldest-file-first."""
    files = {}
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as z:
            for name in z.namelist():
                files[name] = z.read(name).decode("utf-8", "replace")
    elif os.path.isdir(path):
        for name in os.listdir(path):
            with open(os.path.join(path, name), encoding="utf-8", errors="replace") as f:
                files[name] = f.read()
    else:
        raise ValueError(f"{path}: not a zip or directory")

    def ordered(base):
        # base.N.jsonl is older than base.(N-1).jsonl; base.jsonl is newest.
        rotated = sorted(
            (n for n in files if n.startswith(base + ".") and n.endswith(".jsonl") and n != base + ".jsonl"),
            key=lambda n: -int(n.split(".")[1]),
        )
        return rotated + ([base + ".jsonl"] if base + ".jsonl" in files else [])

    def records(base):
        out, bad = [], 0
        for name in ordered(base):
            for line in files[name].splitlines():
                if not line.strip():
                    continue
                try:
                    rec = json.loads(line)
                except json.JSONDecodeError:
                    bad += 1  # torn final line after a kill; expected, rare
                    continue
                if isinstance(rec, dict) and isinstance(rec.get("t"), int):
                    out.append(rec)
        return out, bad

    meta = {}
    if "meta.json" in files:
        meta = json.loads(files["meta.json"].splitlines()[0])
    activity, bad_a = records("activity")
    diag, bad_d = records("diag")
    meta["_bad_lines"] = bad_a + bad_d
    return meta, activity, diag


def ts(ms):
    return dt.datetime.fromtimestamp(ms / 1000).strftime("%Y-%m-%d %H:%M:%S")


def seq_gaps(records):
    """Missing sequence numbers: lines lost (process killed before flush) or files rotated away."""
    seqs = sorted(r["seq"] for r in records if isinstance(r.get("seq"), int))
    gaps = []
    for a, b in zip(seqs, seqs[1:]):
        if b > a + 1:
            gaps.append((a, b, b - a - 1))
    return gaps


def coverage(activity, end_t):
    """Intervals where the VPN was not running, from svc/integ records."""
    events = []
    for r in activity:
        if r["type"] == "svc" and r.get("comp") == "vpn" and r.get("state") in ("start",):
            events.append((r["t"], True))
        elif r["type"] == "svc" and r.get("comp") == "vpn" and r.get("state") in (
            "stop", "revoked", "destroyed", "establish_failed"
        ):
            events.append((r["t"], False))
        elif r["type"] == "integ" and r.get("key") == "vpn":
            events.append((r["t"], r.get("value") == "running"))
        elif r["type"] == "svc" and r.get("comp") == "app" and r.get("state") == "process_start":
            events.append((r["t"], None))  # unknown until a vpn record says otherwise
    events.sort()
    gaps, down_since, state = [], None, None
    for t, up in events:
        if up is None:
            continue
        if up and down_since is not None:
            gaps.append((down_since, t))
            down_since = None
        elif not up and state is not False:
            down_since = t
        state = up
    if down_since is not None:
        gaps.append((down_since, end_t))
    return gaps


def battery(diag, max_gap=2 * HOUR):
    samples = sorted((r for r in diag if r["type"] == "bat"), key=lambda r: r["t"])
    acc = {True: [0, 0, 0], False: [0, 0, 0]}  # ms, pct, cpu
    excluded = 0
    for a, b in zip(samples, samples[1:]):
        d = b["t"] - a["t"]
        if d <= 0:
            continue
        if a["plugged"] or b["plugged"] or b["up"] < a["up"] or d > max_gap:
            excluded += d
            continue
        k = acc[bool(a["screen"])]
        k[0] += d
        k[1] += a["level"] - b["level"]
        if a.get("pid") == b.get("pid") and b["cpu"] >= a["cpu"]:
            k[2] += b["cpu"] - a["cpu"]
    return acc, excluded, len(samples)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("export", help="export .zip or an unzipped directory")
    ap.add_argument("--since", help="ignore records before this date (YYYY-MM-DD)")
    ap.add_argument("--top", type=int, default=15)
    ap.add_argument("--csv", metavar="DIR", help="also write one CSV per record type")
    args = ap.parse_args()

    try:
        meta, activity, diag = load(args.export)
    except (ValueError, OSError, zipfile.BadZipFile, json.JSONDecodeError) as e:
        print(f"error: {e}", file=sys.stderr)
        return 2

    if args.since:
        cut = int(dt.datetime.strptime(args.since, "%Y-%m-%d").timestamp() * 1000)
        activity = [r for r in activity if r["t"] >= cut]
        diag = [r for r in diag if r["t"] >= cut]

    allr = activity + diag
    if not allr:
        print("no records")
        return 0
    t0, t1 = min(r["t"] for r in allr), max(r["t"] for r in allr)
    span_h = (t1 - t0) / HOUR

    print("== export ==")
    for k in ("app_version", "manufacturer", "model", "sdk", "rules", "adlist_enabled",
              "log_allowed_dns", "log_urls", "usage_access", "a11y_enabled", "battery_opt_exempt"):
        if k in meta:
            print(f"  {k:20} {meta[k]}")
    print(f"  span                 {ts(t0)} → {ts(t1)}  ({span_h:.1f} h)")
    print(f"  records              activity={len(activity)} diag={len(diag)} unreadable_lines={meta.get('_bad_lines', 0)}")
    for name, recs in (("activity", activity), ("diag", diag)):
        gaps = seq_gaps(recs)
        lost = sum(g[2] for g in gaps)
        if gaps:
            print(f"  {name} seq gaps     {len(gaps)} gaps, {lost} lines missing (kill before flush, or rotation)")

    print("\n== protection coverage (VPN) ==")
    gaps = coverage(activity, t1)
    down = sum(b - a for a, b in gaps)
    print(f"  down {down / HOUR:.2f} h of {span_h:.1f} h  in {len(gaps)} interval(s)")
    for a, b in gaps[:20]:
        print(f"    {ts(a)} → {ts(b)}  ({(b - a) / 60000:.0f} min)")
    integ = [r for r in activity if r["type"] == "integ"]
    if integ:
        print("  integrity changes:")
        for r in integ[-25:]:
            print(f"    {ts(r['t'])}  {r['key']}={r['value']}  (was {r.get('was')})")

    print("\n== battery (sampled while a Focus Fence service ran) ==")
    acc, excluded, n = battery(diag)
    for screen in (True, False):
        ms, pct, cpu = acc[screen]
        h = ms / HOUR
        label = "screen on " if screen else "screen off"
        if h > 0:
            print(f"  {label}  {h:6.2f} h  {pct / h:5.2f} %/h device drain   {cpu / h:8.0f} ms/h app cpu")
        else:
            print(f"  {label}  no usable samples")
    print(f"  excluded {excluded / HOUR:.2f} h (charging/reboot/gaps), {n} samples")
    print("  %/h is the whole device; compare against a baseline run (tools/battery-baseline.sh).")

    print("\n== how well it works (diag perf) ==")
    perf = [r for r in diag if r["type"] == "perf"]
    tot = collections.Counter()
    p95s = []
    for r in perf:
        for k, v in r.items():
            if k in ("seq", "t", "type", "win", "p50", "p95", "p99", "max"):
                continue
            if isinstance(v, int):
                tot[k] += v
        if isinstance(r.get("p95"), int):
            p95s.append(r["p95"])
    q = tot["q"]
    fwd, fail = tot["fwd"], tot["fwd_fail"]
    print(f"  dns queries          {q}  ({q / span_h:.0f}/h)" if span_h else f"  dns queries {q}")
    if fwd + fail:
        print(f"  forward failure      {fail / (fwd + fail):.2%}  (failovers {tot['fwd_failover']})")
    if p95s:
        print(f"  latency p95          median {statistics.median(p95s):.0f} ms, worst window {max(p95s)} ms")
    if tot["url_read"] + tot["url_miss"]:
        print(f"  url read miss        {tot['url_miss'] / (tot['url_read'] + tot['url_miss']):.1%}")
    print(f"  a11y events          {tot['a11y_ev']}  ({tot['a11y_ev'] / span_h:.0f}/h)" if span_h else "")
    shown = ("q", "fwd", "fwd_fail", "fwd_failover", "url_read", "url_miss", "a11y_ev", "http_req", "http_ms", "http_4xx", "http_5xx")
    if tot["sync_ms"]:
        shown += ("sync", "sync_fail", "sync_ms", "sync_cpu_us", "ask_poll")
    other = {k: v for k, v in tot.items() if k not in shown and not k.startswith(("http_fail_", "feed_"))}
    if other:
        print("  other counters       " + ", ".join(f"{k}={v}" for k, v in sorted(other.items())))
    errs = collections.Counter(f"{r.get('comp')}:{r.get('what')}" for r in diag if r["type"] == "err")
    if errs:
        print("  errors               " + ", ".join(f"{k}={v}" for k, v in errs.most_common()))
    # Partners and sync (2026-11-25): requests, how they fail, and why a partner's phone did or didn't show an alert.
    if tot["http_req"]:
        req = tot["http_req"]
        fails = {k[len("http_fail_"):]: v for k, v in tot.items() if k.startswith("http_fail_")}
        nfail = sum(fails.values())
        print(f"  requests             {req}" + (f" ({req / span_h:.0f}/h)" if span_h else "") +
              f", avg {tot['http_ms'] / max(1, req - nfail):.0f} ms, failed {nfail / req:.1%} {fails or ''}, "
              f"4xx {tot['http_4xx']}, 5xx {tot['http_5xx']}")
    if tot["sync_ms"]:
        print(f"  syncs                {tot['sync']} ({tot['sync_fail']} with an error), avg {tot['sync_ms'] / tot['sync']:.0f} ms, "
              f"{tot['sync_cpu_us'] / tot['sync'] / 1000:.0f} ms cpu; ask polls {tot['ask_poll']}")
    if tot["feed_read"]:
        print(f"  partner feed         reads {tot['feed_read']} (failed {tot['feed_fail']}), new {tot['feed_new']}: "
              f"shown {tot['feed_now']}, other {tot['feed_other']}, held {tot['feed_held']}, not shown {tot['feed_skip']}, "
              f"held sent {tot['feed_flush']}")
    sync_errs = [r for r in diag if r["type"] == "err" and r.get("comp") == "sync"]
    if sync_errs:
        print("  sync errors          " + "; ".join(f"{r.get('what')}: {r.get('detail', '')[:60]}" for r in sync_errs[-5:]))
    notes = [r for r in diag if r["type"] == "svc" and r.get("comp") in ("notify", "partner", "link")]
    for r in notes[-6:]:
        print(f"  {r.get('comp')}/{r.get('state'):<14} {r.get('detail', '')}")
    loads = [r for r in diag if r["type"] == "svc" and r.get("comp") == "adlist"]
    if loads:
        print(f"  ad-list loads        {len(loads)}; last: {loads[-1].get('detail')}")

    print("\n== detection (activity) ==")
    dns = [r for r in activity if r["type"] == "dns"]
    dec = collections.Counter(r.get("decision") for r in dns)
    print("  dns decisions        " + ", ".join(f"{k}={v}" for k, v in dec.most_common()))
    reasons = collections.Counter(r.get("reason") for r in dns if r.get("decision") == "blocked")
    if reasons:
        print("  blocked by           " + ", ".join(f"{k}={v}" for k, v in reasons.most_common()))
    ruled = collections.Counter((r.get("rule"), r.get("mode"), r.get("decision")) for r in dns if r.get("rule"))
    if ruled:
        print("  user-rule matches:")
        for (rule, mode, d), c in ruled.most_common(args.top):
            print(f"    {c:7d}  {rule} [{mode}] {d}")
    for label, recs, key in (
        ("top blocked hosts", [r for r in dns if r.get("decision") == "blocked"], "host"),
        ("top allowed hosts", [r for r in dns if r.get("decision") == "allowed"], "host"),
        ("top browser sites", [r for r in activity if r["type"] == "url"], "host"),
    ):
        c = collections.Counter(r.get(key) for r in recs)
        if c:
            print(f"  {label}:")
            for k, v in c.most_common(args.top):
                print(f"    {v:7d}  {k}")

    # Foreground time per app from fg/bg pairs.
    fg_time = collections.Counter()
    current = {}
    for r in sorted((r for r in activity if r["type"] in ("app", "screen")), key=lambda r: r["t"]):
        if r["type"] == "app" and r["state"] == "fg":
            current = {r["pkg"]: r["t"]}
        elif r["type"] == "app" and r["state"] == "bg" and r["pkg"] in current:
            fg_time[r["pkg"]] += r["t"] - current.pop(r["pkg"])
        elif r["type"] == "screen" and r["state"] == "off":
            for pkg, start in current.items():  # normally already closed by a bg record
                fg_time[pkg] += r["t"] - start
            current = {}
    if fg_time:
        print("  foreground time by app:")
        for pkg, ms in fg_time.most_common(args.top):
            print(f"    {ms / 60000:7.1f} min  {pkg}")
    enf = collections.Counter((r.get("action"), r.get("pkg")) for r in activity if r["type"] == "enforce")
    if enf:
        print("  enforcement actions:")
        for (a, p), c in enf.most_common(args.top):
            print(f"    {c:7d}  {a} {p}")

    print("\n== activity by hour (dns lookups / app switches / urls) ==")
    hours = collections.defaultdict(lambda: [0, 0, 0])
    for r in activity:
        h = dt.datetime.fromtimestamp(r["t"] / 1000).strftime("%m-%d %H:00")
        i = {"dns": 0, "app": 1, "url": 2}.get(r["type"])
        if i is not None:
            hours[h][i] += 1
    for h in sorted(hours)[-48:]:
        d, a, u = hours[h]
        print(f"  {h}  {d:6d} {a:5d} {u:5d}")

    if args.csv:
        os.makedirs(args.csv, exist_ok=True)
        by_type = collections.defaultdict(list)
        for r in allr:
            by_type[r["type"]].append(r)
        for typ, recs in by_type.items():
            cols = sorted({k for r in recs for k in r}, key=lambda k: (k not in ("seq", "t", "type"), k))
            with open(os.path.join(args.csv, f"{typ}.csv"), "w", newline="") as f:
                w = csv.DictWriter(f, fieldnames=cols)
                w.writeheader()
                w.writerows(recs)
        print(f"\nwrote {len(by_type)} CSV files to {args.csv}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
