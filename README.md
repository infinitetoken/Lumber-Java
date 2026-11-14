# Lumber for Java and Kotlin

Java and Kotlin client for [Lumber](https://lumber.infinitetoken.com): cloud logging with end-to-end encryption. Every log is encrypted with AES-256-GCM before it leaves your process, so Lumber's servers store only ciphertext plus each log's level (kept readable for filtering and push alerts). You read your logs in the Lumber app, which decrypts them on your device.

No dependencies beyond the JDK: it sends with `java.net.http` and encrypts with `javax.crypto`. Logs are sent from a background thread, so logging never waits on the network, and `lumber-logback` plugs it into SLF4J and Logback.

## Installation

Gradle:

```kotlin
dependencies {
    implementation("com.infinitetoken:lumber:1.0.0")
}
```

Maven:

```xml
<dependency>
  <groupId>com.infinitetoken</groupId>
  <artifactId>lumber</artifactId>
  <version>1.0.0</version>
</dependency>
```

Java 11 or later, on any JVM language. Not Android, which has no `java.net.http`.

## Quick start

```java
import com.infinitetoken.lumber.LumberClient;

LumberClient lumber = LumberClient.create(System.getenv("LUMBER_API_KEY"));

lumber.info("Server started", Map.of("port", 8080));
lumber.warn("Rate limit approaching", Map.of("requestId", "req-123", "userId", 456));
lumber.error("Payment failed", Map.of("traceId", "trace-abc", "orderId", 789), exception);
```

In Kotlin:

```kotlin
val lumber = LumberClient.create(System.getenv("LUMBER_API_KEY"))

lumber.info("Server started", mapOf("port" to 8080))
lumber.error("Payment failed", mapOf("orderId" to 789), e)
```

Make one client at startup and share it: it's safe to use from any thread. Logs still queued when the program exits get up to 5 seconds to send, from a shutdown hook. `close()` sends them and stops the client's thread, and `flush(Duration)` waits for them without stopping.

## Getting your API key

Create an app in the Lumber app and it takes you straight to adding an API key. The key is shown only once, so copy it right away. Any device holding the app's key can add more from the app's **Secrets** screen, one per deployment if you like.

An API key is two values joined by a dot: a token, then the app's encryption key. The client sends only the token to Lumber and encrypts every log with the rest, so the encryption key never leaves your process. Keep the API key in an environment variable or your secrets manager, never in source control.

## With SLF4J and Logback

`lumber-logback` is a Logback appender, so the loggers your code and its libraries already use through SLF4J send to Lumber too. Spring Boot logs through Logback unless you've changed it.

```kotlin
dependencies {
    implementation("com.infinitetoken:lumber-logback:1.0.0")
}
```

Add the appender to `logback.xml`:

```xml
<configuration>
  <appender name="LUMBER" class="com.infinitetoken.lumber.logback.LumberAppender">
    <apiKey>${LUMBER_API_KEY}</apiKey>
    <environment>production</environment>
  </appender>

  <root level="INFO">
    <appender-ref ref="LUMBER" />
  </root>
</configuration>
```

In Spring Boot, the same goes in `logback-spring.xml`, beside Spring's console output:

```xml
<configuration>
  <include resource="org/springframework/boot/logging/logback/defaults.xml" />
  <include resource="org/springframework/boot/logging/logback/console-appender.xml" />

  <appender name="LUMBER" class="com.infinitetoken.lumber.logback.LumberAppender">
    <apiKey>${LUMBER_API_KEY}</apiKey>
  </appender>

  <root level="INFO">
    <appender-ref ref="CONSOLE" />
    <appender-ref ref="LUMBER" />
  </root>
</configuration>
```

Then log as you already do:

```java
private static final Logger log = LoggerFactory.getLogger(Billing.class);

log.info("Report ready in {}s", 2.4);
log.atWarn().addKeyValue("requestId", "req-123").addKeyValue("rows", 1500).log("Report slow");
log.error("Payment failed", exception);
log.error(MarkerFactory.getMarker("FATAL"), "Database unreachable");
```

Each event becomes a log at the matching level: TRACE and DEBUG are DEBUG, and an event with a marker named `FATAL` is FATAL. Its message is the formatted message. The MDC's entries and the event's key-value pairs go into the log's metadata, except `requestId` and `traceId` (or `request_id` and `trace_id`), which are the log's own fields, and a `context` map, which goes into its context. So the `traceId` Spring Boot's tracing puts in the MDC becomes the log's trace ID. The logger and thread go into the context too, and an exception goes into the metadata as `exception` and `stackTrace`. The appender encrypts each event where it's logged and sends it from the client's thread, so it needs no `AsyncAppender` in front of it.

The appender takes the client's options as elements: `apiKey`, `environment`, `endpoint`, `autoContext`, `timeout` (`10 seconds`), `shutdownTimeout` (`5 seconds`) and `maxQueue`, plus `includeCallerData` (send the file, line and method that logged; off unless set, since Logback finds them from a stack trace on every event) and `includeMdc` (on unless set). Give it a `ThresholdFilter` to send fewer levels than your console shows. To share one client between the appender and your own calls, make the appender in code and pass it the client with `setClient`.

## Log methods

| Method | Level |
|--------|-------|
| `debug(message)`, `debug(message, data)` | DEBUG |
| `info(message)`, `info(message, data)` | INFO |
| `warn(message)`, `warn(message, data)`, `warn(message, exception)`, `warn(message, data, exception)` | WARN |
| `error(...)`, with the same arguments as `warn` | ERROR |
| `fatal(...)`, with the same arguments as `warn` | FATAL. It doesn't exit |
| `log(level, message, data, exception)` | Any `LogLevel`; `data` and `exception` can be null |
| `flush(timeout)` | Waits for queued logs to send; `false` if the time ran out first |
| `close()`, `close(timeout)` | Sends what's queued, waiting for the timeout at most, and stops |

Every log method returns at once and never throws.

## Data

A map's keys become the log's fields:

```java
lumber.info("User signed in", Map.of(
    "requestId", "req-abc",                   // the log's request ID
    "traceId", "trace-xyz",                   // the log's trace ID
    "context", Map.of("region", "us-east"),   // merged into its context
    "metadata", Map.of("plan", "pro"),        // merged into its metadata
    "userId", 123                             // any other key goes into metadata
));
```

An explicit `metadata` key wins over a colliding key. An exception goes into metadata as `exception` (`java.lang.IllegalStateException: card declined`) and `stackTrace`. Values JSON has no type for are sent as text (an `Instant` or `LocalDate` as ISO 8601, a `UUID` as its text, an enum as its name), and a NaN as null. Everything, message included, is encrypted.

## Options

```java
LumberClient lumber = LumberClient.builder(System.getenv("LUMBER_API_KEY"))
    .environment("production")              // sent as `env` in every log's context
    .autoContext(true)                      // send the process id and hostname with every log
    .caller(false)                          // also send the file, line and method that logged
    .callerDepth(0)                         // frames to skip for `caller`, one per wrapper around Lumber
    .timeout(Duration.ofSeconds(10))        // how long to wait for Lumber to answer one log
    .maxQueue(10_000)                       // logs waiting to send before new ones are dropped
    .shutdownTimeout(Duration.ofSeconds(5)) // how long queued logs get when the program exits; zero adds no hook
    .httpClient(httpClient)                 // your own java.net.http.HttpClient, for a proxy or custom TLS
    .warnings(message -> { /* your logger */ })
    .build();
```

`build()` throws `IllegalArgumentException` when the API key is missing or has no valid encryption key.

## Delivery

Logs are sent in order, one `POST /logs` per log, from a daemon thread over connections kept open between logs. A network error or a 5xx counts as a failed attempt: each log gets 5, waiting 1, 2, 4, then 8 seconds between them, and is then dropped with a warning. A log Lumber refuses (a 4xx, such as an invalid API key, or a 413 for a log over 16 KB once encrypted) is dropped at once with a warning, since sending it again won't help. Errors always get through: past your team's monthly logs, Lumber still takes error and fatal logs, and answers the rest 200 without keeping them, so the client carries on with no warning (the Lumber app shows when your team is at its limit). At twice your team's monthly logs Lumber answers 429: the client warns once and drops logs until the month turns. While Lumber can't be reached, up to `maxQueue` logs wait; past that, new ones are dropped. Warnings go to standard error unless you pass `warnings`, never through a logging library that could send them back to Lumber.

## Serverless

A serverless platform can freeze a function as soon as it returns, with logs still queued, and a frozen function's threads don't run. Make the client once, outside the handler, and flush it before returning, with a time limit so a slow answer can't hold the function to its maximum duration. On AWS Lambda:

```java
public class Handler implements RequestHandler<Map<String, Object>, String> {
    private static final LumberClient lumber = LumberClient.create(System.getenv("LUMBER_API_KEY"));

    @Override
    public String handleRequest(Map<String, Object> event, Context context) {
        lumber.info("Handled request", Map.of("requestId", context.getAwsRequestId()));
        lumber.flush(Duration.ofSeconds(10));
        return "ok";
    }
}
```

## Development

```bash
./gradlew build
```

That runs the tests on the JDK running Gradle (17 or later); `./gradlew test -PtestJava=11` runs them on Java 11, downloading it if it isn't installed, as CI does for each supported version. `kotlin-usage` is Kotlin code using the library as a Kotlin app would, and isn't published.

`lumber/src/test/resources/contract` holds [Lumber-Kit](https://github.com/infinitetoken/lumber-kit)'s contract at the version in its `VERSION` file: a JSON Schema of the wire format and known answers for API keys, encryption, payloads and delivery, which Lumber's Node, Python and Go clients are tested against too. `scripts/sync-contract.sh 0.8.0` moves it to a new kit version.

## License

MIT. See `LICENSE`.
