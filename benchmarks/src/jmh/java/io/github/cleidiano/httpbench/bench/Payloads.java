package io.github.cleidiano.httpbench.bench;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Payload sizes shared with server/nginx/10-payloads.sh. Keep both in sync. */
final class Payloads {

    /** Length of nginx's {@code /post} response: {@code {"ok":true}}. */
    static final int POST_RESPONSE_LENGTH = 11;

    private Payloads() {
    }

    static int size(String name) {
        return switch (name) {
            case "0" -> 0;
            case "1k" -> 1024;
            case "100k" -> 100 * 1024;
            default -> throw new IllegalArgumentException("Unknown payload size: " + name);
        };
    }

    /** A syntactically valid JSON document of exactly {@code size} bytes. */
    static byte[] json(int size) {
        byte[] prefix = "{\"data\":\"".getBytes(StandardCharsets.US_ASCII);
        byte[] suffix = "\"}".getBytes(StandardCharsets.US_ASCII);
        int filler = size - prefix.length - suffix.length;
        if (filler < 0) {
            throw new IllegalArgumentException("JSON payload too small: " + size);
        }
        byte[] out = new byte[size];
        System.arraycopy(prefix, 0, out, 0, prefix.length);
        Arrays.fill(out, prefix.length, prefix.length + filler, (byte) 'x');
        System.arraycopy(suffix, 0, out, size - suffix.length, suffix.length);
        return out;
    }

    static void expectLength(byte[] body, int expected, String what) {
        if (body.length != expected) {
            throw new IllegalStateException(what + ": expected " + expected + " bytes, got " + body.length);
        }
    }
}
