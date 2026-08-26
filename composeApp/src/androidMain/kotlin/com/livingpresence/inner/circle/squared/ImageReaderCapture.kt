package com.livingpresence.inner.circle.squared

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.PixelCopy
import android.view.Surface
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Renders ExoPlayer video into an off-screen surface and captures a frame via
 * [PixelCopy]. Used by [PreviewFrameEngine] to grab poster/scrub frames without
 * a visible view.
 *
 * **Reliability.** Frame capture is best-effort: hardware decoders output
 * vendor-private buffer formats that [android.media.ImageReader] rejects
 * (a hard crash on Mali/Samsung devices), and a detached [SurfaceTexture]
 * can't be sampled without an EGL context. This class therefore uses
 * [PixelCopy] against a plain [Surface] (no GL), and **every** capture failure
 * returns null — the caller falls back to the placeholder tile. A capture
 * failure must never crash playback.
 *
 * Requires API 24 ([PixelCopy]); on API 23 the engine falls back to the
 * placeholder (no thumbnail).
 *
 * @param width  Capture width (px).
 * @param height Capture height (px).
 */
class ImageReaderCapture(width: Int, height: Int) {

    private val handlerThread = HandlerThread("FrameCapture").apply { start() }
    private val handler = Handler(handlerThread.looper)

    /**
     * A SurfaceTexture used purely to give ExoPlayer a surface to render into.
     * We never call [SurfaceTexture.updateTexImage] (that needs an EGL context
     * and throws on a detached texture) — [PixelCopy.request] samples the
     * Surface's buffers directly via the framework, regardless of buffer format.
     */
    private val surfaceTexture: SurfaceTexture = SurfaceTexture(0).apply {
        setDefaultBufferSize(width.coerceAtLeast(1), height.coerceAtLeast(1))
    }

    /** The surface the player should render onto via [androidx.media3.common.Player.setVideoSurface]. */
    val surface: Surface = Surface(surfaceTexture)

    private val captureWidth: Int = width.coerceAtLeast(1)
    private val captureHeight: Int = height.coerceAtLeast(1)

    /**
     * Copies a rendered frame out of the surface via [PixelCopy], retrying until
     * one is available or [timeoutMs] elapses. Returns null on API 23, on
     * persistent copy failure, or if the request throws — never raises.
     *
     * The retry is the whole point. How long the decoder takes to queue its first
     * buffer after a seek varies with network latency, segment size and codec
     * startup, so a single sample at a fixed delay is a race: `PixelCopy` fails
     * with "Surface doesn't have any previously queued frames" and the tile
     * silently falls back to a placeholder. Polling converts a coin flip into a
     * wait, and a frame that arrives at 900 ms is just as good as one at 400 ms.
     */
    suspend fun awaitFrame(timeoutMs: Long = DEFAULT_AWAIT_TIMEOUT_MS): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
        // Give the decoder a beat before the first attempt — usually enough, and
        // it avoids a guaranteed-failing copy on the common path.
        kotlinx.coroutines.delay(FIRST_FRAME_SETTLE_MS)
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            copyFrame()?.let { return it }
            if (SystemClock.uptimeMillis() >= deadline) return null
            kotlinx.coroutines.delay(RETRY_INTERVAL_MS)
        }
    }

    /** One [PixelCopy] attempt. Null when no frame is on the surface yet. */
    private suspend fun copyFrame(): Bitmap? {
        val bitmap = Bitmap.createBitmap(captureWidth, captureHeight, Bitmap.Config.ARGB_8888)
        return suspendCancellableCoroutine { cont ->
            val listener = PixelCopy.OnPixelCopyFinishedListener { result ->
                if (result == PixelCopy.SUCCESS && cont.isActive) {
                    cont.resume(bitmap)
                } else {
                    bitmap.recycle()
                    if (cont.isActive) cont.resume(null)
                }
            }
            try {
                PixelCopy.request(surface, bitmap, listener, handler)
            } catch (t: Throwable) {
                bitmap.recycle()
                if (cont.isActive) cont.resume(null)
            }
            cont.invokeOnCancellation { runCatching { bitmap.recycle() } }
        }
    }

    fun release() {
        runCatching { surface.release() }
        runCatching { surfaceTexture.release() }
        handlerThread.quitSafely()
    }

    private companion object {
        // Let the first frames land before sampling. PixelCopy needs a buffer
        // present; sampling too early yields SUCCESS but a black frame or none.
        const val FIRST_FRAME_SETTLE_MS = 400L

        /** Gap between retries — cheap enough to poll, coarse enough not to spin. */
        const val RETRY_INTERVAL_MS = 100L

        /**
         * How long to keep retrying. Comfortably covers a slow segment fetch plus
         * decoder startup, while still leaving room inside the engine's per-tier
         * budget for the next rendition to be tried.
         */
        const val DEFAULT_AWAIT_TIMEOUT_MS = 4_000L
    }
}
