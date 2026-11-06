#!/usr/bin/env python3
"""Turn a result set (a folder of JMH JSON files) into Markdown.

    scripts/results.py report  <set-dir>              > <set-dir>/REPORT.md
    scripts/results.py compare <old-set> <new-set>    > <new-set>/COMPARE-<old-set-name>.md
    scripts/results.py scoreboard <set> [--into RESULTS.md]

A result set is one folder holding the JSON + .env files of one measurement campaign (normally
one scripts/run-all.sh run) and a versions.txt with the library versions it measured. Each file
is matched to a scenario by its label: the part of the name after the timestamp, e.g.
20261101-171355-cost-t1.json -> cost-t1. Labels this script doesn't know go to "Other runs".

report: one section per scope, one table per metric. Bold marks the best value of each column;
"±N%" after a throughput marks a JMH error above 5% of the score.

compare: matches every measurement present in both sets (same label, benchmark, parameters,
threads, executor and client) and lists the changes larger than --threshold percent (default 5)
whose confidence intervals don't overlap. Everything else counts as noise.
"""
import argparse
import json
import math
import os
import re
import sys
from collections import defaultdict
from dataclasses import dataclass, field

CLIENT_ORDER = [
    "apache5", "spring-restclient-apache5", "feign-apache5",
    "okhttp", "feign-okhttp",
    "jetty", "jetty-vt", "spring-restclient-jetty", "spring-webclient-jetty",
    "jdk", "jdk-vt", "spring-restclient-jdk", "spring-webclient-jdk", "feign-jdk",
    "reactor", "spring-restclient-reactor", "spring-webclient-reactor",
    "apache5-async", "spring-webclient-apache5", "okhttp-async", "okhttp-vt-async",
    "jetty-async", "jetty-vt-async", "jdk-async", "jdk-vt-async", "reactor-async",
]

# --- scenarios -------------------------------------------------------------------------------


@dataclass
class Scenario:
    number: int
    title: str
    conditions: str
    labels: str  # regex matched against the whole label
    metrics: list
    layers: bool = False


@dataclass
class Scope:
    title: str
    question: str
    scenarios: list


SCOPES = [
    Scope("Raw clients, blocking API",
          "What does each client cost, alone and under concurrency?", [
              Scenario(1, "Cost per request", "loopback, 1 thread", r"cost-t1",
                       ["time", "cpu", "alloc", "gc"]),
              Scenario(2, "Concurrency", "loopback, GET", r"get-t4|loopback-1k-t16", ["ops", "cpu"]),
              Scenario(3, "Latency distribution", "loopback, GET 1 KB, 1 thread", r"latency-1k", []),
              Scenario(4, "Simulated network", "bridge + 500 µs RTT, GET 1 KB",
                       r"network-500us-t\d+", ["ops", "cpu"]),
          ]),
    Scope("Virtual threads",
          "Do virtual-thread callers change throughput, CPU or memory?", [
              Scenario(5, "Platform vs. virtual threads", "20 ms RTT, GET 1 KB, pool of 1,024",
                       r"vt-(PLATFORM|VIRTUAL)-t\d+", ["ops", "cpu", "threads", "rss"]),
          ]),
    Scope("Async APIs",
          "Can one thread drive many requests in flight through each native async API?", [
              Scenario(6, "Async APIs, default threads", "20 ms RTT, GET 1 KB, 1 calling thread",
                       r"async", ["ops", "cpu", "threads", "rss"]),
              Scenario(7, "Async APIs, virtual threads", "20 ms RTT, GET 1 KB, 1 calling thread",
                       r"async-vt", ["ops", "cpu", "threads", "rss"]),
          ]),
    Scope("Abstraction layers",
          "What do Spring and Feign add on top of the client they wrap?", [
              Scenario(8, "Layers, cost per request", "loopback, 1 thread", r"layers-t1",
                       ["ops", "cpu", "alloc"], layers=True),
              Scenario(9, "WebClient used asynchronously", "20 ms RTT, 1,024 in flight",
                       r"layers-async", ["ops", "cpu", "threads"], layers=True),
          ]),
]

METRICS = {
    # name: (table title, unit, higher is better)
    "ops": ("Throughput", "ops/s", True),
    "time": ("Time per request", "µs", False),
    "cpu": ("CPU per request", "µs", False),
    "alloc": ("Allocation per request", "KB", False),
    "gc": ("GC collections, all forks", "count", False),
    "threads": ("Platform threads, peak", "count", False),
    "rss": ("Resident memory", "MB", False),
}

# --- loading ---------------------------------------------------------------------------------


@dataclass
class Row:
    label: str
    file: str
    bench: str            # e.g. GetBenchmark
    mode: str
    params: dict          # without client
    threads: int
    executor: str
    client: str
    raw: dict = field(repr=False)

    @property
    def concurrency(self):
        return int(self.params.get("inFlight", self.threads))

    def value(self, metric):
        pm = self.raw["primaryMetric"]
        sec = self.raw.get("secondaryMetrics", {})
        if metric == "ops":
            return pm["score"]
        if metric == "time":
            return self.concurrency * 1e6 / pm["score"]
        names = {"cpu": ("cpu.time.norm", 1e-3), "alloc": ("gc.alloc.rate.norm", 1e-3),
                 "gc": ("gc.count", 1), "threads": ("threads.platform.peak", 1), "rss": ("rss", 1)}
        name, scale = names[metric]
        m = sec.get(name)
        return None if m is None else m["score"] * scale

    def error_pct(self):
        pm = self.raw["primaryMetric"]
        err = pm["scoreError"]
        return 100 * err / pm["score"] if isinstance(err, (int, float)) and not math.isnan(err) else None


