# Results

Four Java HTTP clients (HTTP/1.1, no TLS) measured with JMH against a local nginx. How to
reproduce: see the [README](README.md) (`scripts/run-all.sh` runs every scenario below).

> **All numbers come from a single machine**, a 2-core/4-thread laptop (Intel i7-5500U) with the
> CPU frequency pinned at 2.4 GHz. Absolute values only hold for that machine; what should carry
> over to other hardware is the relative order and the shape of the costs (fixed cost vs. cost
> per byte, network-bound vs. CPU-bound).

> **Summary:** **Apache HttpClient 5** has the lowest cost per request (2.5–5x less CPU than the
> others on small requests) and the highest throughput when the CPU is the bottleneck. **Jetty**
> allocates 2 to 20 times less memory, triggers the fewest GCs and has the lowest cost per byte.
> **OkHttp** is balanced and stable at moderate concurrency but degrades with hundreds of
> connections, because finding a free connection scans the whole pool on every request. The
> **JDK HttpClient** spends 2–3x the time and up to 5x the CPU of Apache per request and has the
> longest latency tail.
>
> With real network latency and moderate concurrency, Apache, OkHttp and Jetty reach the same
> throughput (the JDK ~10% behind), but **the CPU they spend still differs by up to 2.6x**.
>
> **Virtual threads** (JDK 25) save ~100–130 KB of memory per avoided platform thread for every
> client. They raise throughput only for clients that hand off between threads internally,
> **JDK (+30–38%) and Jetty (+39%)**, at 1,024 concurrent requests. For **Apache and OkHttp**,
> which block on the calling thread, they **cost 15–36% more CPU per request** at moderate
> concurrency.
>
> **Async APIs** (one thread, 1,024 requests in flight): **Apache's async client** matches its
> classic client's throughput and CPU with **10 platform threads instead of 1,030**. **Jetty's**
> async API is 45% faster than its blocking API on platform threads. The **JDK** only benefits once
> its internal executor runs on virtual threads. **OkHttp's** `enqueue` is not non-blocking: it
> held 887 platform threads for 1,024 requests in flight.

---

## When to pick each client

| Situation | Choice | Evidence |
|---|---|---|
| Many small calls (JSON APIs, microservices) with the CPU as the bottleneck | **Apache 5** | Lowest cost per request: 39 µs of CPU vs. 97–202 µs (GET 0 B); 1.6–3.8x the throughput at 4 threads |
| Container with little CPU (e.g. 0.5–1 vCPU) | **Apache 5**, then **OkHttp** | Lowest CPU per request with and without network latency; with the CPU saturated, the loopback ranking returns even with latency (scenario 4, 16 threads) |
| Small heap or sensitive to GC pauses; large responses | **Jetty** | 4.6 KB/request vs. 10–18 KB; 3 collections vs. 6–15; allocates ~1x the body on downloads |
| Large downloads | **Jetty** or **Apache 5** | Tie at 1 thread (183 µs each); Jetty allocates half as much |
| Large uploads | **Apache 5** (or **Jetty**) | Write the `byte[]` straight to the socket: 11 KB and 6 KB allocated per 100 KB POST, vs. 60–122 KB |
| Moderate concurrency (up to tens of connections), balanced profile; Android/Kotlin ecosystem | **OkHttp** | Stable between runs; matches Apache at 16 threads on loopback (24.2k vs. 23.2k ops/s) |
| Hundreds or thousands of concurrent HTTP/1.1 requests to the same server | **Apache 5** or **Jetty**; avoid **OkHttp** | With 1,024 connections OkHttp spends 26% of CPU looking for a free connection and reaches 0.63–0.71x Apache's throughput (investigation below) |
| Virtual threads with the JDK `HttpClient` | **JDK** with a virtual-thread executor | At 1,024 virtual threads: 20.2k ops/s with `.executor(newVirtualThreadPerTaskExecutor())`, vs. 11.7k with the default executor. Its async API alone doesn't help (9.8k, like blocking on platform threads) |
| Thousands of concurrent requests with few threads | **Apache 5 async** (`CloseableHttpAsyncClient`) | 25.1k ops/s at 1,024 in flight with 10 platform threads; same CPU per request as the classic client (80 vs. 85 µs) |
| Jetty at high concurrency | **Jetty async API**, optionally with `VirtualThreadPool` | +45% over its blocking API on platform threads at 1,024; the virtual-thread pool cuts CPU per request by ~33% at moderate load and threads from 208 to 13 |
| Virtual threads with Apache or OkHttp | only for the memory | ~130 MB less RSS at 1,024 threads, but +15–36% CPU per request at 64–256 threads and no throughput gain. For Apache, its async client gets the same savings without the CPU cost |
| OkHttp `enqueue` for concurrency | doesn't save threads | One platform thread per in-flight call (887 at 1,024); with a virtual-thread dispatcher 100 threads, but no faster than blocking calls on virtual threads |
| Virtual threads on JDK 21 | avoid, or use JDK 24+ | `synchronized` pins the virtual thread to its carrier: deadlock with `jdk-vt`, pinning in OkHttp while opening connections |
| No dependencies; low volume; CLIs, scripts, internal tools | **JDK** | With network latency and 1–4 threads it is within ~10–20% of the others |
| Latency-critical path (p99.9) or high volume | avoid **JDK** | Longest tail (p99.9 679 µs, 3.0x its median; p99.99 3.9 ms vs. 0.5–0.7 ms); 2–3x the time and up to 5x the CPU per request |

