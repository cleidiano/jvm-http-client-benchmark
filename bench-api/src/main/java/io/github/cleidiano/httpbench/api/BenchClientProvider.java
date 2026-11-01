package io.github.cleidiano.httpbench.api;

/**
 * Service-provider hook: each module registers its adapters in
 * {@code META-INF/services/io.github.cleidiano.httpbench.api.BenchClientProvider}, so new client
 * families (e.g. Spring RestClient, OpenFeign) plug in without touching the benchmarks.
 */
public interface BenchClientProvider {

    /** Value used in the JMH {@code client} parameter, e.g. {@code "okhttp"}. */
    String name();

    BenchClient create(ClientConfig config) throws Exception;
}
