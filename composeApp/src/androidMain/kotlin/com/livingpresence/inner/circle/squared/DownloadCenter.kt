package com.livingpresence.inner.circle.squared

import android.content.Context
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.MimeTypes
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import com.livingpresence.mediakit.EventInfo
import com.livingpresence.mediakit.MediaKitConfig
import com.livingpresence.mediakit.RenditionTier
import java.io.File

private const val TAG = "DownloadCenter"
private const val DOWNLOAD_CONTENT_DIR = "media_downloads"

/**
 * Offline downloads: a [DownloadManager] + [SimpleCache] backed by
 * [DownloadsService] (so unmet-requirement restarts go through WorkManager).
 *
 * Eligibility: only bounded (non-live) events are downloadable — a live window's
 * playlist has no `#EXT-X-ENDLIST`, so it isn't a finite download. Live events
 * get a LIVE badge and no download affordance.
 *
 * Downloads target a concrete rendition URL (default `_360p`) rather than the
 * synthesized `data:`-URI ladder — this keeps `DownloadManager` cache keys
 * stable and sidesteps chunklist `w`-token rotation for stored content.
 *
 * The cache is shared with playback via [cacheDataSourceFactory], so a
 * downloaded event plays straight from disk (airplane-mode playback is the
 * acceptance test).
 *
 * Network policy: downloads only need *a* connected network by default, matching
 * playback — the same 360p bytes cost the same whether streamed or stored. Call
 * [setWifiOnly] to restrict them to unmetered networks; while a requirement is
 * unmet the UI reports [DownloadState.WAITING] rather than a silent 0%.
 */
