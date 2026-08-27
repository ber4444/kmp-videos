package com.livingpresence.mediakit

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime

/** One line of the extras manifest: a playlist URL plus the label to show. */
public data class ExtraVideo(
    public val url: String,
    public val title: String,
)

/** A manifest body together with the wall-clock time it was fetched. */
public data class CachedManifest(
    public val body: String,
    public val fetchedAtEpochMs: Long,
)

/**
 * Where the fetched manifest is kept between calls — and, on the platforms that
 * implement it, between launches.
 *
 * A day-long TTL is only worth anything if the cache outlives the process, so
 * the app supplies a store backed by its platform's key-value storage. The
 * default [InMemoryManifestStore] keeps the SDK usable (and testable) on its
 * own; it simply re-fetches after every cold start.
 */
public interface ManifestStore {
    /** The stored manifest, or null when nothing has been cached (or it is unreadable). */
    public fun read(): CachedManifest?

    /** Stores [manifest], replacing any previous one. Failures must not throw. */
    public fun write(manifest: CachedManifest)
}

/** Process-lifetime [ManifestStore]. The default when the host supplies none. */
public class InMemoryManifestStore : ManifestStore {
    private var entry: CachedManifest? = null
    override fun read(): CachedManifest? = entry
    override fun write(manifest: CachedManifest) {
        entry = manifest
    }
}

/**
 * The extra videos appended to the feed, listed in a plain-text manifest hosted
 * outside the app (a secret gist, say) so the list can change without shipping a
 * release.
 *
 * ## Manifest format
 * One video per line, URL first, with an optional title after it:
 * ```
 * # comments and blank lines are ignored
 * https://host/vod/a-recording-8-20-26/playlist.m3u8?DVR   A Recording, Aug 20
 * https://host/vod/some-other-talk/playlist.m3u8?DVR
 * ```
 * A line with no title gets one derived from its URL path. Duplicate URLs are
 * dropped, keeping the first occurrence.
 *
 * ## Caching
 * The manifest body is cached in [store] and reused for [ttl] (a day by
 * default), so the list is fetched roughly once a day rather than on every
 * gallery open. When a refresh fails, a *stale* cached body is used rather than
 * losing the videos — the manifest changes rarely, and a network blip should not
 * empty the feed.
 *
 * ## Probing
 * Each URL is probed like a numbered event, so extras get the same LIVE badge
 * and duration label. A 4xx drops the entry (a dead link is worse than a missing
 * one); any other probe failure keeps it, unprobed, as a bounded video.
 *
 * @param httpClient Ktor client used for the manifest fetch and the probes.
 * @param manifestUrl Absolute URL of the manifest. Blank disables extras
 *   entirely — the feed is then exactly what [EventCatalog] returns.
 * @param store Where the fetched body is cached. See [ManifestStore].
 * @param ttl How long a cached body is reused before re-fetching.
 * @param maxConcurrency Bounds concurrent probes, as in [EventCatalog].
 * @param nowEpochMs Wall clock, injectable so TTL behaviour is testable.
 */
