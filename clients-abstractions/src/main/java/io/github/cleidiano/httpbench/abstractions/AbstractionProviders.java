package io.github.cleidiano.httpbench.abstractions;

import feign.hc5.ApacheHttp5Client;
import feign.http2client.Http2Client;
import io.github.cleidiano.httpbench.api.BenchClient;
import io.github.cleidiano.httpbench.api.BenchClientProvider;
import io.github.cleidiano.httpbench.api.ClientConfig;
import io.github.cleidiano.httpbench.clients.NativeClients;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.JettyClientHttpRequestFactory;
import org.springframework.http.client.ReactorClientHttpRequestFactory;
import org.springframework.http.client.reactive.HttpComponentsClientHttpConnector;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.http.client.reactive.JettyClientHttpConnector;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;

/**
 * Service providers for every abstraction layer. Each one builds the underlying client with
 * {@link NativeClients} (same settings as the raw benchmark) and closes it when the layer closes.
 * Naming: {@code <layer>-<underlying client>}.
 */
public final class AbstractionProviders {

    private AbstractionProviders() {
    }

    // --- Spring RestClient ---------------------------------------------------------------------

    public static final class RestClientApache5 implements BenchClientProvider {
        @Override
        public String name() {
            return "spring-restclient-apache5";
        }

        @Override
        public BenchClient create(ClientConfig config) {
            var client = NativeClients.apache5(config);
            return new RestClientBenchClient(new HttpComponentsClientHttpRequestFactory(client),
                    config.baseUri(), client);
        }
    }

    public static final class RestClientJdk implements BenchClientProvider {
        @Override
        public String name() {
            return "spring-restclient-jdk";
        }

        @Override
        public BenchClient create(ClientConfig config) {
            var client = NativeClients.jdk(config, null);
            var factory = new JdkClientHttpRequestFactory(client);
            factory.setReadTimeout(config.timeout()); // the raw JDK adapter sets the same per-request timeout
            // Spring 7 turns compression on by default for this factory (Accept-Encoding: gzip, deflate
            // plus a decompressing body handler per request); every other client runs without it.
            factory.enableCompression(false);
            return new RestClientBenchClient(factory, config.baseUri(), client::close);
        }
    }

    public static final class RestClientJetty implements BenchClientProvider {
        @Override
        public String name() {
            return "spring-restclient-jetty";
        }

        @Override
        public BenchClient create(ClientConfig config) throws Exception {
            var client = NativeClients.jetty(config, false);
            return new RestClientBenchClient(new JettyClientHttpRequestFactory(client),
                    config.baseUri(), client::stop);
        }
    }

    public static final class RestClientReactor implements BenchClientProvider {
        @Override
        public String name() {
            return "spring-restclient-reactor";
        }

        @Override
        public BenchClient create(ClientConfig config) {
            var reactor = NativeClients.reactor(config);
            return new RestClientBenchClient(new ReactorClientHttpRequestFactory(reactor.client()),
                    config.baseUri(), () -> reactor.pool().disposeLater().block());
        }
    }

    // --- Spring WebClient ----------------------------------------------------------------------

    public static final class WebClientReactor implements BenchClientProvider {
        @Override
        public String name() {
            return "spring-webclient-reactor";
        }

        @Override
        public BenchClient create(ClientConfig config) {
            var reactor = NativeClients.reactor(config);
            return new WebClientBenchClient(new ReactorClientHttpConnector(reactor.client()),
                    config.baseUri(), () -> reactor.pool().disposeLater().block());
        }
    }

    public static final class WebClientJdk implements BenchClientProvider {
        @Override
        public String name() {
            return "spring-webclient-jdk";
        }

        @Override
        public BenchClient create(ClientConfig config) {
            var client = NativeClients.jdk(config, null);
            var connector = new JdkClientHttpConnector(client);
            connector.setReadTimeout(config.timeout()); // same per-request timeout as the raw JDK adapter
            return new WebClientBenchClient(connector, config.baseUri(), client::close);
        }
    }

    public static final class WebClientJetty implements BenchClientProvider {
        @Override
        public String name() {
            return "spring-webclient-jetty";
        }

        @Override
        public BenchClient create(ClientConfig config) throws Exception {
            var client = NativeClients.jetty(config, false);
            return new WebClientBenchClient(new JettyClientHttpConnector(client), config.baseUri(), client::stop);
        }
    }

    /** WebClient over Apache needs the async client (non-blocking I/O), not the classic one. */
    public static final class WebClientApache5 implements BenchClientProvider {
        @Override
        public String name() {
            return "spring-webclient-apache5";
        }

        @Override
        public BenchClient create(ClientConfig config) {
            var client = NativeClients.apache5Async(config);
            return new WebClientBenchClient(new HttpComponentsClientHttpConnector(client),
                    config.baseUri(), client);
        }
    }

    // --- OpenFeign -----------------------------------------------------------------------------

    public static final class FeignApache5 implements BenchClientProvider {
        @Override
        public String name() {
            return "feign-apache5";
        }

        @Override
        public BenchClient create(ClientConfig config) {
            var client = NativeClients.apache5(config);
            return new FeignBenchClient(new ApacheHttp5Client(client), config.baseUri(), config.timeout(), client);
        }
    }

    public static final class FeignOkHttp implements BenchClientProvider {
        @Override
        public String name() {
            return "feign-okhttp";
        }

        @Override
        public BenchClient create(ClientConfig config) {
            var client = NativeClients.okhttp(config, null);
            return new FeignBenchClient(new feign.okhttp.OkHttpClient(client), config.baseUri(), config.timeout(),
                    () -> {
                        client.dispatcher().executorService().shutdown();
                        client.connectionPool().evictAll();
                    });
        }
    }

    public static final class FeignJdk implements BenchClientProvider {
        @Override
        public String name() {
            return "feign-jdk";
        }

        @Override
        public BenchClient create(ClientConfig config) {
            var client = NativeClients.jdk(config, null);
            return new FeignBenchClient(new Http2Client(client), config.baseUri(), config.timeout(), client::close);
        }
    }
}