**Performance is only one criterion.** Features and ecosystem may weigh more:
- **JDK:** zero dependencies, built-in HTTP/2.
- **OkHttp:** standard on Android, interceptors.
- **Jetty:** HTTP/2 and HTTP/3, asynchronous API.
- **Apache:** mature, classic and async APIs.

And when the network dominates request time, throughput stops telling the clients apart (see
scenario 4); CPU per request still does.

---

## Environment

| | |
|---|---|
| CPU | Intel i7-5500U (2 cores / 4 threads). JMH on CPUs 0,2 (one physical core), nginx on CPUs 1,3 |
| Frequency | Pinned at 2.4 GHz for every scenario (`performance` governor, turbo off) |
| JVM | OpenJDK 21.0.12 (Zulu); scenarios 5–7 on OpenJDK 25.0.5 (Zulu). Always `-Xms1g -Xmx1g -XX:+AlwaysPreTouch`, G1 |
| Kernel | Linux 7.0 |
| Server | nginx 1.30.6 (Docker), keep-alive, gzip off |
| Clients | JDK `HttpClient` · Apache HttpClient 5.6.5 (classic) · OkHttp 5.5.1 · Jetty 12.1.14 |
| JMH | 1.37; 3 forks × (10 × 3 s warmup + 5 × 3 s measurement). Scenarios 5–7: 2 forks |
| Runs | Scenarios 1–5: one `scripts/run-all.sh` run (190 min). Scenarios 6–7: a second run the next day (37 min). No failed benchmark |

Same configuration for every client: HTTP/1.1, pool of 64 connections (1,024 in scenario 5),
`TCP_NODELAY`, no compression, redirects, retries or cookies, body read fully into a `byte[]`.

