package com.livingpresence.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val CONCERTS_URL = "https://lists.example/concerts.txt"
private const val SOURCE = "https://media.example:443"

class ConcertRelayTest {

    private val requested = mutableListOf<String>()

    private val source = HttpClient(
        MockEngine { request ->
            val path = request.url.encodedPath
            requested += path
            when {
                request.url.host == "lists.example" -> respond(
                    "$SOURCE/live/event15/playlist.m3u8?DVR Saturday concert\n" +
                        "# a comment\n" +
                        "$SOURCE/vod/recital/playlist.m3u8\n",
                )
                path == "/live/event15/playlist.m3u8" -> respond(
                    playlist("chunklist_w1_DVR.m3u8"),
                    headers = headersOf(HttpHeaders.ContentType, "application/vnd.apple.mpegurl"),
                )
                path == "/live/event15_360p/playlist.m3u8" -> respond(
                    playlist("$SOURCE/live/event15_360p/chunklist_w2.m3u8"),
                    headers = headersOf(HttpHeaders.ContentType, "application/vnd.apple.mpegurl"),
                )
                path == "/live/event15/media_w1_DVR_0.ts" -> respond(
                    "segment-bytes",
                    if (request.headers[HttpHeaders.Range] != null) HttpStatusCode.PartialContent else HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "video/mp2t"),
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        },
    )

    @Test
    fun theListPointsEveryConcertAtThisService() = relayApp {
        val body = client.get(CONCERTS_MANIFEST_PATH).let {
            assertEquals(HttpStatusCode.OK, it.status)
            it.bodyAsText()
        }

        assertEquals(
            listOf(
                "http://localhost$CONCERTS_RELAY_PREFIX/live/event15/playlist.m3u8?DVR Saturday concert",
                "http://localhost$CONCERTS_RELAY_PREFIX/vod/recital/playlist.m3u8",
            ),
            body.lines().filter { it.isNotEmpty() },
        )
        assertFalse(body.contains("media.example"))
        assertFalse(body.contains("lists.example"))
    }

    @Test
    fun theForwardedSchemeIsUsedBehindTheProxy() = relayApp {
        val body = client.get(CONCERTS_MANIFEST_PATH) {
            header("X-Forwarded-Proto", "https")
        }.bodyAsText()

        assertTrue(body.startsWith("https://localhost$CONCERTS_RELAY_PREFIX/"))
    }

    @Test
    fun aListedPlaylistAndItsSegmentsAreServed() = relayApp {
        val playlist = client.get("$CONCERTS_RELAY_PREFIX/live/event15/playlist.m3u8?DVR")
        val segment = client.get("$CONCERTS_RELAY_PREFIX/live/event15/media_w1_DVR_0.ts")

        assertEquals(HttpStatusCode.OK, playlist.status)
        assertTrue(playlist.bodyAsText().contains("chunklist_w1_DVR.m3u8"))
        assertEquals("*", playlist.headers[HttpHeaders.AccessControlAllowOrigin])
        assertEquals("segment-bytes", segment.bodyAsText())
        assertEquals("video/mp2t", segment.headers[HttpHeaders.ContentType])
    }

    @Test
    fun aListedStreamsSiblingRenditionsAreServedForTheAdaptiveLadder() = relayApp {
        val response = client.get("$CONCERTS_RELAY_PREFIX/live/event15_360p/playlist.m3u8?DVR")

        assertEquals(HttpStatusCode.OK, response.status)
        // Absolute source URLs in a playlist come back pointing at this service.
        val body = response.bodyAsText()
        assertTrue(body.contains("http://localhost$CONCERTS_RELAY_PREFIX/live/event15_360p/chunklist_w2.m3u8"))
        assertFalse(body.contains("media.example"))
    }

    @Test
    fun rangeRequestsArePassedThrough() = relayApp {
        val response = client.get("$CONCERTS_RELAY_PREFIX/live/event15/media_w1_DVR_0.ts") {
            header(HttpHeaders.Range, "bytes=0-3")
        }

        assertEquals(HttpStatusCode.PartialContent, response.status)
    }

    @Test
    fun onlyListedConcertsAreServed() = relayApp {
        client.get(CONCERTS_MANIFEST_PATH)
        requested.clear()

        val paths = listOf(
            "$CONCERTS_RELAY_PREFIX/live/event16/playlist.m3u8?DVR",
            "$CONCERTS_RELAY_PREFIX/live/event150/playlist.m3u8?DVR",
            "$CONCERTS_RELAY_PREFIX/vod/other/playlist.m3u8",
            "$CONCERTS_RELAY_PREFIX/vod/recital/sub/playlist.m3u8",
            "$CONCERTS_RELAY_PREFIX/live/event15/..%2F..%2Fplaylist.m3u8",
            "$CONCERTS_RELAY_PREFIX/playlist.m3u8",
        )
        paths.forEach { path ->
            assertEquals(HttpStatusCode.NotFound, client.get(path).status, path)
        }
        assertTrue(requested.isEmpty(), "nothing should be fetched: $requested")
    }

    @Test
    fun withNoListConfiguredConcertsAreUnavailable() = testApplication {
        application {
            module(testConfig(), httpClient = source, concerts = ConcertCatalog(source, manifestUrl = ""))
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, client.get(CONCERTS_MANIFEST_PATH).status)
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("$CONCERTS_RELAY_PREFIX/live/event15/playlist.m3u8?DVR").status,
        )
    }

    @Test
    fun aFailedRefreshKeepsServingTheLastList() = runTest {
        var up = true
        var now = 0L
        val flaky = HttpClient(
            MockEngine { if (up) respond("$SOURCE/live/event15/playlist.m3u8 One") else respond("", HttpStatusCode.BadGateway) },
        )
        val catalog = ConcertCatalog(flaky, CONCERTS_URL, clock = { now })

        assertEquals(1, catalog.concerts()?.size)
        up = false
        now += 10 * 60 * 1000
        assertEquals(1, catalog.concerts()?.size)
    }

    private fun relayApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            module(testConfig(), httpClient = source, concerts = ConcertCatalog(source, CONCERTS_URL))
        }
        block()
    }

    private fun playlist(uri: String) = "#EXTM3U\n#EXT-X-VERSION:3\n$uri\n"
}
