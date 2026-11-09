package com.infinitetoken.lumber;

import static com.infinitetoken.lumber.ContractFiles.VECTORS;
import static com.infinitetoken.lumber.ContractFiles.assertJson;
import static com.infinitetoken.lumber.ContractFiles.conforms;
import static com.infinitetoken.lumber.ContractFiles.list;
import static com.infinitetoken.lumber.ContractFiles.object;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** Every known answer in Lumber-Kit's contract, against this library's own code. */
class ContractTest {
    private static Stream<Map<String, Object>> vectors(String section) {
        return list(VECTORS.get(section)).stream().map(ContractFiles::object);
    }

    @Test
    void constants() {
        Map<String, Object> c = object(VECTORS.get("constants"));
        assertEquals(c.get("defaultEndpoint"), LumberClient.DEFAULT_ENDPOINT);
        assertEquals(c.get("ingestPath"), Contract.INGEST_PATH);
        assertEquals(c.get("keyVersion"), (long) Contract.KEY_VERSION);
        assertEquals(c.get("keyBytes"), (long) Contract.KEY_BYTES);
        assertEquals(c.get("nonceBytes"), (long) Contract.NONCE_BYTES);
        assertEquals(c.get("tagBytes"), (long) Contract.TAG_BYTES);

        Map<String, Object> levels = new LinkedHashMap<>();
        for (LogLevel level : LogLevel.values()) {
            levels.put(level.name().toLowerCase(java.util.Locale.ROOT), level.value());
        }
        assertJson(c.get("levels"), levels);

        Map<String, Object> delivery = object(c.get("delivery"));
        assertEquals(delivery.get("maxAttempts"), (long) Contract.MAX_ATTEMPTS);
        assertEquals(delivery.get("baseDelayMs"), Contract.BASE_DELAY_MS);
        assertEquals(delivery.get("maxDelayMs"), Contract.MAX_DELAY_MS);
        assertEquals(delivery.get("pauseMs"), Contract.PAUSE_MS);
    }

    @TestFactory
    Stream<DynamicTest> apiKeys() {
        return vectors("apiKeys").map(v -> DynamicTest.dynamicTest((String) v.get("name"), () -> {
            Contract.ApiKey parts = Contract.splitApiKey((String) v.get("apiKey"));
            assertEquals(v.get("token"), parts.token);
            assertEquals(v.get("encryptionKey"), parts.encryptionKey);
        }));
    }

    @Test
    void aNullApiKeyIsEmpty() {
        assertEquals("", Contract.splitApiKey(null).token);
        assertNull(Contract.splitApiKey(null).encryptionKey);
    }

    @TestFactory
    Stream<DynamicTest> encryption() {
        Base64.Decoder base64 = Base64.getDecoder();
        return vectors("encryption").map(v -> DynamicTest.dynamicTest((String) v.get("name"), () -> {
            byte[] sealed = Crypto.seal(
                    new SecretKeySpec(base64.decode((String) v.get("key")), "AES"),
                    base64.decode((String) v.get("nonce")),
                    ((String) v.get("plaintext")).getBytes(StandardCharsets.UTF_8));
            assertEquals(v.get("payload"), Base64.getEncoder().encodeToString(sealed));
        }));
    }

    @TestFactory
    Stream<DynamicTest> payloads() {
        return vectors("payloads").map(v -> DynamicTest.dynamicTest((String) v.get("name"), () -> {
            Map<String, Object> payload = Contract.buildPayload(
                    ((Long) v.get("level")).intValue(),
                    (String) v.get("message"),
                    nullableObject(v.get("data")),
                    nullableObject(v.get("autoContext")));
            assertJson(v.get("payload"), payload);
            conforms("LogPayload", payload);
        }));
    }

    /**
     * Through the whole client: what it sends is a LogIngestBody whose plaintext is the vector's payload. The vectors
     * with an automatic context are left to {@link #payloads()}, since the client makes its own.
     */
    @TestFactory
    Stream<DynamicTest> theClientSendsThePayloads() {
        return vectors("payloads")
                .filter(v -> !v.containsKey("autoContext"))
                .map(v -> DynamicTest.dynamicTest((String) v.get("name"), () -> {
                    List<String> warnings = new ArrayList<>();
                    try (FakeLumber server = new FakeLumber();
                            LumberClient client = server.client(warnings::add)) {
                        LogLevel level = LogLevel.values()[((Long) v.get("level")).intValue()];
                        client.log(level, (String) v.get("message"), nullableObject(v.get("data")), null);
                        client.flush(java.time.Duration.ofSeconds(5));

                        Map<String, Object> body = server.requests().get(0).body;
                        conforms("LogIngestBody", body);
                        Map<String, Object> encrypted = object(body.get("encrypted"));
                        assertEquals(v.get("level"), body.get("level"));
                        assertEquals(object(VECTORS.get("constants")).get("keyVersion"), encrypted.get("keyVersion"));
                        assertEquals(
                                Contract.NONCE_BYTES,
                                Base64.getDecoder().decode((String) encrypted.get("nonce")).length);
                        assertJson(v.get("payload"), FakeLumber.decrypt(body));
                        assertEquals(List.of(), warnings);
                    }
                }));
    }

    @Test
    void deliveryOutcomes() {
        vectors("deliveryOutcomes").forEach(v -> {
            Object status = v.get("status");
            int code = status == null ? 0 : ((Long) status).intValue();
            assertEquals(v.get("outcome"), Contract.deliveryOutcome(code).name().toLowerCase(java.util.Locale.ROOT),
                    "status " + status);
        });
    }

    @Test
    void retryDelays() {
        vectors("retryDelaysMs").forEach(v -> assertEquals(
                v.get("delayMs"),
                Contract.retryDelayMs(((Long) v.get("failedAttempts")).intValue()),
                "after " + v.get("failedAttempts") + " failures"));
        assertEquals(Contract.MAX_DELAY_MS, Contract.retryDelayMs(200), "a delay past the cap is the cap");
    }

    private static Map<String, Object> nullableObject(Object value) {
        return value == null ? null : object(value);
    }
}
