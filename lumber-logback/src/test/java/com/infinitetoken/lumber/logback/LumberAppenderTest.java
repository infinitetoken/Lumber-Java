package com.infinitetoken.lumber.logback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.status.Status;
import com.infinitetoken.lumber.FakeLumber;
import com.infinitetoken.lumber.LumberClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.slf4j.MarkerFactory;

class LumberAppenderTest {
    private FakeLumber server;
    private LoggerContext context;
    private LumberAppender appender;

    @BeforeEach
    void start() {
        server = new FakeLumber();
        context = new LoggerContext();
        context.setName("test");
        // SLF4J's provider gives its own context an MDC; a context made here needs one too
        context.setMDCAdapter(new LogbackMDCAdapter());
    }

    @AfterEach
    void stop() {
        MDC.clear();
        context.stop();
        server.close();
    }

    /** A logger sending to the fake server through a started appender, with no automatic context. */
    private Logger logger(String name) {
        appender = new LumberAppender();
        appender.setContext(context);
        appender.setName("LUMBER");
        appender.setApiKey(FakeLumber.API_KEY);
        appender.setEndpoint(server.url());
        appender.setAutoContext(false);
        appender.start();
        assertTrue(appender.isStarted(), context.getStatusManager().getCopyOfStatusList().toString());
        FakeLumber.noRetryWait(appender.getClient());
        Logger logger = context.getLogger(name);
        logger.setAdditive(false);
        logger.setLevel(Level.TRACE);
        logger.addAppender(appender);
        return logger;
    }

