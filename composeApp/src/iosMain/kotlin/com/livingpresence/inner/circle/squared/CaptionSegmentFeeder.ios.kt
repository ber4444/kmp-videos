@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.livingpresence.inner.circle.squared

import cnames.supported.AVPlayerBridge
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import platform.Foundation.NSData
import platform.Foundation.create
import kotlin.math.abs

/**
 * Feeds the caption pipeline on iOS by pulling the audio-only (`_aac`) rendition
 * over plain HTTP, rather than by tapping the player.
 *
 * The player's own audio is unreachable: `MTAudioProcessingTap` is not supported
 * for HTTP Live Streaming, so an audio mix installed on the playing item is
 * accepted without error and its process callback is simply never invoked (true
 * for the muxed variant *and* for `_aac` played on its own). The tap approach
 * cannot be made to work, so this reads the same audio from the other end.
 *
 * That rendition is cheap and convenient: ~51 kbps, and its segments are HLS
 * "packed audio" — an ID3 header followed by raw ADTS AAC — which
 * [AVPlayerBridge.decodeAudioSegment] decodes directly, no demuxer needed.
 *
 * Feeding is paced against the playhead rather than run flat out, so the
 * transcriber receives audio at roughly the rate a listener hears it and
 * captions stay aligned with what is on screen. A seek is detected as a jump
 * between the playhead and how far this has fed, and re-anchors the segment
 * cursor.
 */
internal class CaptionSegmentFeeder(private val http: HttpClient) {

    /**
     * Pull [chunklistUrl] and feed its audio to [CaptionAudioRouter] until the
     * calling coroutine is cancelled — which is what stops captions, since the
     * caller ties this to the toggle.
     *
     * @param positionSeconds the player's current position, read fresh each pass.
     */
    suspend fun stream(chunklistUrl: String, positionSeconds: () -> Double) {
        var segments = fetchSegments(chunklistUrl) ?: return
        if (segments.isEmpty()) return

        var cursor = indexCovering(segments, positionSeconds())
        var fedThroughSec = segments.getOrNull(cursor)?.startSec ?: 0.0

        while (currentCoroutineContext().isActive) {
            val here = positionSeconds()

            // The viewer seeked, or playback drifted far enough that what we are
            // feeding no longer matches what they hear. Re-anchor.
            if (abs(fedThroughSec - here) > RESYNC_THRESHOLD_SEC) {
                cursor = indexCovering(segments, here)
                fedThroughSec = segments.getOrNull(cursor)?.startSec ?: here
            }

            if (cursor >= segments.size) {
                // A live window slides, so refresh and keep going; a bounded VOD
                // playlist is simply finished.
                val refreshed = fetchSegments(chunklistUrl)
                if (refreshed == null || refreshed.size <= segments.size) {
                    delay(REFRESH_DELAY_MS)
                    if (refreshed != null && refreshed.size <= segments.size) continue
                    return
                }
                segments = refreshed
                continue
            }

            // Stay a little ahead of the playhead, never far ahead: the streaming
            // recognizer is meant to receive audio at about listening speed.
            if (fedThroughSec > here + LEAD_SEC) {
                delay(PACING_DELAY_MS)
                continue
            }

            val segment = segments[cursor]
            val bytes = runCatching { http.get(segment.url).readRawBytes() }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) {
                decodeAndFeed(bytes)
            }
            fedThroughSec = segment.startSec + segment.durationSec
            cursor += 1
        }
    }

    private fun decodeAndFeed(bytes: ByteArray) {
        val data = bytes.toNSData() ?: return
        AVPlayerBridge.decodeAudioSegment(data) { pcm, frames, channels, sampleRate ->
            CaptionAudioRouter.get().onPcm(pcm, frames, channels, sampleRate)
        }
    }

    private suspend fun fetchSegments(chunklistUrl: String): List<Segment>? =
        runCatching { parseSegments(http.get(chunklistUrl).bodyAsText(), chunklistUrl) }.getOrNull()

    private companion object {
        /** How far ahead of the playhead to feed before waiting. */
        const val LEAD_SEC = 6.0

        /** A playhead/feed gap this large means a seek, not drift. */
        const val RESYNC_THRESHOLD_SEC = 10.0

        const val PACING_DELAY_MS = 250L
        const val REFRESH_DELAY_MS = 2_000L
    }
}

/** One entry of a media playlist, with where it starts in the timeline. */
private data class Segment(
    val url: String,
    val durationSec: Double,
    val startSec: Double,
)

/**
 * Parse `#EXTINF` durations and their segment URIs out of a media playlist.
 *
 * `PlaylistInspector.parseMediaPlaylist` in `:mediakit` reads the same file but
 * reports only the *total* duration, and the cursor here needs each segment's
 * own so it can map a playhead position onto a segment. Adding that upstream
 * would widen a binary-compatibility-validated public API for one caller.
 */
private fun parseSegments(playlistText: String, chunklistUrl: String): List<Segment> {
    val segments = mutableListOf<Segment>()
    var pendingDuration = 0.0
    var start = 0.0
    for (raw in playlistText.lines()) {
        val line = raw.trim()
        when {
            line.startsWith("#EXTINF:") ->
                pendingDuration = line.removePrefix("#EXTINF:")
                    .substringBefore(',')
                    .trim()
                    .toDoubleOrNull() ?: 0.0

            line.isNotEmpty() && !line.startsWith("#") -> {
                segments += Segment(resolveAgainst(chunklistUrl, line), pendingDuration, start)
                start += pendingDuration
                pendingDuration = 0.0
            }
        }
    }
    return segments
}

/** Resolve a playlist-relative segment reference against the playlist's own URL. */
private fun resolveAgainst(chunklistUrl: String, reference: String): String = when {
    reference.startsWith("http://") || reference.startsWith("https://") -> reference
    else -> chunklistUrl.substringBefore('?').substringBeforeLast('/') + "/" + reference
}

/** Index of the segment containing [positionSec], clamped to the playlist. */
private fun indexCovering(segments: List<Segment>, positionSec: Double): Int {
    if (segments.isEmpty()) return 0
    val found = segments.indexOfLast { it.startSec <= positionSec }
    return if (found < 0) 0 else found
}

private fun ByteArray.toNSData(): NSData? {
    if (isEmpty()) return null
    return usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    }
}
