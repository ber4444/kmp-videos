package com.livingpresence.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val REVIEWER_ID = "100000000000000001"
private const val MEMBER_ID = "200000000000000002"
private const val REVIEWER_TOKEN = "reviewer-token"
private const val MEMBER_TOKEN = "member-token"

/**
 * Who is told where the videos are.
 *
 * This route *is* the Apollo membership check, so the properties worth pinning
 * are about disclosure rather than display: a non-member must not learn the
 * stream host, and a review account must not learn it either — being confined to
 * the demo list has to mean the numbered events are unreachable, not merely
 * unlisted.
 */
class FeedPolicyRouteTest {

    @Test
    fun anApolloMemberIsGivenTheStreamHostAndTheExtrasManifest() = testApplication {
        application { module(testConfig(), httpClient = unusedSoniox(), feedPolicy = resolver(discord())) }

        val body = get(token = MEMBER_TOKEN).let {
            assertEquals(HttpStatusCode.OK, it.status)
            it.bodyAsText()
        }

        assertTrue(body.contains(TEST_STREAM_HOST))
        assertTrue(body.contains(EXTRAS_MANIFEST_URL))
    }

    @Test
    fun aReviewAccountIsGivenTheDemoManifestAndNoStreamHost() = testApplication {
        application {
            module(
                testConfig(),
                httpClient = unusedSoniox(),
                feedPolicy = resolver(discord(), reviewersAllowed = true),
            )
        }

        val response = get(token = REVIEWER_TOKEN)

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains(DEMO_MANIFEST_URL))
        assertFalse(
            response.bodyAsText().contains(TEST_STREAM_HOST),
            "without the host the numbered events are unreachable, not merely unlisted",
        )
        assertFalse(response.bodyAsText().contains(EXTRAS_MANIFEST_URL))
    }

    /**
     * The review account is not on Apollo — that is the whole reason the allowlist
     * exists, and without it this request is the [aNonMemberIsRefused] case.
     */
    @Test
    fun aReviewAccountIsLetInWithoutBeingAnApolloMember() = testApplication {
        val calls = mutableListOf<HttpRequestData>()
        application {
            module(
                testConfig(),
                httpClient = unusedSoniox(),
                feedPolicy = resolver(
                    discord(memberOf = { emptyList() }, captured = calls),
                    reviewersAllowed = true,
                ),
            )
        }

        assertEquals(HttpStatusCode.OK, get(token = REVIEWER_TOKEN).status)
        assertTrue(
            calls.none { it.url.encodedPath.endsWith("/guilds") },
            "the allowlist decides on its own; joining Apollo later must not change the answer",
        )
    }

    @Test
    fun aNonMemberIsRefused() = testApplication {
        application {
            module(
                testConfig(),
                httpClient = unusedSoniox(),
                feedPolicy = resolver(discord(memberOf = { listOf("111") })),
            )
        }

        val response = get(token = "outsider-token")

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertFalse(
            response.bodyAsText().contains(TEST_STREAM_HOST),
            "a refusal must not leak the address it is refusing access to",
        )
    }

    @Test
    fun aRequestWithNoTokenIsRefusedWithoutCallingDiscord() = testApplication {
        val calls = mutableListOf<HttpRequestData>()
        application {
            module(testConfig(), httpClient = unusedSoniox(), feedPolicy = resolver(discord(captured = calls)))
        }

        val response = client.get(FEED_POLICY_PATH) { header("Fly-Client-IP", "1.1.1.1") }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(calls.isEmpty(), "no token is answerable here; Discord should not be asked")
    }

    /**
     * A named review account with no manifest behind it is a half-applied config.
     * Falling through to the membership check would refuse them here anyway — but
     * a deployment that *had* put them on Apollo would then hand over the host and
     * the real catalogue, which is the opposite of what naming them meant.
     */
    @Test
    fun aReviewAccountWithNoDemoManifestIsRefusedRatherThanGivenTheRealFeed() = testApplication {
        application {
            module(
                testConfig(),
                httpClient = unusedSoniox(),
                feedPolicy = DiscordFeedPolicyResolver(
                    httpClient = discord(memberOf = { listOf(TEST_GUILD_ID) }),
                    guildId = TEST_GUILD_ID,
                    streamHost = TEST_STREAM_HOST,
                    extraVideosUrl = EXTRAS_MANIFEST_URL,
                    testUserIds = setOf(REVIEWER_ID),
                    demoVideosUrl = "",
                ),
            )
        }

        assertEquals(HttpStatusCode.Forbidden, get(token = REVIEWER_TOKEN).status)
    }

    @Test
    fun aDiscordOutageDeniesRatherThanLettingEveryoneThrough() = testApplication {
        val discord = HttpClient(MockEngine { throw RuntimeException("connection reset") })
        application { module(testConfig(), httpClient = unusedSoniox(), feedPolicy = resolver(discord)) }

        assertEquals(HttpStatusCode.Forbidden, get(token = MEMBER_TOKEN).status)
    }

    @Test
    fun anExpiredTokenIsRefused() = testApplication {
        val discord = HttpClient(
            MockEngine {
                respond(
                    content = ByteReadChannel(""),
                    status = HttpStatusCode.Unauthorized,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        application { module(testConfig(), httpClient = unusedSoniox(), feedPolicy = resolver(discord)) }

        assertEquals(HttpStatusCode.Forbidden, get(token = "stale-token").status)
    }

    @Test
    fun repeatedRequestsCostOneRoundOfDiscordCalls() = testApplication {
        // The gallery reloads on every launch and every pull to refresh; uncached,
        // one viewer would be a stream of Discord calls.
        val calls = mutableListOf<HttpRequestData>()
        application {
            module(testConfig(), httpClient = unusedSoniox(), feedPolicy = resolver(discord(captured = calls)))
        }

        repeat(4) { assertEquals(HttpStatusCode.OK, get(token = MEMBER_TOKEN).status) }

        assertEquals(2, calls.size, "one /users/@me and one /users/@me/guilds, then the cache")
    }

    @Test
    fun theCacheDoesNotConfuseOneAccountForAnother() = testApplication {
        application {
            module(
                testConfig(),
                httpClient = unusedSoniox(),
                feedPolicy = resolver(discord(), reviewersAllowed = true),
            )
        }

        assertTrue(get(token = MEMBER_TOKEN).bodyAsText().contains(TEST_STREAM_HOST))
        assertFalse(get(token = REVIEWER_TOKEN).bodyAsText().contains(TEST_STREAM_HOST))
        assertTrue(get(token = MEMBER_TOKEN).bodyAsText().contains(TEST_STREAM_HOST))
    }

    @Test
    fun aRevokedMembershipIsRecheckedOnceTheCacheExpires() = testApplication {
        var memberOf = listOf(TEST_GUILD_ID)
        var now = 0L
        application {
            module(
                testConfig(),
                httpClient = unusedSoniox(),
                feedPolicy = DiscordFeedPolicyResolver(
                    httpClient = discord(memberOf = { memberOf }),
                    guildId = TEST_GUILD_ID,
                    streamHost = TEST_STREAM_HOST,
                    extraVideosUrl = EXTRAS_MANIFEST_URL,
                    testUserIds = emptySet(),
                    demoVideosUrl = "",
                    nowMs = { now },
                ),
            )
        }

        assertEquals(HttpStatusCode.OK, get(token = MEMBER_TOKEN).status)
        memberOf = emptyList()
        assertEquals(HttpStatusCode.OK, get(token = MEMBER_TOKEN).status, "still cached")

        now += DiscordFeedPolicyResolver.CACHE_TTL_MS + 1
        assertEquals(HttpStatusCode.Forbidden, get(token = MEMBER_TOKEN).status)
    }

    private suspend fun ApplicationTestBuilder.get(token: String) =
        client.get(FEED_POLICY_PATH) {
            header("Fly-Client-IP", "1.1.1.1")
            header(HttpHeaders.Authorization, "Bearer $token")
        }

    private fun resolver(discord: HttpClient, reviewersAllowed: Boolean = false) =
        DiscordFeedPolicyResolver(
            httpClient = discord,
            guildId = TEST_GUILD_ID,
            streamHost = TEST_STREAM_HOST,
            extraVideosUrl = EXTRAS_MANIFEST_URL,
            testUserIds = if (reviewersAllowed) setOf(REVIEWER_ID) else emptySet(),
            demoVideosUrl = if (reviewersAllowed) DEMO_MANIFEST_URL else "",
        )

    /**
     * Stands in for Discord: `/users/@me` answers with an id derived from the
     * bearer token, so one engine plays both accounts, and `/users/@me/guilds`
     * answers with [memberOf].
     */
    private fun discord(
        memberOf: () -> List<String> = { listOf(TEST_GUILD_ID) },
        captured: MutableList<HttpRequestData> = mutableListOf(),
    ) = HttpClient(
        MockEngine { request ->
            captured += request
            val body = if (request.url.encodedPath.endsWith("/guilds")) {
                memberOf().joinToString(prefix = "[", postfix = "]") { """{"id":"$it"}""" }
            } else {
                val reviewer = request.headers[HttpHeaders.Authorization] == "Bearer $REVIEWER_TOKEN"
                """{"id":"${if (reviewer) REVIEWER_ID else MEMBER_ID}"}"""
            }
            respond(
                content = ByteReadChannel(body),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        },
    )

    /** The feed route must never reach Soniox; calling this fails the test. */
    private fun unusedSoniox() = HttpClient(
        MockEngine { error("the feed route must not call Soniox") },
    )
}
