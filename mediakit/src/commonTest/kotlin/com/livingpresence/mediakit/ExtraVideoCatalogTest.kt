package com.livingpresence.mediakit

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

class ExtraVideoCatalogTest {

    private val manifestUrl = "https://gist.test/raw/extras.txt"
    private val videoUrl = "https://test.local/vod/thudin-8-20-26/playlist.m3u8?DVR"

    // ---- Manifest parsing ---------------------------------------------------

    @Test
    fun parseManifest_readsUrlPerLine_ignoringBlanksAndComments() {
        val videos = ExtraVideoCatalog.parseManifest(
            """
            # a comment
            https://test.local/vod/one/playlist.m3u8?DVR

              https://test.local/vod/two/playlist.m3u8?DVR
            """.trimIndent()
        )

        assertEquals(
            listOf(
                "https://test.local/vod/one/playlist.m3u8?DVR",
                "https://test.local/vod/two/playlist.m3u8?DVR",
            ),
            videos.map { it.url },
        )
    }

    @Test
    fun parseManifest_takesTitleAfterTheUrl() {
        val videos = ExtraVideoCatalog.parseManifest(
            """
            $videoUrl   Thudin, Aug 20
            https://test.local/vod/two/playlist.m3u8?DVR | Piped Title
            """.trimIndent()
        )

        assertEquals(listOf("Thudin, Aug 20", "Piped Title"), videos.map { it.title })
    }

    @Test
    fun parseManifest_derivesTitleFromUrlWhenAbsent() {
        val videos = ExtraVideoCatalog.parseManifest(videoUrl)
        assertEquals("Thudin 8 20 26", videos.single().title)
    }

    @Test
    fun parseManifest_dropsNonHttpLinesAndDuplicates() {
        val videos = ExtraVideoCatalog.parseManifest(
            """
            ftp://test.local/vod/nope/playlist.m3u8
            /vod/relative/playlist.m3u8
            $videoUrl First
            $videoUrl Second
            """.trimIndent()
        )

        assertEquals(1, videos.size)
        assertEquals("First", videos.single().title)
    }

    // ---- Caching ------------------------------------------------------------

    @Test
    fun loadManifest_servesFromCacheWithinTtl() = runTest {
        var now = 0L
        val store = InMemoryManifestStore()
        val (catalog, engine) = catalog(store = store, nowEpochMs = { now })

        catalog.loadManifest()
        now += 23.hours.inWholeMilliseconds
        catalog.loadManifest()

        assertEquals(1, engine.manifestRequests)
    }

    @Test
    fun loadManifest_refetchesOnceTheTtlHasPassed() = runTest {
        var now = 0L
        val (catalog, engine) = catalog(nowEpochMs = { now })

        catalog.loadManifest()
        now += 1.days.inWholeMilliseconds + 1
        catalog.loadManifest()

        assertEquals(2, engine.manifestRequests)
    }

    @Test
    fun loadManifest_forceRefreshBypassesAFreshCache() = runTest {
        val (catalog, engine) = catalog(nowEpochMs = { 0L })

        catalog.loadManifest()
        catalog.loadManifest(forceRefresh = true)

        assertEquals(2, engine.manifestRequests)
    }

    @Test
    fun loadManifest_survivesAProcessRestartThroughTheStore() = runTest {
        var now = 0L
        val store = InMemoryManifestStore()
        val (first, _) = catalog(store = store, nowEpochMs = { now })
        first.loadManifest()

        // A new catalog over the same store is what a cold start looks like.
        now += 1.hours.inWholeMilliseconds
        val (second, secondEngine) = catalog(store = store, nowEpochMs = { now })
        val videos = second.loadManifest()

        assertEquals(0, secondEngine.manifestRequests)
        assertEquals(videoUrl, videos.single().url)
    }

    @Test
    fun loadManifest_fallsBackToAStaleBodyWhenTheFetchFails() = runTest {
        var now = 0L
        var failing = false
        val store = InMemoryManifestStore()
        val (catalog, _) = catalog(store = store, nowEpochMs = { now }, failManifest = { failing })

        catalog.loadManifest()
        now += 2.days.inWholeMilliseconds
        failing = true

        assertEquals(videoUrl, catalog.loadManifest().single().url)
    }

    @Test
    fun loadManifest_isEmptyWhenNoManifestIsConfigured() = runTest {
        val (catalog, engine) = catalog(manifestUrl = "")
        assertTrue(catalog.loadManifest().isEmpty())
        assertEquals(0, engine.manifestRequests)
    }