def label_of(filename):
    stem = os.path.basename(filename)[:-len(".json")]
    return re.sub(r"^\d{8}-\d{6}-?", "", stem) or stem


def read_kv(path):
    out = {}
    if os.path.exists(path):
        with open(path) as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    out[k.strip()] = v.strip()
    return out


@dataclass
class ResultSet:
    path: str
    rows: list
    envs: dict      # label -> .env dict
    versions: dict


def load_set(path):
    rows, envs = [], {}
    files = sorted(f for f in os.listdir(path) if f.endswith(".json"))
    if not files:
        sys.exit(f"no JSON files in {path}")
    for name in files:
        full = os.path.join(path, name)
        label = label_of(name)
        env = read_kv(full[:-len(".json")] + ".env")
        envs[label] = env
        with open(full) as f:
            data = json.load(f)
        for r in data:
            params = dict(r.get("params", {}))
            client = params.pop("client", "?")
            rows.append(Row(label, name, r["benchmark"].split(".")[-2], r["mode"], params,
                            r["threads"], env.get("executor", "PLATFORM"), client, r))
    return ResultSet(path, rows, envs, read_kv(os.path.join(path, "versions.txt")))


# --- formatting ------------------------------------------------------------------------------

def client_key(name):
    return (CLIENT_ORDER.index(name) if name in CLIENT_ORDER else len(CLIENT_ORDER), name)


PAYLOAD_ORDER = {"0": 0, "1k": 1, "100k": 2}


def payload_text(p):
    return {"0": "0 B", "1k": "1 KB", "100k": "100 KB"}.get(p, p)


def column_key(row):
    return (row.bench, PAYLOAD_ORDER.get(row.params.get("payload"), 9), row.params.get("payload", ""),
            row.threads, int(row.params.get("inFlight", 0)), row.executor)


def column_label(key, varying):
    bench, _, payload, threads, in_flight, executor = key
    parts = []
    if "bench" in varying:
        parts.append(bench.replace("Benchmark", "").upper())
    if "payload" in varying:
        parts.append(payload_text(payload))
    if "threads" in varying:
        parts.append(f"{threads:,} thr")
    if "inFlight" in varying:
        parts.append(f"{in_flight:,} in flight")
    if "executor" in varying:
        parts.append({"PLATFORM": "PT", "VIRTUAL": "VT"}.get(executor, executor))
    return ", ".join(parts) or "value"


def varying_dims(keys):
    names = ["bench", None, "payload", "threads", "inFlight", "executor"]
    return {names[i] for i in (0, 2, 3, 4, 5) if len({k[i] for k in keys}) > 1}


def fmt(metric, v):
    if v is None:
        return "—"
    if metric == "alloc":
        return f"{v:,.1f}"
    return f"{v:,.0f}"


def table(header, rows):
    out = ["| " + " | ".join(header) + " |", "|" + "---|" * len(header)]
    out += ["| " + " | ".join(r) + " |" for r in rows]
    return "\n".join(out)


def best_index(values, higher):
    present = [(v, i) for i, v in enumerate(values) if v is not None]
    if len(present) < 2:
        return None
    return (max if higher else min)(present)[1]


def cell(metric, row):
    v = row.value(metric) if row else None
    text = fmt(metric, v)
    if metric in ("ops", "time") and row is not None:
        err = row.error_pct()
        if err is not None and err > 5:
            text += f" ±{err:.0f}%"
    return v, text


# --- report ----------------------------------------------------------------------------------

def run_line(rows, envs):
    first = rows[0].raw
    jdk = first.get("vmVersion", "?").split("+")[0]
    forks = first.get("forks")
    meas = f"{first.get('measurementIterations')} × {first.get('measurementTime')}"
    labels = sorted({r.label for r in rows}, key=natural_key)
    env = envs.get(labels[0], {})
    pool = env.get("max_connections", "?")
    pool = f"{int(pool):,}" if pool.isdigit() else pool
    parts = [", ".join(f"`{l}`" for l in labels), f"JDK {jdk}", f"{forks} fork{'s' if forks != 1 else ''} × {meas}",
             f"pool of {pool}"]
    return " · ".join(parts)


def natural_key(text):
    return [int(t) if t.isdigit() else t for t in re.split(r"(\d+)", text)]


def row_order(clients, layers):
    """Client order; in layer scenarios each layer goes right below the raw client it wraps."""
    if not layers:
        return sorted(clients, key=client_key)
    is_async = any(c.endswith("-async") for c in clients)

    def key(c):
        base = layer_base(c, is_async)
        return (client_key(base or c), base is not None, client_key(c))
    return sorted(clients, key=key)


