package io.github.cleidiano.httpbench.api;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * A client benchmarked through its native asynchronous API.
 *
 * <p>Same fairness rules as {@link BenchClient}: the future completes only after the whole body was
 * read, and completes exceptionally ({@link HttpStatusException}) on any non-2xx status.
 */
public interface AsyncBenchClient extends BenchClient {

    /** GET {@code baseUri + path} without blocking the calling thread. */
    CompletableFuture<byte[]> getAsync(String path);

    /** Blocking GET on top of {@link #getAsync}; used to validate the setup before measuring. */
    @Override
    default byte[] get(String path) throws Exception {
        try {
            return getAsync(path).get();
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
        }
    }

    @Override
    default byte[] postJson(String path, byte[] body) throws Exception {
        throw new UnsupportedOperationException("async adapters only implement GET");
    }
}
