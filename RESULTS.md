# Results

Every number here links back to a **result set**: one folder of JMH output for one set of library
versions on one machine. Full tables are in each set's `REPORT.md`; this page keeps the index and
the conclusions. How sets are produced and compared: [README](README.md#results-and-regressions).

## Result sets

| Machine | Set | What it measured | Report | Compared with |
|---|---|---|---|---|
| i7-5500U | `2026-11-01-baseline` | first measurement: Apache 5.6.5, OkHttp 5.5.1, Jetty 12.1.14, Reactor Netty 1.3.8, Spring 7.0.10, Feign 13.17; JDK 21.0.12 and 25.0.5 | [REPORT.md](results/i7-5500U/2026-11-01-baseline/REPORT.md) | — |

**Noise to expect between sets:** with identical versions, two runs on this machine differed by up
to ~5% (once 20%, Apache at 1 thread). `scripts/results.py compare` only flags changes above 5% whose
confidence intervals don't overlap; re-run a scenario before trusting a single flagged regression.

## Investigations

One-off studies, outside the regular scenarios.

| Machine | Investigation | Answer |
|---|---|---|
| i7-5500U | [OkHttp pool scan](results/i7-5500U/investigations/2026-11-01-okhttp-pool-scan/README.md) | OkHttp scans its whole pool for a free connection on every request: 26% of CPU at 1,024 connections |

---

## Conclusions

> Current set: **i7-5500U / 2026-11-01-baseline**. One 2-core/4-thread laptop, CPU pinned at
> 2.4 GHz, HTTP/1.1 without TLS. Absolute numbers hold for this machine only; the order between
> clients and the shape of the costs should carry over.

### At a glance

| | `apache5` | `okhttp` | `jetty` | `jdk` |
|---|---|---|---|---|
| CPU per request, GET 1 KB, 1 thread | **54 µs** | 124 µs | 116 µs | 262 µs |
| Allocation per request, GET 1 KB | 14.0 KB | 11.6 KB | **6.3 KB** | 21.1 KB |
| Throughput, loopback, 4 threads, GET 1 KB | **26.2k** | 20.0k | 15.9k | 9.1k |
| Latency p99.9, GET 1 KB | **140 µs** | 213 µs | 222 µs | 679 µs |
| Throughput at 1,024 concurrent requests, best model | **25.1k** (async client) | 15.9k (blocking, VT) | 21.7k (async API) | 20.2k (blocking, VT + VT executor) |

### Raw clients (scenarios 1–4)

- **Apache:** lowest fixed cost per request. 2.1–5.9x less CPU than the others on small requests.
- **Jetty:** lowest cost per byte. Allocates about the body size on downloads; ties Apache on a
  100 KB GET (183 µs each).
- **OkHttp:** about 2x Apache's CPU. At 16 threads on loopback it matches Apache (24.2k vs.
  23.2k ±12% ops/s).
- **JDK:** 1.6–3x Apache's time and 2.9–5.9x its CPU. Longest tail: p99.99 3.9 ms vs. 0.5–0.7 ms.
- **With 500 µs RTT:** at 1–4 threads throughput ties (JDK ~11% behind) but CPU per request still
  differs 2.6x. At 16 threads the CPU saturates and the loopback order returns.

POST times include an nginx `proxy_pass`: compare clients within one request type only.

### Virtual threads (scenario 5, JDK 25)

| | Effect at 1,024 threads | Effect at 64–256 threads |
|---|---|---|
| `jetty`, `jdk`, `jdk-vt` | **+30 to +39% throughput**, 24–30% less CPU | ~same CPU (`jdk-vt`: −9 to −18%) |
| `jetty-vt` | +16% throughput | −15% CPU |
| `apache5`, `okhttp` | no gain (0.92x, 1.03x) | **+15 to +36% CPU** |
| all | −89 to −134 MB RSS (~100–130 KB per avoided platform thread) | |

- The gain goes to clients that hand work between threads internally. Clients that block on the
  calling thread pay for the extra poller handoff (hypothesis, not profiled).
- **JDK 21:** a virtual thread inside `synchronized` pins its carrier: deadlock with `jdk-vt`,
  pinning in OkHttp while connecting. Use JDK 24+.

### Async APIs (scenarios 6–7, JDK 25)

1,024 concurrent requests, ops/s (platform threads alive):