**Metrics.** *Time per request* is wall-clock time seen by one benchmark thread (threads ÷
throughput). *CPU per request* is the CPU time of the whole client process (including the
client's internal threads, JIT and GC) divided by the requests completed; with the CPU saturated
it overestimates by up to ~6%. *CPUs used* = throughput × CPU per request.

**Reproducibility.** An earlier full run on the same machine (not published) matched these
results within ±5% at 4 and 16 threads; at 1 thread the median difference was 4%.

---

## Scenario 1: cost per request (loopback, 1 thread)

On loopback the network latency is practically zero: this measures what the client costs.

### Time per request (µs, lower is better)

| | GET 0 B | GET 1 KB | GET 100 KB | POST 1 KB | POST 100 KB |
|---|---|---|---|---|---|
| **apache5** | **61.6** | **83.2** | **182.8** | **152.9** | **288.8** |
| okhttp | 83.5 | 103.8 | 250.1 | 180.2 | 440.3 |
| jetty | 99.3 | 102.5 | 183.1 | 215.3 | 334.8 |
| jdk | 182.2 | 226.5 | 373.1 | 307.7 | 463.3 |

Error between 0.6% and 9.5%. POST times include an internal nginx `proxy_pass` (needed to read the
whole body); compare clients within the same request type only, not POST against GET.

### CPU per request (µs, lower is better)

| | GET 0 B | GET 1 KB | GET 100 KB | POST 1 KB | POST 100 KB | CPUs used (GET 0 B) |
|---|---|---|---|---|---|---|
| **apache5** | **39.3** | **54.4** | **146.5** | **43.3** | **112.9** | **0.64** |
| okhttp | 97.2 | 124.4 | 291.3 | 107.0 | 478.2 | 1.16 |
| jetty | 113.5 | 116.2 | 198.7 | 138.0 | 194.3 | 1.14 |
| jdk | 201.6 | 261.6 | 421.0 | 255.1 | 384.2 | 1.11 |

With a **single calling thread**, OkHttp, Jetty and the JDK keep more than one CPU busy: they
spend more CPU per request than wall-clock time, so part of the work runs in parallel on their
internal threads (selector and executor in Jetty and the JDK; for OkHttp the likely candidate,
not verified, is the pool-cleanup task scheduled on its `TaskRunner` threads every time a
connection is released). Apache uses no extra thread and stays at 0.64 CPU. In CPU, the gap is
much wider than in time: **2.5x (OkHttp) to 5x (JDK)** on small requests.

### Fixed cost vs. cost per byte (time)

| | Fixed cost (GET 0 B) | + 100 KB download | + ~100 KB upload |
|---|---|---|---|
| apache5 | **62 µs** | 121 µs | 136 µs |
| okhttp | 84 µs | 167 µs | 260 µs |
| jetty | 99 µs | **84 µs** | **120 µs** |
| jdk | 182 µs | 191 µs | 156 µs |

- **Apache** wins on its low fixed cost.
- **Jetty** has the lowest cost per byte, which is why it ties with Apache on large downloads.
- **OkHttp** is the most expensive on upload: it copies the body into Okio's internal buffers, allocating ~60 KB.
- Most of the **JDK**'s disadvantage is fixed cost, ~120 µs above Apache before any byte is transferred.

### Memory and GC

| | B/request, GET 0 B | B/request, GET 100 KB | B/request, POST 100 KB | MB/s allocated (GET 0 B) | Collections (GET 0 B) |
|---|---|---|---|---|---|
| apache5 | 11,910 | 216,842 (2.1x the body) | 11,222 | 184 | 15 |
| okhttp | 10,513 | 154,400 (1.5x) | 59,857 | 120 | 9 |
| jetty | **4,628** | **109,661 (1.07x)** | **5,914** | **44** | **3** |
| jdk | 17,847 | 226,511 (2.2x) | 121,937 | 93 | 6 |

Collections add up the 3 forks (45 s of measurement in total).

- **Per request**, Jetty allocates 2.3–3.9x less than the others on small requests, and
  practically only the body on large responses. Apache and the JDK allocate twice the body (a
  buffer that grows and gets copied).
- **Per second**, Apache allocates the most, precisely because it serves more requests: 15
  collections against Jetty's 3.
- **GC is still cheap in this benchmark**: a few milliseconds per second at most. The heap is 1 GB
  and the client's garbage dies young. In an application with live data on the heap each
  collection costs more, so **these numbers understate the GC effect**.

---

## Scenario 2: concurrency (loopback, GET)

With 4 threads competing for one physical core the CPU saturates, and throughput measures
efficiency under contention.

| ops/s, 4 threads | 0 B | 1 KB | 100 KB | gain vs. 1 thread (0 B / 1 KB / 100 KB) | CPU per request, 0 B |
|---|---|---|---|---|---|
| **apache5** | **36,216** | **26,215** | **9,724** | 2.23x / 2.18x / 1.78x | **54 µs** |
| okhttp | 23,018 | 19,983 | 8,803 | 1.92x / 2.07x / 2.20x | 85 µs |
| jetty | 18,301 | 15,867 | 8,907 | 1.82x / 1.63x / 1.63x | 106 µs |
| jdk | 9,598 (±16.9%) | 9,140 (±15.5%) | 4,981 | 1.75x / 2.07x / 1.86x | 207 µs |

- **Apache** extends its lead on small bodies: 2x Jetty, 3.8x the JDK.
- **Jetty** gains the least from more threads (~1.6–1.8x) and loses the tie on the 100 KB
  download. The unverified hypothesis is the handoff between its selector and executor threads
  competing with the benchmark threads for the same CPU.
- **JDK** is unstable at 4 threads (±15–17%), with isolated drops within the forks.

### Loopback with 1, 4 and 16 threads (GET 1 KB)

| ops/s (CPU per request) | 1 thread | 4 threads | 16 threads |
|---|---|---|---|
| apache5 | **12,014** (54 µs) | **26,215** (63 µs) | 23,180 ±12.5% (89 µs) |
| okhttp | 9,637 (124 µs) | 19,983 (96 µs) | **24,220** (82 µs) |
| jetty | 9,755 (116 µs) | 15,867 (122 µs) | 19,683 (101 µs) |
| jdk | 4,415 (262 µs) | 9,140 ±15.5% (214 µs) | 9,862 ±8.8% (202 µs) |

At 16 threads (16 threads on 2 logical CPUs) OkHttp catches up with Apache: Apache's CPU per
request rises with oversubscription (54 → 89 µs) while OkHttp's falls (124 → 82 µs).

---

## Scenario 3: latency distribution (loopback, GET 1 KB, 1 thread)

| µs | p50 | p90 | p99 | p99.9 | p99.99 | max | p99.9 / p50 |
|---|---|---|---|---|---|---|---|
| **apache5** | **70** | **78** | **113** | **140** | **540** | 9,601 | **2.0x** |
| okhttp | 101 | 108 | 136 | 213 | 732 | 10,093 | 2.1x |
| jetty | 98 | 109 | 134 | 222 | 707 | 6,447 | 2.3x |
| jdk | 227 | 262 | 327 | 679 | 3,920 | 7,135 | 3.0x |

- Apache, OkHttp and Jetty have narrow distributions: p99 is 1.35–1.6x the median.
- The **JDK** has the longest tail: p99.99 at 3.9 ms, 5–7x the others.
- The max (6–10 ms for everyone) is dominated by the environment (GC, kernel, scheduler) and
  rests on a handful of samples.
- JMH measures in a closed loop, which understates the tail (*coordinated omission*). These
  percentiles are for comparing clients with each other, not an SLA.

---

## Scenario 4: simulated network (bridge + 500 µs RTT, GET 1 KB)

The client talks to the container's IP (MTU 1500), and `tc netem` adds 0.5 ms of RTT.

| ops/s (CPU per request) | 1 thread | 4 threads | 16 threads |
|---|---|---|---|
| apache5 | **1,457** (99 µs) | **5,907** (87 µs) | **23,358** (67 µs) |
| okhttp | 1,372 (197 µs) | 5,793 (125 µs) | 20,177 (96 µs) |
| jetty | 1,343 (215 µs) | 5,813 (145 µs) | 16,318 (120 µs) |
| jdk | 1,201 (311 µs) | 5,255 (228 µs) | 10,139 ±11.9% (197 µs) |
| first vs. last | 1.21x | 1.12x | 2.30x |

On loopback the gap between first and last was ~2.7x at 1 thread and ~2.9x at 4 threads.

1. **1 thread:** latency flattens the differences (from 2.7x to 1.2x). Each request takes
   686–833 µs: 500 µs of RTT plus the client and the bridge/veth path.
2. **4 threads:** Apache, OkHttp and Jetty **tie** (~5.8–5.9k ops/s; by Little's law, 4 threads ÷
   ~0.68 ms); the JDK is ~11% behind. The CPU is far from saturated, but **CPU per request still
   differs by 2.6x** (87 µs for Apache vs. 228 µs for the JDK): equal throughput, unequal cost.
3. **16 threads:** Apache reaches the network ceiling (16 ÷ 0.686 ms ≈ 23.3k ops/s) using 1.57
   CPUs; the others saturate the CPU first, and **the loopback ranking comes back**.

**Practical conclusion:** the client's cost matters whenever the CPU saturates, even with real
network latency, which happens with high concurrency per core and, above all, in containers with
CPU limits. Below saturation it shows up as CPU left for (or taken from) the application.

---

## Scenario 5: virtual threads vs. platform threads (JDK 25, 20 ms RTT, GET 1 KB)

Two axes: JMH's threads, which call the client, run as platform threads (PT) or virtual threads
(VT); and the `jdk-vt` and `jetty-vt` variants also run the client's internal work on virtual
threads. With 20 ms of RTT, 64 and 256 threads are network-bound and 1,024 threads saturate the
CPU. Pool of up to 1,024 connections.

### 64 and 256 threads: network-bound

Every client reaches the network ceiling (~3.1k and ~10–12.6k ops/s) with both thread kinds:
**virtual threads don't change throughput** here. They change what the throughput costs:

| CPU per request, PT → VT | 64 threads | 256 threads | Platform threads alive at 256 (PT → VT) | RSS at 256 |
|---|---|---|---|---|
| apache5 | 83 → 95 µs (**+15%**) | 77 → 99 µs (**+29%**) | 262 → **11** | −30 MB |
| okhttp | 109 → 148 µs (**+36%**) | 126 → 157 µs (**+25%**) | 286 → 53 | −25 MB |
| jetty | 140 → 137 µs (−2%) | 145 → 141 µs (−3%) | 463 → 143 | −30 MB |
| jetty-vt | 106 → 89 µs (**−16%**) | 115 → 97 µs (**−15%**) | 268 → **13** | −34 MB |
| jdk | 216 → 222 µs (+3%) | 197 → 182 µs (−8%) | 372 → 109 | −30 MB |
| jdk-vt | 176 → 144 µs (**−18%**) | 149 → 135 µs (−9%) | 266 → **11** | −23 MB |

The likely explanation, consistent with every row but not verified directly: on a platform
thread, a blocking socket read waits in the kernel with no other thread involved. On a virtual
thread, the JVM registers the socket with a poller (an internal platform thread), parks the
virtual thread and has the poller wake it up when data arrives: **one more handoff**. Apache and
OkHttp, which did their I/O directly on the calling thread, *gain* that handoff and spend more
CPU. `jdk-vt` and `jetty-vt`, which already had internal handoffs, get cheaper ones and spend
less.

### 1,024 threads: CPU-bound

| | PT ops/s | VT ops/s | VT/PT | CPU per request, PT → VT | Platform threads (PT → VT) | RSS |
|---|---|---|---|---|---|---|
| **apache5** | **24,344** | **22,374** | 0.92x | 85 → 91 µs | 1,030 → 11 | −128 MB |
| jetty | 14,953 | 20,808 | **1.39x** | 142 → 99 µs | 1,231 → 211 | −98 MB |
| jdk-vt | 14,692 | 20,224 | **1.38x** | 140 → 101 µs | 1,034 → 11 | −115 MB |
| jetty-vt | 17,686 | 20,528 | 1.16x | 116 → 99 µs | 1,036 → 13 | −108 MB |
| okhttp | 15,397 | 15,853 | 1.03x | 129 → 131 µs | 1,061 → 66 | −134 MB |
| jdk | 9,036 | 11,739 | 1.30x | 225 → 171 µs | 1,183 → 314 | −89 MB |

Error 1.3–6.9%. With 1,024 platform threads on 2 CPUs, the OS's context switching dominates, and
the picture shifts:

1. **Clients with internal thread handoffs gain throughput**: Jetty (+39%) and the JDK (+30 to
   +38%), with 24–30% less CPU per request.
2. **Apache and OkHttp don't gain** (0.92x and 1.03x). Apache is still the fastest in absolute
   terms with either thread kind.
3. **Mixing models in the JDK is expensive**: with virtual-thread callers, the default `jdk`
   (internal executor on platform threads) does 11.7k ops/s and `jdk-vt` does 20.2k (**1.7x**).
4. **Memory**: −90 to −134 MB of RSS for every client, about 100–130 KB per avoided platform thread.

`jetty-vt` with 1,024 platform-thread callers failed once in an earlier run (one fork in five, a
10 s idle timeout); it did not fail in this run. The unverified hypothesis is that the 2 carriers
of its internal virtual threads get a tiny share of CPU while competing with 1,024 platform threads.

**JDK 21 vs. JDK 24+.** On JDK 21 a virtual thread blocked inside `synchronized` (or waiting to
enter a monitor) pins its carrier. Observed consequences:

- **Deadlock** with `jdk-vt` and virtual-thread callers: JMH initializes benchmark state inside
  `synchronized`, both carriers get pinned, and the client's internal virtual threads, which
  would deliver the response, never run (confirmed with a thread dump). The benchmark now refuses
  that combination on JDK < 24.
- **Pinning inside OkHttp** in `RealCall.initExchange` while opening connections (`PIN_TRACE=1`).
  Apache, the JDK and Jetty showed no pinning in their own code.

On JDK 25 there were no pinning events at all (JFR).

---

## Scenarios 6 and 7: async APIs (JDK 25, 20 ms RTT, GET 1 KB)

A single benchmark thread keeps 64, 256 or 1,024 requests in flight through each client's native
async API (a semaphore caps the pending requests; each completion frees a slot). Scenario 6 uses
the clients' default internal threads; scenario 7 runs everything on virtual threads: the
calling thread and each client's internal executor (JDK executor, Jetty `VirtualThreadPool`,
OkHttp dispatcher). Same network and JDK as scenario 5.

