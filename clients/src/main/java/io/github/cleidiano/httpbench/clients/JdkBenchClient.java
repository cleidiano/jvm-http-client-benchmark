package io.github.cleidiano.httpbench.clients;

import io.github.cleidiano.httpbench.api.AsyncBenchClient;
import io.github.cleidiano.httpbench.api.BenchClient;
import io.github.cleidiano.httpbench.api.BenchClientProvider;
import io.github.cleidiano.httpbench.api.ClientConfig;
import io.github.cleidiano.httpbench.api.HttpStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * {@code java.net.http.HttpClient}.
 *
 * <p>Caveats: the JDK client has no public cap on pool size (it opens as many
 * connections as there are concurrent requests), and its only read timeout is the
 * per-request timeout, which schedules a timer per call.
 */
public final class JdkBenchClient implements AsyncBenchClient {

    private final HttpClient client;
    private final String baseUri;
    private final Duration timeout;
    private final ExecutorService ownedExecutor;

    /**
     * @param executor executor for the client's internal async work (response handling,
     *                 body subscribers); {@code null} keeps the JDK default (a cached pool of
     *                 platform threads). The selector thread is always a platform thread.
     */
    JdkBenchClient(ClientConfig config, ExecutorService executor) {
        this.baseUri = config.baseUri();
        this.timeout = config.timeout();
        this.ownedExecutor = executor;
        this.client = NativeClients.jdk(config, executor);
    }

    @Override
    public byte[] get(String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(baseUri + path))
                .timeout(timeout)
                .GET()
                .build();
        var response = client.send(request, BodyHandlers.ofByteArray());
        return HttpStatusException.check(response.statusCode(), path, response.body());
    }

    @Override
    public byte[] postJson(String path, byte[] body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(baseUri + path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        var response = client.send(request, BodyHandlers.ofByteArray());
        return HttpStatusException.check(response.statusCode(), path, response.body());
    }

    /** Native async API: {@code sendAsync}, completed by the client's executor. */
    @Override
    public CompletableFuture<byte[]> getAsync(String path) {
        var request = HttpRequest.newBuilder(URI.create(baseUri + path))
                .timeout(timeout)
                .GET()
                .build();
        return client.sendAsync(request, BodyHandlers.ofByteArray())
                .thenApply(response -> HttpStatusException.check(response.statusCode(), path, response.body()));
    }

    @Override
    public void close() {
        client.close();
        if (ownedExecutor != null) {
            ownedExecutor.close();
        }
    }

    /** Same client, measured through {@code sendAsync} (GetAsyncBenchmark). */
    public static final class AsyncProvider implements BenchClientProvider {
        @Override
        public String name() {
            return "jdk-async";
        }

        @Override
        public BenchClient create(ClientConfig config) throws Exception {
            return new JdkBenchClient(config, null);
        }
    }

    /** {@code sendAsync} with the client's internal executor on virtual threads. */
    public static final class VirtualThreadsAsyncProvider implements BenchClientProvider {
        @Override
        public String name() {
            return "jdk-vt-async";
        }

        @Override
        public BenchClient create(ClientConfig config) throws Exception {
            return new JdkBenchClient(config, Executors.newVirtualThreadPerTaskExecutor());
        }
    }

    public static final class Provider implements BenchClientProvider {
        @Override
        public String name() {
            return "jdk";
        }

        @Override
        public BenchClient create(ClientConfig config) {
            return new JdkBenchClient(config, null);
        }
    }

    /** Same client, internal async work on virtual threads instead of the default platform pool. */
    public static final class VirtualThreadsProvider implements BenchClientProvider {
        @Override
        public String name() {
            return "jdk-vt";
        }

        @Override
        public BenchClient create(ClientConfig config) {
            return new JdkBenchClient(config, Executors.newVirtualThreadPerTaskExecutor());
        }
    }
}