public class ExtraVideoCatalog(
    httpClient: HttpClient,
    private val manifestUrl: String,
    private val store: ManifestStore = InMemoryManifestStore(),
    private val ttl: Duration = DEFAULT_TTL,
    private val maxConcurrency: Int = EventCatalog.DEFAULT_MAX_CONCURRENCY,
    private val maxProbeAttempts: Int = EventCatalog.DEFAULT_MAX_PROBE_ATTEMPTS,
    private val retryBackoff: Duration = EventCatalog.DEFAULT_RETRY_BACKOFF,
    private val nowEpochMs: () -> Long = ::systemEpochMs,
) {
    init {
        require(ttl >= Duration.ZERO) { "ttl must be non-negative" }
        require(maxConcurrency > 0) { "maxConcurrency must be > 0" }
    }

    private val client = httpClient
    private val probe = PlaylistProbe(httpClient)

    /**
     * The manifest's videos as feed entries, probed for live/duration metadata
     * and numbered from [EXTRA_EVENT_NUMBER_BASE] up (in manifest order).
     *
     * Returns an empty list when no manifest is configured, when it cannot be
     * fetched and nothing was ever cached, or when every entry 404s.
     *
     * @param forceRefresh Bypass the cached body and re-fetch the manifest.
     */
    public suspend fun loadExtras(forceRefresh: Boolean = false): List<EventInfo> {
        val videos = loadManifest(forceRefresh)
        if (videos.isEmpty()) return emptyList()

        val gate = Semaphore(maxConcurrency)
        return coroutineScope {
            videos
                .mapIndexed { index, video ->
                    async {
                        val outcome = gate.withPermit {
                            probe.probeWithRetry(video.url, maxProbeAttempts, retryBackoff)
                        }
                        when (outcome) {
                            // A link that is genuinely gone: drop it, exactly as
                            // EventCatalog drops a 404 event.
                            ProbeOutcome.Missing -> null
                            // Unreachable for now — keep the curated entry and
                            // let playback report any real problem.
                            ProbeOutcome.Transient -> entry(index, video, isLive = false, durationMs = 0L)
                            is ProbeOutcome.Found ->
                                entry(index, video, outcome.isLive, outcome.durationMs)
                        }
                    }
                }
                .awaitAll()
                .filterNotNull()
        }
    }

    /**
     * The parsed manifest, from cache when it is younger than [ttl] and from the
     * network otherwise. A failed fetch falls back to whatever is cached, however
     * stale.
     */
    public suspend fun loadManifest(forceRefresh: Boolean = false): List<ExtraVideo> {
        if (manifestUrl.isBlank()) return emptyList()

        val cached = runCatching { store.read() }.getOrNull()
        if (!forceRefresh && cached != null && isFresh(cached)) {
            return parseManifest(cached.body)
        }

        val fetched = fetchManifest()
        if (fetched != null) {
            runCatching { store.write(CachedManifest(fetched, nowEpochMs())) }
            return parseManifest(fetched)
        }
        return cached?.let { parseManifest(it.body) } ?: emptyList()
    }

    private fun isFresh(cached: CachedManifest): Boolean {
        val age = (nowEpochMs() - cached.fetchedAtEpochMs).milliseconds
        // A negative age means the clock moved backwards since the write; treat
        // that as stale rather than trusting an entry from the future.
        return age >= Duration.ZERO && age < ttl
    }

    private suspend fun fetchManifest(): String? {
        val response: HttpResponse = runCatching { client.get(manifestUrl) }
            .onFailure { println("ExtraVideoCatalog: manifest request failed: $it") }
            .getOrNull() ?: return null
        if (!response.status.isSuccess()) {
            println("ExtraVideoCatalog: manifest fetch returned ${response.status}")
            return null
        }
        return runCatching { response.bodyAsText() }
            .onFailure { println("ExtraVideoCatalog: manifest bodyAsText failed: $it") }
            .getOrNull()
    }

    private fun entry(index: Int, video: ExtraVideo, isLive: Boolean, durationMs: Long): EventInfo =
        EventInfo(
            eventNumber = EXTRA_EVENT_NUMBER_BASE + index,
            isLive = isLive,
            durationMs = durationMs,
            title = video.title,
            streamUrl = video.url,
        )

    public companion object {
        /** Manifest bodies are reused for a day before the next fetch. */
        public val DEFAULT_TTL: Duration = 1.days

        /**
         * Synthetic event numbers for manifest extras start here, well clear of
         * the numbered events (1..[MediaKitConfig.DEFAULT_MAX_EVENT_NUMBER]) so
         * the two never collide as feed keys.
         */
        public const val EXTRA_EVENT_NUMBER_BASE: Int = 10_000

        /**
         * Parses a manifest body: one video per line, `#` comments and blank
         * lines ignored, URL first, optional title after the first run of
         * whitespace (or a `|`). Non-http lines and duplicate URLs are dropped.
         */
        public fun parseManifest(body: String): List<ExtraVideo> =
            body.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val url = line.split(SEPARATOR, limit = 2).first().trim()
                    if (!url.startsWith("http://") && !url.startsWith("https://")) return@mapNotNull null
                    val title = line.removePrefix(url).trim().trimStart('|').trim()
                    ExtraVideo(url, title.ifEmpty { titleFromUrl(url) })
                }
                .distinctBy { it.url }
                .toList()

        /**
         * A readable label for a URL with no title in the manifest:
         * `…/vod/a-recording-8-20-26/playlist.m3u8?DVR` → `A recording 8 20 26`.
         */
        public fun titleFromUrl(url: String): String {
            val path = url.substringBefore('?').substringBefore('#')
            val slug = path.trimEnd('/')
                .split('/')
                .asReversed()
                .firstOrNull { segment ->
                    segment.isNotBlank() && !segment.endsWith(".m3u8") && !segment.endsWith(".mp4")
                }
                ?: return "Video"
            val words = slug.replace('-', ' ').replace('_', ' ').trim()
            return words.replaceFirstChar { it.uppercase() }.ifEmpty { "Video" }
        }

        private val SEPARATOR = Regex("""\s+|\|""")
    }
}

/** Wall clock in epoch milliseconds — the default for [ExtraVideoCatalog.nowEpochMs]. */
@OptIn(ExperimentalTime::class)
private fun systemEpochMs(): Long = Clock.System.now().toEpochMilliseconds()
