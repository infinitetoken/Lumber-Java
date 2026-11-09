package com.infinitetoken.lumber;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.IntToLongFunction;

/**
 * Sends logs to POST /logs from one daemon thread, in the order they were logged, over the HTTP client's kept-alive
 * connections. What each answer means and how long to wait are Lumber-Kit's (see {@link Contract}).
 */
final class Sender {
    private final HttpClient http;
    private final boolean ownsHttp;
    private final URI uri;
    private final String authorization;
    private final String userAgent;
    private final Duration timeout;
    private final Consumer<String> warn;
    private final int maxQueue;

    private final BlockingQueue<byte[]> queue;
    private final AtomicLong pending = new AtomicLong();
    private final Object idle = new Object();
    private final AtomicBoolean fullWarned = new AtomicBoolean();
    private final CountDownLatch stop = new CountDownLatch(1);
    private volatile boolean closed;
    private volatile boolean paused;
    volatile long pausedUntil; // System.nanoTime()

    /** How long to wait after a log's nth failure, in milliseconds. The tests make it 0. */
    volatile IntToLongFunction retryWait = Contract::retryDelayMs;

    final Thread thread;

    Sender(
            HttpClient http,
            boolean ownsHttp,
            String endpoint,
            String token,
            String userAgent,
            Duration timeout,
            int maxQueue,
            Consumer<String> warn) {
        this.http = http;
        this.ownsHttp = ownsHttp;
        this.uri = URI.create(endpoint + Contract.INGEST_PATH);
        this.authorization = "Bearer " + token;
        this.userAgent = userAgent;
        this.timeout = timeout;
        this.maxQueue = maxQueue;
        this.warn = warn;
        this.queue = new ArrayBlockingQueue<>(maxQueue);
        this.thread = new Thread(this::run, "lumber");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    /** Never blocks: a log that doesn't fit is dropped, with one warning until the queue has room again. */
    void enqueue(byte[] body) {
        if (closed || isPaused()) {
            return;
        }
        pending.incrementAndGet();
        if (queue.offer(body)) {
            fullWarned.set(false);
        } else {
            done();
            if (!fullWarned.getAndSet(true)) {
                warn.accept("dropping logs: " + maxQueue + " are already waiting to send");
            }
        }
    }

    /** Waits until every queued log is sent or dropped: false when {@code timeout} ran out first (null waits on). */
    boolean flush(Duration timeout) {
        long deadline = timeout == null ? 0 : System.nanoTime() + nanos(timeout);
        synchronized (idle) {
            while (pending.get() > 0) {
                if (closed) {
                    return false;
                }
                try {
                    if (timeout == null) {
                        idle.wait();
                    } else {
                        long remaining = deadline - System.nanoTime();
                        if (remaining <= 0) {
                            return false;
                        }
                        TimeUnit.NANOSECONDS.timedWait(idle, remaining);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }

    /** Sends what's queued, waiting for {@code timeout} at most, and stops. Logs after this are dropped. */
    boolean close(Duration timeout) {
        long deadline = timeout == null ? 0 : System.nanoTime() + nanos(timeout);
        boolean flushed = flush(timeout);
        closed = true;
        stop.countDown();
        thread.interrupt();
        if (Thread.currentThread() != thread) {
            try {
                long remaining = timeout == null ? 1_000 : TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                thread.join(Math.max(1, remaining));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (ownsHttp && !thread.isAlive() && http instanceof AutoCloseable) {
            // Java 21 and later: let the HTTP client's own threads and connections go now, not when it's collected
            try {
                ((AutoCloseable) http).close();
            } catch (Exception e) {
                // Nothing is waiting on it
            }
        }
        return flushed;
    }

    boolean isClosed() {
        return closed;
    }

    private boolean isPaused() {
        return paused && System.nanoTime() - pausedUntil < 0;
    }

    private void done() {
        if (pending.decrementAndGet() <= 0) {
            synchronized (idle) {
                idle.notifyAll();
            }
        }
    }

    private void run() {
        try {
            while (!closed) {
                byte[] body = queue.take();
                try {
                    deliver(body);
                } catch (RuntimeException e) {
                    warn.accept("send error: " + describe(e));
                } finally {
                    done();
                }
            }
        } catch (InterruptedException e) {
            // close() stops the thread
        }
    }

    private void deliver(byte[] body) throws InterruptedException {
        for (int failed = 0; ; ) {
            if (isPaused()) {
                return;
            }
            Answer answer = post(body);
            switch (Contract.deliveryOutcome(answer.status)) {
                case SENT:
                    return;
                case PAUSE:
                    pause(answer);
                    return;
                case DROP:
                    // A bad key or a malformed log won't go through on a later try
                    String message = answer.message();
                    warn.accept("log refused: HTTP " + answer.status + (message == null ? "" : " (" + message + ")"));
                    return;
                case RETRY:
                default:
                    break;
            }
            warn.accept(
                    answer.error != null
                            ? "send error: " + describe(answer.error)
                            : "send failed: HTTP " + answer.status);
            failed++;
            if (failed >= Contract.MAX_ATTEMPTS) {
                warn.accept("dropping log after " + Contract.MAX_ATTEMPTS + " failed attempts");
                return;
            }
            if (stop.await(retryWait.applyAsLong(failed), TimeUnit.MILLISECONDS)) {
                return;
            }
        }
    }

    /** Sends one log: the answer's status (0 when there was none), its Retry-After and body. */
    private Answer post(byte[] body) throws InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Authorization", authorization)
                .header("Content-Type", "application/json")
                .header("User-Agent", userAgent)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            return new Answer(
                    response.statusCode(),
                    response.headers().firstValue("Retry-After").orElse(null),
                    response.body(),
                    null);
        } catch (IOException e) {
            return new Answer(0, null, null, e);
        }
    }

    /**
     * Stops sending until the API's Retry-After (the start of the next month, for the monthly cap), dropping what's
     * queued, with one warning for the whole pause.
     */
    private void pause(Answer answer) {
        long waitMs = Contract.PAUSE_MS;
        if (answer.retryAfter != null) {
            try {
                double seconds = Double.parseDouble(answer.retryAfter.trim());
                if (seconds > 0 && Double.isFinite(seconds)) {
                    waitMs = (long) Math.min(seconds * 1000, 1e15);
                }
            } catch (NumberFormatException e) {
                // An HTTP date, or nothing usable: the contract's pause
            }
        }
        pausedUntil = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs);
        paused = true;
        while (queue.poll() != null) {
            done();
        }
        String message = answer.message();
        if (message == null) {
            message = "Lumber is refusing logs for now (HTTP 429).";
        }
        Instant until = Instant.now().plusMillis(waitMs).truncatedTo(ChronoUnit.SECONDS);
        warn.accept(message + " Dropping logs until " + until + ".");
    }

    static String describe(Throwable error) {
        String text = error.getClass().getSimpleName() + (error.getMessage() == null ? "" : ": " + error.getMessage());
        Throwable cause = error.getCause();
        if (error.getMessage() == null && cause != null && cause != error) {
            return text + " (" + describe(cause) + ")";
        }
        return text;
    }

    private static long nanos(Duration duration) {
        try {
            return Math.max(0, duration.toNanos());
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE / 2;
        }
    }

    private static final class Answer {
        final int status;
        final String retryAfter;
        final byte[] body;
        final IOException error;

        Answer(int status, String retryAfter, byte[] body, IOException error) {
            this.status = status;
            this.retryAfter = retryAfter;
            this.body = body;
            this.error = error;
        }

        /** The API's error message ({@code { message }}), if it sent one. */
        String message() {
            if (body == null || body.length == 0) {
                return null;
            }
            try {
                Object parsed = Json.parse(new String(body, StandardCharsets.UTF_8));
                Object message = parsed instanceof Map ? ((Map<?, ?>) parsed).get("message") : null;
                return message instanceof String && !((String) message).isEmpty() ? (String) message : null;
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }
}
