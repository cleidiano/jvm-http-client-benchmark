package io.github.cleidiano.httpbench.clients;

import io.github.cleidiano.httpbench.api.AsyncBenchClient;
import io.github.cleidiano.httpbench.api.BenchClient;
import io.github.cleidiano.httpbench.api.BenchClientProvider;
import io.github.cleidiano.httpbench.api.ClientConfig;
import io.github.cleidiano.httpbench.api.HttpStatusException;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * OkHttp, synchronous {@code Call.execute()}.
 *
 * <p>Caveat: OkHttp always adds {@code Accept-Encoding: gzip}. The server has gzip
 * disabled, so responses are identical; only the request header differs.
 */
public final class OkHttpBenchClient implements AsyncBenchClient {

    private static final MediaType JSON = MediaType.get("application/json");

    private final OkHttpClient client;
    private final String baseUri;

    OkHttpBenchClient(ClientConfig config) {
        this(config, null);
    }

    /**
     * @param dispatcherExecutor runs {@code enqueue}d calls (each one does blocking I/O on its thread);
     *                           {@code null} keeps OkHttp's default pool of platform threads
     */
    OkHttpBenchClient(ClientConfig config, ExecutorService dispatcherExecutor) {
        this.baseUri = config.baseUri();
        this.client = NativeClients.okhttp(config, dispatcherExecutor);
    }

    @Override
    public byte[] get(String path) throws IOException {
        return execute(new Request.Builder().url(baseUri + path).build(), path);
    }

    @Override
    public byte[] postJson(String path, byte[] body) throws IOException {
        var request = new Request.Builder()
                .url(baseUri + path)
                .post(RequestBody.create(body, JSON))
                .build();
        return execute(request, path);
    }

    private byte[] execute(Request request, String path) throws IOException {
        try (var response = client.newCall(request).execute()) {
            return HttpStatusException.check(response.code(), path, response.body().bytes());
        }
    }

    /**
     * OkHttp's async API, {@code Call.enqueue}. Not non-blocking I/O: the dispatcher runs each call's
     * blocking I/O on its own thread pool, so every in-flight call holds a platform thread.
     */
    @Override
    public CompletableFuture<byte[]> getAsync(String path) {
        var future = new CompletableFuture<byte[]>();
        client.newCall(new Request.Builder().url(baseUri + path).build()).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                future.completeExceptionally(e);
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (response) {
                    future.complete(HttpStatusException.check(response.code(), path, response.body().bytes()));
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            }
        });
        return future;
    }

    @Override
    public void close() {
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }

    /** Same client, measured through {@code Call.enqueue} (GetAsyncBenchmark). */
    public static final class AsyncProvider implements BenchClientProvider {
        @Override
        public String name() {
            return "okhttp-async";
        }

        @Override
        public BenchClient create(ClientConfig config) throws Exception {
            return new OkHttpBenchClient(config);
        }
    }

    /** {@code enqueue} with the dispatcher running each call on a virtual thread instead of a platform thread. */
    public static final class VirtualThreadsAsyncProvider implements BenchClientProvider {
        @Override
        public String name() {
            return "okhttp-vt-async";
        }

        @Override
        public BenchClient create(ClientConfig config) throws Exception {
            return new OkHttpBenchClient(config, Executors.newVirtualThreadPerTaskExecutor());
        }
    }

    public static final class Provider implements BenchClientProvider {
        @Override
        public String name() {
            return "okhttp";
        }

        @Override
        public BenchClient create(ClientConfig config) {
            return new OkHttpBenchClient(config);
        }
    }
}
