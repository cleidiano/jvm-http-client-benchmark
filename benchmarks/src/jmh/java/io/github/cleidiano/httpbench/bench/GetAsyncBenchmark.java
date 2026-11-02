package io.github.cleidiano.httpbench.bench;

import io.github.cleidiano.httpbench.api.AsyncBenchClient;
import io.github.cleidiano.httpbench.api.BenchClients;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * GET through each client's native async API, keeping {@code inFlight} requests outstanding.
 *
 * <p>Meant to run with a single benchmark thread ({@code -t 1}): that one thread submits requests
 * while a window (a semaphore with {@code inFlight} permits) caps how many are pending; each
 * completion frees a permit. In steady state the measured rate of submissions equals the rate of
 * completions, so the score is requests per second with {@code inFlight} concurrent requests and
 * one calling thread. Compare with GetBenchmark at {@code -t inFlight} (one thread per request).
 *
 * <p>A request that fails or returns a wrong body is recorded by its callback and rethrown on the
 * benchmark thread, so failures never pass silently.
 */
@State(Scope.Benchmark)
public class GetAsyncBenchmark extends BenchmarkDefaults {

    /** Scenario 6 uses these; scenario 7 selects the {@code *-vt-async} variants with {@code -p client=}. */
    @Param({"jdk-async", "apache5-async", "okhttp-async", "jetty-async"})
    public String client;

    @Param({"1k"})
    public String payload;

    @Param({"64", "256", "1024"})
    public int inFlight;

    private AsyncBenchClient http;
    private String path;
    private int expectedLength;
    private Semaphore window;
    private final AtomicReference<Throwable> failure = new AtomicReference<>();

    @Setup(Level.Trial)
    public void setup() throws Exception {
        ClientState.verifyExecutor();
        ClientState.refuseKnownDeadlock(client);
        var config = ClientState.configFromProperties();
        if (config.maxConnections() < inFlight) {
            throw new IllegalStateException("bench.maxConnections=" + config.maxConnections()
                    + " is below inFlight=" + inFlight + "; set MAX_CONNECTIONS >= " + inFlight);
        }
        var created = BenchClients.create(client, config);
        if (!(created instanceof AsyncBenchClient async)) {
            created.close();
            throw new IllegalArgumentException(client + " has no async adapter (use a *-async client)");
        }
        http = async;
        path = "/bytes/" + payload;
        expectedLength = Payloads.size(payload);
        Payloads.expectLength(http.get(path), expectedLength, client + " GET " + path);
        window = new Semaphore(inFlight);
    }

    @Benchmark
    public void get() throws Exception {
        Throwable error = failure.get();
        if (error != null) {
            throw new IllegalStateException(client + ": async request failed", error);
        }
        window.acquire();
        CompletableFuture<byte[]> response;
        try {
            response = http.getAsync(path);
        } catch (Throwable t) {
            window.release();
            throw t;
        }
        response.whenComplete((body, e) -> {
            if (e != null) {
                failure.compareAndSet(null, e);
            } else if (body.length != expectedLength) {
                failure.compareAndSet(null, new IllegalStateException(
                        "expected " + expectedLength + " bytes, got " + body.length));
            }
            window.release();
        });
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        // Let outstanding requests finish before closing the client underneath them.
        window.tryAcquire(inFlight, 30, TimeUnit.SECONDS);
        http.close();
    }
}
