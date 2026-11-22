package org.oneticket.notifications

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandlers
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

// A real PostgreSQL instead of the developer's one: the test passes on GitHub too, where no database
// and no password exist. No RabbitMQ container is needed until the first @RabbitListener exists.
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["SPRING_DATASOURCE_PASSWORD=test"]
)
class NotificationsApplicationTests {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }

    @Value("\${local.server.port}")
    private var port: Int = 0

    private val client = HttpClient.newHttpClient()

    private fun get(path: String, headers: Map<String, String> = emptyMap()): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://localhost:$port$path")).GET()
        headers.forEach { (name, value) -> builder.header(name, value) }
        return client.send(builder.build(), BodyHandlers.ofString())
    }

    @Test
    fun contextLoads() {
    }

    @Test
    fun `liveness and readiness are UP without RabbitMQ`() {
        assertEquals(200, get("/actuator/health/liveness").statusCode())
        assertEquals(200, get("/actuator/health/readiness").statusCode())
    }

    @Test
    fun `unknown route gives a problem+json 404`() {
        val r = get("/api/v1/nothing-here")
        assertEquals(404, r.statusCode())
        assertTrue(r.headers().firstValue("Content-Type").get().startsWith("application/problem+json"))
        assertTrue(r.body().contains("\"status\":404"))
        assertTrue(r.body().contains("\"instance\":\"/api/v1/nothing-here\""))
    }

    @Test
    fun `every response carries a request id`() {
        assertTrue(get("/api/v1/nothing-here").headers().firstValue("X-Request-Id").isPresent)
    }

    @Test
    fun `the caller's request id is echoed, an unsafe one is replaced`() {
        val ok = get("/api/v1/nothing-here", mapOf("X-Request-Id" to "req-123"))
        assertEquals("req-123", ok.headers().firstValue("X-Request-Id").get())
        val bad = get("/api/v1/nothing-here", mapOf("X-Request-Id" to "bad id with spaces"))
        assertNotEquals("bad id with spaces", bad.headers().firstValue("X-Request-Id").get())
    }
}
