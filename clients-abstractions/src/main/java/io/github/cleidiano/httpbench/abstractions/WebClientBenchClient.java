package io.github.cleidiano.httpbench.abstractions;

import io.github.cleidiano.httpbench.api.AsyncBenchClient;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.concurrent.CompletableFuture;

/**
 * Spring's reactive {@link WebClient} on top of a {@link ClientHttpConnector} that wraps one of the
 * raw clients. Measured both ways it is used: blocking with {@code block()} (GetBenchmark, common
 * in Spring MVC applications) and asynchronously with {@code toFuture()} (GetAsyncBenchmark).
 */
final class WebClientBenchClient implements AsyncBenchClient {

    private static final byte[] EMPTY = new byte[0];

    private final WebClient web;
    private final AutoCloseable underlying;

    WebClientBenchClient(ClientHttpConnector connector, String baseUri, AutoCloseable underlying) {
        this.web = WebClient.builder().clientConnector(connector).baseUrl(baseUri).build();
        this.underlying = underlying;
    }

    private Mono<byte[]> getMono(String path) {
        // retrieve() turns 4xx/5xx into WebClientResponseException.
        return web.get().uri(path).retrieve().bodyToMono(byte[].class).defaultIfEmpty(EMPTY);
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
        return web.post().uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(byte[].class)
                .defaultIfEmpty(EMPTY)
                .block();
    }

    @Override
    public void close() throws Exception {
        underlying.close();
    }
}