def pivot_tables(rows, metrics, layers=False):
    by_col = defaultdict(dict)
    for r in rows:
        by_col[column_key(r)][r.client] = r
    keys = sorted(by_col)
    varying = varying_dims(keys)
    clients = row_order({r.client for r in rows}, layers)
    out = []
    if len(keys) == 1:
        col = by_col[keys[0]]
        metrics = [m for m in metrics if any(col[c].value(m) is not None for c in col)]
        values = {m: [col[c].value(m) if c in col else None for c in clients] for m in metrics}
        best = {m: best_index(values[m], METRICS[m][2]) for m in metrics}
        body = []
        for i, c in enumerate(clients):
            line = [f"`{c}`"]
            for m in metrics:
                _, text = cell(m, col.get(c))
                line.append(f"**{text}**" if best[m] == i else text)
            body.append(line)
        header = ["client"] + [f"{METRICS[m][0]} ({METRICS[m][1]})" for m in metrics]
        out.append(table(header, body))
        return out
    for m in metrics:
        if not any(r.value(m) is not None for r in rows):
            continue
        title, unit, higher = METRICS[m]
        grid = [[cell(m, by_col[k].get(c)) for k in keys] for c in clients]
        best = [best_index([grid[i][j][0] for i in range(len(clients))], higher) for j in range(len(keys))]
        body = []
        for i, c in enumerate(clients):
            line = [f"`{c}`"]
            for j in range(len(keys)):
                text = grid[i][j][1]
                line.append(f"**{text}**" if best[j] == i else text)
            body.append(line)
        better = "higher is better" if higher else "lower is better"
        out.append(f"**{title}** ({unit}, {better})\n\n" +
                   table(["client"] + [column_label(k, varying) for k in keys], body))
    return out


def sample_table(rows):
    cols = [("p50", "50.0"), ("p90", "90.0"), ("p99", "99.0"), ("p99.9", "99.9"),
            ("p99.99", "99.99"), ("max", "100.0")]
    rows = sorted(rows, key=lambda r: client_key(r.client))
    grid = [[r.raw["primaryMetric"]["scorePercentiles"].get(k) for _, k in cols] for r in rows]
    best = [best_index([g[j] for g in grid], False) for j in range(len(cols))]
    body = []
    for i, r in enumerate(rows):
        line = [f"`{r.client}`"]
        for j in range(len(cols)):
            text = fmt("time", grid[i][j])
            line.append(f"**{text}**" if best[j] == i else text)
        p50, p999 = grid[i][0], grid[i][3]
        line.append(f"{p999 / p50:.1f}x" if p50 else "—")
        body.append(line)
    unit = rows[0].raw["primaryMetric"]["scoreUnit"].replace("us/op", "µs")
    return f"**Latency** ({unit}, lower is better)\n\n" + table(
        ["client"] + [c for c, _ in cols] + ["p99.9 ÷ p50"], body)


def layer_base(client, is_async):
    m = re.fullmatch(r"(spring-restclient|spring-webclient|feign)-(\w+)", client)
    if not m:
        return None
    layer, engine = m.groups()
    if layer == "spring-webclient" and engine == "apache5":
        return "apache5-async"
    return f"{engine}-async" if is_async else engine


def layer_table(rows):
    by_col = defaultdict(dict)
    for r in rows:
        by_col[column_key(r)][r.client] = r
    keys = sorted(by_col)
    varying = varying_dims(keys)
    layers = [c for c in row_order({r.client for r in rows}, True) if layer_base(c, False)]
    header = ["layer", "on top of"]
    for k in keys:
        prefix = column_label(k, varying) + ": " if len(keys) > 1 else ""
        header += [f"{prefix}÷ raw ops/s", f"{prefix}+ CPU µs", f"{prefix}+ KB"]
    body = []
    for c in layers:
        line = None
        for k in keys:
            col = by_col[k]
            layer = col.get(c)
            base = col.get(layer_base(c, layer.bench == "GetAsyncBenchmark")) if layer else None
            if line is None:
                line = [f"`{c}`", f"`{base.client}`" if base else "—"]
            if not (layer and base):
                line += ["—"] * 3
                continue
            ratio = layer.value("ops") / base.value("ops")
            d_cpu = layer.value("cpu") - base.value("cpu") if layer.value("cpu") is not None else None
            d_kb = (layer.value("alloc") - base.value("alloc")
                    if layer.value("alloc") is not None and base.value("alloc") is not None else None)
            line += [f"{ratio:.2f}x",
                     "—" if d_cpu is None else f"{d_cpu:+,.0f}",
                     "—" if d_kb is None else f"{d_kb:+,.1f}"]
        body.append(line)
    return ("**Cost of the layer** (layer minus the raw client it wraps, same run)\n\n" +
            table(header, body))


