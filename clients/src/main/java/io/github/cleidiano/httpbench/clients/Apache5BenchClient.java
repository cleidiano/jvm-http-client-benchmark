package io.github.cleidiano.httpbench.clients;

import io.github.cleidiano.httpbench.api.BenchClient;
import io.github.cleidiano.httpbench.api.BenchClientProvider;
import io.github.cleidiano.httpbench.api.ClientConfig;
import io.github.cleidiano.httpbench.api.HttpStatusException;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;

import java.io.IOException;

/** Apache HttpClient 5, classic (blocking) API. */
public final class Apache5BenchClient implements BenchClient {

    private static final byte[] EMPTY = new byte[0];

    private final CloseableHttpClient client;
    private final String baseUri;

    Apache5BenchClient(ClientConfig config) {
        this.baseUri = config.baseUri();
        this.client = NativeClients.apache5(config);
    }

    @Override
    public byte[] get(String path) throws IOException {
        return client.execute(new HttpGet(baseUri + path), response -> read(response, path));
    }

    @Override
    public byte[] postJson(String path, byte[] body) throws IOException {
        var post = new HttpPost(baseUri + path);
        post.setEntity(new ByteArrayEntity(body, ContentType.APPLICATION_JSON));
        return client.execute(post, response -> read(response, path));
    }

    private static byte[] read(ClassicHttpResponse response, String path) throws IOException {
        var entity = response.getEntity();
        byte[] body = entity == null ? EMPTY : EntityUtils.toByteArray(entity);
        return HttpStatusException.check(response.getCode(), path, body);
    }

    @Override
    public void close() throws IOException {
        client.close();
    }

    public static final class Provider implements BenchClientProvider {
        @Override
        public String name() {
            return "apache5";
        }

        @Override
        public BenchClient create(ClientConfig config) {
            return new Apache5BenchClient(config);
        }
    }
}