`apache5-async` has no executor to swap (its I/O reactor is a non-blocking loop) and ran in both
scenarios as a shared reference: 3,122 / 3,120, 12,529 / 12,545 and 25,071 / 24,664 ops/s, within
1.6%, so the two runs compare directly.

### 64 and 256 in flight: network-bound

Throughput sits at the network ceiling for everyone (~3.1k and ~12.5k ops/s) except the JDK at
256 (9.3k / 10.1k), which already saturates the CPU. CPU per request:

| CPU per request | 64, default threads | 64, virtual threads | 256, default threads | 256, virtual threads |
|---|---|---|---|---|
| apache5-async | **87 µs** | 93 µs (reference) | **84 µs** | 95 µs (reference) |
| jetty | 123 µs | **82 µs (−34%)** | 126 µs | **86 µs (−32%)** |
| jdk | 231 µs | 167 µs (−28%) | 212 µs | 135 µs (−36%) |
| okhttp | 150 µs | 162 µs (+7%) | 157 µs | 163 µs (+4%) |

With its internal work on virtual threads, **Jetty's async API costs as little CPU as Apache's**.
OkHttp goes the other way: its dispatcher still runs blocking I/O per call, now on virtual
threads, which adds the poller handoff seen in scenario 5. The reference's slightly higher CPU in
scenario 7 comes from its calling thread being virtual there.

