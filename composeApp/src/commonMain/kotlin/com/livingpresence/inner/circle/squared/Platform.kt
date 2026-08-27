package com.livingpresence.inner.circle.squared

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.livingpresence.mediakit.MediaKitConfig
import io.ktor.client.HttpClient

expect fun createHttpClient(): HttpClient

/**
 * The HTTP client engine and offline-download manager are provided via Koin DI,
 * not the platform object.
 */

@Composable
expect fun PlatformPlayerScreen(
    url: String,
    onClose: () -> Unit,
)

/**
 * Platform-specific click handler for event tiles.
 * Wasm bypasses the player screen and opens the stream in a new tab directly.
 */
expect fun onEventClick(eventNumber: Int, defaultAction: () -> Unit)

/**
 * Platform-specific thumbnail for a feed tile. Android renders a frame extracted
 * from the stream via a shared [PreviewFrameEngine]; wasmJs shows a poster
 * placeholder with a hover-to-play overlay.
 *
 * @param eventNumber Identity of the entry — the cache key for the extracted
 *   frame, not necessarily a number the URL can be derived from (manifest extras
 *   are numbered synthetically).
 * @param streamUrl The playlist to pull the frame from. For a numbered event the
 *   platform may substitute a cheaper rendition of the same stream.
 * @param contentDescription Accessibility description for the thumbnail.
 * @param modifier Layout modifier from the tile.
 */
@Composable
expect fun LiveEventThumbnail(
    eventNumber: Int,
    streamUrl: String,
    contentDescription: String?,
    modifier: Modifier,
)

/**
 * The base (720p) playlist URL for [eventNumber]. Delegates to [MediaKitConfig]
 * so the production host lives in one place (the `:mediakit` SDK) rather than
 * being sprinkled through app code.
 */
fun getUrl(eventNumber: Int): String =
    MediaKitConfig.Default.eventUrl(eventNumber)

/**
 * Extracts the event number from a stream/rendition [url], or null if none is
 * present — which is the case for the feed's manifest extras, whose URLs are
 * arbitrary and have no rendition ladder behind them.
 *
 * Delegates to [MediaKitConfig.eventNumberIn] so the URL scheme is described in
 * exactly one place.
 */
fun parseEventNumber(url: String): Int? = MediaKitConfig.eventNumberIn(url)

/**
 * Background for the landing screen — the `background_image` photo, cropped to
 * fill.
 *
 * iOS and wasm paint it from the shared `composeResources` copy. Android cannot:
 * the AGP KMP library plugin assembles composeResources for the iOS and wasm
 * targets only, so `Res.drawable.background_image` compiles but throws
 * `MissingResourceException` at runtime. The Android actual reads the host
 * module's `res/drawable` copy instead, via `HostBridge.backgroundDrawableResId`.
 */
@Composable
expect fun loginBackgroundModifier(): Modifier
