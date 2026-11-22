package com.livingpresence.inner.circle.squared

import com.livingpresence.mediakit.MediaKitConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A finished download as iOS records it, so it outlives the process.
 *
 * AVFoundation writes an HLS download to a package of its own choosing and
 * reports where only once, in the delegate callback. Nothing else can find it
 * again, so the location is saved the moment it is reported. It is relative to
 * the home directory because the container's absolute path changes when the
 * app is updated or reinstalled, which would orphan every saved download.
 *
 * @property eventNumber Feed ID the download is keyed by, as everywhere else.
 * @property streamUrl The URL that was downloaded. The offline fallback plays
 *   it, and the player matches it to find this file.
 * @property relativePath Package location relative to `NSHomeDirectory()`.
 */
@Serializable
internal data class SavedDownload(
    @SerialName("event_number") val eventNumber: Int,
    @SerialName("stream_url") val streamUrl: String,
    @SerialName("relative_path") val relativePath: String,
)

internal object SavedDownloads {

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(downloads: List<SavedDownload>): String = json.encodeToString(downloads)

    /** Unreadable or missing data reads as no downloads rather than a crash at launch. */
    fun decode(text: String?): List<SavedDownload> =
        text?.let { runCatching { json.decodeFromString<List<SavedDownload>>(it) }.getOrNull() }.orEmpty()

    /**
     * The saved download that holds the stream [url] plays, if any.
     *
     * The gallery hands the player a numbered event's master URL, but what was
     * downloaded is one rendition of it (`event15_360p`), so the two are matched
     * on host and stream number, not by string. Any other URL matches only itself.
     */
    fun matching(url: String, downloads: Collection<SavedDownload>): SavedDownload? {
        val key = streamKey(url)
        return downloads.firstOrNull { streamKey(it.streamUrl) == key }
    }

    private fun streamKey(url: String): String {
        val number = MediaKitConfig.eventNumberIn(url) ?: return url
        return url.substringBefore(EVENT_PATH) + EVENT_PATH + number
    }

    private const val EVENT_PATH = "/live/event"
}

/**
 * Download progress as the gallery shows it, 0–100, from the seconds of media
 * loaded so far against the seconds the download will fetch in total.
 */
internal fun downloadPercent(loadedSeconds: List<Double>, expectedSeconds: Double): Float {
    if (expectedSeconds <= 0.0 || expectedSeconds.isNaN()) return 0f
    val loaded = loadedSeconds.filterNot { it.isNaN() }.sum()
    return (loaded / expectedSeconds * 100.0).toFloat().coerceIn(0f, 100f)
}
