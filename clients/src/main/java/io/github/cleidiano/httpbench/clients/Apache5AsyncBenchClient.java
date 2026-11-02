package io.github.cleidiano.httpbench.clients;

import io.github.cleidiano.httpbench.api.AsyncBenchClient;
import io.github.cleidiano.httpbench.api.BenchClient;
import io.github.cleidiano.httpbench.api.BenchClientProvider;
import io.github.cleidiano.httpbench.api.ClientConfig;
import io.github.cleidiano.httpbench.api.HttpStatusException;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.async.methods.SimpleRequestBuilder;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http2.HttpVersionPolicy;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.hc.core5.util.Timeout;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * Apache HttpClient 5, async API ({@link CloseableHttpAsyncClient}): non-blocking I/O on its own
 * I/O reactor threads (one per CPU by default). A different client from the classic one, not a
 * wrapper around it.
 */
public final class Apache5AsyncBenchClient implements AsyncBenchClient {

    private static final byte[] EMPTY = new byte[0];

    private final CloseableHttpAsyncClient client;
    private final String baseUri;

    Apache5AsyncBenchClient(ClientConfig config) {
        this.baseUri = config.baseUri();
        var timeout = Timeout.ofMilliseconds(config.timeout().toMillis());
        var connectionManager = PoolingAsyncClientConnectionManagerBuilder.create()
                .setMaxConnTotal(config.maxConnections())
                .setMaxConnPerRoute(config.maxConnections())
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(timeout)
                        .setSocketTimeout(timeout)
                        .build())
                .build();
        this.client = HttpAsyncClients.custom()
                .setConnectionManager(connectionManager)
                // Same protocol as every other client; the async client could otherwise use HTTP/2.
                .setVersionPolicy(HttpVersionPolicy.FORCE_HTTP_1)
                .setIOReactorConfig(IOReactorConfig.custom().setTcpNoDelay(true).setSoTimeout(timeout).build())
                .setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(timeout).build())
                .disableContentCompression()
                .disableCookieManagement()
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .build();
        client.start();
    }

    @Override
    public CompletableFuture<byte[]> getAsync(String path) {
        var future = new CompletableFuture<byte[]>();
        client.execute(SimpleRequestBuilder.get(baseUri + path).build(), new FutureCallback<>() {
            @Override
            public void completed(SimpleHttpResponse response) {
                byte[] body = response.getBodyBytes();
                try {
                    future.complete(HttpStatusException.check(response.getCode(), path, body == null ? EMPTY : body));
                } catch (RuntimeException e) {
                    future.completeExceptionally(e);
                }
            }

            @Override
            public void failed(Exception e) {
                future.completeExceptionally(e);
            }

            @Override
            public void cancelled() {
                future.cancel(false);
            }
        });
        return future;
    }

    @Override
    public void close() throws IOException {
        client.close();
    }

    public static final class Provider implements BenchClientProvider {
        @Override
        public String name() {
            return "apache5-async";
        }

        @Override
        public BenchClient create(ClientConfig config) {
            return new Apache5AsyncBenchClient(config);
        }
    }
}
