# jvm-http-client-benchmark

[JMH](https://github.com/openjdk/jmh) benchmarks comparing Java HTTP clients against nginx running in Docker.

**Results and when to pick each client: [RESULTS.md](RESULTS.md).**

| Client | Version | API used |
|---|---|---|
| `jdk` | `java.net.http.HttpClient` (JDK 21+) | synchronous `send()` |
| `apache5` | Apache HttpClient 5.6.5 | classic (blocking) |
| `okhttp` | OkHttp 5.5.1 | `Call.execute()` |
| `jetty` | Jetty HttpClient 12.1.14 | `Request.send()` |
| `jdk-vt` | same as `jdk` | client's internal executor on virtual threads |
| `jetty-vt` | same as `jetty` | client's internal pool on virtual threads (`VirtualThreadPool`) |
| `okhttp-sharded` | same as `okhttp` | experiment: 8 clients/pools (see the OkHttp investigation in RESULTS.md) |
| `jdk-async` | same as `jdk` | `sendAsync()` → `CompletableFuture` |
| `apache5-async` | Apache HttpClient 5.6.5 | async client (`CloseableHttpAsyncClient`, non-blocking I/O reactor) |
| `okhttp-async` | same as `okhttp` | `Call.enqueue()` (blocking I/O on the dispatcher's thread pool) |
| `jetty-async` | same as `jetty` | `CompletableResponseListener` |
| `jdk-vt-async` | same as `jdk-async` | internal executor on virtual threads |
| `jetty-vt-async` | same as `jetty-async` | internal pool on virtual threads (`VirtualThreadPool`) |
| `okhttp-vt-async` | same as `okhttp-async` | dispatcher runs each call on a virtual thread |
| `reactor` / `reactor-async` | Reactor Netty 1.3.8 | `block()` / `toFuture()`; the engine behind Spring's WebClient |

Abstraction layers, each wrapping one of the raw clients above (built by the same
`NativeClients` code, so the configuration is identical):

| Client | Layer | Underlying client |
|---|---|---|
| `spring-restclient-{apache5,jdk,jetty,reactor}` | Spring 7.0.10 `RestClient` | Apache classic, JDK, Jetty, Reactor Netty |
| `spring-webclient-{apache5,jdk,jetty,reactor}` | Spring 7.0.10 `WebClient` (`block()` or `toFuture()`) | Apache async, JDK, Jetty, Reactor Netty |
| `feign-{apache5,jdk,okhttp}` | OpenFeign 13.17 (plain, no Spring Cloud) | Apache classic, JDK, OkHttp |

Spring 7 has no OkHttp integration, and OpenFeign has no official Jetty client, hence the gaps.

By default the blocking benchmarks run the first four raw clients; the others are selected with
`-p client=...` (scenarios 5, 8 and 9), and the async ones are used by `GetAsyncBenchmark`
(scenarios 6, 7 and 9).

## Requirements

- JDK 21 (JDK 24+ for scenarios 5, 6, 7 and 9)
- Linux, Docker (your user in the `docker` group)
- Optional: `sudo` + `cpupower`, to pin the CPU frequency (`TUNE_CPU=1`)

The build uses the Gradle Wrapper (`./gradlew`); nothing else needs to be installed.

## Layout

```
bench-api/      common contract: BenchClient, ClientConfig, BenchClientProvider (ServiceLoader)
clients/        one adapter per library; NativeClients configures every underlying client
clients-abstractions/  Spring RestClient and WebClient, OpenFeign, on top of NativeClients
benchmarks/     GetBenchmark, PostBenchmark, GetAsyncBenchmark, ResourceProfiler (src/jmh)
server/nginx/   nginx.conf + payload generation (/bytes/0, /bytes/1k, /bytes/100k, /post)
server/netem/   helper image with `tc` to simulate network latency
scripts/        run-all.sh, server.sh, bench.sh, summarize.py, lib/cpu.sh
results/        curated JMH results, one folder per machine (local runs go to results/local/)
```

## How the clients are configured (same for all)

- HTTP/1.1, no TLS, keep-alive, pool of up to 64 connections (`MAX_CONNECTIONS`)
- `TCP_NODELAY` on
- no compression, redirects, automatic retries or cookies
- one client instance shared by all benchmark threads
- the response body is always read fully into a `byte[]`
- before measuring, the setup checks the response size (fails fast if something is off)

Differences that cannot be removed:

- **JDK:** no configurable pool limit, and the only read timeout is the per-request timeout,
  which schedules a timer on every call.
- **OkHttp and Jetty:** always send `Accept-Encoding: gzip`. gzip is off in nginx, so the
  responses are identical; only the request header differs.

## Choosing CPUs for your machine

The client (JMH) and the server (nginx) should run on **different physical cores**. The defaults
(`BENCH_CPUS=0,2`, `SERVER_CPUS=1,3`) fit the 2-core/4-thread laptop the published results come
from, where CPUs 0 and 2 are the two hyperthreads of core 0. Check your topology with `lscpu -e`
(the `CORE` column) and set both variables accordingly; update `worker_processes` in
`server/nginx/nginx.conf` to the number of server CPUs.

## Getting started

```bash
./gradlew :benchmarks:jmhJar                  # build (bench.sh also does this)
scripts/server.sh restart                     # nginx on loopback
scripts/bench.sh '(Get|Post)Benchmark' -f 1 -wi 1 -w 1s -i 1 -r 1s  # smoke test (~2 min): only validates, ignore the numbers
scripts/server.sh stop
```

## Run everything

`scripts/run-all.sh` runs all nine scenarios below in one go (about 5 hours), unattended:

```bash
scripts/run-all.sh --quick --no-tune      # ~10 min dry run: checks the whole pipeline here
JAVA_VT=/path/to/jdk-25/bin/java scripts/run-all.sh   # the real thing
```

- **Asks for sudo once**, pins the CPU frequency for the whole run, keeps sudo alive and
  restores the CPU at the end (Ctrl+C included). `--no-tune` skips this.
- **Checks everything up front** (Docker, JDK 24+ for scenario 5, sudo, build), so it doesn't
  fail two hours in.
- **A benchmark that throws doesn't lose the others**: the clients that worked are kept and the
  step is reported as a warning. A scenario that cannot run at all is reported as failed and the
  next one starts.
- **Writes everything to `results/local/run-<timestamp>/`**: JSON + `.env` per step, a log per
  step, `run-all.log` and `summary.txt`.
- `--only 1,3` runs a subset; `--list` shows the plan.

Keep the machine otherwise idle, and run it inside tmux/screen (or `nohup`) so that closing the
terminal doesn't stop it.

## Scenarios

Each scenario answers a different question. Time estimates are for the current defaults
(3 forks × [10 warmup + 5 measurement iterations] × 3 s, ≈ 2.4 min per parameter combination).

For stable numbers, run with `TUNE_CPU=1`: it sets the `performance` governor and disables
turbo for the run, restoring everything at the end (Ctrl+C included). sudo asks for a password,
so each scenario starts with `sudo -v`.

### How the scenarios guard against mistakes

Each block below runs in a `( ... )` subshell with `set -e`:

- **any failure stops the whole scenario**: server not starting, netem not applied, a benchmark
  throwing (JMH runs with fail-on-error);
- **`NETWORK` and `LATENCY` only apply inside the block** and don't leak into the next scenario;
- **`server.sh restart`** removes any server left over from a previous scenario;
- **`bench.sh` checks that the running server matches `NETWORK`/`LATENCY`** and aborts before
  measuring (and before asking for sudo) if it doesn't;
- **each result gets a `.env` next to it** with what was actually measured: target, server
  configuration, CPUs, governor, JDK, kernel.

Don't chain a block with `&&` (e.g. `cd dir && ( set -e ... )`): bash disables `set -e` inside
it in that case.

### 1. Cost per request (loopback, 1 thread, allocation)

> How much CPU and memory does each client spend per request?

On loopback the network latency is practically zero, so the measured time is almost entirely
the client's (and the kernel's) cost. `-prof gc` adds bytes allocated per request, and
`ResourceProfiler` adds CPU time per request (see scenario 5 for what its columns mean).

```bash
(
  set -e
  export NETWORK=host LATENCY=
  sudo -v
  scripts/server.sh restart
  TUNE_CPU=1 LABEL=cost-t1 scripts/bench.sh '(Get|Post)Benchmark' -t 1 -prof gc -prof io.github.cleidiano.httpbench.bench.ResourceProfiler
  scripts/server.sh stop
)
```

Time: ~48 min (20 combinations: GET 4×3 + POST 4×2).

### 2. Concurrency on loopback

> How does each client scale when several threads compete for the same pool?

```bash
(
  set -e
  export NETWORK=host LATENCY=
  sudo -v
  scripts/server.sh restart
  TUNE_CPU=1 LABEL=get-t4 scripts/bench.sh GetBenchmark -t 4 -prof io.github.cleidiano.httpbench.bench.ResourceProfiler
  TUNE_CPU=1 LABEL=loopback-1k-t16 scripts/bench.sh GetBenchmark -p payload=1k -t 16 -prof io.github.cleidiano.httpbench.bench.ResourceProfiler
  scripts/server.sh stop
)
```

Time: ~39 min. With 2 physical cores, 16 threads mostly measures CPU contention; on a bigger
machine try `-t 8`, `-t 16`, `-t 64`.

### 3. Latency distribution (percentiles)

> What are the typical latency (p50) and the tail (p99, p99.9) of each client?

```bash
(
  set -e
  export NETWORK=host LATENCY=
  sudo -v
  scripts/server.sh restart
  TUNE_CPU=1 LABEL=latency-1k scripts/bench.sh GetBenchmark -p payload=1k -bm sample -tu us -t 1
  scripts/server.sh stop
)
```

Time: ~10 min.

JMH measures in a closed loop: each thread only sends the next request after the previous one
returns. That hides part of the tail (*coordinated omission*). Use the percentiles to compare
clients with each other, not as an SLA.

### 4. Simulated network (container + latency)

> With real network latency, does the client's cost still matter? How does concurrency change
> the picture?

`NETWORK=bridge` makes the client talk to the container's IP (MTU 1500, like a real network).
`LATENCY` adds round-trip time with `tc netem` on the container's interface only, without
affecting the rest of the machine.

```bash
(
  set -e
  export NETWORK=bridge LATENCY=500us
  sudo -v
  scripts/server.sh restart
  scripts/server.sh status        # shows the container URI and the netem qdisc
  for t in 1 4 16; do
    TUNE_CPU=1 LABEL=network-500us-t$t scripts/bench.sh GetBenchmark -p payload=1k -t $t \
      -prof io.github.cleidiano.httpbench.bench.ResourceProfiler
  done
  scripts/server.sh stop
)
```

Time: ~29 min (≈10 min per thread count).

What to expect: with 1 thread, every client is capped at about 1/RTT (≈1,400 ops/s at 0.5 ms)
and the differences almost vanish. With more threads they come back. If the 1-thread result is
well above that, the latency was not applied.

Useful variations (change the block's `export`):

- **Control without latency:** `export NETWORK=bridge LATENCY=`. Compared with scenario 1, it
  isolates the cost of the bridge + veth path.
- **Another distance:** `export NETWORK=bridge LATENCY=2ms` simulates another zone or a nearby region.
- **More threads:** keep `MAX_CONNECTIONS` greater than or equal to the thread count (default 64).

### 5. Virtual threads vs platform threads

> With many concurrent requests waiting on the network, do virtual threads reduce CPU and memory
> cost? Does any client misbehave with them?

Two axes:

| Axis | How | What changes |
|---|---|---|
| Callers | `EXECUTOR=PLATFORM` or `VIRTUAL` | JMH's threads (the ones calling the client) become virtual threads |
| Inside the client | clients `jdk-vt` and `jetty-vt` | JDK and Jetty also run their internal work on virtual threads |

Apache classic and synchronous OkHttp do the I/O on the calling thread, so they have no `-vt`
variant.

**Run it on JDK 24+.** Up to JDK 23, a virtual thread blocked inside `synchronized` pins the
platform thread carrying it; JDK 24 fixed that (JEP 491). On JDK 21 the `-vt` clients with
`EXECUTOR=VIRTUAL` deadlock, because JMH itself initializes benchmark state inside
`synchronized`. The benchmark detects that combination and fails fast.

With 20 ms of RTT (a cross-region call), 64 and 256 threads are network-bound and 1,024 threads
saturate the CPU of a small machine, so both regimes show up in one scenario.

```bash
(
  set -e
  export NETWORK=bridge LATENCY=20ms
  export JAVA=/path/to/jdk-25/bin/java MAX_CONNECTIONS=1024
  sudo -v
  scripts/server.sh restart
  for executor in PLATFORM VIRTUAL; do
    for t in 64 256 1024; do
      TUNE_CPU=1 EXECUTOR=$executor LABEL=vt-$executor-t$t scripts/bench.sh GetBenchmark \
        -p payload=1k -p client=jdk,jdk-vt,apache5,okhttp,jetty,jetty-vt -t $t -f 2 \
        -prof gc -prof io.github.cleidiano.httpbench.bench.ResourceProfiler
    done
  done
  scripts/server.sh stop
)
```

Time: ~60 min (6 combinations × ~10 min; uses 2 forks instead of 3 to fit).

Besides ops/s, what to look at in the `summarize.py` table:

- **cpu us/op**: CPU time of the whole process per request, including the client's internal
  threads. With the CPU saturated it overestimates by up to ~6% (the CPU window is slightly
  larger than JMH's timing window); the error is the same for every client.
- **threads**: peak live platform threads (virtual threads are not counted).
- **rss MB**: resident memory of the process. It includes the 1 GB heap touched up front
  (`-XX:+AlwaysPreTouch`), so compare differences between runs, not absolute values.

#### Pinning diagnostics

`PIN_TRACE=1` prints a stack trace whenever a virtual thread pins its carrier. Use it to find
**whether and where** a client does that, for example on JDK 21:

```bash
scripts/server.sh restart
for c in jdk apache5 okhttp jetty; do
  EXECUTOR=VIRTUAL PIN_TRACE=1 scripts/bench.sh GetBenchmark -p client=$c -p payload=1k \
    -t 64 -wi 1 -w 2s -i 1 -r 2s 2>&1 | grep -B1 '<=='
done
scripts/server.sh stop
```

This mode runs JMH without forking (the trace output is lost in forked runs): **don't use its
numbers**. Lines with `jmh_generated` come from JMH itself; what matters are lines in the
client's code (on JDK 21, for example, OkHttp pins in `RealCall.initExchange` while opening
connections). On JDK 24+ `jdk.tracePinnedThreads` no longer exists; use the JFR event
`jdk.VirtualThreadPinned` instead.

### 6. Async APIs: one thread, many requests in flight

> Can one thread drive 1,024 concurrent requests through a native async API, and how does that
> compare with 1,024 platform or virtual threads making blocking calls (scenario 5)?

`GetAsyncBenchmark` runs on a single benchmark thread that keeps `inFlight` requests (64, 256,
1,024) outstanding: a semaphore caps the pending requests and each completion frees a slot. In
steady state the score is completed requests per second. Failures inside callbacks are rethrown
on the benchmark thread, so they never pass silently. Same network and JDK as scenario 5.

| Client | Async API | Non-blocking I/O |
|---|---|---|
| `jdk-async` | `sendAsync()` | yes |
| `apache5-async` | `CloseableHttpAsyncClient` (a different client from the classic one) | yes |
| `jetty-async` | `CompletableResponseListener` | yes |
| `okhttp-async` | `Call.enqueue()` | no: each in-flight call blocks a dispatcher thread |

```bash
(
  set -e
  export NETWORK=bridge LATENCY=20ms
  export JAVA=/path/to/jdk-25/bin/java MAX_CONNECTIONS=1024
  sudo -v
  scripts/server.sh restart
  TUNE_CPU=1 LABEL=async scripts/bench.sh GetAsyncBenchmark -t 1 -f 2 \
    -prof gc -prof io.github.cleidiano.httpbench.bench.ResourceProfiler
  scripts/server.sh stop
)
```

Time: ~20 min. Check with Little's law: at 64 in flight and ~20.5 ms per request the ceiling is
~3,100 ops/s; a result above that means something is wrong. `MAX_CONNECTIONS` must be at least the
largest `inFlight` (the setup refuses to run otherwise). In the `summarize.py` table, `us/op` is
`inFlight ÷ throughput`, the average time each request takes.

### 7. Async APIs on virtual threads

> Does running the async clients' internal work on virtual threads change throughput, CPU or the
> number of platform threads?

Scenario 6 again with virtual threads everywhere: the calling thread (`EXECUTOR=VIRTUAL`) and each
client's internal executor.

| Client | Internal work on virtual threads |
|---|---|
| `jdk-vt-async` | `HttpClient.executor(Executors.newVirtualThreadPerTaskExecutor())` |
| `jetty-vt-async` | `VirtualThreadPool` |
| `okhttp-vt-async` | `new Dispatcher(Executors.newVirtualThreadPerTaskExecutor())`: each enqueued call still does blocking I/O, but on a virtual thread instead of a platform thread |
| `apache5-async` | none: its I/O reactor is a non-blocking event loop. It runs as the reference shared with scenario 6 |

```bash
(
  set -e
  export NETWORK=bridge LATENCY=20ms EXECUTOR=VIRTUAL
  export JAVA=/path/to/jdk-25/bin/java MAX_CONNECTIONS=1024
  sudo -v
  scripts/server.sh restart
  TUNE_CPU=1 LABEL=async-vt scripts/bench.sh GetAsyncBenchmark \
    -p client=apache5-async,jdk-vt-async,jetty-vt-async,okhttp-vt-async -t 1 -f 2 \
    -prof gc -prof io.github.cleidiano.httpbench.bench.ResourceProfiler
  scripts/server.sh stop
)
```

Time: ~20 min. Compare each `*-vt-async` client with its `*-async` counterpart from scenario 6;
`apache5-async` should match across both runs. Needs JDK 24+ for the same pinning reasons as
scenario 5 (the benchmark refuses `*-vt*` clients with virtual-thread callers on older JDKs).

### 8. Spring and Feign layers: cost per request

> How much does each abstraction layer add on top of the client it wraps?

Every layer runs next to the raw client it wraps, on loopback with one thread (like scenario 1),
so the difference in time, CPU and allocation per request is the cost of the layer itself. The
response is read as `byte[]` everywhere: this measures the HTTP abstraction (URI building, request
and response wrapping, status handling, the byte-array converter), not JSON conversion.

```bash
(
  set -e
  export NETWORK=host LATENCY=
  sudo -v
  scripts/server.sh restart
  TUNE_CPU=1 LABEL=layers-t1 scripts/bench.sh GetBenchmark \
    -p client=apache5,apache5-async,jdk,jetty,okhttp,reactor,spring-restclient-apache5,spring-restclient-jdk,spring-restclient-jetty,spring-restclient-reactor,spring-webclient-apache5,spring-webclient-jdk,spring-webclient-jetty,spring-webclient-reactor,feign-apache5,feign-jdk,feign-okhttp \
    -p payload=1k,100k -t 1 -f 2 -prof gc -prof io.github.cleidiano.httpbench.bench.ResourceProfiler
  scripts/server.sh stop
)
```

Time: ~55 min. Pairs to compare: `apache5` with `spring-restclient-apache5` and `feign-apache5`;
`apache5-async` with `spring-webclient-apache5`; `jdk`, `jetty` and `reactor` with their
`spring-restclient-*`, `spring-webclient-*` and `feign-jdk`; `okhttp` with `feign-okhttp`.

Settings that differ from each framework's defaults, so that only the layer is measured:
Spring 7's `JdkClientHttpRequestFactory` enables compression by default (it is disabled here,
like in every other client), and Feign runs with no retries and the same timeouts.

### 9. Spring WebClient used asynchronously

> Used as intended (non-blocking), what does WebClient cost on top of the async client it wraps?

Same conditions as scenario 6, at 1,024 requests in flight from one thread.

```bash
(
  set -e
  export NETWORK=bridge LATENCY=20ms
  export JAVA=/path/to/jdk-25/bin/java MAX_CONNECTIONS=1024
  sudo -v
  scripts/server.sh restart
  TUNE_CPU=1 LABEL=layers-async scripts/bench.sh GetAsyncBenchmark \
    -p client=apache5-async,jdk-async,jetty-async,reactor-async,spring-webclient-apache5,spring-webclient-jdk,spring-webclient-jetty,spring-webclient-reactor \
    -p inFlight=1024 -t 1 -f 2 -prof gc -prof io.github.cleidiano.httpbench.bench.ResourceProfiler
  scripts/server.sh stop
)
```

Time: ~15 min.

## Analyzing results

```bash
scripts/summarize.py results/local/*-cost-t1.json      # one table per scenario
```

The table shows ops/s, error, min and max iteration, µs per request, the ratio to the best
client, bytes allocated per request and, with `ResourceProfiler`, CPU per request, platform
threads and resident memory. For `-bm sample` runs it shows p50, p90, p99, p99.9 and the max.

For charts, drop the JSON on <https://jmh.morethan.io>.

Read carefully:

- **Error above ~10%** or a **wide min..max**: look at the per-iteration values in the JSON
  (`rawData`). If values keep rising within a fork, warmup was too short; if a whole fork
  differs from another, there is environment noise.
- **Differences smaller than the error** are not differences.

To publish results, move the files you want from `results/local/` to `results/<machine>/`.

## Quick reference

| Variable | Script | Default | Purpose |
|---|---|---|---|
| `NETWORK` | server.sh, bench.sh | `host` | `host` (loopback) or `bridge` (container IP); in bench.sh, the expected server configuration |
| `LATENCY` | server.sh, bench.sh | — | extra RTT, e.g. `500us`, `2ms` (`bridge` only); in bench.sh, the expected latency |
| `SERVER_CPUS` | server.sh | `1,3` | nginx CPUs |
| `BENCH_CPUS` | bench.sh | `0,2` | JMH CPUs |
| `TUNE_CPU` | bench.sh | `0` | `1` pins the CPU frequency during the run (sudo) |
| `FAIL_ON_ERROR` | bench.sh | `1` | `0` keeps running the remaining benchmarks after one throws (run-all.sh uses it) |
| `JAVA_VT` | run-all.sh | — | JDK 24+ `java` for scenarios 5, 6, 7 and 9 |
| `LABEL` | bench.sh | — | suffix of the results file |
| `RESULTS_DIR` | bench.sh | `results/local` | where results go (ignored by git) |
| `JAVA` | bench.sh | `java` on PATH | JDK used for the benchmark |
| `EXECUTOR` | bench.sh | `PLATFORM` | `VIRTUAL` runs the benchmark threads as virtual threads (verified in setup) |
| `PIN_TRACE` | bench.sh | `0` | `1` prints where virtual threads pin their carrier (diagnostics, no fork) |
| `MAX_CONNECTIONS` | bench.sh | `64` | pool size (keep ≥ threads) |
| `BASE_URI` | bench.sh | `server.sh uri` | explicit target (e.g. a server on another machine); skips the server check |

Extra arguments to `bench.sh` go straight to JMH:

| JMH flag | Example | Effect |
|---|---|---|
| filter (regex) | `GetBenchmark` | runs the benchmarks whose name matches; without a filter JMH runs all of them, including `GetAsyncBenchmark`, which needs `MAX_CONNECTIONS` ≥ its `inFlight` |
| `-p` | `-p client=jdk,okhttp -p payload=1k` | restricts parameters |
| `-t` | `-t 4` | threads |
| `-bm` / `-tu` | `-bm sample -tu us` | mode and unit |
| `-prof` | `-prof gc` | profiler: allocation and GC |
| `-prof` | `-prof io.github.cleidiano.httpbench.bench.ResourceProfiler` | CPU per request, platform threads, resident memory |
| `-f` / `-wi` / `-i` | `-f 1 -wi 2 -i 3` | forks, warmup and measurement iterations |
| `-l` / `-lp` | `-lp` | lists benchmarks and parameters |

## Pitfalls found along the way

- **POST against nginx `return 200`:** nginx answers before reading the body, so the client
  never waits for its own upload. `/post` now goes through an internal `proxy_pass`, which
  forces reading the whole body. To verify, log `$request_length`.
- **OkHttp and Nagle:** OkHttp leaves Nagle's algorithm on. On a 100 KB POST the last chunk of
  the body waited for the server's delayed ACK, dropping from ~1,750 to ~50 ops/s. The adapter
  enables `TCP_NODELAY`, like the other clients.
- **JDK warmup:** with 10 s of warmup the JDK client was still speeding up between iterations
  (2.3k → 7.5k ops/s). The default is now 30 s.
- **Virtual threads on JDK 21:** see scenario 5.

## Next steps

- JSON to object conversion through each layer (Jackson), and Spring Cloud OpenFeign / Spring
  HTTP Interface on top of the layers measured here
- HTTP/2 and TLS
- POST and large bodies through the async APIs
