package com.infinitetoken.lumber.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import ch.qos.logback.core.util.Duration;
import com.infinitetoken.lumber.LogLevel;
import com.infinitetoken.lumber.LumberClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Marker;
import org.slf4j.event.KeyValuePair;

/**
 * Sends Logback's events, and so everything logged through SLF4J, to Lumber. Each event is encrypted where it's logged
 * and sent from the client's background thread, so it needs no {@code AsyncAppender} in front of it.
 *
 * <pre>{@code
 * <appender name="LUMBER" class="com.infinitetoken.lumber.logback.LumberAppender">
 *   <apiKey>${LUMBER_API_KEY}</apiKey>
 *   <environment>production</environment>
 * </appender>
 *
 * <root level="INFO">
 *   <appender-ref ref="LUMBER" />
 * </root>
 * }</pre>
 *
 * <p>Each event becomes a log at the matching level: TRACE and DEBUG are DEBUG, and an event with a marker named
 * {@code FATAL} is FATAL. Its message is the formatted message. The MDC's entries and the event's key-value pairs go
 * into the log's metadata, except {@code requestId} and {@code traceId} (or {@code request_id} and {@code trace_id}),
 * which are the log's own fields, and a {@code context} map, which goes into its context. The logger and thread go
 * into the context too, with the file, line and method when {@code includeCallerData} is on. An exception goes into
 * the metadata as {@code exception} and {@code stackTrace}.
 */
public class LumberAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {
    private String apiKey;
    private String endpoint;
    private String environment;
    private boolean autoContext = true;
    private boolean includeCallerData;
    private boolean includeMdc = true;
    private Duration timeout;
    private Duration shutdownTimeout;
    private int maxQueue = 10_000;
    private volatile LumberClient client;
    private boolean ownsClient;

    @Override
    public void start() {
        if (isStarted()) {
            return;
        }
        if (client == null) {
            if (apiKey == null || apiKey.isBlank()) {
                addError("No apiKey for the appender named \"" + name + "\". Set <apiKey>${LUMBER_API_KEY}</apiKey> to"
                        + " the API key from your app's Secrets screen in Lumber.");
                return;
            }
            try {
                LumberClient.Builder builder = LumberClient.builder(apiKey)
                        .environment(environment)
                        .autoContext(autoContext)
                        .maxQueue(maxQueue)
                        .warnings(this::warn);
                if (endpoint != null && !endpoint.isBlank()) {
                    builder.endpoint(endpoint);
                }
                if (timeout != null) {
                    builder.timeout(java.time.Duration.ofMillis(timeout.getMilliseconds()));
                }
                if (shutdownTimeout != null) {
                    builder.shutdownTimeout(java.time.Duration.ofMillis(shutdownTimeout.getMilliseconds()));
                }
                client = builder.build();
                ownsClient = true;
            } catch (IllegalArgumentException e) {
                addError(e.getMessage(), e);
                return;
            }
        }
        super.start();
    }

    /** Sends what's queued, waiting for the client's shutdown timeout (5 seconds unless set) at most, and stops. */
    @Override
    public void stop() {
        if (!isStarted()) {
            return;
        }
        super.stop();
        if (ownsClient && client != null) {
            client.close();
            client = null;
            ownsClient = false;
        }
    }

