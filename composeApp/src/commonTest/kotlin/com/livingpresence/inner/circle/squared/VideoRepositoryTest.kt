package com.livingpresence.inner.circle.squared

import com.livingpresence.mediakit.EventCatalog
import com.livingpresence.mediakit.ExtraVideoCatalog
import com.livingpresence.mediakit.MediaKitConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val HOST = "https://stream.example.com"
private const val EXTRAS_MANIFEST = "https://manifests.example/extras.txt"
private const val DEMO_MANIFEST = "https://manifests.example/demo.txt"
private const val POLICY_ENDPOINT = "https://tokens.example"

/**
 * What each account's feed is made of.
 *
 * The property under test is containment, not ordering: an account `:server` has
 * restricted must not be able to see a numbered event or a build-time extra, no
 * matter that both are configured and reachable.
 */
class VideoRepositoryTest {

    @Test
    fun anUnrestrictedAccountGetsTheEventsAndTheExtras() = runTest {
        val client = backend(restrictedTo = null)

        val events = repository(client).loadEvents()

        assertEquals(2, events.size)
        assertEquals(1, events[0].eventNumber)
        assertEquals(ExtraVideoCatalog.EXTRA_EVENT_NUMBER_BASE, events[1].eventNumber)
        assertEquals("Extra one", events[1].title)
    }

    @Test
    fun aRestrictedAccountGetsOnlyTheManifestTheServerNamed() = runTest {
        val client = backend(restrictedTo = DEMO_MANIFEST)

        val events = repository(client).loadEvents()

        assertEquals(1, events.size, "the events and the extras are both configured, and both excluded")
        assertEquals("Demo one", events[0].title)
        assertEquals("$HOST/demo/playlist.m3u8", events[0].streamUrl)
    }

    /**
     * The manifest body is cached inside the catalogue, so rebuilding one per load
     * would re-fetch it on every gallery open.
     */
    @Test
    fun aRestrictedAccountReusesOneCatalogueAcrossLoads() = runTest {
        var manifestFetches = 0
        val client = backend(DEMO_MANIFEST) { if (it == DEMO_MANIFEST) manifestFetches++ }
        val repo = repository(client)

        repeat(3) { repo.loadEvents() }

        assertEquals(1, manifestFetches)
    }

    @Test
    fun aForcedRefreshRefetchesTheRestrictedManifest() = runTest {
        var manifestFetches = 0
        val client = backend(DEMO_MANIFEST) { if (it == DEMO_MANIFEST) manifestFetches++ }
        val repo = repository(client)

        repo.loadEvents()
        repo.loadEvents(forceRefresh = true)

        assertEquals(2, manifestFetches)
    }

    /**
     * A build with no token service, a signed-out user and an outage all arrive
     * here as "no policy". None may empty the gallery: the ordinary feed is the
     * app's own configuration and does not depend on `:server` being reachable.
     */
    @Test
    fun noPolicyClientLeavesTheOrdinaryFeedIntact() = runTest {
        val client = backend(restrictedTo = null)
        val repo = VideoRepository(
            httpClient = client,
            catalog = EventCatalog(client, config = oneEvent()),
            extras = ExtraVideoCatalog(httpClient = client, manifestUrl = EXTRAS_MANIFEST),
            policyClient = null,
        )

        assertEquals(2, repo.loadEvents().size)
    }

    @Test
    fun anUnreachablePolicyServiceLeavesTheOrdinaryFeedIntact() = runTest {
        val client = backend(restrictedTo = null)
        val repo = VideoRepository(
            httpClient = client,
            catalog = EventCatalog(client, config = oneEvent()),
            extras = ExtraVideoCatalog(httpClient = client, manifestUrl = EXTRAS_MANIFEST),
            policyClient = FeedPolicyClient(
                httpClient = HttpClient(MockEngine { throw RuntimeException("connection reset") }),
                baseUrl = { POLICY_ENDPOINT },
                discordToken = { "a-token" },
            ),
        )

        assertEquals(2, repo.loadEvents().size)
    }

    @Test
    fun aRestrictedAccountWithAnEmptyManifestGetsAnEmptyFeedNotTheRealOne() = runTest {
        val client = backend(restrictedTo = DEMO_MANIFEST, demoManifestBody = "")

        assertTrue(repository(client).loadEvents().isEmpty())
    }

    /** The app's own feed is always fully configured here; [backend] decides what reaches it. */
    private fun repository(client: HttpClient) = VideoRepository(
        httpClient = client,
        catalog = EventCatalog(client, config = oneEvent()),
        extras = ExtraVideoCatalog(httpClient = client, manifestUrl = EXTRAS_MANIFEST),
        policyClient = FeedPolicyClient(
            httpClient = client,
            baseUrl = { POLICY_ENDPOINT },
            discordToken = { "a-token" },
        ),
    )

    private fun oneEvent() = MediaKitConfig(host = HOST).copy(maxEventNumber = 1)

    /**
     * One engine standing in for everything the repository talks to: the policy
     * route, both manifests, and the Wowza playlists behind every URL in them.
     */
    private fun backend(
        restrictedTo: String?,
        demoManifestBody: String = "$HOST/demo/playlist.m3u8 Demo one",
        onRequest: (String) -> Unit = {},
    ) = HttpClient(MockEngine) {
        engine {
            addHandler { request ->
                val url = request.url.toString()
                onRequest(url)
                when {
                    url.startsWith("$POLICY_ENDPOINT${FeedPolicyClient.FEED_POLICY_PATH}") ->
                        json(restrictedTo?.let { """{"restricted_manifest_url":"$it"}""" } ?: "{}")
                    url == EXTRAS_MANIFEST -> text("$HOST/extra/playlist.m3u8 Extra one")
                    url == DEMO_MANIFEST -> text(demoManifestBody)
                    url.substringBefore('?').endsWith("playlist.m3u8") ->
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
