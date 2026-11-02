package io.github.cleidiano.httpbench.clients;

import io.github.cleidiano.httpbench.api.ClientConfig;
import io.netty.channel.ChannelOption;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.core5.http2.HttpVersionPolicy;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.hc.core5.util.Timeout;
import org.eclipse.jetty.http.HttpCookieStore;
import org.eclipse.jetty.util.thread.VirtualThreadPool;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import javax.net.SocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The single place where each underlying HTTP client is built and configured.
 *
 * <p>Both the raw adapters in this module and the abstraction layers (Spring RestClient and
 * WebClient, Feign) build their clients here, so a layer always wraps a client configured exactly
 * like the raw benchmark it is compared with: HTTP/1.1, pool sized to {@code maxConnections},
 * {@code TCP_NODELAY}, no compression, redirects, retries or cookies, same timeouts.
 */
public final class NativeClients {

    private NativeClients() {
    }

    /** Apache HttpClient 5, classic (blocking) API. */
    public static CloseableHttpClient apache5(ClientConfig config) {
        var timeout = Timeout.ofMilliseconds(config.timeout().toMillis());
        var connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(config.maxConnections())
                .setMaxConnPerRoute(config.maxConnections())
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(timeout)
                        .setSocketTimeout(timeout)
                        .build())
                .build();
        return HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(timeout).build())
                // Defaults add gzip negotiation, cookies, redirects and retries; the other clients don't.
                .disableContentCompression()
                .disableCookieManagement()
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .build();
    }

    /** Apache HttpClient 5, async API on its non-blocking I/O reactor; returned started. */
    public static CloseableHttpAsyncClient apache5Async(ClientConfig config) {
        var timeout = Timeout.ofMilliseconds(config.timeout().toMillis());
        var connectionManager = PoolingAsyncClientConnectionManagerBuilder.create()
                .setMaxConnTotal(config.maxConnections())
                .setMaxConnPerRoute(config.maxConnections())
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(timeout)
                        .setSocketTimeout(timeout)
                        .build())
                .build();
        var client = HttpAsyncClients.custom()
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
        return client;
    }

    /**
     * {@code java.net.http.HttpClient}.
     *
     * @param executor executor for the client's internal async work; {@code null} keeps the JDK
     *                 default (a cached pool of platform threads)
     */
    public static java.net.http.HttpClient jdk(ClientConfig config, ExecutorService executor) {
        var builder = java.net.http.HttpClient.newBuilder()
                // Default is HTTP_2, which sends an h2c Upgrade header on cleartext requests.
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .connectTimeout(config.timeout());
        if (executor != null) {
            builder.executor(executor);
        }
        return builder.build();
    }

    /**
     * Jetty 12 HttpClient over HTTP/1.1; returned started.
     *
     * @param virtualThreads run Jetty's internal tasks on virtual threads instead of the default
     *                       {@code QueuedThreadPool}
     */
    public static org.eclipse.jetty.client.HttpClient jetty(ClientConfig config, boolean virtualThreads)
            throws Exception {
        var client = new org.eclipse.jetty.client.HttpClient();
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
        return client;
    }

    /**
     * OkHttp.
     *
     * @param dispatcherExecutor runs {@code enqueue}d calls; {@code null} keeps OkHttp's default
     *                           pool of platform threads
     */
    public static OkHttpClient okhttp(ClientConfig config, ExecutorService dispatcherExecutor) {
        var dispatcher = dispatcherExecutor == null ? new Dispatcher() : new Dispatcher(dispatcherExecutor);
        dispatcher.setMaxRequests(config.maxConnections());
        dispatcher.setMaxRequestsPerHost(config.maxConnections());
        return new OkHttpClient.Builder()
                .protocols(List.of(Protocol.HTTP_1_1))
                .connectionPool(new ConnectionPool(config.maxConnections(), 5, TimeUnit.MINUTES))
                .dispatcher(dispatcher)
                .connectTimeout(config.timeout())
                .readTimeout(config.timeout())
                .writeTimeout(config.timeout())
                .followRedirects(false)
                .retryOnConnectionFailure(false)
                .socketFactory(new NoDelaySocketFactory())
                .build();
    }

    /** A Reactor Netty client and the connection pool it owns (dispose the pool to close it). */
    public record Reactor(HttpClient client, ConnectionProvider pool) {
    }

    /**
     * Reactor Netty HttpClient, the default engine of Spring's WebClient. Uses Reactor Netty's
     * shared event loops (one per CPU). Compression and redirects are off by default.
     */
    public static Reactor reactor(ClientConfig config) {
        var pool = ConnectionProvider.builder("bench")
                .maxConnections(config.maxConnections())
                .pendingAcquireMaxCount(-1)
                .build();
        var client = HttpClient.create(pool)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) config.timeout().toMillis())
                .responseTimeout(config.timeout())
                .compress(false)
                .followRedirect(false)
                .keepAlive(true);
        return new Reactor(client, pool);
    }

    /**
     * OkHttp leaves Nagle's algorithm on, unlike the other clients (all enable TCP_NODELAY).
     * With large uploads the last partial segment then waits for the server's delayed ACK,
     * adding tens of milliseconds per request, so we enable TCP_NODELAY for parity.
     */
    private static final class NoDelaySocketFactory extends SocketFactory {
        private final SocketFactory delegate = SocketFactory.getDefault();

        private static Socket noDelay(Socket socket) throws SocketException {
            socket.setTcpNoDelay(true);
            return socket;
        }

        @Override
        public Socket createSocket() throws IOException {
            return noDelay(delegate.createSocket());
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return noDelay(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            return noDelay(delegate.createSocket(host, port, localHost, localPort));
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return noDelay(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
                throws IOException {
            return noDelay(delegate.createSocket(address, port, localAddress, localPort));
        }
    }
}
