package io.github.cleidiano.httpbench.bench;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/** GET a static body of {@code payload} bytes served by nginx at {@code /bytes/<payload>}. */
@State(Scope.Benchmark)
public class GetBenchmark extends BenchmarkDefaults {

    @Param({"0", "1k", "100k"})
    public String payload;

    private String path;

    @Setup(Level.Trial)
    public void setup(ClientState state) throws Exception {
        path = "/bytes/" + payload;
        // Fail fast on a misconfigured server/client, and open the first connection.
        Payloads.expectLength(state.http.get(path), Payloads.size(payload), state.client + " GET " + path);
    }

    @Benchmark
    public byte[] get(ClientState state) throws Exception {
        return state.http.get(path);
    }
}