### 1,024 in flight: CPU-bound

| | Default threads (scenario 6) | Virtual threads (scenario 7) |
|---|---|---|
| **apache5-async** | **25,071 ops/s**, 80 µs CPU, **10 threads** | 24,664 ops/s (reference) |
| jetty | 21,742, 92 µs, 208 threads | 21,731, 93 µs, **13 threads** |
| jdk | 9,774 (±16.4%), 207 µs, 166 threads | **17,331**, 116 µs, **12 threads** |
| okhttp | 13,284, 152 µs, **887 threads**, RSS 1,226 MB | 14,524 (±9.6%), 139 µs, 100 threads, RSS 1,147 MB |

### Four ways to run 1,024 concurrent requests

Throughput (platform threads alive). The blocking columns come from scenario 5, the async ones
from scenarios 6 and 7; all with the CPU pinned and the same network, but scenario 5 ran on a
different day, so treat differences below ~5% across columns as ties.

| | Blocking, platform threads | Blocking, virtual threads | Async | Async + virtual threads |
|---|---|---|---|---|
| **Apache 5** | 24.3k (1,030) | 22.4k (11) | **25.1k (10)** | — |
| **Jetty** | 15.0k (1,231) | 20.8k (211) | **21.7k (208)** | **21.7k (13)** |
| **JDK** | 9.0k (1,183) | **20.2k (11)**\* | 9.8k (166) | 17.3k (12) |
| **OkHttp** | 15.4k (1,061) | **15.9k (66)** | 13.3k (887) | 14.5k (100) |