def report(path):
    rs = load_set(path)
    by_label = defaultdict(list)
    for r in rs.rows:
        by_label[r.label].append(r)
    used = set()
    out = ["# Benchmark results", ""]

    dates = sorted(e.get("date", "")[:10] for e in rs.envs.values() if e.get("date"))
    env = next(iter(rs.envs.values()), {})
    info = [["Machine", env.get("cpu", "?")],
            ["Measured", f"{dates[0]} to {dates[-1]}" if dates and dates[0] != dates[-1] else (dates[0] if dates else "?")],
            ["CPU frequency", f"governor `{env.get('governor', '?')}`, no_turbo={env.get('no_turbo', '?')}" +
             ("" if all(e.get("governor") == "performance" and e.get("no_turbo") == "1" for e in rs.envs.values())
              else " · **not pinned in every run: numbers are noisy**")],
            ["Kernel", env.get("kernel", "?")]]
    for k, name in (("commit", "Commit"), ("java", "JDK"), ("java_vt", "JDK 24+ (scenarios 5-7, 9)")):
        if k in rs.versions:
            info.append([name, rs.versions[k]])
    out += [table(["", ""], info), ""]
    libs = [[f"`{k}`", v] for k, v in rs.versions.items() if k not in ("commit", "java", "java_vt")]
    if libs:
        out += ["**Library versions**", "", table(["library", "version"], libs), ""]

    out += ["**Reading the tables:** bold is the best value in its column. `±N%` after a throughput",
            "is a JMH error above 5% of the score: treat that number as unstable. CPU per request is the",
            "CPU of the whole client process divided by the requests completed. Differences smaller than",
            "the error are not differences.", ""]

    blocks = scoreboard_blocks(rs)
    out.append("**Contents**\n")
    if any(blocks.values()):
        out.append("- Overall evaluation: score by profile, measured value per criterion, engines per layer")
    for scope in SCOPES:
        out.append(f"- {scope.title}: " + ", ".join(
            f"{s.number}. {s.title}" for s in scope.scenarios))
    out.append("")
    if any(blocks.values()):
        out += ["---", "", "## Overall evaluation", ""]
        if blocks["native"]:
            out += ["### Native clients", "", blocks["native"]]
        if blocks["layers"]:
            out += ["### Spring and Feign", "", blocks["layers"], layer_detail(rs) or ""]

    for scope in SCOPES:
        out += ["---", "", f"## {scope.title}", "", f"_{scope.question}_", ""]
        for s in scope.scenarios:
            labels = [l for l in by_label if re.fullmatch(s.labels, l)]
            used.update(labels)
            out += [f"### {s.number}. {s.title} ({s.conditions})", ""]
            rows = [r for l in labels for r in by_label[l]]
            if not rows:
                out += ["Not measured in this set.", ""]
                continue
            out += [run_line(rows, rs.envs), ""]
            if s.metrics:
                for t in pivot_tables(rows, s.metrics, s.layers):
                    out += [t, ""]
            else:
                out += [sample_table(rows), ""]
            if s.layers:
                out += [layer_table(rows), ""]
        if scope.title == "Async APIs":
            models = concurrency_models(rs)
            if models:
                out += ["### All models at 1,024 concurrent requests", "", models, ""]

    others = [l for l in sorted(by_label) if l not in used]
    if others:
        out += ["---", "", "## Other runs", ""]
        for l in others:
            rows = by_label[l]
            out += [f"### `{l}`", "", run_line(rows, rs.envs), ""]
            if rows[0].mode == "sample":
                out += [sample_table(rows), ""]
            else:
                for t in pivot_tables(rows, ["ops", "cpu", "alloc", "threads", "rss"]):
                    out += [t, ""]
    return "\n".join(out).rstrip() + "\n"


# --- scoreboard ------------------------------------------------------------------------------
#
# Score of one measurement, 0-10, from its ratio to the best client (always >= 1):
#   - tie: within 2% of the best, or confidence intervals overlapping -> 10
#   - otherwise 10 x (1 - log(ratio) / log(ZERO_AT)): 2x worse -> 7.0, 5x -> 3.0, 10x or more -> 0
# A criterion averages its measurements; a profile is a weighted mean of criteria. Change criteria
# and weights here, never in the generated Markdown.

NATIVE = ["apache5", "okhttp", "jetty", "jdk"]
TIE_PCT = 2.0
ZERO_AT = 10.0


@dataclass
class Pick:
    labels: str          # regex on the file label
    metric: str          # Row.value metric, or "p99.9" for SampleTime runs
    bench: str = None
    params: dict = field(default_factory=dict)
    text: str = ""       # what this measurement is, shown in the values table


@dataclass
class Criterion:
    key: str
    name: str
    picks: list               # measurements; the criterion score is their mean
    any_model: bool = False   # each client uses its best variant (jetty-vt-async counts as jetty)


CRITERIA = [
    # Everything that the CPU limits goes into one criterion, so it is not counted several times.
    Criterion("efficiency", "CPU efficiency", [
        Pick(r"cost-t1", "cpu", "GetBenchmark", {"payload": "1k"}, "CPU per request, GET 1 KB, 1 thread"),
        Pick(r"network-500us-t4", "cpu", text="CPU per request, 500 µs RTT, 4 threads"),
        Pick(r"get-t4", "ops", "GetBenchmark", {"payload": "1k"}, "throughput, CPU saturated, loopback, 4 threads"),
        Pick(r"network-500us-t16", "ops", text="throughput, CPU saturated, 500 µs RTT, 16 threads"),
    ]),
    Criterion("large", "Large bodies", [
        Pick(r"cost-t1", "time", "GetBenchmark", {"payload": "100k"}, "time per GET 100 KB"),
        Pick(r"cost-t1", "time", "PostBenchmark", {"payload": "100k"}, "time per POST 100 KB"),
    ]),
    Criterion("memory", "Memory", [
        Pick(r"cost-t1", "alloc", "GetBenchmark", {"payload": "1k"}, "allocated per request, GET 1 KB"),
    ]),
    Criterion("tail", "Tail latency", [
        Pick(r"latency-1k", "p99.9", text="p99.9, GET 1 KB"),
    ]),
    Criterion("concurrent", "High concurrency", [
        Pick(r"vt-(PLATFORM|VIRTUAL)-t1024", "ops"),
        Pick(r"async(-vt)?", "ops", "GetAsyncBenchmark", {"inFlight": "1024"}),
    ], any_model=True),
]
CONCURRENT_TEXT = "throughput at 1,024 concurrent requests, best model"

