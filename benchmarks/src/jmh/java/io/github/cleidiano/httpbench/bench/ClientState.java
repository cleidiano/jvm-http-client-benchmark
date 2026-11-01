package io.github.cleidiano.httpbench.bench;

import io.github.cleidiano.httpbench.api.BenchClient;
import io.github.cleidiano.httpbench.api.BenchClients;
import io.github.cleidiano.httpbench.api.ClientConfig;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.time.Duration;

/**
 * One client instance shared by all benchmark threads, as in a real application.
 *
 * <p>Configured through system properties (pass with {@code -jvmArgsPrepend}):
 * {@code bench.baseUri} (default {@code http://127.0.0.1:8080}) and
 * {@code bench.maxConnections} (default 64; keep it &gt;= the {@code -t} thread count).
 */
@State(Scope.Benchmark)
public class ClientState {

    @Param({"jdk", "apache5", "okhttp", "jetty"})
    public String client;

    public BenchClient http;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        verifyExecutor();
        refuseKnownDeadlock(client);
        var config = new ClientConfig(
                System.getProperty("bench.baseUri", "http://127.0.0.1:8080"),
                Integer.getInteger("bench.maxConnections", 64),
                Duration.ofSeconds(10));
        http = BenchClients.create(client, config);
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        http.close();
    }

    /**
     * JMH initializes {@code Scope.Benchmark} state inside {@code synchronized}. Before JDK 24
     * (JEP 491) a virtual thread blocked in or on a monitor pins its carrier, so with
     * EXECUTOR=VIRTUAL every carrier ends up pinned by benchmark threads while a client whose
     * own work runs on virtual threads ({@code *-vt}) waits for a carrier that never frees up:
     * a deadlock, observed with jdk-vt on JDK 21. Fail fast instead of hanging.
     */
    private static void refuseKnownDeadlock(String client) {
        boolean virtualCallers = System.getProperty("jmh.executor", "PLATFORM").equals("VIRTUAL");
        if (virtualCallers && client.endsWith("-vt") && Runtime.version().feature() < 24) {
            throw new IllegalStateException(client + " with EXECUTOR=VIRTUAL deadlocks on JDK "
                    + Runtime.version().feature() + " (synchronized pins virtual threads before JDK 24, "
                    + "and JMH initializes state inside synchronized). Run it on JDK 24+ (JAVA=...).");
        }
    }

    /**
     * Setup runs on a benchmark worker thread, so this proves the run really uses the thread
     * kind it claims ({@code -Djmh.executor}, passed by bench.sh as EXECUTOR).
     */
    private static void verifyExecutor() {
        String executor = System.getProperty("jmh.executor", "PLATFORM");
        boolean expectVirtual = executor.equals("VIRTUAL");
        boolean isVirtual = Thread.currentThread().isVirtual();
        if (expectVirtual != isVirtual) {
            throw new IllegalStateException("jmh.executor=" + executor + " but benchmark thread "
                    + Thread.currentThread() + " is " + (isVirtual ? "virtual" : "a platform thread"));
        }
    }
}
