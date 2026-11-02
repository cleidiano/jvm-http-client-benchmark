package io.github.cleidiano.httpbench.clients;

import io.github.cleidiano.httpbench.api.AsyncBenchClient;
import io.github.cleidiano.httpbench.api.BenchClient;
import io.github.cleidiano.httpbench.api.BenchClientProvider;
import io.github.cleidiano.httpbench.api.ClientConfig;
import io.github.cleidiano.httpbench.api.HttpStatusException;
import org.eclipse.jetty.client.BytesRequestContent;
import org.eclipse.jetty.client.CompletableResponseListener;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpCookieStore;
import org.eclipse.jetty.util.thread.VirtualThreadPool;

import java.util.concurrent.CompletableFuture;

/** Jetty 12 HttpClient, blocking {@code Request.send()} over HTTP/1.1. */
public final class JettyBenchClient implements AsyncBenchClient {

    private final HttpClient client;
    private final String baseUri;

    /**
     * @param virtualThreads run Jetty's internal tasks (selector-produced I/O work) on virtual
     *                       threads instead of the default {@code QueuedThreadPool}
     */
    JettyBenchClient(ClientConfig config, boolean virtualThreads) throws Exception {
        this.baseUri = config.baseUri();
        this.client = new HttpClient();
        if (virtualThreads) {
            // The default cap is 200 concurrent tasks; size it to the pool so it never throttles.
            client.setExecutor(new VirtualThreadPool(Math.max(200, config.maxConnections() * 2)));
        }
        client.setMaxConnectionsPerDestination(config.maxConnections());
        client.setConnectTimeout(config.timeout().toMillis());
        client.setIdleTimeout(config.timeout().toMillis());
        client.setFollowRedirects(false);
        client.setHttpCookieStore(new HttpCookieStore.Empty());
        client.start();
    }

    @Override
    public byte[] get(String path) throws Exception {
        return check(client.newRequest(baseUri + path).send(), path);
    }

    @Override
    public byte[] postJson(String path, byte[] body) throws Exception {
        var response = client.POST(baseUri + path)
                .body(new BytesRequestContent("application/json", body))
                .send();
        return check(response, path);
    }

    private static byte[] check(ContentResponse response, String path) {
        return HttpStatusException.check(response.getStatus(), path, response.getContent());
    }

    /** Native async API: a {@link CompletableResponseListener} buffering the content. */
    @Override
    public CompletableFuture<byte[]> getAsync(String path) {
        return new CompletableResponseListener(client.newRequest(baseUri + path)).send()
                .thenApply(response -> HttpStatusException.check(response.getStatus(), path, response.getContent()));
    }

    @Override
    public void close() throws Exception {
        client.stop();
    }

    /** Same client, measured through its async API (AsyncGetBenchmark). */
    public static final class AsyncProvider implements BenchClientProvider {
        @Override
        public String name() {
            return "jetty-async";
        }

        @Override
        public BenchClient create(ClientConfig config) throws Exception {
            return new JettyBenchClient(config, false);
        }
    }

    /** Async API with Jetty's internal tasks on its {@link VirtualThreadPool}. */
    public static final class VirtualThreadsAsyncProvider implements BenchClientProvider {
        @Override
        public String name() {
            return "jetty-vt-async";
        }

        @Override
        public BenchClient create(ClientConfig config) throws Exception {
            return new JettyBenchClient(config, true);
        }
    }

    public static final class Provider implements BenchClientProvider {
        @Override
        public String name() {
            return "jetty";
        }

        @Override
        public BenchClient create(ClientConfig config) throws Exception {
            return new JettyBenchClient(config, false);
        }
    }

    /** Same client, internal tasks on Jetty's {@link VirtualThreadPool}. */
    public static final class VirtualThreadsProvider implements BenchClientProvider {
        @Override
        public String name() {
            return "jetty-vt";
        }

        @Override
        public BenchClient create(ClientConfig config) throws Exception {
            return new JettyBenchClient(config, true);
        }
    }
}
