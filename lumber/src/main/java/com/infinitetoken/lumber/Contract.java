package com.infinitetoken.lumber;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lumber-Kit's contract in Java: the constants, API key format, payload and delivery rules every Lumber client shares.
 * The tests check everything here against the kit's schema and known answers (src/test/resources/contract).
 */
final class Contract {
    static final String DEFAULT_ENDPOINT = "https://api.lumber.infinitetoken.com";
    static final String INGEST_PATH = "/logs";
    static final int KEY_VERSION = 1;
    static final int KEY_BYTES = 32;
    static final int NONCE_BYTES = 12;
    static final int TAG_BYTES = 16;

    static final int MAX_ATTEMPTS = 5;
    static final long BASE_DELAY_MS = 1_000;
    static final long MAX_DELAY_MS = 30_000;
    static final long PAUSE_MS = 3_600_000;

    /** What to do with a log once POST /logs answers. */
    enum Outcome {
        SENT,
        RETRY,
        DROP,
        PAUSE
    }

    /** An API key's halves: the token sent to Lumber, and the app's encryption key, or null when there's none. */
    static final class ApiKey {
        final String token;
        final String encryptionKey;

        ApiKey(String token, String encryptionKey) {
            this.token = token;
            this.encryptionKey = encryptionKey;
        }
    }

    private Contract() {}

    /** Splits an API key, {@code <token>.<encryptionKey>}, at its first dot. A value with no dot is all token. */
    static ApiKey splitApiKey(String apiKey) {
        String trimmed = apiKey == null ? "" : apiKey.strip();
        int dot = trimmed.indexOf('.');
        if (dot < 0) {
            return new ApiKey(trimmed, null);
        }
        return new ApiKey(trimmed.substring(0, dot), trimmed.substring(dot + 1));
    }

    /**
     * The plaintext a log encrypts. requestId and traceId become the payload's own fields, the log's context goes over
     * the automatic context, and every other key goes into metadata, where an explicit metadata key wins. A context or
     * metadata that isn't a map is kept as an ordinary metadata key, and a null one is left out.
     */
    static Map<String, Object> buildPayload(int level, String message, Map<String, ?> data, Map<String, ?> autoContext) {
        Map<String, Object> rest = new LinkedHashMap<>();
        if (data != null) {
            for (Map.Entry<String, ?> entry : data.entrySet()) {
                rest.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        Object requestId = rest.remove("requestId");
        Object traceId = rest.remove("traceId");
        Object givenContext = rest.remove("context");
        Object givenMetadata = rest.remove("metadata");

        Map<String, Object> context = asMap(givenContext);
        Map<String, Object> metadata = asMap(givenMetadata);
        if (givenContext != null && context == null) {
            rest.put("context", givenContext);
        }
        if (givenMetadata != null && metadata == null) {
            rest.put("metadata", givenMetadata);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("level", level);
        payload.put("message", message);
        if (requestId != null) {
            payload.put("requestId", text(requestId));
        }
        if (traceId != null) {
            payload.put("traceId", text(traceId));
        }
        if (autoContext != null && !autoContext.isEmpty()) {
            payload.put("context", merge(autoContext, context));
        } else if (context != null) {
            payload.put("context", context);
        }
        if (!rest.isEmpty()) {
            payload.put("metadata", merge(rest, metadata));
        } else if (metadata != null) {
            payload.put("metadata", metadata);
        }
        return payload;
    }

    /** What to do with a log once POST /logs answers with {@code status}, or 0 for no answer (a network error). */
    static Outcome deliveryOutcome(int status) {
        if (status == 0) {
            return Outcome.RETRY;
        }
        if (status >= 200 && status < 300) {
            return Outcome.SENT;
        }
        if (status == 429) {
            return Outcome.PAUSE;
        }
        if (status >= 400 && status < 500) {
            return Outcome.DROP;
        }
        return Outcome.RETRY;
    }

    /** How long to wait before trying a log again after its nth failure: 1s, 2s, 4s and so on, up to 30s. */
    static long retryDelayMs(int failedAttempts) {
        int doublings = Math.max(0, failedAttempts - 1);
        if (doublings >= 31) {
            return MAX_DELAY_MS;
        }
        return Math.min(BASE_DELAY_MS << doublings, MAX_DELAY_MS);
    }

    /** A map with its keys as strings, or null for anything that isn't a map. */
    static Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map)) {
            return null;
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            copy.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return copy;
    }

    static Map<String, Object> merge(Map<String, ?> base, Map<String, ?> over) {
        Map<String, Object> merged = new LinkedHashMap<>(base);
        if (over != null) {
            merged.putAll(over);
        }
        return merged;
    }

    // The schema has requestId and traceId as strings: a UUID or a number is sent as its text
    private static Object text(Object value) {
        return value instanceof String ? value : String.valueOf(value);
    }
}
