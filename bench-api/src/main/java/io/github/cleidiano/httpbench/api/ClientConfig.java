package io.github.cleidiano.httpbench.api;

import java.time.Duration;

/**
 * Settings applied identically to every client.
 *
 * @param baseUri        scheme, host and port, without trailing slash (e.g. {@code http://127.0.0.1:8080})
 * @param maxConnections upper bound for the connection pool; must be &gt;= benchmark threads
 * @param timeout        connect and read timeout, configured the idiomatic way for each client
 */
public record ClientConfig(String baseUri, int maxConnections, Duration timeout) {

    public ClientConfig {
        if (baseUri.endsWith("/")) {
            baseUri = baseUri.substring(0, baseUri.length() - 1);
        }
        if (maxConnections < 1) {
            throw new IllegalArgumentException("maxConnections must be >= 1");
        }
    }
}
