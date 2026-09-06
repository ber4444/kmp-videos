package com.livingpresence.mediakit

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val STREAM_HOST_NAME = "stream.example"
private const val HOST = "https://$STREAM_HOST_NAME:443"
private const val PLAYED_URL = "$HOST/live/event15/playlist.m3u8?DVR"

/**
 * The regression this guards: an account issued **no stream host** must still be
 * able to resolve the siblings of a stream it was handed an absolute URL for.
 *
 * Restricting an account to a manifest means clearing
 * [MediaKitConfig.defaultHost], which is what makes the numbered events
 * unreachable to it. But live captions read the audio-only `_aac` sibling, and
 * that lookup used to go through the (now empty) default — building
 * `/live/event15_aac/playlist.m3u8?DVR`, resolving nowhere, and silently
 * yielding no captions while the video itself played fine from the absolute URL
 * the manifest supplied.
 *
 * These run the real [LadderResolver] against a mock that serves *only* absolute
 * URLs, so a regression to the default-host path fails here rather than in
 * someone's hands.
 */
class LadderWithoutDefaultHostTest {

    private var previousHost: String = ""

    @BeforeTest
    fun clearTheDefaultHost() {
        previousHost = MediaKitConfig.defaultHost
        MediaKitConfig.defaultHost = ""
    }

    @AfterTest
    fun restoreTheDefaultHost() {
        MediaKitConfig.defaultHost = previousHost
    }

    @Test
    fun theAudioSiblingResolvesFromThePlayedUrlAlone() = runTest {
        val resolver = LadderResolver(client(), MediaKitConfig.forStreamUrl(PLAYED_URL)!!)

        // Off the virtual clock on purpose: LadderResolver wraps each probe in
        // withTimeout(3s), and runTest's scheduler would fast-forward to that
        // deadline the moment the mock suspends, failing every probe for a
        // reason that has nothing to do with what is under test.
        val ladder = withContext(Dispatchers.Default) { resolver.resolve(15) }

        assertNotNull(ladder, "no ladder means no audio track, and so no captions")
        val audio = ladder.renditions.firstOrNull { it.isAudioOnly }
        assertNotNull(audio, "the audio-only sibling is what the caption feeder reads")
        assertTrue(audio.chunklistUri.startsWith(HOST), "and it has to be absolute to be fetchable")
    }

    /**
     * The failing shape, kept as a test so the difference is legible: with the
     * default host empty, every probe URL is relative and nothing resolves.
     */
    @Test
    fun theDefaultConfigFindsNothingWithoutAHost() = runTest {
        val resolver = LadderResolver(client(), MediaKitConfig.Default)

        assertNull(withContext(Dispatchers.Default) { resolver.resolve(15) })
    }

    @Test
    fun anExtraOnSomeOtherPathStillHasNoSiblingsToFind() = runTest {
        assertNull(MediaKitConfig.forStreamUrl("$HOST/vod/a-talk/playlist.m3u8?DVR"))
    }

    /**
     * Serves the `/live/event15*` ladder, and only for [STREAM_HOST_NAME].
     *
     * Matched on the parsed host rather than a string prefix: Ktor drops the
     * default `:443`, so comparing against the configured URL text would reject
     * the very requests this is meant to serve. A relative URL — the shape the
     * bug produced — arrives here as `http://localhost/...` and falls through to
     * the 404, which is precisely how the failure stayed invisible in production.
     */
    private fun client() = HttpClient(
        MockEngine { request ->
            val onOurHost = request.url.host == STREAM_HOST_NAME
            val path = request.url.encodedPath
            val body = when {
                !onOurHost -> null
                path.contains("/live/event15") && path.endsWith("playlist.m3u8") ->
                    "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1200000\nchunklist.m3u8\n"
                path.contains("/live/event15") && path.endsWith("chunklist.m3u8") ->
                    "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2.0,\nseg0.ts\n#EXT-X-ENDLIST\n"
                else -> null
            }
            if (body == null) {
                respond(content = "", status = HttpStatusCode.NotFound)
            } else {
                respond(
                    content = body,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/vnd.apple.mpegurl"),
                )
            }
        },
    )

    @Test
    fun theProbeUrlsAreAbsolute() {
        val config = MediaKitConfig.forStreamUrl(PLAYED_URL)!!

        assertEquals("$HOST/live/event15_aac/playlist.m3u8?DVR", config.renditionUrl(15, RenditionTier.AUDIO))
    }
}
