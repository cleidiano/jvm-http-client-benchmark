# Benchmark results

|  |  |
|---|---|
| Machine | Intel(R) Core(TM) i7-5500U CPU @ 2.40GHz |
| Measured | 2026-11-01 to 2026-11-02 |
| CPU frequency | governor `performance`, no_turbo=1 |
| Kernel | 7.0.0-30-generic |
| Commit | fadd3f3 |
| JDK | openjdk version "21.0.12.1" 2026-08-18 LTS |
| JDK 24+ (scenarios 5-7, 9) | openjdk version "25.0.5.0.3" 2026-10-24 LTS |

**Library versions**

| library | version |
|---|---|
| `jmh` | 1.37 |
| `jmh-plugin` | 0.7.4 |
| `httpclient5` | 5.6.5 |
| `httpcore5` | 5.4.4 |
| `okhttp` | 5.5.1 |
| `jetty` | 12.1.14 |
| `jetty-reactive` | 4.1.5 |
| `reactor-netty` | 1.3.8 |
| `spring` | 7.0.10 |
| `feign` | 13.17 |

**Reading the tables:** bold is the best value in its column. `±N%` after a throughput
is a JMH error above 5% of the score: treat that number as unstable. CPU per request is the
CPU of the whole client process divided by the requests completed. Differences smaller than
the error are not differences.

**Contents**

- Raw clients, blocking API: 1. Cost per request, 2. Concurrency, 3. Latency distribution, 4. Simulated network
- Virtual threads: 5. Platform vs. virtual threads
- Async APIs: 6. Async APIs, default threads, 7. Async APIs, virtual threads
- Abstraction layers: 8. Layers, cost per request, 9. WebClient used asynchronously

---

## Raw clients, blocking API

_What does each client cost, alone and under concurrency?_

### 1. Cost per request (loopback, 1 thread)

`cost-t1` · JDK 21.0.12.1 · 3 forks × 5 × 3 s · pool of 64

**Time per request** (µs, lower is better)

| client | GET, 0 B | GET, 1 KB | GET, 100 KB | POST, 1 KB | POST, 100 KB |
|---|---|---|---|---|---|
| `apache5` | **62** | **83** | **183** | **153** | **289** |
| `okhttp` | 84 | 104 | 250 | 180 | 440 |
| `jetty` | 99 ±10% | 103 | 183 | 215 | 335 |
| `jdk` | 182 | 227 | 373 | 308 | 463 |

**CPU per request** (µs, lower is better)

| client | GET, 0 B | GET, 1 KB | GET, 100 KB | POST, 1 KB | POST, 100 KB |
|---|---|---|---|---|---|
| `apache5` | **39** | **54** | **147** | **43** | **113** |
| `okhttp` | 97 | 124 | 291 | 107 | 478 |
| `jetty` | 113 | 116 | 199 | 138 | 194 |
| `jdk` | 202 | 262 | 421 | 255 | 384 |

**Allocation per request** (KB, lower is better)

| client | GET, 0 B | GET, 1 KB | GET, 100 KB | POST, 1 KB | POST, 100 KB |
|---|---|---|---|---|---|
| `apache5` | 11.9 | 14.0 | 216.8 | 11.2 | 11.2 |
| `okhttp` | 10.5 | 11.6 | 154.4 | 9.9 | 59.9 |
| `jetty` | **4.6** | **6.3** | **109.7** | **5.8** | **5.9** |
| `jdk` | 17.8 | 21.1 | 226.5 | 19.4 | 121.9 |

**GC collections, all forks** (count, lower is better)

| client | GET, 0 B | GET, 1 KB | GET, 100 KB | POST, 1 KB | POST, 100 KB |
|---|---|---|---|---|---|
| `apache5` | 15 | 12 | 85 | 6 | 3 |
| `okhttp` | 9 | 7 | **43** | **3** | 9 |
| `jetty` | **3** | **5** | 43 | 3 | **0** |
| `jdk` | 6 | 6 | 45 | 6 | 18 |

### 2. Concurrency (loopback, GET)

`get-t4`, `loopback-1k-t16` · JDK 21.0.12.1 · 3 forks × 5 × 3 s · pool of 64

**Throughput** (ops/s, higher is better)

