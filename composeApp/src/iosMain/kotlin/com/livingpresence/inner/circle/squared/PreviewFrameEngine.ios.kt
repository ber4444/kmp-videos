package com.livingpresence.inner.circle.squared

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import cnames.supported.AVPlayerBridge
import com.livingpresence.mediakit.MediaKitConfig
import com.livingpresence.mediakit.RenditionTier
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.writeToURL
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.posix.memcpy
import kotlin.coroutines.resume

/**
 * Extracts gallery and scrub-preview frames through AVPlayer.
 *
 * AVAssetImageGenerator supports HLS only for I-frame-only playlists. Our
 * ordinary media playlists do not provide one, so feeding the generator either
 * the playlist or a hand-picked segment reliably produces no image. AVPlayer
 * owns HLS loading and decoding, and the native bridge returns its first
 * decoded frame at the requested position.
 */
@OptIn(ExperimentalForeignApi::class)
class PreviewFrameEngine {

    private val cacheDir: NSURL by lazy {
        val urls = NSFileManager.defaultManager.URLsForDirectory(NSCachesDirectory, NSUserDomainMask)
        val caches = urls.first() as NSURL
        val dir = caches.URLByAppendingPathComponent("ics-cache")!!
        NSFileManager.defaultManager.createDirectoryAtURL(
            url = dir,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        dir
    }

    suspend fun getFrame(
        eventNumber: Int,
        timeMs: Long,
        streamUrl: String = MediaKitConfig.Default.eventUrl(eventNumber),
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        val fileUrl = cacheDir.URLByAppendingPathComponent("${eventNumber}_${timeMs}.jpg")!!

        if (NSFileManager.defaultManager.fileExistsAtPath(fileUrl.path!!)) {
            NSData.dataWithContentsOfURL(fileUrl)?.toImageBitmap()?.let { return@withContext it }
        }

        for (url in frameCandidates(streamUrl)) {
            val image = captureFrame(url, timeMs) ?: continue
            val data = UIImageJPEGRepresentation(image, 0.7) ?: continue
            data.writeToURL(fileUrl, atomically = true)
            data.toImageBitmap()?.let { return@withContext it }
        }
        null
    }

    /** Mirrors Android: try the cheapest rendition but do not require it. */
    private fun frameCandidates(streamUrl: String): List<String> {
        val eventNumber = MediaKitConfig.eventNumberIn(streamUrl) ?: return listOf(streamUrl)
        return listOf(RenditionTier.P160, RenditionTier.P360, RenditionTier.P720)
            .map { MediaKitConfig.Default.renditionUrl(eventNumber, it) }
    }

    private suspend fun captureFrame(url: String, timeMs: Long): UIImage? =
        suspendCancellableCoroutine { continuation ->
            val nsUrl = NSURL.URLWithString(url)
            if (nsUrl == null) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            AVPlayerBridge.capturePreviewFrameForURL(
                url = nsUrl,
                atTime = CMTimeMakeWithSeconds(timeMs / 1000.0, 600),
            ) { image, error ->
                if (continuation.isActive) {
                    if (image == null) {
                        println("PreviewFrameEngine: $url failed — ${error?.localizedDescription}")
                    }
                    continuation.resume(image)
                }
            }
        }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toImageBitmap(): ImageBitmap? {
    val size = length.toInt()
    if (size == 0) return null
    val bytes = ByteArray(size)
    bytes.usePinned { pinned ->
        memcpy(pinned.addressOf(0), this.bytes, length)
    }
    return runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
}
