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
 * the `:mediakit` SDK's [EventCatalog], followed by the extra videos listed in a
 * remote manifest ([ExtraVideoCatalog]).
 *
 * The actual probing (parallel fetch, 404 exclusion, live/duration metadata
 * extraction via playlist inspection) lives in the SDK. This wrapper keeps the
 * app's [MainViewModel] decoupled from the SDK's HTTP client wiring.
 *
 * ## Where the feed comes from
 * Both addresses — the stream host and the manifest URL — arrive from `:server`
 * per account, and the app has neither of its own. An Apollo member is given the
 * host and the members' manifest; a review account is given only its demo
 * manifest, so for it the numbered events are not filtered out but unreachable.
 * Nothing is hardcoded to fall back on, which is what makes the membership check
 * hold rather than merely display. See [FeedPolicyClient].
 *
 * The catalogues are therefore built here, once the policy has been read, rather
 * than injected at construction: their addresses are not known until an account
 * has connected.
 */
open class VideoRepository(
    private val httpClient: HttpClient,
    private val policyClient: FeedPolicyClient? = null,
) {

    /**
     * The catalogues built for the last addresses the server issued, so repeat
     * loads reuse the SDK's probe cache and manifest cache instead of starting
     * cold on every gallery open. Rebuilt whenever an address changes, which
     * drops the previous account's cached results with it.
     */
    private var events: Pair<String, EventCatalog>? = null
    private var extras: Pair<String, ExtraVideoCatalog>? = null
    private val catalogLock = Mutex()

    /**
     * The full feed (event number, isLive, duration, title, stream URL): probed
     * events newest-first, then the manifest extras in manifest order.
     *
     * A failure to load the extras is swallowed — the manifest is a bolt-on, and
     * losing it should not take the events down with it. A failure to probe the
     * events still propagates, so the gallery can offer its retry, as does a
     * policy the service could not answer for.
     *
     * @param forceRefresh Bypass both caches (event probes and manifest body).
     */
    open suspend fun loadEvents(forceRefresh: Boolean = false): List<EventInfo> = coroutineScope {
        val policy = policy()
        // The SDK's global backs getUrl() and the offline fallback's URL rebuild,
        // so it has to track whatever this account was issued — including being
        // cleared back to empty when the account has no host.
        FeedConfig.streamHost = policy.streamHost

        val probed = async { eventCatalog(policy.streamHost)?.loadEvents(forceRefresh).orEmpty() }
        val extraVideos = async {
            runCatching { extraCatalog(policy.manifestUrl)?.loadExtras(forceRefresh).orEmpty() }
                .getOrDefault(emptyList())
        }
        probed.await() + extraVideos.await()
    }

    /** Just the event numbers, for any call site that still needs them. */
    open suspend fun getAvailableVideos(): List<Int> = loadEvents().map { it.eventNumber }

    /**
     * This account's feed addresses.
     *
     * An unreachable service throws, so the gallery shows its error and its retry
     * rather than an empty grid that looks like "there is nothing to watch". Not
     * signed in yet, no endpoint configured, and an outright refusal all resolve
     * to an empty feed instead: none of them is a fault the user can retry away,
     * and a refusal has already been handled at the gate.
     */
    private suspend fun policy(): FeedPolicy = when (val result = policyClient?.fetch()) {
        is FeedPolicyResult.Resolved -> result.policy
        is FeedPolicyResult.Absent ->
            if (result.reason == FeedPolicyAbsence.UNAVAILABLE) {
                throw FeedUnavailableException()
            } else {
                FeedPolicy()
            }
        null -> FeedPolicy()
    }

    private suspend fun eventCatalog(host: String): EventCatalog? = catalogLock.withLock {
        if (host.isBlank()) return@withLock null
        events?.takeIf { it.first == host }?.second
            ?: EventCatalog(httpClient, MediaKitConfig(host)).also { events = host to it }
    }

    private suspend fun extraCatalog(manifestUrl: String): ExtraVideoCatalog? = catalogLock.withLock {
        if (manifestUrl.isBlank()) return@withLock null
        extras?.takeIf { it.first == manifestUrl }?.second
            ?: ExtraVideoCatalog(httpClient = httpClient, manifestUrl = manifestUrl)
                .also { extras = manifestUrl to it }
    }
}

/**
 * The feed service could not be reached, so what this account may watch is
 * unknown.
 *
 * Distinct from an empty feed on purpose: the gallery renders this as a failure
 * with a retry, where an empty list would claim there is nothing to see.
 */
class FeedUnavailableException : Exception("Could not reach the feed service. Check your connection.")
