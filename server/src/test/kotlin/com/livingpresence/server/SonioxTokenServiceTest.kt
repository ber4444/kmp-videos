package com.livingpresence.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the service asks Soniox for, and what it refuses to pass back.
 *
 * The request options are the security control, not decoration: without
 * `single_use` and `max_session_duration_seconds` a key skimmed off the wire is
 * good for as many unbounded sessions as its TTL allows.
 */
class SonioxTokenServiceTest {

    @Test
    fun mintsASingleUseTranscriptionScopedKeyWithBothBounds() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val service = service(requests)

        val minted = service.mint()

        assertEquals("temp-key-123", minted.apiKey)
        val body = Json.parseToJsonElement(requests.single().bodyText()).jsonObject
        assertEquals("transcribe_websocket", body.getValue("usage_type").jsonPrimitive.content)
        assertEquals(true, body.getValue("single_use").jsonPrimitive.content.toBoolean())
        assertEquals(60, body.getValue("expires_in_seconds").jsonPrimitive.content.toInt())
        assertEquals(
            3_600,
            body.getValue("max_session_duration_seconds").jsonPrimitive.content.toInt(),
            "expiry gates the connect; only this caps the stream that follows it",
        )
    }

    @Test
    fun sendsTheLongLivedKeyAsABearerTokenAndNowhereElse() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val service = service(requests)

        service.mint()

        val request = requests.single()
        assertEquals("Bearer $LONG_LIVED_KEY", request.headers[HttpHeaders.Authorization])
        assertFalse(
            request.url.toString().contains(LONG_LIVED_KEY),
            "a key in the URL lands in every proxy and access log on the path",
        )
        assertFalse(request.bodyText().contains(LONG_LIVED_KEY))
    }

    @Test
    fun aRejectionFromSonioxNeverCarriesTheProviderBodyOutwards() = runTest {
        val service = service(
            body = """{"error":"invalid api key: sk-live-abc"}""",
            status = HttpStatusCode.Unauthorized,
        )

        val failure = assertFailsWith<SonioxException> { service.mint() }

        assertEquals(401, failure.status)
        assertFalse(
            failure.message.orEmpty().contains("sk-live-abc"),
            "the provider can quote our request back at us; that must not reach a client or a log",
        )
        assertTrue(failure.message.orEmpty().contains("401"))
    }

    private fun service(
        captured: MutableList<HttpRequestData> = mutableListOf(),
        body: String = """{"api_key":"temp-key-123","expires_at":"2026-08-31T12:00:00Z"}""",
        status: HttpStatusCode = HttpStatusCode.Created,
    ): SonioxTokenService {
        val engine = MockEngine { request ->
            captured += request
            respond(
                content = ByteReadChannel(body),
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return SonioxTokenService(HttpClient(engine), testConfig())
    }
}

internal const val LONG_LIVED_KEY = "long-lived-secret"

internal fun testConfig(
    rateLimit: Int = 30,
    allowedOrigins: List<String> = emptyList(),
) = ServerConfig(
    sonioxApiKey = LONG_LIVED_KEY,
    port = 0,
    allowedOrigins = allowedOrigins,
    keyTtlSeconds = ServerConfig.DEFAULT_KEY_TTL_SECONDS,
    maxSessionSeconds = ServerConfig.DEFAULT_MAX_SESSION_SECONDS,
    rateLimit = rateLimit,
    rateLimitRefillSeconds = ServerConfig.DEFAULT_RATE_LIMIT_REFILL_SECONDS,
)

internal suspend fun HttpRequestData.bodyText(): String =
    (body as io.ktor.http.content.TextContent).text