| client | 0 B, 4 thr | 1 KB, 4 thr | 1 KB, 16 thr | 100 KB, 4 thr |
|---|---|---|---|---|
| `apache5` | **36,216** | **26,215** | 23,180 ±12% | **9,724** |
| `okhttp` | 23,018 | 19,983 | **24,220** | 8,803 |
| `jetty` | 18,301 | 15,867 | 19,683 | 8,907 |
| `jdk` | 9,598 ±17% | 9,140 ±15% | 9,862 ±9% | 4,981 |

**CPU per request** (µs, lower is better)

| client | 0 B, 4 thr | 1 KB, 4 thr | 1 KB, 16 thr | 100 KB, 4 thr |
|---|---|---|---|---|
| `apache5` | **54** | **63** | 89 | **203** |
| `okhttp` | 85 | 96 | **82** | 221 |
| `jetty` | 106 | 122 | 101 | 217 |
| `jdk` | 207 | 214 | 202 | 366 |

### 3. Latency distribution (loopback, GET 1 KB, 1 thread)

`latency-1k` · JDK 21.0.12.1 · 3 forks × 5 × 3 s · pool of 64

**Latency** (µs, lower is better)

| client | p50 | p90 | p99 | p99.9 | p99.99 | max | p99.9 ÷ p50 |
|---|---|---|---|---|---|---|---|
| `apache5` | **70** | **78** | **113** | **140** | **540** | 9,601 | 2.0x |
| `okhttp` | 101 | 108 | 136 | 213 | 732 | 10,093 | 2.1x |
| `jetty` | 98 | 109 | 134 | 222 | 707 | **6,447** | 2.3x |
| `jdk` | 227 | 262 | 327 | 679 | 3,920 | 7,135 | 3.0x |

### 4. Simulated network (bridge + 500 µs RTT, GET 1 KB)

`network-500us-t1`, `network-500us-t4`, `network-500us-t16` · JDK 21.0.12.1 · 3 forks × 5 × 3 s · pool of 64

**Throughput** (ops/s, higher is better)

| client | 1 thr | 4 thr | 16 thr |
|---|---|---|---|
| `apache5` | **1,457** | **5,907** | **23,358** |
| `okhttp` | 1,372 | 5,793 | 20,177 |
| `jetty` | 1,343 | 5,813 | 16,318 |
| `jdk` | 1,201 | 5,255 | 10,139 ±12% |

**CPU per request** (µs, lower is better)

| client | 1 thr | 4 thr | 16 thr |
|---|---|---|---|
| `apache5` | **99** | **87** | **67** |
| `okhttp` | 197 | 125 | 96 |
| `jetty` | 215 | 145 | 120 |
| `jdk` | 311 | 228 | 197 |

---

## Virtual threads

_Do virtual-thread callers change throughput, CPU or memory?_

### 5. Platform vs. virtual threads (20 ms RTT, GET 1 KB, pool of 1,024)

`vt-PLATFORM-t64`, `vt-PLATFORM-t256`, `vt-PLATFORM-t1024`, `vt-VIRTUAL-t64`, `vt-VIRTUAL-t256`, `vt-VIRTUAL-t1024` · JDK 25.0.5.0.3 · 2 forks × 5 × 3 s · pool of 1,024

**Throughput** (ops/s, higher is better)

| client | 64 thr, PT | 64 thr, VT | 256 thr, PT | 256 thr, VT | 1,024 thr, PT | 1,024 thr, VT |
|---|---|---|---|---|---|---|
| `apache5` | **3,143** | **3,133** | 12,562 | 12,495 | **24,344 ±5%** | **22,374 ±7%** |
| `okhttp` | 3,137 | 3,131 | **12,584** | 12,381 | 15,397 ±7% | 15,853 ±6% |
| `jetty` | 3,130 | 3,127 | 12,548 | **12,588** | 14,953 | 20,808 |
| `jetty-vt` | 3,127 | 3,126 | 12,519 | 12,526 | 17,686 | 20,528 |
| `jdk` | 3,099 | 3,101 | 10,146 | 10,811 | 9,036 | 11,739 |
| `jdk-vt` | 3,094 | 3,105 | 11,641 | 11,729 | 14,692 | 20,224 |

**CPU per request** (µs, lower is better)

