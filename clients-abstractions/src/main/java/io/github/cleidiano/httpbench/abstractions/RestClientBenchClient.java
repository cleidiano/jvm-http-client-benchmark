package io.github.cleidiano.httpbench.abstractions;

import io.github.cleidiano.httpbench.api.BenchClient;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Spring's {@link RestClient} (blocking) on top of a {@link ClientHttpRequestFactory} that wraps
 * one of the raw clients. The response body is read as {@code byte[]}, so what this adds over the
 * raw client is the RestClient layer itself: URI building, request/response wrapping, message
 * conversion with the byte-array converter and status handling ({@code retrieve()} throws on
 * 4xx/5xx).
 */
final class RestClientBenchClient implements BenchClient {

    private static final byte[] EMPTY = new byte[0];

    private final RestClient rest;
    private final AutoCloseable underlying;

    RestClientBenchClient(ClientHttpRequestFactory factory, String baseUri, AutoCloseable underlying) {
        this.rest = RestClient.builder().requestFactory(factory).baseUrl(baseUri).build();
        this.underlying = underlying;
    }

    @Override
    public byte[] get(String path) {
        byte[] body = rest.get().uri(path).retrieve().body(byte[].class);
        return body == null ? EMPTY : body;
    }

    @Override
    public byte[] postJson(String path, byte[] body) {
        byte[] response = rest.post().uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(byte[].class);
        return response == null ? EMPTY : response;
    }

    @Override
    public void close() throws Exception {
        underlying.close();
    }
}
