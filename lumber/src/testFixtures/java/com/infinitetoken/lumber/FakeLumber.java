package com.infinitetoken.lumber;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * A stand-in for the Lumber API: it records each POST /logs and answers from a script (201 once it runs out). For this
 * repository's tests only.
 */
public final class FakeLumber implements AutoCloseable {
    static {
        // The JDK's server otherwise waits on Nagle's algorithm between a response's headers and body, about 15ms a
        // request. Read once, when the server's classes load.
        System.setProperty("sun.net.httpserver.nodelay", "true");
    }

    /** The token half of {@link #API_KEY}. */
    public static final String TOKEN = "3f9c0e7a1b2d4c6e8f0a1b2c3d4e5f60";

    /** The encryption key half of {@link #API_KEY}: the contract's first encryption vector's key. */
    public static final String KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    /** An API key for the tests. */
    public static final String API_KEY = TOKEN + "." + KEY;

    /** One request the server received. */
    public static final class Request {
        public final Map<String, Object> body;
        public final Map<String, List<String>> headers;
        public final String remote;

        Request(Map<String, Object> body, Map<String, List<String>> headers, String remote) {
            this.body = body;
            this.headers = headers;
            this.remote = remote;
        }

        public String header(String name) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                    return entry.getValue().get(0);
                }
            }
            return null;
        }
    }

    private static final class Answer {
        final int status;
        final String retryAfter;
        final String message;

        Answer(int status, String retryAfter, String message) {
            this.status = status;
            this.retryAfter = retryAfter;
            this.message = message;
        }
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final List<Request> requests = new ArrayList<>();
    private final Deque<Answer> answers = new ArrayDeque<>();
    private final Semaphore arrived = new Semaphore(0);
    private volatile CountDownLatch held;

    public FakeLumber() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    /** The server's URL, for {@link LumberClient.Builder#endpoint(String)}. */
    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** A client sending here, with no automatic context, warnings to {@code warnings}, and retries at once. */
    public LumberClient client(Consumer<String> warnings) {
        return client(LumberClient.builder(API_KEY), warnings);
    }

    /** {@link #client(Consumer)} from a builder with other options set. */
    public LumberClient client(LumberClient.Builder builder, Consumer<String> warnings) {
        LumberClient client = builder.endpoint(url())
                .autoContext(false)
                .shutdownTimeout(Duration.ZERO)
                .warnings(warnings)
                .build();
        noRetryWait(client);
        return client;
    }

    /** Tries a failed log again at once, not after 1s, 2s, 4s. */
    public static void noRetryWait(LumberClient client) {
        client.sender.retryWait = failed -> 0;
    }

    /** Answers the next request with {@code status}, a Retry-After header and an error message when not null. */
    public synchronized void answer(int status, String retryAfter, String message) {
        answers.add(new Answer(status, retryAfter, message));
    }

    /** Holds every request until {@link #release()}. */
    public void hold() {
        held = new CountDownLatch(1);
    }

    public void release() {
        CountDownLatch latch = held;
        held = null;
        if (latch != null) {
            latch.countDown();
        }
    }

    /** Waits until a request has arrived (held or not). */
    public void awaitArrival() throws InterruptedException {
        if (!arrived.tryAcquire(5, TimeUnit.SECONDS)) {
            throw new AssertionError("no request arrived");
        }
    }

    public synchronized List<Request> requests() {
        return new ArrayList<>(requests);
    }

    /** The decrypted payloads received, in order. */
    public List<Map<String, Object>> logs() {
        List<Map<String, Object>> logs = new ArrayList<>();
        for (Request request : requests()) {
            logs.add(decrypt(request.body));
        }
        return logs;
    }

    public List<String> messages() {
        List<String> messages = new ArrayList<>();
        for (Map<String, Object> log : logs()) {
            messages.add((String) log.get("message"));
        }
        return messages;
    }

    /** How many connections the requests came over. */
    public int connections() {
        Set<String> remotes = new HashSet<>();
        for (Request request : requests()) {
            remotes.add(request.remote);
        }
        return remotes.size();
    }

    @Override
    public void close() {
        release();
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            byte[] raw;
            try (InputStream in = exchange.getRequestBody()) {
                raw = in.readAllBytes();
            }
            if (!"POST".equals(exchange.getRequestMethod()) || !"/logs".equals(exchange.getRequestURI().getPath())) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            arrived.release();
            CountDownLatch latch = held;
            if (latch != null) {
                try {
                    latch.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            Answer answer;
            synchronized (this) {
                requests.add(new Request(
                        asObject(parseJson(new String(raw, StandardCharsets.UTF_8))),
                        Map.copyOf(exchange.getRequestHeaders()),
                        exchange.getRemoteAddress().toString()));
                answer = answers.poll();
            }
            if (answer == null) {
                answer = new Answer(201, null, null);
            }
            byte[] reply = answer.message != null
                    ? Json.encode(Map.of("message", answer.message))
                    : answer.status < 300 ? "{\"data\":{}}".getBytes(StandardCharsets.UTF_8) : new byte[0];
            if (answer.retryAfter != null) {
                exchange.getResponseHeaders().set("Retry-After", answer.retryAfter);
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(answer.status, reply.length == 0 ? -1 : reply.length);
            if (reply.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(reply);
                }
            }
        } finally {
            exchange.close();
        }
    }

    /** Opens POST /logs' body with {@link #KEY}: the payload's JSON, parsed. */
    public static Map<String, Object> decrypt(Map<String, Object> body) {
        return decrypt(body, KEY);
    }

    /** Opens POST /logs' body with an encryption key: the payload's JSON, parsed. */
    public static Map<String, Object> decrypt(Map<String, Object> body, String key) {
        Map<String, Object> encrypted = asObject(body.get("encrypted"));
        Base64.Decoder base64 = Base64.getDecoder();
        byte[] nonce = base64.decode((String) encrypted.get("nonce"));
        byte[] sealed = base64.decode((String) encrypted.get("payload"));
        // Lumber puts the tag first, Java expects it last
        byte[] javaOrder = new byte[sealed.length];
        System.arraycopy(sealed, 16, javaOrder, 0, sealed.length - 16);
        System.arraycopy(sealed, 0, javaOrder, sealed.length - 16, 16);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(base64.decode(key), "AES"), new GCMParameterSpec(128, nonce));
            return asObject(parseJson(new String(cipher.doFinal(javaOrder), StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new AssertionError("decrypting: " + e, e);
        }
    }

    /** Lumber's own JSON reader: maps in order, lists, strings, booleans, null, and Long or Double numbers. */
    public static Object parseJson(String text) {
        return Json.parse(text);
    }

    /** Lumber's own JSON writer. */
    public static String toJson(Object value) {
        return Json.stringify(value);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object value) {
        if (!(value instanceof Map)) {
            throw new AssertionError("not a JSON object: " + value);
        }
        return (Map<String, Object>) value;
    }
}