| client | 64 thr, PT | 64 thr, VT | 256 thr, PT | 256 thr, VT | 1,024 thr, PT | 1,024 thr, VT |
|---|---|---|---|---|---|---|
| `apache5` | **83** | 95 | **77** | 99 | **85** | **91** |
| `okhttp` | 109 | 148 | 126 | 157 | 129 | 131 |
| `jetty` | 140 | 137 | 145 | 141 | 142 | 99 |
| `jetty-vt` | 106 | **89** | 115 | **97** | 116 | 99 |
| `jdk` | 216 | 222 | 197 | 182 | 225 | 171 |
| `jdk-vt` | 176 | 144 | 149 | 135 | 140 | 101 |

**Platform threads, peak** (count, lower is better)

| client | 64 thr, PT | 64 thr, VT | 256 thr, PT | 256 thr, VT | 1,024 thr, PT | 1,024 thr, VT |
|---|---|---|---|---|---|---|
| `apache5` | **70** | **11** | **262** | **11** | **1,030** | **11** |
| `okhttp` | 91 | 40 | 286 | 53 | 1,061 | 66 |
| `jetty` | 118 | 58 | 463 | 143 | 1,231 | 211 |
| `jetty-vt` | 76 | 13 | 268 | 13 | 1,036 | 13 |
| `jdk` | 140 | 67 | 372 | 109 | 1,183 | 314 |
| `jdk-vt` | 74 | 11 | 266 | 11 | 1,034 | 11 |

**Resident memory** (MB, lower is better)

| client | 64 thr, PT | 64 thr, VT | 256 thr, PT | 256 thr, VT | 1,024 thr, PT | 1,024 thr, VT |
|---|---|---|---|---|---|---|
| `apache5` | **1,133** | 1,138 | **1,165** | **1,135** | 1,265 | **1,137** |
| `okhttp` | 1,137 | **1,135** | 1,166 | 1,141 | 1,272 | 1,138 |
| `jetty` | 1,149 | 1,154 | 1,189 | 1,159 | 1,267 | 1,169 |
| `jetty-vt` | 1,147 | 1,138 | 1,173 | 1,139 | **1,255** | 1,146 |
| `jdk` | 1,163 | 1,159 | 1,186 | 1,156 | 1,268 | 1,179 |
| `jdk-vt` | 1,150 | 1,149 | 1,176 | 1,153 | 1,267 | 1,152 |

---

## Async APIs

_Can one thread drive many requests in flight through each native async API?_

### 6. Async APIs, default threads (20 ms RTT, GET 1 KB, 1 calling thread)

`async` · JDK 25.0.5.0.3 · 2 forks × 5 × 3 s · pool of 1,024

**Throughput** (ops/s, higher is better)

| client | 64 in flight | 256 in flight | 1,024 in flight |
|---|---|---|---|
| `apache5-async` | 3,122 | 12,529 | **25,071 ±6%** |
| `okhttp-async` | 3,115 | 12,375 | 13,284 |
| `jetty-async` | **3,125** | **12,621** | 21,742 |
| `jdk-async` | 3,085 | 9,299 ±6% | 9,774 ±16% |

**CPU per request** (µs, lower is better)

| client | 64 in flight | 256 in flight | 1,024 in flight |
|---|---|---|---|
| `apache5-async` | **87** | **84** | **80** |
| `okhttp-async` | 150 | 157 | 152 |
| `jetty-async` | 123 | 126 | 92 |
| `jdk-async` | 231 | 212 | 207 |

**Platform threads, peak** (count, lower is better)

| client | 64 in flight | 256 in flight | 1,024 in flight |
|---|---|---|---|
| `apache5-async` | **10** | **10** | **10** |
| `okhttp-async` | 94 | 284 | 887 |
| `jetty-async` | 51 | 104 | 208 |
| `jdk-async` | 74 | 120 | 166 |

**Resident memory** (MB, lower is better)

| client | 64 in flight | 256 in flight | 1,024 in flight |
|---|---|---|---|
| `apache5-async` | 1,152 | **1,149** | 1,187 |
| `okhttp-async` | **1,141** | 1,170 | 1,226 |
| `jetty-async` | 1,146 | 1,150 | **1,158** |
| `jdk-async` | 1,152 | 1,158 | 1,164 |

### 7. Async APIs, virtual threads (20 ms RTT, GET 1 KB, 1 calling thread)

