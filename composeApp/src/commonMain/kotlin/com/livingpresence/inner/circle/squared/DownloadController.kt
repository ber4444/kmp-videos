package com.livingpresence.inner.circle.squared

import androidx.compose.runtime.Composable
import com.livingpresence.mediakit.EventInfo
import com.livingpresence.mediakit.ExtraVideoCatalog
import com.livingpresence.mediakit.MediaKitConfig
import com.livingpresence.mediakit.RenditionTier
import kotlinx.coroutines.flow.StateFlow

/**
 * Per-event download state surfaced to the UI (common shape across platforms).
 *
 * [streamUrl] is the URL that was actually enqueued — a specific rendition of a
 * numbered event, or a manifest extra's own playlist. The offline fallback in
 * `GalleryScreen` needs it: when the feed cannot be loaded, this is the only
 * record of where a downloaded extra came from. Empty when the platform cannot
 * report it.
 */
internal data class EventDownloadState(
    val eventNumber: Int,
    val state: DownloadStatus,
    val percent: Float,
    val streamUrl: String = "",
)

/**
 * Per-event download lifecycle.
 *
 * [WAITING] is distinct from [QUEUED]: the download is accepted but cannot make
 * progress until a platform requirement is met (no network, or Wi-Fi-only is on
 * and the device is metered). Showing it as `0%` is indistinguishable from a
 * stalled transfer, so the UI calls it out.
 */
internal enum class DownloadStatus { QUEUED, WAITING, DOWNLOADING, COMPLETED, FAILED, REMOVING, NOT_DOWNLOADED }

/** User-selectable download quality. ~220 MB/h at 360p vs ~450 MB/h at 720p. */
internal enum class DownloadQuality { P720, P360, P160, AUDIO }

/** Maps a user-facing [DownloadQuality] to the mediakit [RenditionTier]. */
internal fun DownloadQuality.toRenditionTier(): RenditionTier = when (this) {
    DownloadQuality.P720 -> RenditionTier.P720
    DownloadQuality.P360 -> RenditionTier.P360
    DownloadQuality.P160 -> RenditionTier.P160
    DownloadQuality.AUDIO -> RenditionTier.AUDIO
}

/**
 * Platform abstraction over offline downloads. Android backs this with the
 * `DownloadCenter` (DownloadService + WorkManager + SimpleCache); wasmJs is a
 * no-op (web has no offline-download path in this phase).
 *
 * Only bounded (non-live) events are downloadable — [EventInfo.isLive] gates
 * the download affordance in the UI. Feed extras are downloadable too, at
 * whatever single rendition their playlist offers: [DownloadQuality] applies
 * only to entries with the ladder ([EventInfo.hasRenditionLadder]).
 */
internal interface DownloadController {
    /** True if the platform supports background downloading of VOD events. */
    val isSupported: Boolean

    /** A map of event number → download state, kept current. */
    val states: StateFlow<Map<Int, EventDownloadState>>

    /** Enqueue a download for [event] at [tier] (default 360p for size). No-op for live events. */
    fun enqueue(event: EventInfo, tier: DownloadQuality = DownloadQuality.P360)

    /** Remove a downloaded event. */
    fun remove(eventNumber: Int)

    /** Refresh the state snapshot from the underlying download manager. */
    fun refresh()
}

/** Platform download-controller factory. Android wires the DownloadCenter; wasmJs is a no-op. */
@Composable
internal expect fun rememberDownloadController(): DownloadController

/** Resolve a download from the stream URL, independently of its feed/cache ID. */
internal fun EventInfo.downloadUrl(tier: RenditionTier): String {
    val streamNumber = MediaKitConfig.eventNumberIn(streamUrl) ?: return streamUrl
    val streamConfig = MediaKitConfig.forStreamUrl(streamUrl) ?: return streamUrl
    return streamConfig.renditionUrl(streamNumber, tier)
}

/**
 * Completed downloads as gallery tiles, for when the feed cannot be loaded.
 *
 * What was downloaded is a specific rendition URL, and for a manifest entry it
 * is the only way back to the stream: the feed that named it is exactly what
 * failed to load. Only a real event number (below
 * [ExtraVideoCatalog.EXTRA_EVENT_NUMBER_BASE]) can rebuild a missing URL. A
 * manifest extra or public concert with no recorded URL is left out, because
 * rebuilding from its synthetic ID would play a stream that does not exist.
 */
internal fun Collection<EventDownloadState>.toOfflineEvents(): List<EventInfo> =
    filter { it.state == DownloadStatus.COMPLETED }
        .mapNotNull { state ->
            val url = state.streamUrl.ifEmpty {
                if (state.eventNumber < ExtraVideoCatalog.EXTRA_EVENT_NUMBER_BASE) {
                    MediaKitConfig.Default.eventUrl(state.eventNumber)
                } else {
                    return@mapNotNull null
                }
            }
            EventInfo(
                eventNumber = state.eventNumber,
                isLive = false,
                durationMs = 0L,
                title = MediaKitConfig.eventNumberIn(url)
                    ?.let { "Event $it" }
                    ?: ExtraVideoCatalog.titleFromUrl(url),
                streamUrl = url,
            )
        }
