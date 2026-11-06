# Results

| Machine | Current set | Versions | Full tables |
|---|---|---|---|
| i7-5500U (2 cores / 4 threads, pinned at 2.4 GHz) | `2026-11-01-baseline` | Apache 5.6.5 · OkHttp 5.5.1 · Jetty 12.1.14 · Reactor Netty 1.3.8 · Spring 7.0.10 · Feign 13.17 · JDK 21.0.12 / 25.0.5 | [REPORT.md](results/i7-5500U/2026-11-01-baseline/REPORT.md) |

HTTP/1.1, no TLS, responses read as bytes. Absolute numbers hold for this machine only; the
order between clients should carry over. Sets and regressions: [README](README.md#results-and-regressions).

---

## 1. Native clients: score by profile

<!-- results.py:native -->
**Score by profile** (0–10, higher is better)

| profile | `apache5` | `jetty` | `okhttp` | `jdk` | winner |
|---|---|---|---|---|---|
| General service | **9.5** | 8.6 | 8.1 | 5.8 | **`apache5`** |
| CPU-limited container | **9.7** | 8.2 | 8.2 | 5.4 | **`apache5`** |
| Small heap / GC-sensitive | 8.3 | **9.4** | 7.8 | 5.7 | **`jetty`** |
| High concurrency | **9.7** | 8.9 | 8.0 | 7.5 | **`apache5`** |

**Score by criterion**

| criterion | `apache5` | `jetty` | `okhttp` | `jdk` |
|---|---|---|---|---|
| **CPU efficiency** | **10.0** | 7.7 | 8.3 | 5.2 |
| **Large bodies** | **10.0** | 9.7 | 8.4 | 7.4 |
| **Memory** | 6.5 | **10.0** | 7.3 | 4.7 |
| **Tail latency** | **10.0** | 8.0 | 8.2 | 3.2 |
| **High concurrency** | **10.0** | 9.4 | 8.0 | 9.1 |

**Measured values** (bold: best, or tied with it within the noise)

| criterion | measurement | `apache5` | `jetty` | `okhttp` | `jdk` |
|---|---|---|---|---|---|
| CPU efficiency | CPU per request, GET 1 KB, 1 thread | **54 µs** | 116 µs | 124 µs | 262 µs |
|  | CPU per request, 500 µs RTT, 4 threads | **87 µs** | 145 µs | 125 µs | 228 µs |
|  | throughput, CPU saturated, loopback, 4 threads | **26,215 ops/s** | 15,867 ops/s | 19,983 ops/s | 9,140 ops/s |
|  | throughput, CPU saturated, 500 µs RTT, 16 threads | **23,358 ops/s** | 16,318 ops/s | 20,177 ops/s | 10,139 ops/s |
| Large bodies | time per GET 100 KB | **183 µs** | **183 µs** | 250 µs | 373 µs |
|  | time per POST 100 KB | **289 µs** | 335 µs | 440 µs | 463 µs |
| Memory | allocated per request, GET 1 KB | 14.0 KB | **6.3 KB** | 11.6 KB | 21.1 KB |
| Tail latency | p99.9, GET 1 KB | **140 µs** | 222 µs | 213 µs | 679 µs |
| High concurrency | throughput at 1,024 concurrent requests, best model | **25,071 ops/s** | 21,742 ops/s | 15,853 ops/s | 20,224 ops/s (`jdk-vt`) |

**Weights per profile**

| criterion | General service | CPU-limited container | Small heap / GC-sensitive | High concurrency |
|---|---|---|---|---|
| CPU efficiency | 40% | 70% | 15% | 30% |
| Large bodies | 10% | 15% | 20% | — |
| Memory | 15% | 10% | 50% | 10% |
| Tail latency | 15% | 5% | 5% | — |
| High concurrency | 20% | — | 10% | 60% |

Rule: each measurement scores 10 for the best client and for any client within 2% of it or with an overlapping confidence interval; the others score 10 × (1 − log(ratio) ÷ log 10), so 2x worse is 7.0, 5x is 3.0 and 10x is 0. A criterion averages its measurements; a profile is the weighted mean of its criteria. Scores are relative to the best client of this set: use `results.py compare` to detect regressions. Criteria and weights: `CRITERIA` and `PROFILES` in `scripts/results.py`.

<!-- /results.py:native -->

## 2. Spring and Feign: score by layer

<!-- results.py:layers -->
**Engine score by layer** (0–10, higher is better)

| layer | best engine | 2nd | 3rd | 4th |
|---|---|---|---|---|
| Spring `RestClient` | **Apache 5** · 10.0 | Jetty · 7.4 | Reactor Netty · 6.9 | JDK · 2.2 |
| Spring `WebClient` | **Reactor Netty** · 10.0 | Apache 5 async · 8.4 | Jetty · 7.8 | JDK · 2.2 |
| OpenFeign | **Apache 5** · 10.0 | OkHttp · 8.0 | JDK · 4.8 |  |

Weights: CPU per request at 1 KB 40%, at 100 KB 20%, allocation at 100 KB 20%, throughput 20% (`WebClient`: async, 1,024 in flight). Same scoring rule as the native clients. Bytes only, no JSON.

<!-- /results.py:layers -->

---

## 3. Decision guide: native clients

| I need... | Use | Avoid | Evidence |
|---|---|---|---|
| A default for a server-side service | **Apache 5** | — | wins 3 of 4 profiles |
| Many small calls on little CPU (JSON APIs, small containers) | **Apache 5** | JDK | CPU per GET 1 KB: 54 µs vs. 262 µs |
| Low allocation: small heap, GC pauses, large responses | **Jetty** | JDK, Apache 5 | 6.3 KB per request; ~1x the body on downloads (Apache and JDK ~2x) |
| Large uploads | **Apache 5** or **Jetty** | OkHttp, JDK | 11 KB and 6 KB allocated per 100 KB POST vs. 60–122 KB |
| Thousands of requests in flight with few threads | **Apache 5 async**, Jetty async | OkHttp | 25.1k and 21.7k ops/s at 1,024 with 10–13 threads vs. 15.9k |
| Hundreds+ concurrent HTTP/1.1 requests to one host | Apache 5, Jetty | **OkHttp** | OkHttp scans its whole pool per request: 0.63–0.71x Apache ([investigation](results/i7-5500U/investigations/2026-11-01-okhttp-pool-scan/README.md)) |
| A virtual-thread application | Apache 5 (still fastest); JDK with a VT executor; Jetty | expecting VT to speed up Apache or OkHttp | VT: JDK/Jetty +30–39% at 1,024; Apache/OkHttp +15–36% CPU |
| The lowest tail latency | **Apache 5** | **JDK** | p99.9 140 µs vs. 679 µs; p99.99 0.5 ms vs. 3.9 ms |
| Android or Kotlin, tens of connections | **OkHttp** | — | matches Apache at 16 threads on loopback (24.2k vs. 23.2k ops/s) |
| Zero dependencies, low volume (CLIs, scripts) | **JDK** | — | within 10–20% of the others once there is network latency |
| Virtual threads on JDK 21 | JDK 24+ | — | `synchronized` pins carriers: deadlock with `jdk-vt`, pinning in OkHttp |

Features may outweigh these numbers: JDK has zero dependencies and HTTP/2; OkHttp is the Android
standard; Jetty has HTTP/2 and HTTP/3; Apache has classic and async APIs.

## 4. Decision guide: Spring and Feign

Two separate choices. The **layer** follows the programming model; the **engine** under it
decides the cost (the same `RestClient` adds +10 µs on Apache 5 and +396 µs on the JDK client).

| Layer | Programming model | Best engine | CPU per request, 1 KB | Added by the layer |
|---|---|---|---|---|
| `RestClient` | blocking, fluent | Apache 5 | **56 µs** | +10 µs |
| OpenFeign | blocking, declarative interface | Apache 5 | 65 µs | +18 µs |
| `WebClient` | reactive (`Mono` / `Flux`) | Reactor Netty | 114 µs blocking · 93 µs async | +19 µs · +15 µs |

| I use... | Engine | Avoid | Evidence |
|---|---|---|---|
| `RestClient` in Spring Boot | **Apache 5**: add `httpclient5`, Boot picks it first | JDK client: Boot's fallback when no other client is on the classpath | +10 µs vs. +396 µs per request |
| `WebClient` in a reactive app | **Reactor Netty** (WebFlux default) | JDK, Jetty | async: 0.87x raw Reactor Netty; JDK/Jetty 0.50–0.63x and ±25% unstable |
| `WebClient` in a blocking app (`.block()`) | switch to `RestClient` on Apache 5 | — | 56 µs vs. 114 µs CPU per request |
| OpenFeign | **Apache 5** (`feign-hc5`) | JDK (`feign-java11`) | 65 µs vs. 366 µs CPU per request |
| Large responses through a layer | `RestClient` on Apache 5, `WebClient` on Reactor Netty | **Feign**, any engine | added allocation per 100 KB: +3 and +9 KB vs. +168–280 KB (Feign copies the body) |

Spring Boot 4.0.9 picks the `RestClient` engine in this order: Apache 5, Jetty, Reactor Netty,
JDK, `HttpURLConnection`.

---

## Appendix

| Investigation | Answer |
|---|---|
| [OkHttp pool scan](results/i7-5500U/investigations/2026-11-01-okhttp-pool-scan/README.md) | OkHttp scans its whole pool for a free connection on every request: 26% of CPU at 1,024 connections |

| Open question | Status |
|---|---|
| Why is the JDK client slower, unstable at 4+ threads, with a long tail? | not profiled |
| Why do Spring's JDK-client integrations cost +396 to +694 µs? | not profiled |
| Why do virtual threads add CPU to blocking clients? | poller-handoff hypothesis, not profiled |
| GC with a small heap and live data; retained memory of Jetty's pools | not measured |
| HTTP/2, TLS, JSON conversion, POST through async APIs | not measured |
| Reactor Netty as a standalone client | measured only as a Spring engine, not scored |