`async-vt` · JDK 25.0.5.0.3 · 2 forks × 5 × 3 s · pool of 1,024

**Throughput** (ops/s, higher is better)

| client | 64 in flight | 256 in flight | 1,024 in flight |
|---|---|---|---|
| `apache5-async` | 3,120 | **12,545** | **24,664** |
| `okhttp-vt-async` | 3,099 | 11,724 | 14,524 ±10% |
| `jetty-vt-async` | **3,128** | 12,524 | 21,731 ±5% |
| `jdk-vt-async` | 3,072 | 10,084 ±8% | 17,331 |

**CPU per request** (µs, lower is better)

| client | 64 in flight | 256 in flight | 1,024 in flight |
|---|---|---|---|
| `apache5-async` | 92 | 95 | **81** |
| `okhttp-vt-async` | 162 | 163 | 139 |
| `jetty-vt-async` | **82** | **86** | 93 |
| `jdk-vt-async` | 167 | 135 | 116 |

**Platform threads, peak** (count, lower is better)

| client | 64 in flight | 256 in flight | 1,024 in flight |
|---|---|---|---|
| `apache5-async` | 13 | 13 | 13 |
| `okhttp-vt-async` | 39 | 51 | 100 |
| `jetty-vt-async` | 13 | 13 | 13 |
| `jdk-vt-async` | **12** | **12** | **12** |

**Resident memory** (MB, lower is better)

| client | 64 in flight | 256 in flight | 1,024 in flight |
|---|---|---|---|
| `apache5-async` | 1,150 | 1,152 | 1,153 |
| `okhttp-vt-async` | 1,137 | **1,134** | 1,147 |
| `jetty-vt-async` | **1,135** | 1,147 | **1,143** |
| `jdk-vt-async` | 1,146 | 1,150 | 1,154 |

---

## Abstraction layers

_What do Spring and Feign add on top of the client they wrap?_

### 8. Layers, cost per request (loopback, 1 thread)

`layers-t1` · JDK 21.0.12.1 · 2 forks × 5 × 3 s · pool of 64

**Throughput** (ops/s, higher is better)

| client | 1 KB | 100 KB |
|---|---|---|
| `apache5` | **14,370** | 5,555 |
| `okhttp` | 10,054 | 4,124 |
| `jetty` | 10,356 | 5,732 |
| `jdk` | 4,309 | 2,687 |
| `reactor` | 9,265 | 5,186 |
| `apache5-async` | 9,523 | 3,831 |
| `spring-restclient-apache5` | 13,071 ±10% | **6,267** |
| `feign-apache5` | 11,624 | 3,352 |
| `spring-webclient-apache5` | 7,810 | 3,118 |
| `feign-okhttp` | 8,653 | 3,071 |
| `spring-restclient-jetty` | 8,059 | 4,725 |
| `spring-webclient-jetty` | 7,406 | 3,373 |
| `spring-restclient-jdk` | 1,990 | 1,368 |
| `spring-webclient-jdk` | 1,564 ±27% | 1,153 ±24% |
| `feign-jdk` | 3,331 | 1,871 |
| `spring-restclient-reactor` | 7,388 | 3,110 |
| `spring-webclient-reactor` | 8,042 | 4,660 |

**CPU per request** (µs, lower is better)

| client | 1 KB | 100 KB |
|---|---|---|
| `apache5` | **46** | 148 |
| `okhttp` | 123 | 289 |
| `jetty` | 114 | 192 |
| `jdk` | 267 | 423 |
| `reactor` | 95 | 158 |
| `apache5-async` | 99 | 211 |
| `spring-restclient-apache5` | 56 | **126** |
| `feign-apache5` | 65 | 232 |
| `spring-webclient-apache5` | 124 | 267 |
| `feign-okhttp` | 141 | 378 |
| `spring-restclient-jetty` | 143 | 226 |
| `spring-webclient-jetty` | 160 | 295 |
| `spring-restclient-jdk` | 663 | 962 |
| `spring-webclient-jdk` | 961 | 1,281 |
| `feign-jdk` | 366 | 635 |
| `spring-restclient-reactor` | 131 | 313 |
| `spring-webclient-reactor` | 114 | 183 |

**Allocation per request** (KB, lower is better)

