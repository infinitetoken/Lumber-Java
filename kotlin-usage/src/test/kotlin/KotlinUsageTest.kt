import com.infinitetoken.lumber.FakeLumber
import com.infinitetoken.lumber.LogLevel
import com.infinitetoken.lumber.LumberClient
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KotlinUsageTest {
    @Test
    fun readsWellFromKotlin() {
        FakeLumber().use { server ->
            LumberClient.builder(FakeLumber.API_KEY)
                .endpoint(server.url())
                .environment("production")
                .shutdownTimeout(Duration.ZERO)
                .build()
                .use { lumber ->
                    lumber.info("Server started", mapOf("port" to 8080))
                    lumber.warn("Rate limit approaching", mapOf("requestId" to "req-123", "userId" to 456))
                    try {
                        error("card declined")
                    } catch (e: IllegalStateException) {
                        lumber.error("Payment failed", mapOf("orderId" to 789), e)
                    }
                    lumber.log(LogLevel.FATAL, "Database unreachable", null, null)
                    assertTrue(lumber.flush(Duration.ofSeconds(5)))
                }

            val logs = server.logs()
            assertEquals(
                listOf("Server started", "Rate limit approaching", "Payment failed", "Database unreachable"),
                logs.map { it["message"] },
            )
            assertEquals(listOf(1L, 2L, 3L, 4L), logs.map { it["level"] })
            assertEquals(mapOf("port" to 8080L), logs[0]["metadata"])
            assertEquals("req-123", logs[1]["requestId"])
            assertEquals("production", (logs[0]["context"] as Map<*, *>)["env"])
            val metadata = logs[2]["metadata"] as Map<*, *>
            assertEquals(789L, metadata["orderId"])
            assertEquals("java.lang.IllegalStateException: card declined", metadata["exception"])
        }
    }
}
