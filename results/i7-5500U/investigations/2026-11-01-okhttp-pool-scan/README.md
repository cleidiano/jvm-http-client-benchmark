# Investigation: OkHttp doesn't scale to many connections

| | |
|---|---|
| Question | Why is OkHttp 29–37% below Apache at 1,024 concurrent requests, with platform and virtual threads alike? |
| Answer | Finding a free connection scans the whole pool on every request. |
| Versions | OkHttp 5.5.1, Apache HttpClient 5.6.5, JDK 25.0.5 |
| Conditions | 20 ms RTT, GET 1 KB, 1,024 threads, pool of 1,024. 1 fork × 3 × 5 s, CPU frequency **not** pinned |
| Reproduce | `git checkout 3da8dcd` (the last commit with the `okhttp-sharded` client) |

## Evidence

**JFR, 1,024 virtual threads**

| | OkHttp | Apache 5 |
|---|---|---|
| Hottest method | `RealConnectionPool.callAcquirePooledConnection`: **24%** of samples | none above 3.6% |
| Monitor contention | `RealConnectionPool.closeConnections`: 43 events, avg 388 ms, max 2.56 s | none |
| Extra platform threads | 61 `OkHttp TaskRunner` + `Okio Watchdog` | none |
| Pinning (JDK 25) | none | none |

**Cost of the lookup grows with the pool**

| OkHttp, virtual threads | 256 connections | 1,024 connections |
|---|---|---|
| CPU in `callAcquirePooledConnection` | 8% | **26%** |
| Lookup samples per request | 1x | ~5.4x |

**Mechanism (bytecode, OkHttp 5.5.1):** for every request, the pool walks all connections (a
`ConcurrentLinkedQueue`), enters `synchronized(connection)` on each and calls `isEligible()` until
one is free. With HTTP/1.1 nearly every connection is busy, so the walk is long. The cleanup task
(`closeConnections`, on `TaskRunner` threads) walks the same list and contends on the same monitors.

## Causal test

`okhttp-sharded` spread the threads over 8 `OkHttpClient` instances (~128 connections per pool).

| ops/s | Platform threads | Virtual threads |
|---|---|---|
| `okhttp` | 16,361 | 18,758 |
| `okhttp-sharded` | **20,400 (+25%)** | **20,988 (+12%)** |
| `apache5` | 25,356 | 26,382 |
| `apache5` ÷ `okhttp-sharded` | 1.24x | 1.26x |

3 iterations per client. The min..max ranges of `okhttp` and `okhttp-sharded` don't overlap;
JMH's error interval is too wide with 1 fork to be used. With the pool split, the gap to Apache
falls to the per-request gap seen on loopback at 1 thread (1.25x).

## Conclusion

- OkHttp gains nothing from virtual threads: it has no thread handoff to remove (same as Apache),
  and the pool scan costs the same CPU with either thread kind.
- Fine with tens of connections. With hundreds of concurrent HTTP/1.1 requests to one host, the
  pool lookup becomes the bottleneck.
- Rejected: Okio's global `AsyncTimeout` lock. It shows up neither among the hottest methods nor
  in the contention events.
- Not tested: HTTP/2, where few multiplexed connections should make the scan short.

## Files

| File | What |
|---|---|
| `*-investigate-shard-PLATFORM.json` | platform-thread callers |
| `*-investigate-shard-VIRTUAL.json` | virtual-thread callers |

JFR recordings and thread dumps are not versioned.
