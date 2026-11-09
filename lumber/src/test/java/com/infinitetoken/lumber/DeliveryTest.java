package com.infinitetoken.lumber;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeliveryTest {
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
    void aRefusedLogIsDroppedAtOnce() {
        server.answer(401, null, "Invalid API key");
        try (LumberClient client = server.client(warnings::add)) {
            client.info("refused");
            client.info("next");
            flush(client);
        }
        assertEquals(List.of("refused", "next"), server.messages());
        assertEquals(List.of("log refused: HTTP 401 (Invalid API key)"), warnings);
    }

    @Test
    void aFailureIsTriedAgain() {
        server.answer(503, null, null);
        server.answer(502, null, null);
        try (LumberClient client = server.client(warnings::add)) {
            client.info("flaky");
            client.info("next");
            flush(client);
        }
        assertEquals(List.of("flaky", "flaky", "flaky", "next"), server.messages());
        assertEquals(List.of("send failed: HTTP 503", "send failed: HTTP 502"), warnings);
    }

    @Test
    void aLogIsDroppedAfterFiveFailures() {
        for (int i = 0; i < 5; i++) {
            server.answer(500, null, null);
        }
        try (LumberClient client = server.client(warnings::add)) {
            client.info("doomed");
            client.info("next");
            flush(client);
        }
        assertEquals(List.of("doomed", "doomed", "doomed", "doomed", "doomed", "next"), server.messages());
        assertEquals(6, warnings.size(), warnings.toString());
        assertEquals("dropping log after 5 failed attempts", warnings.get(5));
    }

    @Test
    void noServer() {
        try (LumberClient client = LumberClient.builder(FakeLumber.API_KEY)
                .endpoint("http://127.0.0.1:9")
                .shutdownTimeout(Duration.ZERO)
                .warnings(warnings::add)
                .build()) {
            FakeLumber.noRetryWait(client);
            client.info("nowhere");
            flush(client);
        }
        assertEquals(6, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).startsWith("send error: "), warnings.get(0));
        assertEquals("dropping log after 5 failed attempts", warnings.get(5));
    }

    @Test
    void retriesWaitOneTwoFourThenEightSeconds() throws InterruptedException {
        List<Long> waits = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 5; i++) {
            server.answer(500, null, null);
        }
        try (LumberClient client = server.client(warnings::add)) {
            client.sender.retryWait = failed -> {
                waits.add(Contract.retryDelayMs(failed));
                return 0;
            };
            client.info("doomed");
            flush(client);
        }
        assertEquals(List.of(1_000L, 2_000L, 4_000L, 8_000L), waits);
    }

    @Test
    void theMonthlyCapPausesSending() throws InterruptedException {
        server.hold();
        server.answer(429, "86400", "This team has sent its 10,000 logs for the month.");
        try (LumberClient client = server.client(warnings::add)) {
            client.info("capped");
            server.awaitArrival();
            client.info("queued behind it");
            server.release();
            flush(client);
            client.info("during the pause");
            flush(client);
            assertEquals(List.of("capped"), server.messages());
            assertEquals(1, warnings.size(), warnings.toString());
            assertTrue(warnings.get(0).startsWith(
                    "This team has sent its 10,000 logs for the month. Dropping logs until "), warnings.get(0));
            long waitSeconds = TimeUnit.NANOSECONDS.toSeconds(client.sender.pausedUntil - System.nanoTime());
            assertTrue(waitSeconds >= 86_390 && waitSeconds <= 86_400, "paused for " + waitSeconds + "s");

            client.sender.pausedUntil = System.nanoTime();
            client.info("next month");
            flush(client);
        }
        assertEquals(List.of("capped", "next month"), server.messages());
    }

    @Test
    void aPauseWithoutRetryAfterLastsAnHour() {
        server.answer(429, null, null);
        try (LumberClient client = server.client(warnings::add)) {
            client.info("capped");
            flush(client);
            long waitMinutes = TimeUnit.NANOSECONDS.toMinutes(client.sender.pausedUntil - System.nanoTime());
            assertTrue(waitMinutes >= 59 && waitMinutes <= 60, "paused for " + waitMinutes + " minutes");
        }
        assertTrue(warnings.get(0).startsWith("Lumber is refusing logs for now (HTTP 429). Dropping logs until "),
                warnings.toString());
    }

    @Test
    void flushGivesUpAtItsTimeout() {
        server.hold();
        try (LumberClient client = server.client(warnings::add)) {
            client.info("stuck");
            long start = System.nanoTime();
            assertFalse(client.flush(Duration.ofMillis(50)));
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2));
            server.release();
            flush(client);
        }
    }

    @Test
    void closeGivesUpAtItsTimeout() {
        server.hold();
        LumberClient client = server.client(warnings::add);
        client.info("stuck");
        long start = System.nanoTime();
        assertFalse(client.close(Duration.ofMillis(100)));
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2));
        server.release();
    }

    @Test
    void aFullQueueDropsNewLogs() throws InterruptedException {
        server.hold();
        try (LumberClient client = server.client(LumberClient.builder(FakeLumber.API_KEY).maxQueue(1), warnings::add)) {
            // The first log is held at the server, so the queue's one place is free for the second and every one after
            // it is dropped
            client.info("log", Map.of("i", 0));
            server.awaitArrival();
            for (int i = 1; i < 10; i++) {
                client.info("log", Map.of("i", i));
            }
            server.release();
            flush(client);
        }
        assertEquals(2, server.requests().size());
        assertEquals(List.of("dropping logs: 1 are already waiting to send"), warnings);
    }

    @Test
    void theConnectionIsKeptOpen() {
        try (LumberClient client = server.client(warnings::add)) {
            for (int i = 0; i < 5; i++) {
                client.info("log", Map.of("i", i));
            }
            flush(client);
        }
        assertEquals(1, server.connections(), "connections for 5 logs");
    }
}