| | Blocking, platform threads | Blocking, virtual threads | Async API | Async API + virtual threads |
|---|---|---|---|---|
| Apache 5 | 24.3k (1,030) | 22.4k (11) | **25.1k (10)** | — |
| Jetty | 15.0k (1,231) | 20.8k (211) | **21.7k (208)** | **21.7k (13)** |
| JDK | 9.0k (1,183) | **20.2k (11)**\* | 9.8k (166) | 17.3k (12) |
| OkHttp | 15.4k (1,061) | **15.9k (66)** | 13.3k (887) | 14.5k (100) |

\* `jdk-vt`: the client's internal executor also on virtual threads. Blocking and async columns
come from runs on different days: treat differences below ~5% across columns as ties.

- **Apache's async client:** the throughput and CPU of the classic client with 10 threads.
- **Jetty:** async API +45% over blocking calls; `VirtualThreadPool` cuts CPU by a third at 64–256.
- **JDK:** the async API alone changes nothing; the virtual-thread executor is what helps.
- **OkHttp:** `enqueue` holds one platform thread per call in flight. Every model stops at
  ~13–16k (see the pool-scan investigation).

### Abstraction layers (scenarios 8–9, bytes only, no JSON)

| Layer on engine | + CPU per request, 1 KB | ÷ raw throughput | Note |
|---|---|---|---|
| RestClient on Apache 5 | **+10 µs** | 0.91x | cheapest blocking layer |
| WebClient on Reactor Netty | +19 µs | 0.87x (0.87x async) | +9 KB at 100 KB: passes the body through |
| Feign on Apache 5 / OkHttp | +18 / +19 µs | 0.81x / 0.86x | copies the body: +168–232 KB per 100 KB |
| RestClient on Jetty / Reactor Netty | +29 / +36 µs | 0.78x / 0.80x | |
| WebClient async on Jetty / JDK | +57 / +214 µs | 0.63x / 0.50x | unstable (±25%) |
| RestClient on JDK | **+396 µs** | 0.46x | Spring Boot's fallback when no other client is on the classpath |

- The engine matters more than the layer: the same RestClient costs +10 µs on Apache and +396 µs
  on the JDK client (cause not profiled).
- Spring Boot 4.0.9 picks the engine in this order: Apache 5, Jetty, Reactor Netty, JDK,
  `HttpURLConnection`. Adding `httpclient5` makes it pick the cheapest.

---

## When to pick each client

| Situation | Pick | Evidence |
|---|---|---|
| Many small calls, CPU is the limit (JSON APIs, microservices, small containers) | **Apache 5** | 39 µs CPU per GET 0 B vs. 97–202 µs; the order holds with 500 µs RTT |
| Small heap, GC-sensitive, large responses | **Jetty** | 4.6 KB per request vs. 10–18 KB; ~1x the body on downloads |
| Large uploads | **Apache 5** or **Jetty** | 11 KB and 6 KB allocated per 100 KB POST, vs. 60–122 KB |
| Tens of connections, Android/Kotlin ecosystem | **OkHttp** | matches Apache at 16 threads on loopback |
| Hundreds+ concurrent HTTP/1.1 requests to one host | **Apache 5** or **Jetty**, not OkHttp | OkHttp: 0.63–0.71x Apache at 1,024 |
| Thousands in flight with few threads | **Apache 5 async** | 25.1k ops/s with 10 platform threads |
| Spring `RestClient` | on **Apache 5** | +10 µs vs. +396 µs on the JDK client |
| Spring `WebClient` | on **Reactor Netty** | +19 µs; 0.87x raw when async |
| No dependencies, low volume (CLIs, scripts) | **JDK** | within 10–20% of the others with network latency at 1–4 threads |
| Latency-critical path | not **JDK** | p99.99 3.9 ms vs. 0.5–0.7 ms |

Features may weigh more than these numbers: the JDK client has zero dependencies and HTTP/2;
OkHttp is standard on Android; Jetty has HTTP/2 and HTTP/3; Apache has classic and async APIs.

## Open questions

| Question | Status |
|---|---|
| Why is the JDK client slower, unstable at 4+ threads, with a long tail? | not profiled |
| Why do Spring's JDK-client integrations cost +396 to +694 µs? | not profiled |
| Why do virtual threads add CPU to blocking clients? | poller-handoff hypothesis, not profiled |
| GC effect with a small heap and live data | not measured; the 1 GB heap understates it |
| Retained memory (Jetty's buffer pools) | not measured |
| HTTP/2, TLS, JSON conversion, POST through async APIs | not measured |
