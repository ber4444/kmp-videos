package com.livingpresence.inner.circle.squared

import com.livingpresence.mediakit.EventCatalog
import com.livingpresence.mediakit.EventInfo
import com.livingpresence.mediakit.ExtraVideoCatalog
import com.livingpresence.mediakit.MediaKitConfig
import io.ktor.client.HttpClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Adapter that exposes the feed to the app's ViewModel: the numbered events from
 * the `:mediakit` SDK's [EventCatalog], followed by the extra videos listed in
 * the remote manifest ([ExtraVideoCatalog]).
 *
 * The actual probing (parallel fetch, 404 exclusion, live/duration metadata
 * extraction via playlist inspection) lives in the SDK. This wrapper keeps the
 * app's [MainViewModel] decoupled from the SDK's HTTP client wiring.
 *
 * ## Restricted accounts
 * [policyClient] asks `:server` whether this account is confined to a manifest of
 * its own — the review accounts used for app-store submission are, and see that
 * manifest *instead of* the catalogue rather than alongside it. The app holds no
 * account list and no demo URL of its own; both are `:server` configuration. See
 * [FeedPolicyClient] for why.
 */
open class VideoRepository(
    private val httpClient: HttpClient,
    private val catalog: EventCatalog = EventCatalog(httpClient, MediaKitConfig.Default),
    private val extras: ExtraVideoCatalog? = null,
    private val policyClient: FeedPolicyClient? = null,
) {

    /**
     * The catalogue built for the last restricted manifest URL the server named.
     *
     * Kept so repeat loads reuse one [ExtraVideoCatalog] — and therefore its
     * manifest cache — rather than re-fetching on every gallery open. Deliberately
     * *not* given the app's persistent [com.livingpresence.mediakit.ManifestStore]:
     * that store holds a single manifest under one key, so sharing it would have
     * the two feeds overwrite each other.
     */
    private var restricted: Pair<String, ExtraVideoCatalog>? = null
    private val restrictedLock = Mutex()

    /**
     * The full feed (event number, isLive, duration, title, stream URL): probed
     * events newest-first, then the manifest extras in manifest order.
     *
     * For an account `:server` has restricted, the feed is exactly that account's
     * manifest — the events are never probed and the build-time extras are never
     * fetched, so nothing from the real catalogue can reach it.
     *
     * A failure to load the extras is swallowed — the manifest is a bolt-on, and
     * losing it should not take the events down with it. A failure to probe the
     * events still propagates, so the gallery can offer its retry.
     *
     * @param forceRefresh Bypass both caches (event probes and manifest body).
     */
    open suspend fun loadEvents(forceRefresh: Boolean = false): List<EventInfo> = coroutineScope {
        restrictedManifestUrl()?.let { url ->
            return@coroutineScope runCatching {
                restrictedCatalog(url).loadExtras(forceRefresh)
            }.getOrDefault(emptyList())
        }
        val events = async { catalog.loadEvents(forceRefresh) }
        val extraVideos = async {
            runCatching { extras?.loadExtras(forceRefresh).orEmpty() }.getOrDefault(emptyList())
        }
        events.await() + extraVideos.await()
    }

    /** Just the event numbers, for any call site that still needs them. */
    open suspend fun getAvailableVideos(): List<Int> = loadEvents().map { it.eventNumber }

    /**
     * The manifest this account is confined to, or null for the ordinary feed.
     *
     * Every way of *not* getting an answer — no endpoint, not signed in, service
     * down — reads as "not restricted", so the gallery keeps working on the
     * build's own configuration. A refusal reads the same way here: this is the
     * feed's shape, not the gate, and the gate has already run by the time the
     * gallery is on screen.
     */
    private suspend fun restrictedManifestUrl(): String? =
        when (val result = policyClient?.fetch()) {
            is FeedPolicyResult.Resolved -> result.policy.restrictedManifestUrl?.takeIf { it.isNotBlank() }
            else -> null
        }

    private suspend fun restrictedCatalog(url: String): ExtraVideoCatalog = restrictedLock.withLock {
        restricted?.takeIf { it.first == url }?.second
            ?: ExtraVideoCatalog(httpClient = httpClient, manifestUrl = url)
                .also { restricted = url to it }
    }
}
