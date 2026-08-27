package com.livingpresence.inner.circle.squared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import kotlinx.browser.window
import kotlinx.browser.document
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import org.w3c.dom.HTMLVideoElement
import com.livingpresence.mediakit.ExtraVideoCatalog
import com.livingpresence.mediakit.MediaKitConfig
import com.livingpresence.mediakit.RenditionTier
import androidx.compose.ui.ExperimentalComposeUiApi

actual fun createHttpClient(): HttpClient = HttpClient(Js)

@JsFun("""
function attachHls(videoElement, url) {
    if (window.Hls && window.Hls.isSupported()) {
        var hls = new window.Hls();
        hls.loadSource(url);
        hls.attachMedia(videoElement);
        hls.on(window.Hls.Events.MANIFEST_PARSED, function() {
            videoElement.currentTime = 1;
        });
        videoElement.addEventListener('loadeddata', function() {
            videoElement.pause();
        });
        return hls;
    } else if (videoElement.canPlayType('application/vnd.apple.mpegurl')) {
        videoElement.src = url;
        videoElement.play().catch(function(){});
    }
    return null;
}
""")
external fun attachHls(videoElement: HTMLVideoElement, url: String): kotlin.js.JsAny?

@JsFun("function destroyHls(hls) { if (hls) hls.destroy(); }")
external fun destroyHls(hls: kotlin.js.JsAny?)


// onEventClick / loginBackgroundModifier are shared with iOS in nonAndroidMain.

@Composable
actual fun PlatformPlayerScreen(
    url: String,
    onClose: () -> Unit,
) {
    WasmPlayerScreen(url, onClose)
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun LiveEventThumbnail(
    eventNumber: Int,
    streamUrl: String,
    contentDescription: String?,
    modifier: Modifier,
) {
    var bounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }

    Box(
        modifier = modifier
            .background(Brush.linearGradient(listOf(Color(0xFF37474F), Color(0xFF263238))))
            .onGloballyPositioned { coordinates -> bounds = coordinates.boundsInWindow() },
        contentAlignment = Alignment.Center,
    ) {
        // A feed extra's synthetic number would read as nonsense; its title sits
        // right below the tile anyway.
        if (eventNumber < ExtraVideoCatalog.EXTRA_EVENT_NUMBER_BASE) {
            Text(
                text = "event $eventNumber",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }

    DisposableEffect(bounds) {
        var hls: kotlin.js.JsAny? = null
        var video: HTMLVideoElement? = null

        val currentBounds = bounds
        if (currentBounds != null) {
            video = document.createElement("video") as HTMLVideoElement
            video.style.apply {
                setProperty("position", "absolute")
                setProperty("left", "${currentBounds.left}px")
                setProperty("top", "${currentBounds.top}px")
                setProperty("width", "${currentBounds.width}px")
                setProperty("height", "${currentBounds.height}px")
                setProperty("z-index", "100")
                setProperty("pointer-events", "none")
                setProperty("object-fit", "cover")
            }
            video.muted = true

            document.body?.appendChild(video)
            // The cheap 160p sibling where there is one; a feed extra plays its
            // own playlist, muted, as its own preview.
            val url = MediaKitConfig.eventNumberIn(streamUrl)
                ?.let { MediaKitConfig.Default.renditionUrl(it, RenditionTier.P160) }
                ?: streamUrl
            hls = attachHls(video, url)
        }

        onDispose {
            hls?.let { destroyHls(it) }
            video?.let { it.remove() }
        }
    }
}
