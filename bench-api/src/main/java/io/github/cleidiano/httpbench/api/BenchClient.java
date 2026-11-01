package io.github.cleidiano.httpbench.api;

/**
 * Minimal contract every benchmarked client implements.
 *
 * <p>Rules every adapter must follow so the comparison stays fair:
 * <ul>
 *   <li>Read the <b>entire</b> response body into memory before returning.</li>
 *   <li>Throw {@link HttpStatusException} on any non-2xx status.</li>
 *   <li>Reuse connections (keep-alive) and be safe to call from many threads.</li>
 *   <li>Build the request per call, the way an application would.</li>
 * </ul>
 */
public interface BenchClient extends AutoCloseable {

    /** GET {@code baseUri + path} and return the full response body. */
    byte[] get(String path) throws Exception;

    /** POST {@code body} as {@code application/json} and return the full response body. */
    byte[] postJson(String path, byte[] body) throws Exception;
}