PROFILES = [
    # (name, weights by criterion key; each sums to 100)
    ("General service", {"efficiency": 40, "large": 10, "memory": 15, "tail": 15, "concurrent": 20}),
    ("CPU-limited container", {"efficiency": 70, "large": 15, "memory": 10, "tail": 5}),
    ("Small heap / GC-sensitive", {"memory": 50, "large": 20, "efficiency": 15, "tail": 5, "concurrent": 10}),
    ("High concurrency", {"concurrent": 60, "efficiency": 30, "memory": 10}),
]

HIGHER_IS_BETTER = {"ops": True, "time": False, "cpu": False, "alloc": False, "threads": False,
                    "rss": False, "p99.9": False}
UNITS = {"ops": "ops/s", "time": "µs", "cpu": "µs", "alloc": "KB", "threads": "threads",
         "rss": "MB", "p99.9": "µs"}
SECONDARY = {"cpu": ("cpu.time.norm", 1e-3), "alloc": ("gc.alloc.rate.norm", 1e-3),
             "threads": ("threads.platform.peak", 1), "rss": ("rss", 1)}


def valid_interval(conf):
    return (isinstance(conf, list) and len(conf) == 2
            and all(isinstance(x, (int, float)) and not math.isnan(x) for x in conf) and conf[0] > 0)


def confidence(row, metric):
    """99.9% confidence interval of a metric, in the same unit as Row.value, or None."""
    if metric == "p99.9":
        return None
    if metric in ("ops", "time"):
        conf = row.raw["primaryMetric"].get("scoreConfidence")
        if not valid_interval(conf):
            return None
        if metric == "time":
            return (row.concurrency * 1e6 / conf[1], row.concurrency * 1e6 / conf[0])
        return tuple(conf)
    name, scale = SECONDARY[metric]
    conf = row.raw.get("secondaryMetrics", {}).get(name, {}).get("scoreConfidence")
    return (conf[0] * scale, conf[1] * scale) if valid_interval(conf) else None


def pick_value(rs, pick, client, any_model):
    """(value, client variant that produced it, confidence interval or None), or None."""
    best = None
    for r in rs.rows:
        if not re.fullmatch(pick.labels, r.label):
            continue
        if pick.bench and r.bench != pick.bench:
            continue
        if any(r.params.get(k) != v for k, v in pick.params.items()):
            continue
        if (r.client.split("-")[0] != client) if any_model else (r.client != client):
            continue
        if pick.metric == "p99.9":
            v = r.raw["primaryMetric"]["scorePercentiles"].get("99.9")
        else:
            v = r.value(pick.metric)
        if v is None:
            continue
        if best is None or (v > best[0]) == HIGHER_IS_BETTER[pick.metric]:
            best = (v, r.client, confidence(r, pick.metric))
    return best


def measurement_scores(values, higher):
    """{client: 0-10} for one measurement, values = {client: (value, variant, interval)}."""
    best_client = (max if higher else min)(values, key=lambda c: values[c][0])
    best, _, best_conf = values[best_client]
    out = {}
    for c, (v, _, conf) in values.items():
        ratio = best / v if higher else v / best
        overlap = conf and best_conf and conf[0] <= best_conf[1] and best_conf[0] <= conf[1]
        if ratio <= 1 + TIE_PCT / 100 or overlap:
            out[c] = 10.0
        else:
            out[c] = max(0.0, 10 * (1 - math.log(ratio) / math.log(ZERO_AT)))
    return out


def criterion_measurements(rs, c, clients):
    """[(text, metric, {client: (value, variant, interval)}, {client: score})] for one criterion."""
    if c.any_model:
        values = {}
        for client in clients:
            found = [v for v in (pick_value(rs, p, client, True) for p in c.picks) if v]
            if found:
                higher = HIGHER_IS_BETTER[c.picks[0].metric]
                values[client] = (max if higher else min)(found, key=lambda x: x[0])
        parts = [(CONCURRENT_TEXT, c.picks[0].metric, values)]
    else:
        parts = [(p.text, p.metric, {cl: v for cl in clients if (v := pick_value(rs, p, cl, False))})
                 for p in c.picks]
    out = []
    for text, metric, values in parts:
        if len(values) == len(clients):
            out.append((text, metric, values, measurement_scores(values, HIGHER_IS_BETTER[metric])))
    return out


def value_text(metric, value, variant, family):
    text = f"{fmt('alloc' if metric == 'alloc' else 'ops', value)} {UNITS[metric]}"
    if variant != family and not variant.startswith(f"{family}-async") and variant != f"{family}-vt-async":
        text += f" (`{variant}`)"
    return text


