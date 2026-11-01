package io.github.cleidiano.httpbench.bench;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/** POST a JSON body of {@code payload} bytes; nginx reads all of it, then answers {@code {"ok":true}}. */
@State(Scope.Benchmark)
public class PostBenchmark extends BenchmarkDefaults {

    private static final String PATH = "/post";

    @Param({"1k", "100k"})
    public String payload;

    private byte[] body;

    @Setup(Level.Trial)
    public void setup(ClientState state) throws Exception {
        body = Payloads.json(Payloads.size(payload));
        Payloads.expectLength(state.http.postJson(PATH, body), Payloads.POST_RESPONSE_LENGTH,
                state.client + " POST " + PATH);
    }

    @Benchmark
    public byte[] post(ClientState state) throws Exception {
        return state.http.postJson(PATH, body);
    }
}
