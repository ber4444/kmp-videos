package com.livingpresence.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The route's contract with the apps, including what it must never return. */
class TemporaryKeyRouteTest {

    @Test
    fun handsBackAMintedKey() = testApplication {
        application { module(testConfig(), httpClient = sonioxReturning(), authorizer = Authorizer.AllowAll) }

        val response = client.post(TEMPORARY_KEY_PATH) { header("Fly-Client-IP", "1.1.1.1") }

        assertEquals(HttpStatusCode.Created, response.status)
        assertTrue(response.bodyAsText().contains("temp-key-123"))
    }

    @Test
    fun theLongLivedKeyIsNeverInAResponse() = testApplication {
        application { module(testConfig(), httpClient = sonioxReturning(), authorizer = Authorizer.AllowAll) }

        val ok = client.post(TEMPORARY_KEY_PATH) { header("Fly-Client-IP", "1.1.1.2") }.bodyAsText()

        assertFalse(ok.contains(LONG_LIVED_KEY))
    }

    @Test
    fun aProviderFailureBecomesA502WithNoProviderDetail() = testApplication {
        application {
            module(
                testConfig(),
                authorizer = Authorizer.AllowAll,
                httpClient = HttpClient(
                    MockEngine {
                        respond(
                            content = ByteReadChannel("""{"error":"invalid api key: $LONG_LIVED_KEY"}"""),
                            status = HttpStatusCode.Unauthorized,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ),
            )
        }

        val response = client.post(TEMPORARY_KEY_PATH) { header("Fly-Client-IP", "1.1.1.3") }

        assertEquals(
            HttpStatusCode.BadGateway,
            response.status,
            "Soniox rejecting OUR key is not the caller's 401 — nothing they send can fix it",
        )
        assertFalse(response.bodyAsText().contains(LONG_LIVED_KEY))
    }

    @Test
    fun oneClientCannotDrainTheEndpoint() = testApplication {
        application { module(testConfig(rateLimit = 3), httpClient = sonioxReturning(), authorizer = Authorizer.AllowAll) }

        val statuses = (1..5).map {
            client.post(TEMPORARY_KEY_PATH) { header("Fly-Client-IP", "9.9.9.9") }.status
        }

        assertEquals(3, statuses.count { it == HttpStatusCode.Created })
        assertEquals(2, statuses.count { it == HttpStatusCode.TooManyRequests })
    }

    @Test
    fun theLimitIsPerClientNotGlobal() = testApplication {
        application { module(testConfig(rateLimit = 1), httpClient = sonioxReturning(), authorizer = Authorizer.AllowAll) }

        // Behind Fly every request shares one source address; keying on that would
        // let the first busy viewer lock everyone else out.
        val first = client.post(TEMPORARY_KEY_PATH) { header("Fly-Client-IP", "10.0.0.1") }
        val second = client.post(TEMPORARY_KEY_PATH) { header("Fly-Client-IP", "10.0.0.2") }

        assertEquals(HttpStatusCode.Created, first.status)
        assertEquals(HttpStatusCode.Created, second.status)
    }

    @Test
    fun aDenyingAuthorizerIsRefusedBeforeAnyKeyIsMinted() = testApplication {
        var minted = 0
        application {
            module(
                testConfig(),
                httpClient = sonioxReturning { minted++ },
                authorizer = { AuthorizationDecision.Denied("Not on the Apollo server") },
            )
        }

        val response = client.post(TEMPORARY_KEY_PATH) { header("Fly-Client-IP", "1.1.1.4") }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals(0, minted, "a refused caller must not cost a Soniox call")
    }

    private fun sonioxReturning(onCall: () -> Unit = {}) = HttpClient(
        MockEngine {
            onCall()
            respond(
                content = ByteReadChannel(
                    """{"api_key":"temp-key-123","expires_at":"2026-08-31T12:00:00Z"}""",
                ),
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        },
    )
}
