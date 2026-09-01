package com.livingpresence.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The identity gate.
 *
 * These run through the real route rather than calling `authorize` directly, so
 * they also pin the thing that actually matters operationally: a caller who is not
 * an Apollo member never reaches [SonioxTokenService], and therefore never costs a
 * Soniox call.
 */
class DiscordGuildAuthorizerTest {

    @Test
    fun anApolloMemberIsMinted() = testApplication {
        val discord = discordReturning(guilds = """[{"id":"$TEST_GUILD_ID"}]""")
        application { module(testConfig(), httpClient = soniox(), authorizer = authorizer(discord)) }

        val response = post(token = "member-token")

        assertEquals(HttpStatusCode.Created, response)
    }

    @Test
    fun someoneOnOtherServersIsRefused() = testApplication {
        val discord = discordReturning(guilds = """[{"id":"111"},{"id":"222"}]""")
        var minted = 0
        application {
            module(testConfig(), httpClient = soniox { minted++ }, authorizer = authorizer(discord))
        }

        val response = post(token = "outsider-token")

        assertEquals(HttpStatusCode.Forbidden, response)
        assertEquals(0, minted, "a non-member must not cost a Soniox call")
    }

    @Test
    fun aRequestWithNoTokenIsRefusedWithoutCallingDiscord() = testApplication {
        val calls = mutableListOf<HttpRequestData>()
        val discord = discordReturning(guilds = "[]", captured = calls)
        application { module(testConfig(), httpClient = soniox(), authorizer = authorizer(discord)) }

        val response = client.post(TEMPORARY_KEY_PATH) { header("Fly-Client-IP", "1.1.1.1") }.status

        assertEquals(HttpStatusCode.Forbidden, response)
        assertTrue(calls.isEmpty(), "no token is answerable here; Discord should not be asked")
    }

    @Test
    fun anExpiredTokenIsRefused() = testApplication {
        val discord = discordReturning(guilds = "", status = HttpStatusCode.Unauthorized)
        application { module(testConfig(), httpClient = soniox(), authorizer = authorizer(discord)) }

        assertEquals(HttpStatusCode.Forbidden, post(token = "stale-token"))
    }

    /**
     * Failing closed is the deliberate choice: an outage that let anyone mint would
     * be a window in which the gate does not exist, which is worse than captions
     * being unavailable for its duration.
     */
    @Test
    fun aDiscordOutageDeniesRatherThanLettingEveryoneThrough() = testApplication {
        val discord = HttpClient(MockEngine { throw RuntimeException("connection reset") })
        application { module(testConfig(), httpClient = soniox(), authorizer = authorizer(discord)) }

        assertEquals(HttpStatusCode.Forbidden, post(token = "any-token"))
    }

    @Test
    fun repeatedRequestsCostOneDiscordCall() = testApplication {
        // Captions reconnect through a long video and mint a key each time. Without
        // the cache one viewer becomes a stream of Discord calls and hits its limit.
        val calls = mutableListOf<HttpRequestData>()
        val discord = discordReturning(guilds = """[{"id":"$TEST_GUILD_ID"}]""", captured = calls)
        application { module(testConfig(), httpClient = soniox(), authorizer = authorizer(discord)) }

        repeat(4) { assertEquals(HttpStatusCode.Created, post(token = "member-token")) }

        assertEquals(1, calls.size)
    }

    @Test
    fun theCacheDoesNotConfuseOneUserForAnother() = testApplication {
        val discord = HttpClient(
            MockEngine { request ->
                val member = request.headers[HttpHeaders.Authorization] == "Bearer member-token"
                respond(
                    content = ByteReadChannel(if (member) """[{"id":"$TEST_GUILD_ID"}]""" else """[]"""),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        application { module(testConfig(), httpClient = soniox(), authorizer = authorizer(discord)) }

        assertEquals(HttpStatusCode.Created, post(token = "member-token"))
        assertEquals(HttpStatusCode.Forbidden, post(token = "outsider-token"))
        assertEquals(HttpStatusCode.Created, post(token = "member-token"))
    }

    @Test
    fun aRevokedMembershipIsRecheckedOnceTheCacheExpires() = testApplication {
        var member = true
        val discord = HttpClient(
            MockEngine {
                respond(
                    content = ByteReadChannel(if (member) """[{"id":"$TEST_GUILD_ID"}]""" else """[]"""),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        var now = 0L
        application {
            module(
                testConfig(),
                httpClient = soniox(),
                authorizer = DiscordGuildAuthorizer(discord, TEST_GUILD_ID, nowMs = { now }),
            )
        }

        assertEquals(HttpStatusCode.Created, post(token = "member-token"))
        member = false
        assertEquals(HttpStatusCode.Created, post(token = "member-token"), "still cached")

        now += DiscordGuildAuthorizer.CACHE_TTL_MS + 1
        assertEquals(HttpStatusCode.Forbidden, post(token = "member-token"))
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.post(token: String) =
        client.post(TEMPORARY_KEY_PATH) {
            header("Fly-Client-IP", "1.1.1.1")
            header(HttpHeaders.Authorization, "Bearer $token")
        }.status

    private fun authorizer(discord: HttpClient) = DiscordGuildAuthorizer(discord, TEST_GUILD_ID)

    private fun discordReturning(
        guilds: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        captured: MutableList<HttpRequestData> = mutableListOf(),
    ) = HttpClient(
        MockEngine { request ->
            captured += request
            respond(
                content = ByteReadChannel(guilds),
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        },
    )

    private fun soniox(onCall: () -> Unit = {}) = HttpClient(
        MockEngine {
            onCall()
            respond(
                content = ByteReadChannel("""{"api_key":"temp-key-123","expires_at":"2026-08-31T12:00:00Z"}"""),
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        },
    )
}