def native_scoreboard(rs):
    measured = []  # (criterion, measurements, {client: criterion score})
    for c in CRITERIA:
        parts = criterion_measurements(rs, c, NATIVE)
        if parts:
            scores = {cl: sum(p[3][cl] for p in parts) / len(parts) for cl in NATIVE}
            measured.append((c, parts, scores))
    if not measured:
        return None
    keys = {c.key for c, _, _ in measured}

    profile_scores = []
    for name, weights in PROFILES:
        used = {k: w for k, w in weights.items() if k in keys}
        if not used:
            continue
        total = sum(used.values())
        sc = {cl: sum(w * next(s for c, _, s in measured if c.key == k)[cl] for k, w in used.items()) / total
              for cl in NATIVE}
        profile_scores.append((name, sc, total < sum(weights.values())))
    order = sorted(NATIVE, key=lambda cl: -profile_scores[0][1][cl])
    clients_header = [f"`{cl}`" for cl in order]

    def score_cells(sc):
        top = max(sc.values())
        return [f"**{sc[cl]:.1f}**" if sc[cl] >= top - 0.05 else f"{sc[cl]:.1f}" for cl in order]

    body = []
    for name, sc, partial in profile_scores:
        top = max(sc.values())
        winners = [cl for cl in order if sc[cl] >= top - 0.05]
        body.append([name + (" \\*" if partial else "")] + score_cells(sc) +
                    [" = ".join(f"**`{w}`**" for w in winners)])
    out = ["**Score by profile** (0–10, higher is better)", "",
           table(["profile"] + clients_header + ["winner"], body), ""]
    if any(p for *_, p in profile_scores):
        out += ["\\* some criteria of this profile were not measured in this set; scored on the rest.", ""]

    body = [[f"**{c.name}**"] + score_cells(scores) for c, _, scores in measured]
    out += ["**Score by criterion**", "", table(["criterion"] + clients_header, body), ""]

    body = []
    for c, parts, _ in measured:
        for i, (text, metric, values, scores) in enumerate(parts):
            cells = []
            for cl in order:
                v, variant, _ = values[cl]
                t = value_text(metric, v, variant, cl)
                cells.append(f"**{t}**" if scores[cl] == 10 else t)
            body.append([c.name if i == 0 else "", text] + cells)
    out += ["**Measured values** (bold: best, or tied with it within the noise)", "",
            table(["criterion", "measurement"] + clients_header, body), ""]

    body = [[c.name] + [f"{weights[c.key]}%" if c.key in weights else "—" for _, weights in PROFILES]
            for c, _, _ in measured]
    out += ["**Weights per profile**", "", table(["criterion"] + [n for n, _ in PROFILES], body), "",
            f"Rule: each measurement scores 10 for the best client and for any client within {TIE_PCT:g}% "
            f"of it or with an overlapping confidence interval; the others score "
            f"10 × (1 − log(ratio) ÷ log {ZERO_AT:g}), so 2x worse is 7.0, 5x is 3.0 and {ZERO_AT:g}x is 0. "
            "A criterion averages its measurements; a profile is the weighted mean of its criteria. "
            "Scores are relative to the best client of this set: use `results.py compare` to detect "
            "regressions. Criteria and weights: `CRITERIA` and `PROFILES` in `scripts/results.py`.", ""]
    return "\n".join(out)


@dataclass
class LayerGroup:
    title: str
    prefix: str
    throughput: Pick
    throughput_text: str


LAYER_GROUPS = [
    LayerGroup("Spring `RestClient`", "spring-restclient-",
               Pick(r"layers-t1", "ops", "GetBenchmark", {"payload": "1k"}), "throughput, 1 KB, 1 thread"),
    LayerGroup("Spring `WebClient`", "spring-webclient-",
               Pick(r"layers-async", "ops", "GetAsyncBenchmark", {"inFlight": "1024"}),
               "throughput, async, 1,024 in flight"),
    LayerGroup("OpenFeign", "feign-",
               Pick(r"layers-t1", "ops", "GetBenchmark", {"payload": "1k"}), "throughput, 1 KB, 1 thread"),
]
LAYER_WEIGHTS = [  # (column, pick builder, weight)
    ("CPU per request, 1 KB", lambda g: Pick(r"layers-t1", "cpu", "GetBenchmark", {"payload": "1k"}), 40),
    ("CPU per request, 100 KB", lambda g: Pick(r"layers-t1", "cpu", "GetBenchmark", {"payload": "100k"}), 20),
    ("allocation per request, 100 KB", lambda g: Pick(r"layers-t1", "alloc", "GetBenchmark", {"payload": "100k"}), 20),
    (None, lambda g: g.throughput, 20),
]
ENGINE_NAMES = {"apache5": "Apache 5", "okhttp": "OkHttp", "jetty": "Jetty", "jdk": "JDK",
                "reactor": "Reactor Netty"}


def engine_name(group, client):
    engine = client[len(group.prefix):]
    name = ENGINE_NAMES.get(engine, engine)
    return name + (" async" if group.prefix == "spring-webclient-" and engine == "apache5" else "")


def layer_data(rs):
    """[(group, columns [(title, metric, weight, {client: (value, variant, interval)}, {client: score})],
    ranked clients, {client: score})]"""
    out = []
    for g in LAYER_GROUPS:
        clients = sorted({r.client for r in rs.rows if r.client.startswith(g.prefix)}, key=client_key)
        cols = []
        for name, build, weight in LAYER_WEIGHTS:
            pick = build(g)
            vals = {c: v for c in clients if (v := pick_value(rs, pick, c, False))}
            cols.append((name or g.throughput_text, pick.metric, weight, vals))
        clients = [c for c in clients if all(c in v for *_, v in cols)]
        if len(clients) < 2:
            continue
        scored = []
        total = defaultdict(float)
        for name, metric, weight, vals in cols:
            vals = {c: vals[c] for c in clients}
            sc = measurement_scores(vals, HIGHER_IS_BETTER[metric])
            scored.append((name, metric, weight, vals, sc))
            for c in clients:
                total[c] += weight * sc[c] / 100
        out.append((g, scored, sorted(clients, key=lambda c: -total[c]), total))
    return out


