package com.livingpresence.inner.circle.squared

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.scheduler.Requirements
import com.livingpresence.mediakit.EventInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.concurrent.atomic.AtomicReference

/** How often in-flight progress is re-read while a download is running. */
private const val PROGRESS_POLL_MS = 500L

/**
 * Android [DownloadController] backed by [DownloadCenter]. Mirrors the
 * [DownloadManager]'s downloads into a [StateFlow] the UI observes.
 *
 * The controller is a process singleton: it wraps the singleton [DownloadCenter]
 * and registers a [DownloadManager.Listener], so creating one per composition
 * would pile up listeners and progress pollers that nothing ever cancels.
 */
@UnstableApi
class AndroidDownloadController private constructor(
    context: Context,
) : DownloadController {
    override val isSupported: Boolean = true

    private val center = DownloadCenter.get(context)

    private val _states = MutableStateFlow<Map<Int, EventDownloadState>>(emptyMap())
    override val states: StateFlow<Map<Int, EventDownloadState>> = _states.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pollingJob: Job? = null

    /**
     * Terminal + persisted states, cached from the SQLite index. Re-read on
     * download events only; the poll below overlays live progress on top of it
     * without touching the database.
     */
    private var indexStates: Map<Int, EventDownloadState> = emptyMap()
    private var activeIds: Set<Int> = emptySet()

    private val listener = object : DownloadManager.Listener {
        override fun onInitialized(downloadManager: DownloadManager) {
            // The manager restores its downloads asynchronously; until it does,
            // getCurrentDownloads() is empty.
            refresh()
            startPollingIfNeeded()
        }

        override fun onDownloadChanged(
            downloadManager: DownloadManager,
            download: Download,
            finalException: Exception?,
        ) {
            refresh()
            startPollingIfNeeded()
        }

        override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
            refresh()
        }

        override fun onRequirementsStateChanged(
            downloadManager: DownloadManager,
            requirements: Requirements,
            notMetRequirements: Int,
        ) {
            // Wi-Fi came back (or went away): QUEUED <-> WAITING flips here, and a
            // resumed download needs the poller running again.
            refresh()
            startPollingIfNeeded()
        }

        override fun onIdle(downloadManager: DownloadManager) {
            refresh()
        }
    }

    init {
        center.downloadManager.addListener(listener)
        refresh()
        startPollingIfNeeded()
    }

    override fun enqueue(event: EventInfo, tier: DownloadQuality) {
        if (event.isLive) return
        center.enqueue(event, tier.toRenditionTier())
        refresh()
        startPollingIfNeeded()
    }

    override fun remove(eventNumber: Int) {
        center.remove(eventNumber)
        refresh()
    }

    /** Full re-read: the persisted index plus live in-flight progress. */
    override fun refresh() {
        indexStates = center.indexSnapshot().mapValues { (_, v) -> v.toCommon() }
        publish()
    }

    /**
     * Overlays in-flight downloads (in-memory, live progress) on the cached index.
     * Returns `true` if the set of in-flight downloads changed, which means the
     * index is now stale — something reached a terminal state.
     */
    private fun publish(): Boolean {
        val active = center.activeSnapshot().mapValues { (_, v) -> v.toCommon() }
        _states.value = indexStates + active
        val changed = active.keys != activeIds
        activeIds = active.keys
        return changed
    }

    private fun startPollingIfNeeded() {
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch {
            while (isActive) {
                // A download that left the in-flight list completed or failed, so the
                // cached index needs re-reading to pick up its terminal state.
                if (publish()) refresh()
                val hasActive = _states.value.values.any {
                    it.state == DownloadStatus.DOWNLOADING || it.state == DownloadStatus.QUEUED
                }
                // WAITING is deliberately not polled: nothing moves until the
                // requirements change, and the listener wakes us when they do.
                if (!hasActive) break
                delay(PROGRESS_POLL_MS)
            }
        }
    }

    companion object {
        @Volatile
        private var instance: AndroidDownloadController? = null

        fun get(context: Context): AndroidDownloadController =
            instance ?: synchronized(this) {
                instance ?: AndroidDownloadController(context.applicationContext)
                    .also { instance = it }
            }
    }
}

private fun DownloadCenter.EventDownloadState.toCommon(): EventDownloadState =
    EventDownloadState(
        eventNumber = eventNumber,
        state = state.toCommon(),
        percent = percent,
        streamUrl = streamUrl,
    )

private fun DownloadCenter.DownloadState.toCommon(): DownloadStatus = when (this) {
    DownloadCenter.DownloadState.QUEUED -> DownloadStatus.QUEUED
    DownloadCenter.DownloadState.WAITING -> DownloadStatus.WAITING
    DownloadCenter.DownloadState.DOWNLOADING -> DownloadStatus.DOWNLOADING
    DownloadCenter.DownloadState.COMPLETED -> DownloadStatus.COMPLETED
    DownloadCenter.DownloadState.FAILED -> DownloadStatus.FAILED
    DownloadCenter.DownloadState.REMOVING -> DownloadStatus.REMOVING
}

/**
 * Wraps a [DownloadController] so the POST_NOTIFICATIONS runtime permission
 * (API 33+) is asked for at the moment the user starts their first download,
 * rather than up front on the landing screen.
 *
 * Downloading is a niche feature, and the permission only buys its progress
 * notification: a [DownloadsService] denied the permission still runs, the
 * system just drops what it posts. So the request is best-effort and gates
 * nothing — whichever way the dialog goes, the download is enqueued from the
 * result callback.
 */
private class NotificationPromptingDownloadController(
    private val context: Context,
    private val delegate: DownloadController,
    private val pending: AtomicReference<Pair<EventInfo, DownloadQuality>?>,
    private val launcher: ActivityResultLauncher<String>,
) : DownloadController by delegate {

    override fun enqueue(event: EventInfo, tier: DownloadQuality) {
        // Live events are not downloadable, so do not spend a permission prompt
        // on a call the delegate is about to drop anyway.
        if (event.isLive) return
        val granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            delegate.enqueue(event, tier)
            return
        }
        pending.set(event to tier)
        // Permanently denied: the system answers immediately with no dialog, and
        // the callback still enqueues.
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

/** Remembers the process-wide [AndroidDownloadController]. */
@Composable
actual fun rememberDownloadController(): DownloadController {
    val context = androidx.compose.ui.platform.LocalContext.current
    val delegate = remember { AndroidDownloadController.get(context) }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return delegate

    // Holds the requested download across the system dialog, which tears down
    // nothing but does suspend the interaction until the user answers.
    val pending = remember { AtomicReference<Pair<EventInfo, DownloadQuality>?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        pending.getAndSet(null)?.let { (event, tier) -> delegate.enqueue(event, tier) }
    }
    return remember(delegate, launcher) {
        NotificationPromptingDownloadController(context, delegate, pending, launcher)
    }
}
