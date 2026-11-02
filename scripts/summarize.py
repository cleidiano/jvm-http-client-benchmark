#!/usr/bin/env python3
"""Summarize JMH JSON results as plain-text tables, one per scenario.

    scripts/summarize.py results/*.json

Columns: throughput, error as % of score, min..max iteration, mean time per request
seen by one request (concurrency / throughput, where concurrency is the thread count, or
inFlight for AsyncGetBenchmark), score relative to the best client in the
scenario, allocation per request (-prof gc), and process CPU time per request, peak live
platform threads and resident memory (-prof io.github.cleidiano.httpbench.bench.ResourceProfiler).
For SampleTime runs (-bm sample) it prints p50/p90/p99/p99.9 instead.
"""
import json
import sys
from collections import defaultdict

PREFERRED_ORDER = ["apache5", "okhttp", "jetty", "jetty-vt", "jdk", "jdk-vt",
                   "apache5-async", "okhttp-async", "jetty-async", "jdk-async", "okhttp-sharded"]


def client_order(name):
    return (PREFERRED_ORDER.index(name) if name in PREFERRED_ORDER else len(PREFERRED_ORDER), name)


def summarize(path):
    with open(path) as f:
        rows = json.load(f)
    groups = defaultdict(list)
    for r in rows:
        params = dict(r.get("params", {}))
        client = params.pop("client", "?")
        bench = ".".join(r["benchmark"].split(".")[-2:])
        key = (bench, r["mode"], tuple(sorted(params.items())), r["threads"])
        groups[key].append((client, r))

    print(f"\n=== {path}")
    for (bench, mode, params, threads), items in groups.items():
        label = " ".join(f"{k}={v}" for k, v in params)
        print(f"\n{bench} [{mode}] {label} threads={threads}")
        items.sort(key=lambda item: client_order(item[0]))
        if mode == "sample":
            print_sample(items)
        else:
            # Async benchmarks keep `inFlight` requests outstanding from one thread.
            concurrency = int(dict(params).get("inFlight", threads))
            print_throughput(items, concurrency)


def secondary(r, name, scale=1.0, fmt="{:9.0f}", width=9):
    score = r.get("secondaryMetrics", {}).get(name, {}).get("score")
    return fmt.format(score * scale) if score is not None else f"{'-':>{width}}"


def print_throughput(items, concurrency):
    best = max(r["primaryMetric"]["score"] for _, r in items)
    w = max(10, max(len(c) for c, _ in items))
    print(f"  {'client':{w}} {'ops/s':>9} {'±err%':>6} {'min..max':>17} {'us/op':>7} {'vs best':>7} "
          f"{'B/op':>9} {'cpu us/op':>9} {'threads':>7} {'rss MB':>7}")
    for client, r in items:
        pm = r["primaryMetric"]
        raw = [x for fork in pm["rawData"] for x in fork]
        # JMH writes "NaN" (a string) when there are too few iterations to compute an error.
        err = pm["scoreError"]
        err_txt = f"{100 * err / pm['score']:5.1f}%" if isinstance(err, (int, float)) else f"{'n/a':>6}"
        print(f"  {client:{w}} {pm['score']:9.0f} {err_txt} "
              f"{min(raw):8.0f}..{max(raw):<8.0f} {concurrency * 1e6 / pm['score']:7.1f} "
              f"{pm['score'] / best:6.2f}x "
              f"{secondary(r, 'gc.alloc.rate.norm')} "
              f"{secondary(r, 'cpu.time.norm', 1e-3, '{:9.1f}')} "
              f"{secondary(r, 'threads.platform.peak', 1, '{:7.0f}', 7)} "
              f"{secondary(r, 'rss', 1, '{:7.0f}', 7)}")


def print_sample(items):
    unit = items[0][1]["primaryMetric"]["scoreUnit"]
    cols = ["50.0", "90.0", "99.0", "99.9", "100.0"]
    w = max(10, max(len(c) for c, _ in items))
    print(f"  {'client':{w}} " + " ".join(f"{'p' + c.rstrip('0').rstrip('.'):>9}" for c in cols) + f"  ({unit})")
    for client, r in items:
        pct = r["primaryMetric"]["scorePercentiles"]
        print(f"  {client:{w}} " + " ".join(f"{pct[c]:9.1f}" for c in cols))


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    for p in sys.argv[1:]:
        summarize(p)
