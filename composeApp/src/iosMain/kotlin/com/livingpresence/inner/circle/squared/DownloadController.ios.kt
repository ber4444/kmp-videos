package com.livingpresence.inner.circle.squared

import androidx.compose.runtime.Composable
import com.livingpresence.mediakit.EventInfo
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import kotlinx.cinterop.useContents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import platform.AVFoundation.AVAssetDownloadDelegateProtocol
import platform.AVFoundation.AVAssetDownloadTask
import platform.AVFoundation.AVAssetDownloadURLSession
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.CMTimeRangeValue
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeRange
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSURLErrorCancelled
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSValue
import platform.darwin.NSObject

@OptIn(ExperimentalForeignApi::class)
private class DownloadDelegate(
    private val onProgress: (NSURLSessionTask, Float) -> Unit,
    private val onLocation: (NSURLSessionTask, NSURL) -> Unit,
    private val onComplete: (NSURLSessionTask, NSError?) -> Unit,
) : NSObject(), AVAssetDownloadDelegateProtocol {

    override fun URLSession(
        session: NSURLSession,
        assetDownloadTask: AVAssetDownloadTask,
        didLoadTimeRange: CValue<CMTimeRange>,
        totalTimeRangesLoaded: List<*>,
        timeRangeExpectedToLoad: CValue<CMTimeRange>,
    ) {
        val loaded = totalTimeRangesLoaded.mapNotNull { value ->
            (value as? NSValue)?.CMTimeRangeValue?.seconds()
        }
        onProgress(assetDownloadTask, downloadPercent(loaded, timeRangeExpectedToLoad.seconds()))
    }

    override fun URLSession(
        session: NSURLSession,
        assetDownloadTask: AVAssetDownloadTask,
        didFinishDownloadingToURL: NSURL,
    ) {
        onLocation(assetDownloadTask, didFinishDownloadingToURL)
    }

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        didCompleteWithError: NSError?,
    ) {
        onComplete(task, didCompleteWithError)
    }

    private fun CValue<CMTimeRange>.seconds(): Double =
        useContents { CMTimeGetSeconds(duration.readValue()) }
}

