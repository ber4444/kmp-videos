package com.livingpresence.inner.circle.squared

import com.livingpresence.mediakit.EventCatalog
import com.livingpresence.mediakit.EventInfo
import com.livingpresence.mediakit.ExtraVideoCatalog
import com.livingpresence.mediakit.MediaKitConfig
import io.ktor.client.HttpClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Adapter that exposes the feed to the app's ViewModel: the numbered events from
 * the `:mediakit` SDK's [EventCatalog], followed by the extra videos listed in
 * the remote manifest ([ExtraVideoCatalog]).
 *
 * The actual probing (parallel fetch, 404 exclusion, live/duration metadata
 * extraction via playlist inspection) lives in the SDK. This wrapper keeps the
 * app's [MainViewModel] decoupled from the SDK's HTTP client wiring.
 */
open class VideoRepository(
    httpClient: HttpClient,
    private val catalog: EventCatalog = EventCatalog(httpClient, MediaKitConfig.Default),
    private val extras: ExtraVideoCatalog? = null,
) {
    /**
     * The full feed (event number, isLive, duration, title, stream URL): probed
     * events newest-first, then the manifest extras in manifest order.
     *
     * A failure to load the extras is swallowed — the manifest is a bolt-on, and
     * losing it should not take the events down with it. A failure to probe the
     * events still propagates, so the gallery can offer its retry.
     *
     * @param forceRefresh Bypass both caches (event probes and manifest body).
     */
    open suspend fun loadEvents(forceRefresh: Boolean = false): List<EventInfo> = coroutineScope {
        val events = async { catalog.loadEvents(forceRefresh) }
        val extraVideos = async {
            runCatching { extras?.loadExtras(forceRefresh).orEmpty() }.getOrDefault(emptyList())
        }
        events.await() + extraVideos.await()
    }

    /** Just the event numbers, for any call site that still needs them. */
    open suspend fun getAvailableVideos(): List<Int> = loadEvents().map { it.eventNumber }
}