    @Test
    fun loadManifest_treatsAFutureTimestampAsStale() = runTest {
        var now = 10.days.inWholeMilliseconds
        val store = InMemoryManifestStore()
        val (catalog, engine) = catalog(store = store, nowEpochMs = { now })

        catalog.loadManifest()
        // Clock moved backwards (a timezone/NTP correction): the entry now looks
        // like it was written in the future, which must not count as fresh.
        now -= 5.days.inWholeMilliseconds
        catalog.loadManifest()

        assertEquals(2, engine.manifestRequests)
    }

    // ---- Feed entries -------------------------------------------------------

    @Test
    fun loadExtras_numbersEntriesFromTheExtraBaseInManifestOrder() = runTest {
        val second = "https://test.local/vod/second/playlist.m3u8?DVR"
        val (catalog, _) = catalog(body = "$videoUrl\n$second\n")

        val extras = catalog.loadExtras()

        assertEquals(
            listOf(
                ExtraVideoCatalog.EXTRA_EVENT_NUMBER_BASE,
                ExtraVideoCatalog.EXTRA_EVENT_NUMBER_BASE + 1,
            ),
            extras.map { it.eventNumber },
        )
        assertEquals(listOf(videoUrl, second), extras.map { it.streamUrl })
    }

    @Test
    fun loadExtras_readsLiveAndDurationFromThePlaylist() = runTest {
        val (catalog, _) = catalog()

        val extra = catalog.loadExtras().single()

        assertFalse(extra.isLive)
        assertEquals(6_000L, extra.durationMs)
        assertFalse(extra.hasRenditionLadder)
    }

    @Test
    fun loadExtras_dropsAnEntryThatIsGone() = runTest {
        val (catalog, _) = catalog(missingVideo = true)
        assertTrue(catalog.loadExtras().isEmpty())
    }

    @Test
    fun loadExtras_keepsAnUnreachableEntryAsBounded() = runTest {
        val (catalog, _) = catalog(videoStatus = HttpStatusCode.ServiceUnavailable)

        val extra = catalog.loadExtras().single()

        assertEquals(videoUrl, extra.streamUrl)
        assertFalse(extra.isLive)
        assertEquals(0L, extra.durationMs)
    }

    // ---- Harness ------------------------------------------------------------

    /** Counts how many times the mock engine was asked for the manifest. */
    private class ManifestRequests {
        var manifestRequests: Int = 0
    }

    private fun catalog(
        manifestUrl: String = this.manifestUrl,
        body: String = "$videoUrl\n",
        store: ManifestStore = InMemoryManifestStore(),
        ttl: Duration = ExtraVideoCatalog.DEFAULT_TTL,
        nowEpochMs: () -> Long = { 0L },
        failManifest: () -> Boolean = { false },
        missingVideo: Boolean = false,
        videoStatus: HttpStatusCode = HttpStatusCode.OK,
    ): Pair<ExtraVideoCatalog, ManifestRequests> {
        val counting = ManifestRequests()
        val engine = MockEngine { request ->
            val url = request.url.toString()
            when {
                manifestUrl.isNotEmpty() && url.startsWith(manifestUrl) -> {
                    counting.manifestRequests++
                    if (failManifest()) {
                        respondError(HttpStatusCode.InternalServerError)
                    } else {
                        respond(content = body, status = HttpStatusCode.OK, headers = textHeaders)
                    }
                }

                missingVideo -> respondError(HttpStatusCode.NotFound)

                videoStatus != HttpStatusCode.OK -> respondError(videoStatus)

                url.contains("chunklist") -> respond(
                    content = "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2.0,\na.ts\n" +
                        "#EXTINF:2.0,\nb.ts\n#EXTINF:2.0,\nc.ts\n#EXT-X-ENDLIST\n",
                    status = HttpStatusCode.OK,
                    headers = playlistHeaders,
                )

                else -> respond(
                    content = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1200000,RESOLUTION=1280x720\nchunklist.m3u8\n",
                    status = HttpStatusCode.OK,
                    headers = playlistHeaders,
                )
            }
        }
        val catalog = ExtraVideoCatalog(
            httpClient = HttpClient(engine),
            manifestUrl = manifestUrl,
            store = store,
            ttl = ttl,
            maxProbeAttempts = 1,
            retryBackoff = Duration.ZERO,
            nowEpochMs = nowEpochMs,
        )
        return catalog to counting
    }

    private val textHeaders = headersOf(HttpHeaders.ContentType, "text/plain")
    private val playlistHeaders = headersOf(HttpHeaders.ContentType, "application/vnd.apple.mpegurl")
}