def layer_summary(rs):
    data = layer_data(rs)
    if not data:
        return None
    width = max(len(ranked) for _, _, ranked, _ in data)
    body = []
    for g, _, ranked, score in data:
        cells = [f"**{engine_name(g, c)}** · {score[c]:.1f}" if i == 0 else f"{engine_name(g, c)} · {score[c]:.1f}"
                 for i, c in enumerate(ranked)]
        body.append([g.title] + cells + [""] * (width - len(cells)))
    header = ["layer", "best engine"] + ["2nd", "3rd", "4th", "5th"][:width - 1]
    return "\n".join(["**Engine score by layer** (0–10, higher is better)", "", table(header, body), "",
                      "Weights: CPU per request at 1 KB 40%, at 100 KB 20%, allocation at 100 KB 20%, "
                      "throughput 20% (`WebClient`: async, 1,024 in flight). Same scoring rule as the native "
                      "clients. Bytes only, no JSON.", ""])


def layer_detail(rs):
    out = []
    for g, cols, ranked, score in layer_data(rs):
        body = []
        for i, c in enumerate(ranked):
            line = [f"**{engine_name(g, c)}**" if i == 0 else engine_name(g, c)]
            for _, metric, _, vals, sc in cols:
                text = f"{fmt('alloc' if metric == 'alloc' else 'ops', vals[c][0])} {UNITS[metric]}"
                line.append(f"**{text}**" if sc[c] == 10 else text)
            line.append(f"**{score[c]:.1f}**" if i == 0 else f"{score[c]:.1f}")
            body.append(line)
        header = ["engine"] + [f"{n} ({w}%)" for n, _, w, *_ in cols] + ["score"]
        out += [f"**{g.title}: engines**", "", table(header, body), ""]
    return "\n".join(out) if out else None


CONCURRENCY_MODELS = [
    ("Blocking, platform threads", Pick(r"vt-PLATFORM-t1024", "ops")),
    ("Blocking, virtual threads", Pick(r"vt-VIRTUAL-t1024", "ops")),
    ("Async API", Pick(r"async", "ops", "GetAsyncBenchmark", {"inFlight": "1024"})),
    ("Async API, virtual threads", Pick(r"async-vt", "ops", "GetAsyncBenchmark", {"inFlight": "1024"})),
]


def concurrency_models(rs):
    """Throughput at 1,024 concurrent requests per client and model, best variant of each family."""
    grid = {cl: [pick_value(rs, p, cl, True) for _, p in CONCURRENCY_MODELS] for cl in NATIVE}
    if sum(1 for v in grid.values() for x in v if x) < 2:
        return None
    by_client = {r.client: r for r in rs.rows if re.fullmatch(r"vt-.*-t1024|async(-vt)?", r.label)}
    body = []
    for cl in NATIVE:
        cells = []
        found = [x for x in grid[cl] if x]
        top = max((x[0] for x in found), default=None)
        for x in grid[cl]:
            if not x:
                cells.append("—")
                continue
            text = f"{x[0] / 1000:.1f}k"
            if x[1] not in (cl, f"{cl}-async", f"{cl}-vt-async"):
                text += f" `{x[1]}`"
            cells.append(f"**{text}**" if x[0] == top else text)
        body.append([f"`{cl}`"] + cells)
    return ("**1,024 concurrent requests, every model** (ops/s, best variant of each client; bold: "
            "best model per client; scenarios 5–7)\n\n" + table(["client"] + [n for n, _ in CONCURRENCY_MODELS], body))


def scoreboard_blocks(rs):
    """Blocks shared by REPORT.md and RESULTS.md (filled with `scoreboard --into`)."""
    return {"native": native_scoreboard(rs), "layers": layer_summary(rs)}


def write_into(path, blocks):
    """Replace <!-- results.py:NAME --> ... <!-- /results.py:NAME --> blocks in a Markdown file."""
    with open(path) as f:
        text = f.read()
    for name, body in blocks.items():
        start, end = f"<!-- results.py:{name} -->", f"<!-- /results.py:{name} -->"
        pattern = re.compile(re.escape(start) + r".*?" + re.escape(end), re.S)
        if not pattern.search(text):
            sys.exit(f"{path}: marker {start} ... {end} not found")
        text = pattern.sub(lambda _: f"{start}\n{body or 'Not measured in this set.'}\n{end}", text)
    with open(path, "w") as f:
        f.write(text)


# --- compare ---------------------------------------------------------------------------------

COMPARED = [
    # (metric name in report, JSON metric, higher is better, scale, unit)
    ("throughput", None, True, 1, "ops/s"),
    ("CPU per request", "cpu.time.norm", False, 1e-3, "µs"),
    ("allocation per request", "gc.alloc.rate.norm", False, 1e-3, "KB"),
]