\* `jdk-vt`: virtual threads also for the client's internal executor.

1. **Apache's async client is the best combination**: classic-client throughput and CPU with the
   thread footprint of virtual threads, and without the CPU that virtual threads cost Apache's
   blocking client (+7 to +29% in scenario 5).
2. **Jetty gains the most from its async API** (+45% over blocking on platform threads). Its
   virtual-thread pool adds nothing to throughput at saturation, but cuts threads to 13 and CPU by
   a third at moderate load.
3. **For the JDK, the async API alone changes nothing**; the virtual-thread executor is what
   matters (+77% at 1,024, −28 to −36% CPU). Its best configuration is still blocking calls on
   virtual threads.
4. **OkHttp's `enqueue` is one thread per request.** A virtual-thread dispatcher brings the
   platform threads from 887 to 100 (the rest are its `TaskRunner` pool-cleanup threads), but every
   model hits the same ~13–16k ceiling set by its linear pool scan (see the investigation below).

**Best model per client at high concurrency:** Apache, async client · Jetty, async API (with
`VirtualThreadPool` for less CPU and fewer threads) · JDK, blocking calls on virtual threads with a
virtual-thread executor · OkHttp, blocking calls on virtual threads.

---

## Investigation: why OkHttp doesn't scale to many connections

**Question:** why does OkHttp gain nothing from virtual threads and stay 29–37% below Apache at
1,024 concurrent requests, with both thread models?