@UnstableApi
class DownloadCenter private constructor(
    private val context: Context,
    val config: MediaKitConfig,
    internal val downloadManager: DownloadManager,
    val cache: SimpleCache,
) {
    /** Per-event download state observed by the UI. */
    data class EventDownloadState(
        val eventNumber: Int,
        val state: DownloadState,
        val percent: Float,
        /** The rendition/playlist URL this download was enqueued with. */
        val streamUrl: String,
    )

    enum class DownloadState { QUEUED, WAITING, DOWNLOADING, COMPLETED, FAILED, REMOVING }

    /** True while downloads are held back solely because a [Requirements] flag is unmet. */
    val isWaitingForRequirements: Boolean
        get() = downloadManager.notMetRequirements != 0

    /**
     * Restrict downloads to unmetered (Wi-Fi) networks, or allow any connected
     * network. Applies to every queued and in-flight download immediately.
     */
    fun setWifiOnly(wifiOnly: Boolean) {
        downloadManager.requirements = requirementsFor(wifiOnly)
    }

    /**
     * Snapshot of all known download states, keyed by event number.
     *
     * Terminal states (completed / failed) live only in the persisted index;
     * in-flight downloads come from [DownloadManager.getCurrentDownloads], whose
     * `Download.progress` is the live object the download task mutates. Reading
     * progress from the index alone would report whatever percentage was last
     * flushed to SQLite — 0% for the first five seconds of every download, and
     * 0% forever for anything that never leaves the queue.
     */
    fun snapshot(): Map<Int, EventDownloadState> = indexSnapshot() + activeSnapshot()

    /** Persisted download index (SQLite). Includes terminal states; progress is stale. */
    fun indexSnapshot(): Map<Int, EventDownloadState> {
        val result = mutableMapOf<Int, EventDownloadState>()
        runCatching {
            downloadManager.downloadIndex.getDownloads().use { cursor ->
                while (cursor.moveToNext()) result.putState(cursor.download)
            }
        }.onFailure { Log.w(TAG, "Could not read the download index.", it) }
        return result
    }

    /** In-flight (non-terminal) downloads, held in memory with live progress. */
    fun activeSnapshot(): Map<Int, EventDownloadState> {
        val result = mutableMapOf<Int, EventDownloadState>()
        downloadManager.currentDownloads.forEach { result.putState(it) }
        return result
    }

    private fun MutableMap<Int, EventDownloadState>.putState(download: Download) {
        val eventNumber = download.request.id.toIntOrNull() ?: return
        put(
            eventNumber,
            EventDownloadState(
                eventNumber = eventNumber,
                state = downloadState(download.state, isWaitingForRequirements),
                // HLS reports C.PERCENTAGE_UNSET (-1) until the segment count is known.
                percent = download.percentDownloaded.coerceIn(0f, 100f),
                streamUrl = download.request.uri.toString(),
            ),
        )
    }

    /** Enqueue a download for [event] at [tier] (default 360p for size). */
    fun enqueue(event: EventInfo, tier: RenditionTier = RenditionTier.P360) {
        require(!event.isLive) { "Cannot download a live (unbounded) event." }
        // Only numbered events have the `_360p`/`_160p`/`_aac` siblings a tier
        // selects between; a feed extra is downloaded from its own playlist.
        val url = if (event.hasRenditionLadder) {
            config.renditionUrl(event.eventNumber, tier)
        } else {
            event.streamUrl
        }
        val request = DownloadRequest.Builder(event.eventNumber.toString(), android.net.Uri.parse(url))
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .build()
        // Go through the service so it comes up in the foreground: a plain
        // addDownload() leaves the work in a background service the platform stops
        // shortly after the app stops being visible, stranding the download.
        val sent = runCatching {
            DownloadService.sendAddDownload(
                context,
                DownloadsService::class.java,
                request,
                /* foreground= */ true,
            )
        }.onFailure { Log.w(TAG, "Foreground download start refused; enqueueing directly.", it) }
            .isSuccess
        if (!sent) downloadManager.addDownload(request)
    }

    /** Remove a downloaded event from the cache + index. */
    fun remove(eventNumber: Int) {
        val sent = runCatching {
            DownloadService.sendRemoveDownload(
                context,
                DownloadsService::class.java,
                eventNumber.toString(),
                /* foreground= */ false,
            )
        }.onFailure { Log.w(TAG, "Download removal via service refused; removing directly.", it) }
            .isSuccess
        if (!sent) downloadManager.removeDownload(eventNumber.toString())
    }

    /**
     * A read-only [CacheDataSource.Factory] that prefers the download cache,
     * then HTTP.
     *
     * Read-only is load-bearing, not an optimization. A live event's chunklist
     * has no `#EXT-X-ENDLIST`, so [androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistTracker]
     * reloads it every target duration to discover new segments. If playback is
     * allowed to write, the first reload caches the chunklist under its URL and
     * every reload after that replays those same bytes — the snapshot never
     * changes, and once it has been unchanged for 3.5x the target duration the
     * tracker fails the source with `PlaylistStuckException`. The stale entry
     * outlives the player, so retrying just reproduces it.
     *
     * Writing is therefore left to [DownloadManager], which is the only thing
     * that should be populating this cache: downloaded events still play from
     * disk (airplane-mode playback is the acceptance test), while streamed ones
     * always go upstream. This also stops a streamed event from evicting
     * downloaded content through the [LeastRecentlyUsedCacheEvictor].
     */
    fun cacheDataSourceFactory(): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(
            DefaultDataSource.Factory(context, DefaultHttpDataSource.Factory()),
        )
        .setCacheWriteDataSinkFactory(null)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    companion object {
        @Volatile
        private var instance: DownloadCenter? = null

        fun get(context: Context, config: MediaKitConfig = MediaKitConfig.Default): DownloadCenter =
            instance ?: synchronized(this) {
                instance ?: create(context, config).also { instance = it }
            }

        /**
         * Maps a media3 [Download.STATE_*] to the UI-facing [DownloadState].
         *
         * A queued download whose [Requirements] are unmet is reported as
         * [DownloadState.WAITING]: it will not make progress until the device is
         * back on an acceptable network, and rendering it as "0%" is
         * indistinguishable from a download that has stalled.
         */
        internal fun downloadState(
            state: Int,
            isWaitingForRequirements: Boolean,
        ): DownloadState = when (state) {
            Download.STATE_DOWNLOADING -> DownloadState.DOWNLOADING
            Download.STATE_COMPLETED -> DownloadState.COMPLETED
            Download.STATE_FAILED -> DownloadState.FAILED
            Download.STATE_REMOVING -> DownloadState.REMOVING
            // QUEUED / RESTARTING / STOPPED: nothing is being transferred yet.
            else -> if (isWaitingForRequirements) DownloadState.WAITING else DownloadState.QUEUED
        }

        private fun requirementsFor(wifiOnly: Boolean) = Requirements(
            if (wifiOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK,
        )

        /**
         * Drops every cached resource that no [Download] owns.
         *
         * Playback used to share write access to this cache, which left live
         * chunklists in it — permanently fatal entries, because the HLS tracker
         * reads back an unchanging playlist and gives up with
         * `PlaylistStuckException` (see [cacheDataSourceFactory]). Making
         * playback read-only stops new ones being written but cannot clear the
         * ones already on disk, and a user has no way to reach them short of
         * clearing app storage. So they are swept once here.
         *
         * A download's resources are the ones under its request URL's directory
         * (the master playlist, the chunklist and the segments all share it),
         * and downloads in flight are kept along with completed ones so a
         * partial transfer is not thrown away. Anything else was written by
         * streaming and is safe to lose: it is re-fetched on demand.
         */
        @UnstableApi
        private fun purgeEntriesNotOwnedByADownload(
            cache: SimpleCache,
            downloadManager: DownloadManager,
        ) {
            val downloadDirectories = runCatching {
                buildSet {
                    downloadManager.downloadIndex.getDownloads().use { cursor ->
                        while (cursor.moveToNext()) {
                            add(cursor.download.request.uri.toString().substringBeforeLast('/') + "/")
                        }
                    }
                }
            }.getOrElse {
                // Without the index we cannot tell downloads from streamed
                // leftovers; keeping everything is the safe direction to err in.
                Log.w(TAG, "Could not read the download index; leaving the cache as-is.", it)
                return
            }

            cache.keys
                .filterNot { key -> downloadDirectories.any(key::startsWith) }
                .forEach { key ->
                    runCatching { cache.removeResource(key) }
                        .onFailure { Log.w(TAG, "Could not evict a stale cache entry.", it) }
                }
        }

        @UnstableApi
        private fun create(context: Context, config: MediaKitConfig): DownloadCenter {
            val appContext = context.applicationContext
            val cacheDir = File(appContext.cacheDir, DOWNLOAD_CONTENT_DIR)
            val databaseProvider = StandaloneDatabaseProvider(appContext)
            val cache = SimpleCache(
                cacheDir,
                LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES),
                databaseProvider,
            )

            val dataSourceFactory = DefaultDataSource.Factory(
                appContext,
                DefaultHttpDataSource.Factory(),
            )

            val downloadManager = DownloadManager(
                appContext,
                databaseProvider,
                cache,
                dataSourceFactory,
                /* executor = */ java.util.concurrent.Executors.newSingleThreadExecutor(),
            ).apply {
                requirements = requirementsFor(WIFI_ONLY_DEFAULT)
            }

            // Before anything can touch the cache: DownloadManager is constructed
            // paused, so this is the one point in the process where no download is
            // writing and no player is reading.
            purgeEntriesNotOwnedByADownload(cache, downloadManager)

            // DownloadManager is normally resumed by DownloadService.onCreate.
            // Resume it here too: if that service start is ever refused (a
            // background start on API 26+, say), every download would otherwise sit
            // in STATE_QUEUED at 0% for the life of the process. resumeDownloads()
            // is idempotent.
            downloadManager.resumeDownloads()

            return DownloadCenter(
                context = appContext,
                config = config,
                downloadManager = downloadManager,
                cache = cache,
            ).also {
                // Start the service so pending downloads resume and media3 registers
                // its DownloadManagerHelper — the helper is what brings the service
                // back to the foreground when a download later changes state.
                runCatching {
                    DownloadService.start(appContext, DownloadsService::class.java)
                }.onFailure { t -> Log.w(TAG, "Could not start the download service.", t) }
            }
        }

        /** Downloads cost the same bytes as streaming, so any connected network is fine. */
        private const val WIFI_ONLY_DEFAULT = false
        private const val MAX_CACHE_BYTES = 1L * 1024 * 1024 * 1024 // 1 GB
    }
}