def measurement(row, json_metric):
    m = row.raw["primaryMetric"] if json_metric is None else row.raw.get("secondaryMetrics", {}).get(json_metric)
    if m is None:
        return None
    conf = m.get("scoreConfidence")
    if not (isinstance(conf, list) and all(isinstance(x, (int, float)) and not math.isnan(x) for x in conf)):
        conf = None
    return m["score"], conf


def row_key(r):
    return (r.label, r.bench, r.mode, tuple(sorted(r.params.items())), r.threads, r.executor, r.client)


def describe(r):
    parts = [{"GetBenchmark": "GET", "PostBenchmark": "POST", "GetAsyncBenchmark": "async GET"}
             .get(r.bench, r.bench)]
    if "payload" in r.params:
        parts.append(payload_text(r.params["payload"]))
    if "inFlight" in r.params:
        parts.append(f"{int(r.params['inFlight']):,} in flight")
    else:
        parts.append(f"{r.threads} thr")
    if r.executor != "PLATFORM":
        parts.append("VT")
    return ", ".join(parts)


def scenario_of(label):
    for scope in SCOPES:
        for s in scope.scenarios:
            if re.fullmatch(s.labels, label):
                return f"{s.number}. {s.title}"
    return f"`{label}`"


def compare(old_path, new_path, threshold):
    old, new = load_set(old_path), load_set(new_path)
    old_rows = {row_key(r): r for r in old.rows if r.mode != "sample"}
    new_rows = {row_key(r): r for r in new.rows if r.mode != "sample"}
    common = [k for k in new_rows if k in old_rows]
    worse, better = [], []
    noise = 0
    for k in common:
        o, n = old_rows[k], new_rows[k]
        for name, json_metric, higher, scale, unit in COMPARED:
            mo, mn = measurement(o, json_metric), measurement(n, json_metric)
            if mo is None or mn is None or mo[0] == 0:
                continue
            change = 100 * (mn[0] - mo[0]) / mo[0]
            overlap = mo[1] and mn[1] and not (mn[1][1] < mo[1][0] or mn[1][0] > mo[1][1])
            if abs(change) <= threshold or overlap:
                noise += 1
                continue
            digits = 1 if unit == "KB" else 0
            line = (scenario_of(n.label), describe(n), f"`{n.client}`", name,
                    f"{mo[0] * scale:,.{digits}f} {unit}", f"{mn[0] * scale:,.{digits}f} {unit}",
                    f"{change:+.1f}%")
            (better if (change > 0) == higher else worse).append((abs(change), line))

    out = [f"# Comparison: `{os.path.basename(os.path.normpath(old_path))}` → "
           f"`{os.path.basename(os.path.normpath(new_path))}`", ""]
    keys = sorted(set(old.versions) | set(new.versions))
    changed = [[f"`{k}`", old.versions.get(k, "—"), new.versions.get(k, "—")]
               for k in keys if old.versions.get(k) != new.versions.get(k) and k != "commit"]
    out += ["**What changed**", "",
            table(["", "old", "new"], changed) if changed else "No version difference recorded in versions.txt.", ""]
    cpus = {e.get("cpu") for s in (old, new) for e in s.envs.values() if e.get("cpu")}
    if len(cpus) > 1:
        out += ["> **Different machines** (" + " vs. ".join(sorted(cpus)) +
                "): absolute numbers are not comparable.", ""]
    out += [table(["", "count"], [
        ["measurements in both sets", str(len(common))],
        [f"regressions (worse by more than {threshold:g}%, intervals apart)", str(len(worse))],
        [f"improvements (better by more than {threshold:g}%, intervals apart)", str(len(better))],
        ["metric comparisons within noise", str(noise)],
        ["only in the old set", str(len(set(old_rows) - set(new_rows)))],
        ["only in the new set", str(len(set(new_rows) - set(old_rows)))],
    ]), ""]
    header = ["scenario", "measurement", "client", "metric", "old", "new", "change"]
    for title, items in (("Regressions", worse), ("Improvements", better)):
        out += [f"## {title}", ""]
        if items:
            items.sort(key=lambda x: (x[1][0], -x[0]))
            out += [table(header, [line for _, line in items]), ""]
        else:
            out += ["None.", ""]
    out += ["Run-to-run noise on this kind of machine reached ~5% (20% in one case) between runs with",
            "identical versions: confirm a regression by re-running that scenario before acting on it.", ""]
    return "\n".join(out)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    rp = sub.add_parser("report", help="Markdown report of one result set")
    rp.add_argument("set")
    cp = sub.add_parser("compare", help="regressions and improvements between two result sets")
    cp.add_argument("old")
    cp.add_argument("new")
    cp.add_argument("--threshold", type=float, default=5.0, help="minimum change in percent (default 5)")
    sp = sub.add_parser("scoreboard", help="weighted scores and winners of one result set")
    sp.add_argument("set")
    sp.add_argument("--into", help="Markdown file whose <!-- results.py:NAME --> blocks to replace")
    args = ap.parse_args()
    if args.cmd == "scoreboard":
        blocks = scoreboard_blocks(load_set(args.set))
        if args.into:
            write_into(args.into, blocks)
        else:
            sys.stdout.write("\n".join(b for b in blocks.values() if b))
    elif args.cmd == "report":
        sys.stdout.write(report(args.set))
    else:
        sys.stdout.write(compare(args.old, args.new, args.threshold))


if __name__ == "__main__":
    main()
