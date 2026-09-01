package com.livingpresence.inner.circle.squared.transcription

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The client half of the credential change: what the app asks the token service
 * for, and how it behaves when the service says no.
 */
class SonioxKeyProviderTest {

    @Test
    fun postsToTheRouteTheServerActuallyServes() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val provider = provider(requests, baseUrl = "https://tokens.example")

        val key = provider.fetch()

        assertEquals("temp-key-123", key)
        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals(
            "https://tokens.example/v1/soniox/temporary-key",
            request.url.toString(),
            "must match TEMPORARY_KEY_PATH in :server — nothing else pins these together",
        )
    }

    @Test
    fun toleratesATrailingSlashOnTheConfiguredUrl() = runTest {
        // The value is hand-entered into secrets.properties, so the shape that
        // would otherwise produce a double slash and a 404 has to be absorbed here.
        val requests = mutableListOf<HttpRequestData>()

        provider(requests, baseUrl = "https://tokens.example/").fetch()

        assertEquals("https://tokens.example/v1/soniox/temporary-key", requests.single().url.toString())
    }

    @Test
    fun anUnconfiguredBuildAsksForNothingAndReturnsBlank() = runTest {
        val requests = mutableListOf<HttpRequestData>()

        val key = provider(requests, baseUrl = "").fetch()

        assertEquals("", key)
        assertTrue(requests.isEmpty(), "an unconfigured build must not dial an empty host")
    }

    @Test
    fun sendsTheDiscordTokenSoTheServiceCanCheckMembership() = runTest {
        val requests = mutableListOf<HttpRequestData>()

        provider(requests, baseUrl = "https://tokens.example", token = "member-token").fetch()

        assertEquals("Bearer member-token", requests.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun withoutASignInItRefusesLocallyInsteadOfAskingTheService() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val provider = provider(requests, baseUrl = "https://tokens.example", token = "")

        val failure = assertFailsWith<TranscriptionKeyException> { provider.fetch() }

        assertEquals(403, failure.status, "terminal: reconnecting cannot produce a sign-in")
        assertTrue(requests.isEmpty(), "a request the service is certain to refuse is not worth making")
    }

    @Test
    fun aRefusalCarriesTheStatusTheRetryDecisionNeeds() = runTest {
        val provider = provider(baseUrl = "https://tokens.example", status = HttpStatusCode.Forbidden)

        val failure = assertFailsWith<TranscriptionKeyException> { provider.fetch() }

        assertEquals(403, failure.status)
        assertTrue(
            failure.message.orEmpty().contains("403"),
            "WebSocketTranscriber.isTerminalFailure reads the message, so the status has to be in it",
        )
    }

    private fun provider(
        captured: MutableList<HttpRequestData> = mutableListOf(),
        baseUrl: String,
        token: String = "discord-token",
        status: HttpStatusCode = HttpStatusCode.Created,
        body: String = """{"api_key":"temp-key-123","expires_at":"2026-08-31T12:00:00Z"}""",
    ): SonioxKeyProvider {
        val engine = MockEngine { request ->
            captured += request
            respond(
                content = ByteReadChannel(body),
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return SonioxKeyProvider(HttpClient(engine), baseUrl = { baseUrl }, discordToken = { token })
    }
}