/**
 * HLS downloads through one background `AVAssetDownloadURLSession`, kept for
 * the life of the process.
 *
 * It is a single object for two reasons. A background session identifier may
 * be in use by only one session at a time, and the gallery composes its
 * controller anew each time it is shown. The player also has to find a
 * finished download, by [localFileFor], from outside the gallery.
 *
 * Finished downloads are recorded in `NSUserDefaults` as [SavedDownload]s, so
 * they are still listed, and still play, after a relaunch. Downloads a previous
 * launch left running are re-attached from the session, which keeps
 * them going while the app is not running. Each task carries its feed ID and URL
 * in `taskDescription` ([DownloadTaskTag]), because a task is all those
 * callbacks are handed, and after a relaunch it is the only place either survives.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IOSDownloads : DownloadController {

    private const val SESSION_IDENTIFIER = "com.livingpresence.downloads"
    private const val SAVED_DOWNLOADS_KEY = "com.livingpresence.downloads.saved.v1"

    override val isSupported: Boolean = true

    private val _states = MutableStateFlow<Map<Int, EventDownloadState>>(emptyMap())
    override val states: StateFlow<Map<Int, EventDownloadState>> = _states.asStateFlow()

    private val defaults = NSUserDefaults.standardUserDefaults
    private val tasks = mutableMapOf<Int, AVAssetDownloadTask>()
    private val urls = mutableMapOf<Int, String>()

    // iOS may purge downloaded media when storage runs low, so a record whose
    // package is gone is dropped instead of being listed and failing to play.
    private var saved: List<SavedDownload> =
        SavedDownloads.decode(defaults.stringForKey(SAVED_DOWNLOADS_KEY)).filter { fileExists(it) }

    private val session: AVAssetDownloadURLSession = AVAssetDownloadURLSession.sessionWithConfiguration(
        configuration = NSURLSessionConfiguration.backgroundSessionConfigurationWithIdentifier(SESSION_IDENTIFIER),
        assetDownloadDelegate = DownloadDelegate(
            onProgress = { task, percent ->
                task.eventNumber()?.let { update(it, DownloadStatus.DOWNLOADING, percent) }
            },
            onLocation = ::recordLocation,
            onComplete = ::finish,
        ),
        // Every callback, and so every touch of the maps above, runs on main.
        delegateQueue = NSOperationQueue.mainQueue,
    )

    init {
        persist()
        _states.value = saved.associate { download ->
            download.eventNumber to EventDownloadState(
                eventNumber = download.eventNumber,
                state = DownloadStatus.COMPLETED,
                percent = 100f,
                streamUrl = download.streamUrl,
            )
        }
        session.getAllTasksWithCompletionHandler { running ->
            NSOperationQueue.mainQueue.addOperationWithBlock {
                running.orEmpty().forEach { reattach(it as? AVAssetDownloadTask) }
            }
        }
    }

    override fun enqueue(event: EventInfo, tier: DownloadQuality) {
        if (event.isLive || tasks.containsKey(event.eventNumber)) return
        val number = event.eventNumber
        // The feed ID may be synthetic even when the URL names a numbered stream.
        val url = event.downloadUrl(tier.toRenditionTier())
        urls[number] = url
        val task = NSURL.URLWithString(url)?.let { nsUrl ->
            session.assetDownloadTaskWithURLAsset(
                URLAsset = AVURLAsset(uRL = nsUrl, options = null),
                assetTitle = event.title,
                assetArtworkData = null,
                options = null,
            )
        }
        if (task == null) {
            update(number, DownloadStatus.FAILED, 0f)
            return
        }
        task.taskDescription = DownloadTaskTag.of(number, url)
        tasks[number] = task
        update(number, DownloadStatus.DOWNLOADING, 0f)
        task.resume()
    }

    override fun remove(eventNumber: Int) {
        // Cancelling still delivers didFinishDownloadingToURL for what was
        // fetched so far, and then a cancelled completion; finish() deletes
        // that partial package.
        tasks.remove(eventNumber)?.cancel()
        urls.remove(eventNumber)
        forget(eventNumber)
        _states.update { it - eventNumber }
    }

    override fun refresh() {}

    /**
     * The downloaded package holding the stream [url] plays, or null when that
     * stream has not finished downloading. AVPlayer plays it like any HLS URL.
     */
    fun localFileFor(url: String): NSURL? {
        val completed = saved.filter { _states.value[it.eventNumber]?.state == DownloadStatus.COMPLETED }
        return SavedDownloads.matching(url, completed)?.takeIf { fileExists(it) }?.let(::fileUrl)
    }

    private fun reattach(task: AVAssetDownloadTask?) {
        val number = task?.eventNumber() ?: return
        tasks[number] = task
        task.streamUrl()?.let { urls[number] = it }
        update(number, DownloadStatus.DOWNLOADING, 0f)
    }

    private fun recordLocation(task: NSURLSessionTask, location: NSURL) {
        val number = task.eventNumber() ?: return
        val relativePath = location.relativePath
        val streamUrl = urls[number] ?: task.streamUrl()
        if (relativePath == null || streamUrl == null) {
            // Nothing could find this package again, so it would only take up
            // space; finish() then reports the download as failed.
            NSFileManager.defaultManager.removeItemAtURL(location, error = null)
            return
        }
        saved = saved.filterNot { it.eventNumber == number } +
            SavedDownload(number, streamUrl, relativePath)
        persist()
    }

    private fun finish(task: NSURLSessionTask, error: NSError?) {
        val number = task.eventNumber() ?: return
        tasks.remove(number)
        if (error == null && saved.any { it.eventNumber == number }) {
            update(number, DownloadStatus.COMPLETED, 100f)
            return
        }
        forget(number)
        // A cancellation is a removal the user asked for, already reflected.
        if (error?.code != NSURLErrorCancelled) update(number, DownloadStatus.FAILED, 0f)
    }

    private fun forget(number: Int) {
        val download = saved.firstOrNull { it.eventNumber == number } ?: return
        NSFileManager.defaultManager.removeItemAtURL(fileUrl(download), error = null)
        saved = saved - download
        persist()
    }

    private fun persist() {
        defaults.setObject(SavedDownloads.encode(saved), forKey = SAVED_DOWNLOADS_KEY)
    }

    private fun update(number: Int, status: DownloadStatus, percent: Float) {
        _states.update {
            val url = urls[number] ?: saved.firstOrNull { d -> d.eventNumber == number }?.streamUrl
            it + (number to EventDownloadState(number, status, percent, url.orEmpty()))
        }
    }

    private fun fileUrl(download: SavedDownload): NSURL =
        NSURL.fileURLWithPath(NSHomeDirectory()).URLByAppendingPathComponent(download.relativePath)!!

    private fun fileExists(download: SavedDownload): Boolean =
        fileUrl(download).path?.let { NSFileManager.defaultManager.fileExistsAtPath(it) } == true

    private fun NSURLSessionTask.eventNumber(): Int? = DownloadTaskTag.parse(taskDescription)?.first

    private fun NSURLSessionTask.streamUrl(): String? = DownloadTaskTag.parse(taskDescription)?.second
}

@Composable
internal actual fun rememberDownloadController(): DownloadController = IOSDownloads
