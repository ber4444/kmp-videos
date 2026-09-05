package com.livingpresence.inner.circle.squared

import com.livingpresence.mediakit.ExtraVideoCatalog
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val HOST = "https://stream.example.com"
private const val EXTRAS_MANIFEST = "https://manifests.example/extras.txt"
private const val DEMO_MANIFEST = "https://manifests.example/demo.txt"
private const val POLICY_ENDPOINT = "https://tokens.example"

/**
 * What each account's feed is made of.
 *
 * The repository holds no addresses of its own, so these tests are about what it
 * does with the two it is issued — and, for a review account, about what the
 * absence of a stream host means: not a filtered list, but no way to build an
 * event URL at all.
 */
class VideoRepositoryTest {

    @AfterTest
    fun clearInjectedHost() {
        // FeedConfig.streamHost is MediaKitConfig's process-wide default, which
        // loadEvents assigns. Left set, it would leak into every later test.
        FeedConfig.streamHost = ""
    }

    @Test
    fun aMemberGetsTheNumberedEventsAndTheExtras() = runTest {
        val client = backend(streamHost = HOST, manifestUrl = EXTRAS_MANIFEST)

        val events = VideoRepository(client, policy(client)).loadEvents()

        assertEquals(2, events.size)
        assertEquals(1, events[0].eventNumber)
        assertEquals(ExtraVideoCatalog.EXTRA_EVENT_NUMBER_BASE, events[1].eventNumber)
        assertEquals("Extra one", events[1].title)
    }

    @Test
    fun aReviewAccountWithNoHostGetsOnlyItsManifest() = runTest {
        val probes = mutableListOf<String>()
        val client = backend(streamHost = "", manifestUrl = DEMO_MANIFEST) { probes += it }

        val events = VideoRepository(client, policy(client)).loadEvents()

        assertEquals(1, events.size)
        assertEquals("Demo one", events[0].title)
        assertEquals("$HOST/demo/playlist.m3u8", events[0].streamUrl)
        assertFalse(
            probes.any { it.contains("/live/event") },
            "with no host there is no event URL to build, so nothing is even probed",
        )
    }

    @Test
    fun anAccountGivenNeitherAddressGetsAnEmptyFeed() = runTest {
        val client = backend(streamHost = "", manifestUrl = "")

        assertTrue(VideoRepository(client, policy(client)).loadEvents().isEmpty())
    }

    /**
     * The host is the SDK's process-wide default, which `getUrl` and the offline
     * fallback also build from. It has to track the account, including back to
     * empty — a stale host would outlive the session that was issued it.
     */
    @Test
    fun theIssuedHostIsPublishedToTheSdkAndClearedWhenThereIsNone() = runTest {
        val member = backend(streamHost = HOST, manifestUrl = EXTRAS_MANIFEST)
        VideoRepository(member, policy(member)).loadEvents()
        assertEquals(HOST, FeedConfig.streamHost)

        val reviewer = backend(streamHost = "", manifestUrl = DEMO_MANIFEST)
        VideoRepository(reviewer, policy(reviewer)).loadEvents()
        assertEquals("", FeedConfig.streamHost)
    }

    /**
     * The manifest body is cached inside the catalogue, so rebuilding one per load
     * would re-fetch it on every gallery open.
     */
    @Test
    fun theCataloguesAreReusedAcrossLoads() = runTest {
        var manifestFetches = 0
        val client = backend(HOST, EXTRAS_MANIFEST) { if (it == EXTRAS_MANIFEST) manifestFetches++ }
        val repo = VideoRepository(client, policy(client))

        repeat(3) { repo.loadEvents() }

        assertEquals(1, manifestFetches)
    }

    @Test
    fun aForcedRefreshRefetchesTheManifest() = runTest {
        var manifestFetches = 0
        val client = backend(HOST, EXTRAS_MANIFEST) { if (it == EXTRAS_MANIFEST) manifestFetches++ }
        val repo = VideoRepository(client, policy(client))

        repo.loadEvents()
        repo.loadEvents(forceRefresh = true)

        assertEquals(2, manifestFetches)
    }

    /**
     * An empty gallery would claim there is nothing to watch. A service that could
     * not answer has said no such thing, so this surfaces as the gallery's error
     * and its retry instead.
     */
    @Test
    fun anUnreachableServiceFailsRatherThanLookingLikeAnEmptyFeed() = runTest {
        val policy = FeedPolicyClient(
            httpClient = HttpClient(MockEngine { throw RuntimeException("connection reset") }),
            baseUrl = { POLICY_ENDPOINT },
            discordToken = { "a-token" },
        )
        val repo = VideoRepository(backend(HOST, EXTRAS_MANIFEST), policy)

        assertFailsWith<FeedUnavailableException> { repo.loadEvents() }
    }

    /** Nobody signed in yet: an empty feed, and nothing asked of the network. */
    @Test
    fun aSignedOutUserGetsAnEmptyFeedWithoutFailing() = runTest {
        val requests = mutableListOf<String>()
        val client = backend(HOST, EXTRAS_MANIFEST) { requests += it }
        val repo = VideoRepository(
            client,
            FeedPolicyClient(client, baseUrl = { POLICY_ENDPOINT }, discordToken = { "" }),
        )

        assertTrue(repo.loadEvents().isEmpty())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun aRefusedAccountGetsAnEmptyFeedRatherThanAnError() = runTest {
        val client = HttpClient(MockEngine { respond(content = "", status = HttpStatusCode.Forbidden) })
        val repo = VideoRepository(
            client,
            FeedPolicyClient(client, baseUrl = { POLICY_ENDPOINT }, discordToken = { "a-token" }),
        )

        assertTrue(repo.loadEvents().isEmpty())
    }

    private fun policy(client: HttpClient) = FeedPolicyClient(
        httpClient = client,
        baseUrl = { POLICY_ENDPOINT },
        discordToken = { "a-token" },
    )

    /**
     * One engine standing in for everything the repository talks to: the policy
     * route, the manifests, and the Wowza playlists behind every URL in them.
     */
    private fun backend(
        streamHost: String,
        manifestUrl: String,
        onRequest: (String) -> Unit = {},
    ) = HttpClient(MockEngine) {
        engine {
            addHandler { request ->
                val url = request.url.toString()
                onRequest(url)
                when {
                    url.startsWith("$POLICY_ENDPOINT${FeedPolicyClient.FEED_POLICY_PATH}") ->
                        json("""{"stream_host":"$streamHost","manifest_url":"$manifestUrl"}""")
                    url == EXTRAS_MANIFEST -> text("$HOST/extra/playlist.m3u8 Extra one")
                    url == DEMO_MANIFEST -> text("$HOST/demo/playlist.m3u8 Demo one")
                    // Only event 1 exists; the rest 404 and drop out, as on the
                    // real server.
                    url.startsWith("$HOST/live/event1/") ||
                        url.substringBefore('?').endsWith("playlist.m3u8") &&
                        !url.contains("/live/event") ->
                        text("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1200000\nchunklist.m3u8\n")
                    url.substringBefore('?').endsWith("chunklist.m3u8") ->
                        text("#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10.0,\nseg1.ts\n#EXT-X-ENDLIST\n")
                    else -> respond(content = "", status = HttpStatusCode.NotFound)
                }
            }
        }
    }

    private fun MockRequestHandleScope.text(body: String) = respond(
        content = body,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/vnd.apple.mpegurl"),
    )

    private fun MockRequestHandleScope.json(body: String) = respond(
        content = body,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )
}
