package com.infinitetoken.lumber;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Sends logs to Lumber, cloud logging with end-to-end encryption. Every log is encrypted with AES-256-GCM before it
 * leaves the process, so Lumber's servers store only ciphertext plus each log's level.
 *
 * <pre>{@code
 * LumberClient lumber = LumberClient.create(System.getenv("LUMBER_API_KEY"));
 * lumber.info("Server started", Map.of("port", 8080));
 * }</pre>
 *
 * <p>Logging never blocks or throws: logs are queued and sent from a background thread. Logs still queued when the
 * program exits get up to the shutdown timeout (5 seconds unless set) to send; {@link #flush(Duration)} waits for them
 * yourself, and {@link #close()} sends them and stops. A client is safe to share between threads, and most programs
 * need just one.
 */
public final class LumberClient implements AutoCloseable {
    /** This library's version, sent in the User-Agent. */
    public static final String VERSION = "1.0.0";

    /** The Lumber API logs go to unless {@link Builder#endpoint(String)} says otherwise. */
    public static final String DEFAULT_ENDPOINT = Contract.DEFAULT_ENDPOINT;

    private final SecretKey key;
    final Sender sender;
    private final Map<String, Object> autoContext;
    private final boolean caller;
    private final int callerDepth;
    private final Duration shutdownTimeout;
    private final Consumer<String> warn;
    private final Thread shutdownHook;

    private LumberClient(Builder builder, SecretKey key, String token, String endpoint) {
        this.key = key;
        this.caller = builder.caller;
        this.callerDepth = builder.callerDepth;
        this.shutdownTimeout = builder.shutdownTimeout;
        this.warn = builder.warnings != null ? builder.warnings : LumberClient::warnToStderr;

        Map<String, Object> context = new LinkedHashMap<>();
        if (builder.autoContext) {
            context.put("pid", ProcessHandle.current().pid());
            String hostname = Host.NAME;
            if (hostname != null) {
                context.put("hostname", hostname);
            }
            if (builder.environment != null && !builder.environment.isEmpty()) {
                context.put("env", builder.environment);
            }
        }
        this.autoContext = Collections.unmodifiableMap(context);

        boolean ownsHttp = builder.httpClient == null;
        HttpClient http = ownsHttp
                ? HttpClient.newBuilder().connectTimeout(builder.timeout).build()
                : builder.httpClient;
        String userAgent = "lumber-java/" + VERSION + " java/" + System.getProperty("java.version");
        this.sender = new Sender(http, ownsHttp, endpoint, token, userAgent, builder.timeout, builder.maxQueue, warn);

        Thread hook = null;
        if (!shutdownTimeout.isZero()) {
            hook = new Thread(() -> {
                if (!sender.flush(shutdownTimeout) && !sender.isClosed()) {
                    warn.accept("exiting with logs still waiting to send");
                }
            }, "lumber-shutdown");
            try {
                Runtime.getRuntime().addShutdownHook(hook);
            } catch (IllegalStateException e) {
                // The JVM is already shutting down
                hook = null;
            }
        }
        this.shutdownHook = hook;
    }

    /**
     * Makes a client with the default options.
     *
     * @param apiKey the API key from the app's Secrets screen in Lumber, {@code <token>.<encryptionKey>}. Only the
     *     token is sent to Lumber; the encryption key encrypts every log and never leaves this process.
     * @return a client, sending logs from its own background thread
     * @throws IllegalArgumentException when the API key is missing or has no valid encryption key
     */
    public static LumberClient create(String apiKey) {
        return builder(apiKey).build();
    }

    /**
     * Starts a client with options: {@code LumberClient.builder(apiKey).environment("production").build()}.
     *
     * @param apiKey the API key from the app's Secrets screen in Lumber, {@code <token>.<encryptionKey>}
     * @return a builder
     */
    public static Builder builder(String apiKey) {
        return new Builder(apiKey);
    }

    // --- Logging ---

    /**
     * Logs at DEBUG.
     *
     * @param message the log's message
     */
    public void debug(String message) {
        log(LogLevel.DEBUG, message, null, null);
    }

    /**
     * Logs at DEBUG with data. See {@link #log(LogLevel, String, Map, Throwable)} for the keys that mean something.
     *
     * @param message the log's message
     * @param data the log's data: {@code requestId}, {@code traceId}, {@code context}, {@code metadata}, and any
     *     others, which go into its metadata
     */
    public void debug(String message, Map<String, ?> data) {
        log(LogLevel.DEBUG, message, data, null);
    }

    /**
     * Logs at INFO.
     *
     * @param message the log's message
     */
    public void info(String message) {
        log(LogLevel.INFO, message, null, null);
    }

    /**
     * Logs at INFO with data.
     *
     * @param message the log's message
     * @param data the log's data (see {@link #debug(String, Map)})
     */
    public void info(String message, Map<String, ?> data) {
        log(LogLevel.INFO, message, data, null);
    }

    /**
     * Logs at WARN.
     *
     * @param message the log's message
     */
    public void warn(String message) {
        log(LogLevel.WARN, message, null, null);
    }

    /**
     * Logs at WARN with data.
     *
     * @param message the log's message
     * @param data the log's data (see {@link #debug(String, Map)})
     */
    public void warn(String message, Map<String, ?> data) {
        log(LogLevel.WARN, message, data, null);
    }

    /**
     * Logs at WARN with an exception.
     *
     * @param message the log's message
     * @param error sent in the log's metadata as {@code exception} and {@code stackTrace}
     */
    public void warn(String message, Throwable error) {
        log(LogLevel.WARN, message, null, error);
    }

    /**
     * Logs at WARN with data and an exception.
     *
     * @param message the log's message
     * @param data the log's data (see {@link #debug(String, Map)})
     * @param error sent in the log's metadata as {@code exception} and {@code stackTrace}
     */
    public void warn(String message, Map<String, ?> data, Throwable error) {
        log(LogLevel.WARN, message, data, error);
    }

    /**
     * Logs at ERROR.
     *
     * @param message the log's message
     */
    public void error(String message) {
        log(LogLevel.ERROR, message, null, null);
    }

    /**
     * Logs at ERROR with data.
     *
     * @param message the log's message
     * @param data the log's data (see {@link #debug(String, Map)})
     */
    public void error(String message, Map<String, ?> data) {
        log(LogLevel.ERROR, message, data, null);
    }

    /**
     * Logs at ERROR with an exception.
     *
     * @param message the log's message
     * @param error sent in the log's metadata as {@code exception} and {@code stackTrace}
     */
    public void error(String message, Throwable error) {
        log(LogLevel.ERROR, message, null, error);
    }

    /**
     * Logs at ERROR with data and an exception.
     *
     * @param message the log's message
     * @param data the log's data (see {@link #debug(String, Map)})
     * @param error sent in the log's metadata as {@code exception} and {@code stackTrace}
     */
    public void error(String message, Map<String, ?> data, Throwable error) {
        log(LogLevel.ERROR, message, data, error);
    }

    /**
     * Logs at FATAL. It doesn't exit: call {@link #close()} first if the program is about to.
     *
     * @param message the log's message
     */
    public void fatal(String message) {
        log(LogLevel.FATAL, message, null, null);
    }

    /**
     * Logs at FATAL with data.
     *
     * @param message the log's message
     * @param data the log's data (see {@link #debug(String, Map)})
     */
    public void fatal(String message, Map<String, ?> data) {
        log(LogLevel.FATAL, message, data, null);
    }

    /**
     * Logs at FATAL with an exception.
     *
     * @param message the log's message
     * @param error sent in the log's metadata as {@code exception} and {@code stackTrace}
     */
    public void fatal(String message, Throwable error) {
        log(LogLevel.FATAL, message, null, error);
    }

    /**
     * Logs at FATAL with data and an exception.
     *
     * @param message the log's message
     * @param data the log's data (see {@link #debug(String, Map)})
     * @param error sent in the log's metadata as {@code exception} and {@code stackTrace}
     */
    public void fatal(String message, Map<String, ?> data, Throwable error) {
        log(LogLevel.FATAL, message, data, error);
    }

    /**
     * Logs at any level. It returns at once and never throws: the log is encrypted here and sent from the client's
     * background thread.
     *
     * <p>The data's keys become the log's fields: {@code requestId} and {@code traceId} are the log's own, a
     * {@code context} map is merged into its context (over the automatic process id, hostname and environment), a
     * {@code metadata} map into its metadata, and any other key goes into its metadata too, where an explicit
     * {@code metadata} key wins. Values JSON has no type for are sent as text (a date as ISO 8601), and a NaN as null.
     *
     * @param level the log's level
     * @param message the log's message
     * @param data the log's data, or null
     * @param error an exception, sent in the log's metadata as {@code exception} (its class and message) and
     *     {@code stackTrace}, or null
     */
    public void log(LogLevel level, String message, Map<String, ?> data, Throwable error) {
        if (sender.isClosed() || Thread.currentThread() == sender.thread) {
            // Logs made while sending (an HTTP library's own) would only feed back into Lumber
            return;
        }
        try {
            Map<String, Object> fields = new LinkedHashMap<>();
            if (data != null) {
                for (Map.Entry<String, ?> entry : data.entrySet()) {
                    fields.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            if (error != null) {
                fields.putIfAbsent("exception", error.toString());
                fields.putIfAbsent("stackTrace", stackTrace(error));
            }
            Map<String, Object> context = autoContext;
            if (caller) {
                Map<String, Object> location = callerContext();
                if (location != null) {
                    context = Contract.merge(autoContext, location);
                }
            }
            Map<String, Object> payload = Contract.buildPayload(
                    Objects.requireNonNull(level, "level").value(), String.valueOf(message), fields, context);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("level", level.value());
            body.put("encrypted", Crypto.encrypt(key, Json.encode(payload)));
            sender.enqueue(Json.encode(body));
        } catch (Exception e) {
            warn.accept("couldn't log \"" + message + "\": " + Sender.describe(e));
        }
    }

    // --- Delivery ---

    /**
     * Waits until every queued log is sent (or dropped), for {@code timeout} at most. On a serverless platform, call it
     * before the function returns.
     *
     * @param timeout how long to wait at most
     * @return true when every log was sent or dropped, false when the time ran out first
     */
    public boolean flush(Duration timeout) {
        return sender.flush(Objects.requireNonNull(timeout, "timeout"));
    }

    /**
     * Sends what's queued, waiting for {@code timeout} at most, and stops the client's thread. Logs after this are
     * dropped.
     *
     * @param timeout how long to wait for queued logs at most
     * @return true when every queued log was sent or dropped, false when the time ran out first
     */
    public boolean close(Duration timeout) {
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException e) {
                // The JVM is shutting down, and the hook is running or done
            }
        }
        return sender.close(Objects.requireNonNull(timeout, "timeout"));
    }

    /** Sends what's queued, waiting for the shutdown timeout (5 seconds unless set) at most, and stops. */
    @Override
    public void close() {
        if (!close(shutdownTimeout)) {
            warn.accept("closed with logs still waiting to send");
        }
    }

    // --- Internals ---

    private Map<String, Object> callerContext() {
        String self = LumberClient.class.getName();
        Optional<StackWalker.StackFrame> frame = StackWalker.getInstance()
                .walk(frames -> frames.dropWhile(f -> f.getClassName().equals(self))
                        .skip(callerDepth)
                        .findFirst());
        if (frame.isEmpty()) {
            return null;
        }
        StackWalker.StackFrame f = frame.get();
        Map<String, Object> location = new LinkedHashMap<>();
        if (f.getFileName() != null) {
            location.put("code", f.getFileName());
        }
        if (f.getLineNumber() > 0) {
            location.put("line", f.getLineNumber());
        }
        location.put("method", f.getClassName() + "." + f.getMethodName());
        return location;
    }

    private static String stackTrace(Throwable error) {
        StringWriter out = new StringWriter();
        error.printStackTrace(new PrintWriter(out));
        return out.toString().stripTrailing();
    }

    // Straight to standard error, never through a logging library, which could send Lumber's own warnings to Lumber
    private static void warnToStderr(String message) {
        System.err.println("[lumber] " + message);
    }

    /** The hostname, looked up once: a container's from its environment, otherwise the machine's. */
    static final class Host {
        static final String NAME = lookup();

        private static String lookup() {
            for (String variable : new String[] {"HOSTNAME", "COMPUTERNAME"}) {
                String value = System.getenv(variable);
                if (value != null && !value.isBlank()) {
                    return value.strip();
                }
            }
            try {
                String name = InetAddress.getLocalHost().getHostName();
                return name == null || name.isBlank() ? null : name;
            } catch (Exception e) {
                return null;
            }
        }
    }

    /** Options for a {@link LumberClient}. */
    public static final class Builder {
        private final String apiKey;
        private String endpoint = DEFAULT_ENDPOINT;
        private String environment;
        private boolean autoContext = true;
        private boolean caller;
        private int callerDepth;
        private Duration timeout = Duration.ofSeconds(10);
        private int maxQueue = 10_000;
        private Duration shutdownTimeout = Duration.ofSeconds(5);
        private HttpClient httpClient;
        private Consumer<String> warnings;

        private Builder(String apiKey) {
            this.apiKey = apiKey;
        }

        /**
         * Sends logs to another Lumber API, for testing against another server.
         *
         * @param endpoint an http or https URL
         * @return this builder
         */
        public Builder endpoint(String endpoint) {
            this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
            return this;
        }

        /**
         * Sends {@code environment} (production, staging) with every log, as {@code env} in its context.
         *
         * @param environment the environment's name
         * @return this builder
         */
        public Builder environment(String environment) {
            this.environment = environment;
            return this;
        }

        /**
         * Whether to send the process id and hostname (and the environment) with every log, in its context. On unless
         * set.
         *
         * @param autoContext false to leave them out
         * @return this builder
         */
        public Builder autoContext(boolean autoContext) {
            this.autoContext = autoContext;
            return this;
        }

        /**
         * Whether to send the file, line and method that logged with each log, in its context. Off unless set, since
         * finding them costs a little on every log.
         *
         * @param caller true to send them
         * @return this builder
         */
        public Builder caller(boolean caller) {
            this.caller = caller;
            return this;
        }

        /**
         * Stack frames to skip when finding the caller, one for each wrapper around the client. 0 unless set.
         *
         * @param callerDepth frames to skip
         * @return this builder
         */
        public Builder callerDepth(int callerDepth) {
            if (callerDepth < 0) {
                throw new IllegalArgumentException("[lumber] callerDepth can't be negative");
            }
            this.callerDepth = callerDepth;
            return this;
        }

        /**
         * How long to wait for Lumber to answer one log. 10 seconds unless set.
         *
         * @param timeout the time limit
         * @return this builder
         */
        public Builder timeout(Duration timeout) {
            if (timeout == null || timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("[lumber] timeout must be positive");
            }
            this.timeout = timeout;
            return this;
        }

        /**
         * How many logs can wait to send, while Lumber can't be reached, before new ones are dropped. 10,000 unless
         * set.
         *
         * @param maxQueue the most logs to hold
         * @return this builder
         */
        public Builder maxQueue(int maxQueue) {
            if (maxQueue < 1) {
                throw new IllegalArgumentException("[lumber] maxQueue must be at least 1");
            }
            this.maxQueue = maxQueue;
            return this;
        }

        /**
         * How long logs still queued when the program exits get to send, from a shutdown hook. 5 seconds unless set;
         * zero adds no hook.
         *
         * @param shutdownTimeout the time limit
         * @return this builder
         */
        public Builder shutdownTimeout(Duration shutdownTimeout) {
            if (shutdownTimeout == null || shutdownTimeout.isNegative()) {
                throw new IllegalArgumentException("[lumber] shutdownTimeout can't be negative");
            }
            this.shutdownTimeout = shutdownTimeout;
            return this;
        }

        /**
         * Sends logs with your own {@link HttpClient}, for a proxy or custom TLS. The client's {@link #timeout} still
         * applies to each log.
         *
         * @param httpClient the HTTP client
         * @return this builder
         */
        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        /**
         * Receives the client's warnings (a log refused, Lumber unreachable) in place of standard error. Pass
         * {@code message -> {}} to silence them. Don't log them back to Lumber.
         *
         * @param warnings called with each warning, from whichever thread had it
         * @return this builder
         */
        public Builder warnings(Consumer<String> warnings) {
            this.warnings = warnings;
            return this;
        }

        /**
         * Makes the client, which starts its background thread.
         *
         * @return the client
         * @throws IllegalArgumentException when the API key is missing or has no valid encryption key, or the endpoint
         *     isn't an http or https URL
         */
        public LumberClient build() {
            Contract.ApiKey parts = Contract.splitApiKey(apiKey);
            if (parts.token.isEmpty()) {
                throw new IllegalArgumentException(
                        "[lumber] Missing API key. Create one on your app's Secrets screen in Lumber.");
            }
            if (parts.encryptionKey == null || parts.encryptionKey.isEmpty()) {
                throw new IllegalArgumentException("[lumber] The API key has no encryption key. Copy a new API key"
                        + " from your app's Secrets screen in Lumber, which includes it.");
            }
            byte[] raw;
            try {
                raw = Base64.getDecoder().decode(parts.encryptionKey);
            } catch (IllegalArgumentException e) {
                raw = new byte[0];
            }
            if (raw.length != Contract.KEY_BYTES) {
                throw new IllegalArgumentException("[lumber] The API key's encryption key should be "
                        + Contract.KEY_BYTES + " bytes of base64, got " + raw.length
                        + ". Copy the API key again from your app's Secrets screen in Lumber.");
            }
            String url = endpoint.strip();
            while (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }
            if (!url.startsWith("https://") && !url.startsWith("http://")) {
                throw new IllegalArgumentException(
                        "[lumber] The endpoint must be an http or https URL, got \"" + endpoint + "\".");
            }
            return new LumberClient(this, new SecretKeySpec(raw, "AES"), parts.token, url);
        }
    }
}
