package com.livingpresence.inner.circle.squared

import androidx.compose.runtime.Composable
import com.livingpresence.mediakit.EventInfo
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
data class EventDownloadState(
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
enum class DownloadStatus { QUEUED, WAITING, DOWNLOADING, COMPLETED, FAILED, REMOVING, NOT_DOWNLOADED }

/** User-selectable download quality. ~220 MB/h at 360p vs ~450 MB/h at 720p. */
enum class DownloadQuality { P720, P360, P160, AUDIO }

/** Maps a user-facing [DownloadQuality] to the mediakit [RenditionTier]. */
fun DownloadQuality.toRenditionTier(): RenditionTier = when (this) {
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
interface DownloadController {
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
expect fun rememberDownloadController(): DownloadController
