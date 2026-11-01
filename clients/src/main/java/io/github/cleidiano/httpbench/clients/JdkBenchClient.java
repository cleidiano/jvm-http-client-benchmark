package io.github.cleidiano.httpbench.clients;

import io.github.cleidiano.httpbench.api.BenchClient;
import io.github.cleidiano.httpbench.api.BenchClientProvider;
import io.github.cleidiano.httpbench.api.ClientConfig;
import io.github.cleidiano.httpbench.api.HttpStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * {@code java.net.http.HttpClient}.
 *
 * <p>Caveats: the JDK client has no public cap on pool size (it opens as many
 * connections as there are concurrent requests), and its only read timeout is the
 * per-request timeout, which schedules a timer per call.
 */
public final class JdkBenchClient implements BenchClient {

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
        var builder = HttpClient.newBuilder()
                // Default is HTTP_2, which sends an h2c Upgrade header on cleartext requests.
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(config.timeout());
        if (executor != null) {
            builder.executor(executor);
        }
        this.client = builder.build();
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

    @Override
    public void close() {
        client.close();
        if (ownedExecutor != null) {
            ownedExecutor.close();
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
