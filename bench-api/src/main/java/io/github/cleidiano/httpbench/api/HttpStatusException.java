package io.github.cleidiano.httpbench.api;

public final class HttpStatusException extends RuntimeException {

    public HttpStatusException(int status, String path) {
        super("Unexpected HTTP status " + status + " for " + path, null, false, false);
    }

    /** Returns {@code body} when {@code status} is 2xx, throws otherwise. */
    public static byte[] check(int status, String path, byte[] body) {
        if (status < 200 || status > 299) {
            throw new HttpStatusException(status, path);
        }
        return body;
    }
}
