package dev.tidepool.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthEndpointsTest(
    @Value("\${local.server.port}") private val port: Int,
) {
    private val http = HttpClient.newHttpClient()

    @ParameterizedTest
    @ValueSource(strings = ["/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"])
    fun `health endpoint reports UP`(path: String) {
        val response = http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("\"status\":\"UP\""), "body was: ${response.body()}")
    }
}