    @Override
    protected void append(ILoggingEvent event) {
        LumberClient lumber = client;
        String logger = event.getLoggerName();
        if (lumber == null || (logger != null && logger.startsWith("jdk.internal.httpclient"))) {
            // The HTTP client sending the logs logs there when its debugging is on: those would only feed back
            return;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        if (includeMdc && event.getMDCPropertyMap() != null) {
            data.putAll(event.getMDCPropertyMap());
        }
        List<KeyValuePair> pairs = event.getKeyValuePairs();
        if (pairs != null) {
            for (KeyValuePair pair : pairs) {
                if (pair.key != null) {
                    data.put(pair.key, pair.value);
                }
            }
        }
        rename(data, "request_id", "requestId");
        rename(data, "trace_id", "traceId");

        Map<String, Object> context = new LinkedHashMap<>();
        if (logger != null) {
            context.put("logger", logger);
        }
        if (event.getThreadName() != null) {
            context.put("thread", event.getThreadName());
        }
        if (includeCallerData) {
            StackTraceElement[] callerData = event.getCallerData();
            if (callerData != null && callerData.length > 0) {
                StackTraceElement frame = callerData[0];
                if (frame.getFileName() != null) {
                    context.put("code", frame.getFileName());
                }
                if (frame.getLineNumber() > 0) {
                    context.put("line", frame.getLineNumber());
                }
                context.put("method", frame.getClassName() + "." + frame.getMethodName());
            }
        }
        Object given = data.remove("context");
        if (given instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) given).entrySet()) {
                context.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        } else if (given != null) {
            // A context that isn't a map is an ordinary metadata key, as Lumber's other clients send it
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("context", given);
            Object explicit = data.get("metadata");
            if (explicit instanceof Map) {
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) explicit).entrySet()) {
                    metadata.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            } else if (explicit != null) {
                metadata.put("metadata", explicit);
            }
            data.put("metadata", metadata);
        }
        data.put("context", context);

        IThrowableProxy error = event.getThrowableProxy();
        if (error != null) {
            String message = error.getMessage();
            data.putIfAbsent("exception", error.getClassName() + (message == null ? "" : ": " + message));
            data.putIfAbsent("stackTrace", ThrowableProxyUtil.asString(error).stripTrailing());
        }

        lumber.log(level(event), event.getFormattedMessage(), data, null);
    }

    /** An event's level as Lumber's: from ERROR up is ERROR, TRACE is DEBUG, and a FATAL marker is FATAL. */
    static LogLevel level(ILoggingEvent event) {
        List<Marker> markers = event.getMarkerList();
        if (markers != null) {
            for (Marker marker : markers) {
                if (marker != null && "FATAL".equals(marker.getName())) {
                    return LogLevel.FATAL;
                }
            }
        }
        int level = event.getLevel().toInt();
        if (level >= Level.ERROR_INT) {
            return LogLevel.ERROR;
        }
        if (level >= Level.WARN_INT) {
            return LogLevel.WARN;
        }
        if (level >= Level.INFO_INT) {
            return LogLevel.INFO;
        }
        return LogLevel.DEBUG;
    }

    private static void rename(Map<String, Object> data, String from, String to) {
        if (data.containsKey(from)) {
            Object value = data.remove(from);
            if (value != null) {
                data.putIfAbsent(to, value);
            }
        }
    }

    // Straight to standard error, as the client's own warnings go, and into Logback's status for a status listener
    private void warn(String message) {
        System.err.println("[lumber] " + message);
        addWarn(message);
    }

    /**
     * The client the appender sends with, or null before it starts.
     *
     * @return the client
     */
    public LumberClient getClient() {
        return client;
    }

    /**
     * Sends with a client made in code, shared with your own calls, in place of one made from the apiKey and the other
     * options here. The appender doesn't close a client it was given.
     *
     * @param client the client
     */
    public void setClient(LumberClient client) {
        this.client = client;
        this.ownsClient = false;
    }

    /**
     * The API key from the app's Secrets screen in Lumber, {@code <token>.<encryptionKey>}. Required unless a client
     * is set. Only the token is sent to Lumber; the encryption key encrypts every log and never leaves this process.
     *
     * @param apiKey the API key, usually {@code ${LUMBER_API_KEY}}
     */
    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * Sends logs to another Lumber API, for testing against another server.
     *
     * @param endpoint an http or https URL
     */
    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    /**
     * Sent with every log (production, staging) as {@code env} in its context.
     *
     * @param environment the environment's name
     */
    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    /**
     * Whether to send the process id and hostname with every log, in its context. True unless set.
     *
     * @param autoContext false to leave them out
     */
    public void setAutoContext(boolean autoContext) {
        this.autoContext = autoContext;
    }

    /**
     * Whether to send the file, line and method that logged, in each log's context. False unless set, since Logback
     * finds them from a stack trace on every event.
     *
     * @param includeCallerData true to send them
     */
    public void setIncludeCallerData(boolean includeCallerData) {
        this.includeCallerData = includeCallerData;
    }

    /**
     * Whether to send the MDC's entries with each log, in its metadata. True unless set.
     *
     * @param includeMdc false to leave them out
     */
    public void setIncludeMdc(boolean includeMdc) {
        this.includeMdc = includeMdc;
    }

    /**
     * How long to wait for Lumber to answer one log ({@code 10 seconds} unless set).
     *
     * @param timeout the time limit, such as {@code 10 seconds}
     */
    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    /**
     * How long logs still queued get to send when the appender stops or the program exits ({@code 5 seconds} unless
     * set).
     *
     * @param shutdownTimeout the time limit, such as {@code 5 seconds}
     */
    public void setShutdownTimeout(Duration shutdownTimeout) {
        this.shutdownTimeout = shutdownTimeout;
    }

    /**
     * How many logs can wait to send, while Lumber can't be reached, before new ones are dropped. 10,000 unless set.
     *
     * @param maxQueue the most logs to hold
     */
    public void setMaxQueue(int maxQueue) {
        this.maxQueue = maxQueue;
    }
}
