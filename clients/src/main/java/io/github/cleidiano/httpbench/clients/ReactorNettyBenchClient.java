package io.github.cleidiano.httpbench.clients;

import io.github.cleidiano.httpbench.api.AsyncBenchClient;
import io.github.cleidiano.httpbench.api.BenchClient;
import io.github.cleidiano.httpbench.api.BenchClientProvider;
import io.github.cleidiano.httpbench.api.ClientConfig;
import io.github.cleidiano.httpbench.api.HttpStatusException;
import io.netty.buffer.Unpooled;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufMono;
import reactor.netty.http.client.HttpClientResponse;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

/**
 * Reactor Netty HttpClient, the default engine behind Spring's WebClient. Raw baseline for the
 * WebClient and RestClient layers built on it. Blocking calls use {@code block()}, async calls
 * {@code toFuture()}.
 */
public final class ReactorNettyBenchClient implements AsyncBenchClient {

    private static final byte[] EMPTY = new byte[0];

    private final NativeClients.Reactor reactor;
    private final String baseUri;

    ReactorNettyBenchClient(ClientConfig config) {
        this.baseUri = config.baseUri();
        this.reactor = NativeClients.reactor(config);
    }

    private static BiFunction<HttpClientResponse, ByteBufMono, Mono<byte[]>> readBody(String path) {
        return (response, body) -> body.asByteArray()
                .defaultIfEmpty(EMPTY)
                .map(bytes -> HttpStatusException.check(response.status().code(), path, bytes));
    }

    private Mono<byte[]> getMono(String path) {
        return reactor.client().get().uri(baseUri + path).responseSingle(readBody(path));
    }

    @Override
    public byte[] get(String path) {
        return getMono(path).block();
    }

    @Override
    public CompletableFuture<byte[]> getAsync(String path) {
        return getMono(path).toFuture();
    }

    @Override
    public byte[] postJson(String path, byte[] body) {
        return reactor.client()
                .headers(headers -> headers.set("Content-Type", "application/json"))
                .post()
                .uri(baseUri + path)
                .send(Mono.fromSupplier(() -> Unpooled.wrappedBuffer(body)))
                .responseSingle(readBody(path))
                .block();
    }

    @Override
    public void close() {
        reactor.pool().disposeLater().block();
    }

    public static final class Provider implements BenchClientProvider {
        @Override
        public String name() {
            return "reactor";
        }

        @Override
        public BenchClient create(ClientConfig config) {
            return new ReactorNettyBenchClient(config);
        }
    }

    /** Same client, measured through {@code toFuture()} (GetAsyncBenchmark). */
    public static final class AsyncProvider implements BenchClientProvider {
        @Override
        public String name() {
            return "reactor-async";
        }

        @Override
        public BenchClient create(ClientConfig config) {
            return new ReactorNettyBenchClient(config);
        }
    }
}