**Method:** OkHttp and Apache with 1,024 virtual threads (JDK 25, 20 ms RTT), with JFR (CPU
profile, monitor contention, pinning) and thread dumps in the middle of the measurement. These
diagnostic runs were shorter (1 fork) and done before the CPU frequency was pinned.

**What JFR showed:**

| | OkHttp | Apache 5 |
|---|---|---|
| Hottest method | `RealConnectionPool.callAcquirePooledConnection`: **24%** of samples | none above 3.6% |
| Monitor contention | `RealConnectionPool.closeConnections`: 43 events, avg 388 ms, **max 2.56 s** | none |
| Platform threads beyond the JVM's | **61 `OkHttp TaskRunner`** (idle at dump time) + `Okio Watchdog` | none |
| Pinning (JDK 25) | none | none |

**Cause.** For every request, OkHttp 5.5.1 walks **all** pooled connections (a
`ConcurrentLinkedQueue`) and, for each one, enters `synchronized(connection)` and calls
`isEligible()` until it finds a free one (confirmed in the bytecode). With HTTP/1.1 connections
nearly all busy, the cost of finding one grows with the pool size:

| OkHttp, virtual threads | 256 connections | 1,024 connections |
|---|---|---|
| CPU in `callAcquirePooledConnection` | 8% | **26%** |
| Lookup samples per request | 1x | ~5.4x |

The pool cleanup (`closeConnections`, on `TaskRunner` threads) walks the same list and contends
on the same monitors. That explains the multi-second waits and the dozens of `TaskRunner` threads
created while others were blocked.

**Causal test.** An experimental variant, `okhttp-sharded`, spreads the threads across 8
`OkHttpClient` instances (~128 connections per pool, an 8x shorter list):

| 1,024 threads, 1 fork | Platform | Virtual |
|---|---|---|
| okhttp | 16,361 | 18,758 |
| okhttp-sharded | **20,400 (+25%)** | **20,988 (+12%)** |
| apache5 | 25,356 | 26,382 |
| apache5 ÷ okhttp-sharded | 1.24x | 1.26x |

The min..max ranges don't overlap. With the pool split, the gap to Apache drops to ~1.25x,
the same as in scenario 1 at 1 thread and 1 KB (1.25x). In other words: without the lookup cost,
OkHttp is back to its normal per-request disadvantage, without the extra degradation at high
concurrency.

**Conclusion.** OkHttp gains nothing from virtual threads for two reasons that add up:
1. there is no thread handoff to remove (same as Apache);
2. with many connections, the limit becomes CPU spent on the linear pool scan, which costs the
   same with platform or virtual threads.

**Rejected hypothesis.** Okio's global lock (`AsyncTimeout`, a static `ReentrantLock` used on
every read and write with a timeout) shows up neither among the hottest methods nor in the
contention events.

