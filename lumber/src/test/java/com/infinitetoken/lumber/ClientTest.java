package com.infinitetoken.lumber;

import static com.infinitetoken.lumber.ContractFiles.assertJson;
import static com.infinitetoken.lumber.ContractFiles.object;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClientTest {
    private FakeLumber server;
    private final List<String> warnings = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void start() {
        server = new FakeLumber();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private static void flush(LumberClient client) {
        assertTrue(client.flush(Duration.ofSeconds(5)), "logs still queued after 5 seconds");
    }

    @Test
    void refusesABadApiKey() {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("", "Missing API key");
        cases.put("   ", "Missing API key");
        cases.put(FakeLumber.TOKEN, "no encryption key");
        cases.put(FakeLumber.TOKEN + ".", "no encryption key");
        cases.put(FakeLumber.TOKEN + "." + Base64.getEncoder().encodeToString(new byte[16]), "32 bytes of base64, got 16");
        cases.put(FakeLumber.TOKEN + ".not base64!", "32 bytes of base64, got 0");
        cases.forEach((apiKey, problem) -> {
            IllegalArgumentException error =
                    assertThrows(IllegalArgumentException.class, () -> LumberClient.create(apiKey), apiKey);
            assertTrue(error.getMessage().contains(problem), error.getMessage());
            assertTrue(error.getMessage().startsWith("[lumber] "), error.getMessage());
        });
        assertThrows(IllegalArgumentException.class, () -> LumberClient.create(null));
        IllegalArgumentException endpoint = assertThrows(
                IllegalArgumentException.class,
                () -> LumberClient.builder(FakeLumber.API_KEY).endpoint("api.lumber.example").build());
        assertTrue(endpoint.getMessage().contains("endpoint"), endpoint.getMessage());
    }

    @Test
    void sendsOnlyTheToken() {
        try (LumberClient client = LumberClient.builder("  " + FakeLumber.API_KEY + "\n")
                .endpoint(server.url() + "/")
                .shutdownTimeout(Duration.ZERO)
                .warnings(warnings::add)
                .build()) {
            client.info("hello");
            flush(client);
        }
        FakeLumber.Request request = server.requests().get(0);
        assertEquals("Bearer " + FakeLumber.TOKEN, request.header("Authorization"));
        assertEquals("application/json", request.header("Content-Type"));
        assertTrue(request.header("User-Agent").startsWith("lumber-java/" + LumberClient.VERSION + " java/"),
                request.header("User-Agent"));
        String sent = FakeLumber.toJson(request.body);
        assertFalse(sent.contains("hello") || sent.contains(FakeLumber.KEY), "the message or key went out unencrypted");
        assertEquals(List.of(), warnings);
    }

    @Test
    void theVersionIsTheBuildsVersion() {
        assertEquals(System.getProperty("lumber.version"), LumberClient.VERSION);
    }

    @Test
    void loggingNeverWaitsForTheNetwork() {
        server.hold();
        try (LumberClient client = server.client(warnings::add)) {
            long start = System.nanoTime();
            for (int i = 0; i < 300; i++) {
                client.info("quick", Map.of("i", i));
            }
            long tookMs = (System.nanoTime() - start) / 1_000_000;
            assertTrue(tookMs < 2_000, "300 logs took " + tookMs + "ms with the server holding every request");
            server.release();
            flush(client);
        }
        assertEquals(300, server.requests().size());
    }

    @Test
    void levels() {
        try (LumberClient client = server.client(warnings::add)) {
            client.debug("d");
            client.info("i");
            client.warn("w");
            client.error("e");
            client.fatal("f");
            client.log(LogLevel.WARN, "logged", null, null);
            flush(client);
        }
        List<Object> levels = new ArrayList<>();
        List<Object> bodyLevels = new ArrayList<>();
        for (Map<String, Object> log : server.logs()) {
            levels.add(log.get("level"));
        }
        for (FakeLumber.Request request : server.requests()) {
            bodyLevels.add(request.body.get("level"));
        }
        assertJson(List.of(0, 1, 2, 3, 4, 2), levels);
        assertEquals(levels, bodyLevels, "the plaintext level and the encrypted one agree");
    }

    @Test
    void data() {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("id", 7);
        order.put("total", new BigDecimal("12.50"));
        Map<String, Object> data = new HashMap<>();
        data.put("requestId", "req-1");
        data.put("traceId", UUID.fromString("6f1c1f2a-3b4d-4e5f-8a9b-0c1d2e3f4a5b"));
        data.put("context", Map.of("region", "us"));
        data.put("metadata", Map.of("plan", "pro"));
        data.put("order", order);
        data.put("at", Instant.parse("2026-11-02T09:30:00Z"));
        data.put("day", LocalDate.of(2026, 11, 2));
        data.put("took", Duration.ofMillis(1500));
        data.put("tags", List.of("a", "b"));
        data.put("scores", new int[] {1, 2});
        data.put("ratio", Double.NaN);
        data.put("level", LogLevel.FATAL);
        data.put("maybe", Optional.empty());
        data.put("text", "line\nbreak \"quoted\" ☃");
        try (LumberClient client = server.client(warnings::add)) {
            client.error("Payment failed", data);
            client.info("none", Collections.singletonMap("requestId", null));
            flush(client);
        }
        List<Map<String, Object>> logs = server.logs();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("plan", "pro");
        metadata.put("order", Map.of("id", 7, "total", 12.5));
        metadata.put("at", "2026-11-02T09:30:00Z");
        metadata.put("day", "2026-11-02");
        metadata.put("took", "PT1.5S");
        metadata.put("tags", List.of("a", "b"));
        metadata.put("scores", List.of(1, 2));
        metadata.put("ratio", null);
        metadata.put("level", "FATAL");
        metadata.put("maybe", null);
        metadata.put("text", "line\nbreak \"quoted\" ☃");
        Map<String, Object> want = new LinkedHashMap<>();
        want.put("level", 3);
        want.put("message", "Payment failed");
        want.put("requestId", "req-1");
        want.put("traceId", "6f1c1f2a-3b4d-4e5f-8a9b-0c1d2e3f4a5b");
        want.put("context", Map.of("region", "us"));
        want.put("metadata", metadata);
        assertJson(want, logs.get(0));
        ContractFiles.conforms("LogPayload", logs.get(0));
        assertJson(Map.of("level", 1, "message", "none"), logs.get(1));
    }

    @Test
    void aStructureThatContainsItselfStillSends() {
        Map<String, Object> loop = new HashMap<>();
        loop.put("name", "loop");
        loop.put("self", loop);
        List<Object> deep = new ArrayList<>();
        List<Object> at = deep;
        for (int i = 0; i < 100; i++) {
            List<Object> next = new ArrayList<>();
            at.add(next);
            at = next;
        }
        try (LumberClient client = server.client(warnings::add)) {
            client.info("odd", Map.of("loop", loop, "deep", deep));
            flush(client);
        }
        Map<String, Object> metadata = object(server.logs().get(0).get("metadata"));
        assertJson(Map.of("name", "loop", "self", "[Circular]"), metadata.get("loop"));
        assertTrue(FakeLumber.toJson(metadata.get("deep")).contains("[Too deep]"));
    }

    @Test
    void exceptions() {
        IllegalStateException error = new IllegalStateException("card declined", new RuntimeException("timeout"));
        try (LumberClient client = server.client(warnings::add)) {
            client.error("Payment failed", error);
            client.warn("Kept its own", Map.of("exception", "mine"), error);
            flush(client);
        }
        List<Map<String, Object>> logs = server.logs();
        Map<String, Object> metadata = object(logs.get(0).get("metadata"));
        assertEquals("java.lang.IllegalStateException: card declined", metadata.get("exception"));
        String stackTrace = (String) metadata.get("stackTrace");
        assertTrue(stackTrace.startsWith("java.lang.IllegalStateException: card declined\n\tat "), stackTrace);
        assertTrue(stackTrace.contains("Caused by: java.lang.RuntimeException: timeout"), stackTrace);
        assertEquals("mine", object(logs.get(1).get("metadata")).get("exception"));
    }

    @Test
    void automaticContext() {
        try (LumberClient client = LumberClient.builder(FakeLumber.API_KEY)
                .endpoint(server.url())
                .environment("production")
                .shutdownTimeout(Duration.ZERO)
                .warnings(warnings::add)
                .build()) {
            client.info("hello", Map.of("context", Map.of("region", "us", "hostname", "mine")));
            client.info("plain");
            flush(client);
        }
        List<Map<String, Object>> logs = server.logs();
        long pid = ProcessHandle.current().pid();
        assertJson(Map.of("pid", pid, "hostname", "mine", "env", "production", "region", "us"), logs.get(0).get("context"));
        assertJson(Map.of("pid", pid, "hostname", LumberClient.Host.NAME, "env", "production"), logs.get(1).get("context"));
    }

    @Test
    void noAutomaticContext() {
        try (LumberClient client = server.client(warnings::add)) {
            client.info("plain");
            flush(client);
        }
        assertFalse(server.logs().get(0).containsKey("context"));
    }

    @Test
    void caller() {
        try (LumberClient client = server.client(LumberClient.builder(FakeLumber.API_KEY).caller(true), warnings::add);
                LumberClient wrapped = server.client(
                        LumberClient.builder(FakeLumber.API_KEY).caller(true).callerDepth(1), warnings::add)) {
            client.info("direct");
            wrap(wrapped, "wrapped");
            flush(client);
            flush(wrapped);
        }
        for (Map<String, Object> log : server.logs()) {
            Map<String, Object> context = object(log.get("context"));
            assertEquals("ClientTest.java", context.get("code"), log.get("message").toString());
            assertEquals("com.infinitetoken.lumber.ClientTest.caller", context.get("method"));
            assertTrue(context.get("line") instanceof Long);
        }
    }

    private static void wrap(LumberClient client, String message) {
        client.info(message);
    }

    @Test
    void closeDropsLaterLogs() {
        LumberClient client = server.client(warnings::add);
        client.info("before");
        assertTrue(client.close(Duration.ofSeconds(5)));
        client.info("after");
        assertTrue(client.flush(Duration.ofSeconds(1)));
        assertTrue(client.close(Duration.ofSeconds(1)), "closing twice is fine");
        assertEquals(List.of("before"), server.messages());
        assertFalse(client.sender.thread.isAlive());
        assertEquals(List.of(), warnings);
    }
}
