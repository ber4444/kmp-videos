package com.livingpresence.inner.circle.squared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The distinction this class exists to make: "the service said no" is a refusal,
 * everything else is an absence. Collapsing the two would either lock members out
 * during an outage or let a refusal look like one.
 */
class FeedPolicyClientTest {

    @Test
    fun aRestrictedAccountReadsBackItsManifest() = runTest {
        val client = clientReturning(HttpStatusCode.OK, """{"restricted_manifest_url":"https://m.example/d.txt"}""")

        val result = client.fetch()

        assertEquals(
            FeedPolicyResult.Resolved(FeedPolicy(restrictedManifestUrl = "https://m.example/d.txt")),
            result,
        )
    }

    @Test
    fun anUnrestrictedAccountResolvesToAPolicyWithNoManifest() = runTest {
        val client = clientReturning(HttpStatusCode.OK, "{}")

        val result = client.fetch()

        assertTrue(result is FeedPolicyResult.Resolved)
        assertNull(result.policy.restrictedManifestUrl)
    }

    @Test
    fun aForbiddenAnswerIsARefusalRatherThanAnAbsence() = runTest {
        val client = clientReturning(HttpStatusCode.Forbidden, """{"error":"nope"}""")

        assertEquals(FeedPolicyResult.Absent(FeedPolicyAbsence.REFUSED), client.fetch())
    }

    @Test
    fun aServerErrorIsAnAbsenceSoTheOrdinaryFeedSurvivesIt() = runTest {
        val client = clientReturning(HttpStatusCode.InternalServerError, "")

        assertEquals(FeedPolicyResult.Absent(FeedPolicyAbsence.UNAVAILABLE), client.fetch())
    }

    @Test
    fun anUnparseableBodyIsAnAbsenceRatherThanACrash() = runTest {
        val client = clientReturning(HttpStatusCode.OK, "not json")

        assertEquals(FeedPolicyResult.Absent(FeedPolicyAbsence.UNAVAILABLE), client.fetch())
    }

    @Test
    fun aTransportFailureIsAnAbsence() = runTest {
        val client = FeedPolicyClient(
            httpClient = HttpClient(MockEngine { throw RuntimeException("connection reset") }),
            baseUrl = { "https://tokens.example" },
            discordToken = { "a-token" },
        )

        assertEquals(FeedPolicyResult.Absent(FeedPolicyAbsence.UNAVAILABLE), client.fetch())
    }

    @Test
    fun aBuildWithNoEndpointNeverAsks() = runTest {
        val calls = mutableListOf<HttpRequestData>()
        val client = clientReturning(HttpStatusCode.OK, "{}", calls, baseUrl = "  ")

        assertEquals(FeedPolicyResult.Absent(FeedPolicyAbsence.NOT_APPLICABLE), client.fetch())
        assertTrue(calls.isEmpty())
    }

    @Test
    fun aSignedOutUserNeverAsks() = runTest {
        val calls = mutableListOf<HttpRequestData>()
        val client = clientReturning(HttpStatusCode.OK, "{}", calls, token = "")

        assertEquals(FeedPolicyResult.Absent(FeedPolicyAbsence.NOT_APPLICABLE), client.fetch())
        assertTrue(calls.isEmpty())
    }

    @Test
    fun theTokenIsSentAsABearerCredential() = runTest {
        val calls = mutableListOf<HttpRequestData>()
        clientReturning(HttpStatusCode.OK, "{}", calls).fetch()

        assertEquals("Bearer a-token", calls.single().headers[HttpHeaders.Authorization])
        assertTrue(calls.single().url.toString().endsWith(FeedPolicyClient.FEED_POLICY_PATH))
    }

    /** A trailing slash on the configured base must not double up in the path. */
    @Test
    fun theBaseUrlIsNormalised() = runTest {
        val calls = mutableListOf<HttpRequestData>()
        clientReturning(HttpStatusCode.OK, "{}", calls, baseUrl = "https://tokens.example/").fetch()

        assertEquals(
            "https://tokens.example${FeedPolicyClient.FEED_POLICY_PATH}",
            calls.single().url.toString(),
        )
    }

    private fun clientReturning(
        status: HttpStatusCode,
        body: String,
        captured: MutableList<HttpRequestData> = mutableListOf(),
        baseUrl: String = "https://tokens.example",
        token: String = "a-token",
    ) = FeedPolicyClient(
        httpClient = HttpClient(
            MockEngine { request ->
                captured += request
                respond(
                    content = body,
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ),
        baseUrl = { baseUrl },
        discordToken = { token },
    )
}