**Implication.** OkHttp does well with tens of connections, the typical app usage. With hundreds
or thousands of concurrent HTTP/1.1 requests to the same server, the pool lookup becomes the
bottleneck. Not tested: with HTTP/2, many requests share few connections, so the problem should
shrink.

---

## Pitfalls found along the way

They would affect any HTTP client benchmark; recorded for whoever reproduces this.

| Problem | Symptom | Fix |
|---|---|---|
| nginx `return 200` on POST | nginx answers before reading the body; Apache's 100 KB POST was as fast as its 1 KB one | `/post` goes through an internal `proxy_pass`, which reads the whole body; verified with `$request_length` |
| OkHttp with Nagle's algorithm on | 100 KB POST at ~20 ms per request (50 ops/s): Nagle on the client plus delayed ACK on the server | `TCP_NODELAY` in the adapter, like the other clients: 1,756 ops/s |
| Short warmup | JDK speeding up between iterations (2.3k → 7.5k ops/s), ±60% error | 30 s warmup |
| Variable CPU frequency | Forks of the same client differing by up to ~35%; with 0.5 ms RTT, ~940 µs per request instead of ~690 µs | `TUNE_CPU=1` / `run-all.sh` (`performance` governor, turbo off) |
| One scenario's server reused by the next | A "with latency" run actually measuring loopback | `server.sh restart`, configuration check in `bench.sh`, `.env` per result |
| Virtual-thread deadlock on JDK 21 | `jdk-vt` stuck in the first warmup iteration | Thread dump: JMH initializes state in `synchronized`; the benchmark now refuses the combination on JDK < 24 |
| Benchmark error with exit code 0 | A run that throws goes unnoticed by scripts | `-foe true` (fail on error) in `bench.sh` |
| One failing client discarding a whole step | A JSON file is only written at the end, so one exception lost every client's results | `run-all.sh` keeps going (`FAIL_ON_ERROR=0`) and reports the step as a warning |
| `jdk.tracePinnedThreads` printing nothing | No trace with forked JMH | `PIN_TRACE=1` runs without forking (diagnostics only) |
| Inflated CPU per request | Up to 3.5 CPUs on a process pinned to 2 | `ResourceProfiler` divides by all of the iteration's operations (`allOps`) |

---

## Limitations and open questions

- **Small machine.** One physical core for the client shows neither lock contention nor scaling
  across many cores. Absolute numbers only hold for this machine; the relative order and the
  split between fixed cost and cost per byte should carry over.
- **Scope.** HTTP/1.1 only, no TLS, GET only for the async APIs.
- **Cross-run comparison.** The four-model table mixes scenario 5 with scenarios 6–7, measured on
  different days; both runs had the CPU pinned, but there is no shared reference between them.
- **Retained memory not measured.** The unverified hypothesis is that Jetty's low allocation
  comes from buffer pooling, which trades allocation for memory that stays occupied.
- **GC under pressure not measured.** A scenario with a small heap and live data is missing; that
  is where allocation would really show.
- **Why is the JDK slower?** The hypotheses (internal thread handoffs, a timeout timer per
  request, several `CompletableFuture` stages) have not been checked with a profiler.
- **Why is the JDK unstable at 4+ threads and why the long tail?** Also open.
- **Virtual-thread CPU overhead for blocking clients.** The poller-handoff explanation fits the
  data but was not profiled.
- **OkHttp with HTTP/2.** Not tested; the linear pool scan should matter less with multiplexing.

## Result files

All under `results/i7-5500U/`, each JSON with a `.env` describing what was measured.

| Scenario | File |
|---|---|
| 1. Cost per request + GC + CPU | `*-cost-t1.json` |
| 2. Concurrency | `*-get-t4.json`, `*-loopback-1k-t16.json` (the 1-thread column comes from `cost-t1`) |
| 3. Latency distribution | `*-latency-1k.json` |
| 4. Simulated network | `*-network-500us-t{1,4,16}.json` |
| 5. Virtual threads | `*-vt-{PLATFORM,VIRTUAL}-t{64,256,1024}.json` |
| 6. Async APIs | `*-async.json` |
| 7. Async APIs on virtual threads | `*-async-vt.json` |
| OkHttp investigation | `*-investigate-shard-{PLATFORM,VIRTUAL}.json` (JFR recordings and thread dumps are not versioned) |