| client | 1 KB | 100 KB |
|---|---|---|
| `apache5` | 14.0 | 216.8 |
| `okhttp` | 11.6 | 154.4 |
| `jetty` | **6.2** | **109.5** |
| `jdk` | 21.0 | 226.7 |
| `reactor` | 10.5 | 113.2 |
| `apache5-async` | 11.0 | 342.0 |
| `spring-restclient-apache5` | 15.8 | 219.7 |
| `feign-apache5` | 24.6 | 385.1 |
| `spring-webclient-apache5` | 25.2 | 432.7 |
| `feign-okhttp` | 25.4 | 386.2 |
| `spring-restclient-jetty` | 11.6 | 217.3 |
| `spring-webclient-jetty` | 20.7 | 332.7 |
| `spring-restclient-jdk` | 46.5 | 355.1 |
| `spring-webclient-jdk` | 36.0 | 452.2 |
| `feign-jdk` | 36.8 | 506.3 |
| `spring-restclient-reactor` | 33.0 | 339.4 |
| `spring-webclient-reactor` | 18.9 | 122.5 |

**Cost of the layer** (layer minus the raw client it wraps, same run)

| layer | on top of | 1 KB: ÷ raw ops/s | 1 KB: + CPU µs | 1 KB: + KB | 100 KB: ÷ raw ops/s | 100 KB: + CPU µs | 100 KB: + KB |
|---|---|---|---|---|---|---|---|
| `spring-restclient-apache5` | `apache5` | 0.91x | +10 | +1.8 | 1.13x | -22 | +2.9 |
| `feign-apache5` | `apache5` | 0.81x | +18 | +10.5 | 0.60x | +84 | +168.3 |
| `spring-webclient-apache5` | `apache5-async` | 0.82x | +24 | +14.2 | 0.81x | +56 | +90.8 |
| `feign-okhttp` | `okhttp` | 0.86x | +19 | +13.9 | 0.74x | +89 | +231.8 |
| `spring-restclient-jetty` | `jetty` | 0.78x | +29 | +5.4 | 0.82x | +35 | +107.7 |
| `spring-webclient-jetty` | `jetty` | 0.72x | +46 | +14.6 | 0.59x | +103 | +223.2 |
| `spring-restclient-jdk` | `jdk` | 0.46x | +396 | +25.5 | 0.51x | +539 | +128.3 |
| `spring-webclient-jdk` | `jdk` | 0.36x | +694 | +15.0 | 0.43x | +857 | +225.5 |
| `feign-jdk` | `jdk` | 0.77x | +99 | +15.8 | 0.70x | +212 | +279.5 |
| `spring-restclient-reactor` | `reactor` | 0.80x | +36 | +22.5 | 0.60x | +155 | +226.3 |
| `spring-webclient-reactor` | `reactor` | 0.87x | +19 | +8.4 | 0.90x | +26 | +9.3 |

### 9. WebClient used asynchronously (20 ms RTT, 1,024 in flight)

`layers-async` · JDK 25.0.5.0.3 · 2 forks × 5 × 3 s · pool of 1,024

| client | Throughput (ops/s) | CPU per request (µs) | Platform threads, peak (count) |
|---|---|---|---|
| `apache5-async` | **25,036** | 80 | **10** |
| `spring-webclient-apache5` | 20,544 ±5% | 97 | 10 |
| `jetty-async` | 22,313 | 90 | 208 |
| `spring-webclient-jetty` | 14,096 ±25% | 146 | 208 |
| `jdk-async` | 9,935 ±8% | 202 | 189 |
| `spring-webclient-jdk` | 4,985 ±26% | 417 | 112 |
| `reactor-async` | 24,845 ±7% | **78** | 13 |
| `spring-webclient-reactor` | 21,611 | 93 | 13 |

**Cost of the layer** (layer minus the raw client it wraps, same run)

| layer | on top of | ÷ raw ops/s | + CPU µs | + KB |
|---|---|---|---|---|
| `spring-webclient-apache5` | `apache5-async` | 0.82x | +17 | +14.3 |
| `spring-webclient-jetty` | `jetty-async` | 0.63x | +57 | +14.0 |
| `spring-webclient-jdk` | `jdk-async` | 0.50x | +214 | +19.1 |
| `spring-webclient-reactor` | `reactor-async` | 0.87x | +15 | +8.3 |
