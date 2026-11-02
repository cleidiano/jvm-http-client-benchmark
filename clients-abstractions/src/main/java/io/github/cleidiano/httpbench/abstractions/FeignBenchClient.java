package io.github.cleidiano.httpbench.abstractions;

import feign.Client;
import feign.Feign;
import feign.Headers;
import feign.Param;
import feign.Request;
import feign.RequestLine;
import feign.Retryer;
import io.github.cleidiano.httpbench.api.BenchClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * OpenFeign (plain, without Spring Cloud) on top of a {@link Client} that wraps one of the raw
 * clients. The declarative interface below is what an application would write; Feign's default
 * decoder returns the body as {@code byte[]} and its default error decoder throws on non-2xx.
 */
final class FeignBenchClient implements BenchClient {

    /** The declarative API, with fixed paths as Feign clients are usually written. */
    interface BytesApi {
        @RequestLine("GET /bytes/{size}")
        byte[] bytes(@Param("size") String size);

        @RequestLine("POST /post")
        @Headers("Content-Type: application/json")
        byte[] post(byte[] body);
    }

    private static final String BYTES_PREFIX = "/bytes/";
    private static final byte[] EMPTY = new byte[0];

    private final BytesApi api;
    private final AutoCloseable underlying;

    FeignBenchClient(Client client, String baseUri, Duration timeout, AutoCloseable underlying) {
        this.api = Feign.builder()
                .client(client)
                // Same timeouts as the raw clients, no redirects, no retries.
                .options(new Request.Options(timeout.toMillis(), TimeUnit.MILLISECONDS,
                        timeout.toMillis(), TimeUnit.MILLISECONDS, false))
                .retryer(Retryer.NEVER_RETRY)
                .target(BytesApi.class, baseUri);
        this.underlying = underlying;
    }

    @Override
    public byte[] get(String path) {
        if (!path.startsWith(BYTES_PREFIX)) {
            throw new IllegalArgumentException("Feign adapter only maps " + BYTES_PREFIX + "<size>, got " + path);
        }
        // Feign decodes an empty body to null with some clients (OkHttp) and to byte[0] with others.
        byte[] body = api.bytes(path.substring(BYTES_PREFIX.length()));
        return body == null ? EMPTY : body;
    }

    @Override
    public byte[] postJson(String path, byte[] body) {
        if (!path.equals("/post")) {
            throw new IllegalArgumentException("Feign adapter only maps /post, got " + path);
        }
        byte[] response = api.post(body);
        return response == null ? EMPTY : response;
    }

    @Override
    public void close() throws Exception {
        underlying.close();
    }
}