    private void flush() {
        assertTrue(appender.getClient().flush(Duration.ofSeconds(5)), "logs still queued after 5 seconds");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    @Test
    void levels() {
        Logger logger = logger("levels");
        logger.trace("t");
        logger.debug("d");
        logger.info("i");
        logger.warn("w");
        logger.error("e");
        logger.error(MarkerFactory.getMarker("FATAL"), "f");
        logger.atError()
                .addMarker(MarkerFactory.getMarker("OUTAGE"))
                .addMarker(MarkerFactory.getMarker("FATAL"))
                .log("FATAL among other markers");
        flush();
        List<Object> levels = new ArrayList<>();
        for (Map<String, Object> log : server.logs()) {
            levels.add(log.get("level"));
        }
        assertEquals(List.of(0L, 0L, 1L, 2L, 3L, 4L, 4L), levels);
    }

    @Test
    void anEvent() {
        Logger logger = logger("com.example.Billing");
        context.getMDCAdapter().put("traceId", "trace-1");
        context.getMDCAdapter().put("tenant", "acme");
        logger.atWarn()
                .addKeyValue("request_id", "req-1")
                .addKeyValue("rows", 1500)
                .addKeyValue("context", Map.of("region", "us"))
                .setCause(new IllegalStateException("too slow"))
                .log("Report ready in {}s", 2.4);
        flush();

        Map<String, Object> log = server.logs().get(0);
        assertEquals(2L, log.get("level"));
        assertEquals("Report ready in 2.4s", log.get("message"));
        assertEquals("req-1", log.get("requestId"));
        assertEquals("trace-1", log.get("traceId"));
        Map<String, Object> context = object(log.get("context"));
        assertEquals("com.example.Billing", context.get("logger"));
        assertEquals(Thread.currentThread().getName(), context.get("thread"));
        assertEquals("us", context.get("region"));
        Map<String, Object> metadata = object(log.get("metadata"));
        assertEquals("acme", metadata.get("tenant"));
        assertEquals(1500L, metadata.get("rows"));
        assertEquals("java.lang.IllegalStateException: too slow", metadata.get("exception"));
        String stackTrace = (String) metadata.get("stackTrace");
        assertTrue(stackTrace.startsWith("java.lang.IllegalStateException: too slow\n\tat "), stackTrace);
        assertEquals(
                List.of("context", "level", "message", "metadata", "requestId", "traceId"),
                new ArrayList<>(new java.util.TreeSet<>(log.keySet())));
    }

    @Test
    void aContextThatIsntAMapIsMetadata() {
        Logger logger = logger("odd");
        context.getMDCAdapter().put("context", "billing");
        logger.info("Invoice sent");
        flush();
        Map<String, Object> log = server.logs().get(0);
        assertEquals("billing", object(log.get("metadata")).get("context"));
        assertEquals(Map.of("logger", "odd", "thread", Thread.currentThread().getName()), log.get("context"));
    }

    @Test
    void noMdc() {
        Logger logger = logger("quiet");
        appender.setIncludeMdc(false);
        context.getMDCAdapter().put("tenant", "acme");
        logger.info("plain");
        flush();
        assertFalse(server.logs().get(0).containsKey("metadata"));
    }

    @Test
    void theHttpClientsOwnLogsAreLeftOut() {
        logger("jdk.internal.httpclient.debug").info("sending");
        logger("app").info("kept");
        flush();
        assertEquals(List.of("kept"), server.messages());
    }

    @Test
    void configuredFromXml() throws Exception {
        context.putProperty("apiKey", FakeLumber.API_KEY);
        context.putProperty("endpoint", server.url());
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        configurator.doConfigure(getClass().getResource("/lumber-test.xml"));
        for (Status status : context.getStatusManager().getCopyOfStatusList()) {
            assertTrue(status.getLevel() < Status.WARN, status.toString());
        }

        context.getLogger("configured").info("From XML");
        appender = (LumberAppender) context.getLogger("configured").getAppender("LUMBER");
        flush();

        Map<String, Object> log = server.logs().get(0);
        Map<String, Object> want = new LinkedHashMap<>();
        want.put("pid", ProcessHandle.current().pid());
        want.put("env", "staging");
        want.put("logger", "configured");
        want.put("code", "LumberAppenderTest.java");
        want.put("method", "com.infinitetoken.lumber.logback.LumberAppenderTest.configuredFromXml");
        Map<String, Object> got = new LinkedHashMap<>(object(log.get("context")));
        assertTrue(got.remove("hostname") instanceof String);
        assertTrue(got.remove("thread") instanceof String);
        assertTrue(got.remove("line") instanceof Long);
        assertEquals(want, got);
    }

    @Test
    void noApiKeyDoesntStart() {
        LumberAppender appender = new LumberAppender();
        appender.setContext(context);
        appender.setName("LUMBER");
        appender.start();
        assertFalse(appender.isStarted());
        String errors = context.getStatusManager().getCopyOfStatusList().toString();
        assertTrue(errors.contains("No apiKey for the appender named \"LUMBER\""), errors);

        appender.setApiKey(FakeLumber.TOKEN);
        appender.start();
        assertFalse(appender.isStarted());
        errors = context.getStatusManager().getCopyOfStatusList().toString();
        assertTrue(errors.contains("no encryption key"), errors);
    }

    @Test
    void aSharedClientIsntClosed() {
        List<String> warnings = Collections.synchronizedList(new ArrayList<>());
        try (LumberClient client = server.client(warnings::add)) {
            LumberAppender shared = new LumberAppender();
            shared.setContext(context);
            shared.setClient(client);
            shared.start();
            Logger logger = context.getLogger("shared");
            logger.addAppender(shared);
            logger.info("through Logback");
            client.info("direct");
            shared.stop();
            client.info("after the appender stopped");
            assertTrue(client.flush(Duration.ofSeconds(5)));
            assertEquals(List.of("through Logback", "direct", "after the appender stopped"), server.messages());
            assertEquals(List.of(), warnings);
        }
    }

    @Test
    void stoppingClosesItsClient() {
        Logger logger = logger("stopping");
        LumberClient client = appender.getClient();
        logger.info("before");
        appender.stop();
        assertNull(appender.getClient());
        client.info("after");
        assertEquals(List.of("before"), server.messages());
    }
}
