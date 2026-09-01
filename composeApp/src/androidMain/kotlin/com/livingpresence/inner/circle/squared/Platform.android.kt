package com.livingpresence.inner.circle.squared

import android.app.Activity
import android.graphics.Bitmap
import android.util.Log
import com.livingpresence.inner.circle.squared.transcription.TranscriptionProvider
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource as androidPainterResource
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistTracker
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.media3.ui.compose.material3.buttons.PlayPauseButton
import com.livingpresence.mediakit.LadderResolver
import com.livingpresence.mediakit.MediaKitConfig
import com.livingpresence.mediakit.ProbedRendition
import io.ktor.client.HttpClient
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.coroutines.delay

private const val APP_TAG = "InnerCircleSquared"
private const val CONTROLS_AUTO_HIDE_MS = 3_000L
private const val LIVE_EDGE_THRESHOLD_MS = 3_000L


/**
 * Scrub-preview tunables (plan.md FU-1, Scrutiny #1).
 */
private const val SCRUB_DEBOUNCE_MS = 200L
private val ScrubPreviewWidth = 160.dp
private val ScrubPreviewHeight = 90.dp
/** Material3 Slider thumb radius; used to map fraction → thumb center x. */
private val SliderThumbRadius = 10.dp

actual fun createHttpClient(): HttpClient = HttpClient()


actual fun onEventClick(eventNumber: Int, defaultAction: () -> Unit) {
    defaultAction()
}

@Composable
actual fun loginBackgroundModifier(): Modifier {
    // See HostBridge.backgroundDrawableResId: Compose resources are not packaged
    // for the Android target, so the background comes from the host module's
    // res/drawable instead. The gradient is the fallback for a host that never
    // set an id — a washed-out landing page beats a crash.
    val resId = HostBridge.backgroundDrawableResId
    if (resId == 0) {
        return Modifier.background(
            Brush.verticalGradient(listOf(Color(0xFF37474F), Color(0xFF263238))),
        )
    }
    return Modifier.paint(
        painter = androidPainterResource(resId),
        contentScale = ContentScale.Crop,
    )
}

@Composable
actual fun PlatformPlayerScreen(
    url: String,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val httpClient = remember { HttpClient() }
    val eventNumber = remember(url) { parseEventNumber(url) }

    // Connect to the service-owned player. It survives config changes and
    // drives background audio / PiP; the composable renders to its surface via
    // the controller (which implements Player).
    val controller = rememberPlaybackController(context)

    // Resolve the ABR ladder (if any) just-in-time, producing a media-item URI
    // (data: URI for the synthesized multivariant playlist, or the plain URL).
    val mediaSourceBuilder = remember { LadderMediaSourceBuilder(context, MediaKitConfig.Default) }
    val ladderResolver = remember(httpClient) { LadderResolver(httpClient, MediaKitConfig.Default) }
    var itemResult by remember(url) { mutableStateOf<LadderMediaSourceBuilder.ItemResult?>(null) }

    // Bumped to resolve the ladder again from scratch. Re-resolving is what
    // recovery actually requires: the synthesized playlist pins Wowza's
    // per-session `w` chunklist token, so once an event's session ends that URL
    // is dead for good and re-preparing the same media item just replays the
    // failure. Re-reading the master playlist picks up the current token.
    var reloadToken by remember(url) { mutableIntStateOf(0) }

    LaunchedEffect(url, reloadToken) {
        itemResult = if (eventNumber != null) {
            runCatching { mediaSourceBuilder.resolveForEvent(eventNumber, ladderResolver) }
                .getOrNull()
                ?: LadderMediaSourceBuilder.ItemResult(url, renditions = null)
        } else {
            LadderMediaSourceBuilder.ItemResult(url, renditions = null)
        }
    }

    val resolvedItem = itemResult
    if (controller == null || resolvedItem == null) {
        PlayerLoadingState(onClose = onClose)
        return
    }

    ExoPlayerScreen(
        player = controller,
        renditions = resolvedItem.renditions,
        resolvedMediaItemUri = resolvedItem.mediaItemUri,
        url = url,
        eventNumber = eventNumber,
        reloadToken = reloadToken,
        onReload = { reloadToken += 1 },
        onClose = onClose,
    )
}

@Composable
private fun ExoPlayerScreen(
    player: Player,
    renditions: List<ProbedRendition>?,
    resolvedMediaItemUri: String,
    url: String,
    eventNumber: Int?,
    reloadToken: Int,
    onReload: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val videoTapInteractionSource = remember { MutableInteractionSource() }

    /**
     * Leaving the player *on purpose* ends playback; merely backgrounding the app
     * does not.
     *
     * The two look identical from the service's side — it owns the player
     * precisely so audio survives the screen going away — so the difference has
     * to be signalled here, at the point where the user actually asked to leave.
     * Disposal is not that signal: the composition is also disposed when Android
     * destroys a backgrounded Activity under memory pressure, which is exactly
     * the case where audio must keep playing.
     *
     * `clearMediaItems` on top of `stop` is what lets the service drop out of the
     * foreground and take the media notification with it; `stop` alone leaves a
     * paused session sitting in the shade.
     */
    val closePlayer: () -> Unit = remember(player, onClose) {
        {
            runCatching {
                player.stop()
                player.clearMediaItems()
            }
            onClose()
        }
    }

    // System back is an explicit close too. Without this it pops the nav stack
    // directly and never reaches [closePlayer], leaving audio running.
    androidx.activity.compose.BackHandler(onBack = closePlayer)

    // [reloadToken] is a key so a reload re-prepares even when re-resolving
    // handed back the same URI (a probe failure falls back to the plain URL).
    LaunchedEffect(player, resolvedMediaItemUri, reloadToken) {
        val item = playbackMediaItem(resolvedMediaItemUri)
        player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
    }
    val state = rememberPlayerState(player)

    // Rotate-to-fullscreen toggle (offered for landscape content).
    val fullscreen = rememberFullscreenToggle()

    // Phase 8: on-device transcription (CC). The RenderersFactory in the service
    // taps PCM; captions render via CaptionOverlay below.
    val captionController = rememberCaptionController()

    var isScrubbing by remember(player) { mutableStateOf(false) }
    var sliderFraction by remember(player) { mutableStateOf(0f) }
    var showVideoControls by remember(player) { mutableStateOf(true) }
    var showStats by remember(player) { mutableStateOf(false) }

    val canJumpToLive by remember(state, isScrubbing, sliderFraction) {
        derivedStateOf {
            val duration = state.duration
            if (!state.isLive || !state.isSeekable || duration <= 0L) {
                false
            } else {
                val effectivePosition = if (isScrubbing) (duration * sliderFraction).roundToLong()
                else state.currentPosition
                effectivePosition < (duration - LIVE_EDGE_THRESHOLD_MS).coerceAtLeast(0L)
            }
        }
    }

    // Keep the slider thumb tracking playback while not being dragged. Needed
    // for live streams in particular: the production Wowza nDVR window grows
    // (duration keeps increasing, MEDIA-SEQUENCE stays 0) rather than sliding,
    // so a fraction computed once would drift further from the true position
    // every time the window grows.
    LaunchedEffect(state.currentPosition, state.duration, isScrubbing) {
        playbackTrackingFraction(state.currentPosition, state.duration, isScrubbing)
            ?.let { sliderFraction = it }
    }

    // ── Scrub preview (plan.md FU-1, Scrutiny #1) ────────────────────────────
    // The shared PreviewFrameEngine seeks the `_160p` rendition to the scrubbed
    // position (CLOSEST_SYNC, ~2 s granularity) and caches by event + position
    // bucket. We show the cached frame above the thumb while dragging, falling
    // back to a time-only bubble if no frame is ready yet — scrubbing never
    // blocks on the network.
    val engine = LocalPreviewFrameEngine.current
    var scrubBitmap by remember { mutableStateOf<Bitmap?>(null) }
    // Thumb center x in root-window pixels, plus the bottom-controls box's root
    // origin/width, so the preview bubble can track the thumb horizontally.
    var thumbCenterRootX by remember { mutableFloatStateOf(0f) }
    var controlsBoxRootX by remember { mutableFloatStateOf(0f) }
    var controlsBoxWidthPx by remember { mutableFloatStateOf(0f) }

    val duration = state.duration
    val scrubTargetPositionMs by remember(state, isScrubbing, sliderFraction, duration) {
        derivedStateOf {
            if (isScrubbing && duration > 0L) {
                (duration * sliderFraction).roundToLong().coerceIn(0L, duration)
            } else {
                0L
            }
        }
    }

    /**
     * The scrub position snapped to the extractor's keyframe bucket.
     *
     * The effect below keys on *this* rather than the raw position. The raw value
     * changes on every drag pixel — roughly every 16 ms — which restarted the
     * effect and cancelled its 200 ms debounce before it could ever elapse, so a
     * preview frame was never requested at all. Bucketing means the effect only
     * restarts when the drag crosses into a new ~2 s keyframe span, which is the
     * granularity the extractor can actually resolve anyway.
     *
     * Deliberately a plain computed value, not `remember { derivedStateOf { … } }`.
     * A keyless `remember` here captured [scrubTargetPositionMs]'s delegate from
     * the first composition — the one whose lambda had closed over `duration` as
     * a plain `0` before the media was loaded — so the bucket was pinned to 0 and
     * every preview was extracted from the start of the stream.
     */
    val scrubBucketMs = (scrubTargetPositionMs / PreviewFrameEngine.KEYFRAME_GRANULARITY_MS) *
        PreviewFrameEngine.KEYFRAME_GRANULARITY_MS

    // Reset the bubble when a scrub ends; otherwise debounce and request a frame.
    LaunchedEffect(isScrubbing, scrubBucketMs) {
        if (!isScrubbing || duration <= 0L || eventNumber == null) {
            scrubBitmap = null
            // Drop the reusable extraction player once the drag is over, rather
            // than leaving an idle decoder holding a surface and a buffer.
            engine?.endScrub()
            return@LaunchedEffect
        }
        // Show any already-cached frame for this position instantly (free).
        scrubBitmap = engine?.cachedScrubBitmap(eventNumber, scrubBucketMs)
        // Debounce so we don't seek on every pixel of drag (plan.md: ~200 ms).
        delay(SCRUB_DEBOUNCE_MS)
        // Capture the target we requested so a late frame after the drag moves
        // on is dropped rather than flashing a stale position.
        val requestedPosition = scrubBucketMs
        val frame = engine?.requestScrubFrame(
            eventNumber = eventNumber,
            positionMs = requestedPosition,
            width = with(density) { ScrubPreviewWidth.roundToPx() },
            height = with(density) { ScrubPreviewHeight.roundToPx() },
        )
        if (frame != null && isScrubbing && scrubBucketMs == requestedPosition) {
            scrubBitmap = frame
        }
    }

    // Auto-hide controls after inactivity (tap toggles, scrub/interaction resets the timer).
    ControlsAutoHide(
        visible = showVideoControls,
        isScrubbing = isScrubbing,
        isPlaying = state.isPlaying,
        onHide = { showVideoControls = false },
    )

    // Immersive mode: hide system bars while the player is on screen (not in PiP).
    ImmersiveSystemBars(active = HostBridge.inPipState.value.let { !it })

    // Report video size + playing state for PiP params, and collapse controls in PiP.
    val pipController = LocalPipController.current
    LaunchedEffect(state.videoSize, state.isPlaying) {
        val size = state.videoSize
        if (size.width > 0 && size.height > 0) {
            pipController?.updateVideoSize(size.width, size.height)
        }
        pipController?.setPlaying(state.isPlaying)
    }
    if (HostBridge.inPipState.value) {
        showVideoControls = false
        showStats = false
    }

    // Backgrounding policy (per plan Scrutiny #9): when the app is backgrounded
    // / in PiP, the video surface is gone but audio keeps playing. With muxed
    // HLS, disabling the video renderer alone would keep downloading full-bitrate
    // segments; constraining track selection to the ladder's audio-only tier
    // cuts the stream to ~51 kbps. On return to foreground, restore video.
    BackgroundAudioPolicy(player = player, isVideoVisible = !HostBridge.inPipState.value)

    val playbackError = state.playbackError
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            val containerAspectRatio = maxWidth.value / maxHeight.value
            val surfaceModifier = videoSurfaceModifier(
                videoAspectRatio = state.videoAspectRatio,
                containerAspectRatio = containerAspectRatio,
                resizeMode = state.resizeMode,
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = videoTapInteractionSource,
                        indication = null,
                    ) { showVideoControls = !showVideoControls },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = surfaceModifier.onGloballyPositioned { coords ->
                        // Report the video's on-screen bounds for the PiP source-rect hint.
                        val pos = coords.positionInRoot()
                        val w = coords.size.width
                        val h = coords.size.height
                        if (w > 0 && h > 0) {
                            pipController?.updateSourceBounds(
                                left = pos.x.toInt(),
                                top = pos.y.toInt(),
                                right = (pos.x + w).toInt(),
                                bottom = (pos.y + h).toInt(),
                            )
                        }
                    },
                ) {
                    PlayerSurface(
                        player = player,
                        surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                // Buffering spinner while the first frame hasn't rendered.
                if (!state.firstFrameRendered && playbackError == null) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = Color.White,
                    )
                }

                AnimatedVisibility(
                    visible = showVideoControls,
                    modifier = Modifier.fillMaxSize(),
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .onGloballyPositioned { coords ->
                                controlsBoxRootX = coords.positionInRoot().x
                                controlsBoxWidthPx = coords.size.width.toFloat()
                            }
                    ) {
                        PlayerControlsOverlay(
                            modifier = Modifier.fillMaxSize(),
                            isPlaying = state.isPlaying,
                            durationMs = state.duration,
                            positionMs = state.currentPosition,
                            isLive = state.isLive,
                            isSeekable = state.isSeekable,
                            isScrubbing = isScrubbing,
                            sliderFraction = sliderFraction,
                            onSliderValueChange = {
                                isScrubbing = true
                                sliderFraction = it
                            },
                            onSliderValueChangeFinished = {
                                val dur = state.duration
                                if (dur > 0L) {
                                    val newPosition = (dur * sliderFraction).roundToLong()
                                        .coerceIn(0L, dur)
                                    player.seekTo(newPosition)
                                    state.currentPosition = newPosition
                                }
                                isScrubbing = false
                            },
                            onPlayPauseToggle = {
                                if (state.isPlaying) player.pause() else player.play()
                            },
                            onJumpToLive = {
                                player.seekToDefaultPosition()
                                player.play()
                                state.currentPosition = state.duration
                                sliderFraction = 1f
                                isScrubbing = false
                            },
                            onClose = closePlayer,
                            onThumbCenterXChanged = { thumbCenterRootX = it },
                            topRightControls = {
                                PlayerTopRightControls(
                                    captionController = captionController,
                                    // The caption overlay is drawn only while the
                                    // controls are down, so switching captions on has
                                    // to take the controls with it or nothing appears.
                                    onCaptionsShown = { showVideoControls = false },
                                    onToggleStats = { showStats = !showStats },
                                    qualityMenu = {
                                        QualityMenu(
                                            renditions = renditions,
                                            onSetAuto = {
                                                player.trackSelectionParameters = player.trackSelectionParameters
                                                    .buildUpon()
                                                    .clearVideoSizeConstraints()
                                                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                                                    .build()
                                            },
                                            onPinToRendition = { rendition ->
                                                player.trackSelectionParameters = player.trackSelectionParameters
                                                    .buildUpon()
                                                    .setMinVideoSize(rendition.width, rendition.height)
                                                    .setMaxVideoSize(rendition.width, rendition.height)
                                                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                                                    .build()
                                            },
                                            onDisableVideo = {
                                                player.trackSelectionParameters = player.trackSelectionParameters
                                                    .buildUpon()
                                                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                                                    .build()
                                            }
                                        )
                                    },
                                )
                            }
                        )
                        
                        if (isScrubbing && state.duration > 0L) {
                            Box(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                                ScrubPreviewBubble(
                                    bitmap = scrubBitmap,
                                    positionLabel = formatPlaybackTime(scrubTargetPositionMs),
                                    thumbCenterRootX = thumbCenterRootX,
                                    boxRootX = controlsBoxRootX,
                                    boxWidthPx = controlsBoxWidthPx,
                                )
                            }
                        }
                    }
                }

                // Debug stats overlay (toggle).
                if (showStats) {
                    StatsOverlay(
                        currentHeight = state.videoSize.height.takeIf { it > 0 },
                        bufferedAfterMs = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L),
                        renditions = renditions,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(top = 56.dp, start = 8.dp),
                    )
                }

                // Phase 8: rolling transcription captions along the bottom edge of the video.
                // They yield the bottom of the frame to the controls: while those are up the
                // captions cross-fade out entirely rather than fighting them for the space,
                // and they come back at the edge as soon as the controls hide again.
                AnimatedVisibility(
                    visible = captionController.enabled && !showVideoControls,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    CaptionOverlay(
                        captions = captionController.captions,
                        modifier = Modifier.padding(bottom = CAPTION_EDGE_INSET_DP.dp),
                    )
                }
            }
        }

        // Error surface with retry (previously log-only).
        if (playbackError != null) {
            PlayerErrorOverlay(
                error = playbackError,
                onRetry = onReload,
                onClose = closePlayer,
            )
        }
    }
}

/**
 * The YouTube-style scrub-preview bubble: a floating card above the seekbar
 * showing the frame at the scrubbed position, or a time-only bubble when the
 * frame isn't ready yet (the graceful fallback — never blocks the drag).
 *
 * Positioning is done in root-window pixels: the slider reports the thumb's
 * center x in root space and this composable receives the bottom-controls box's
 * root origin, so the offset = thumb − box origin places the bubble correctly
 * regardless of where the controls sit. The bubble is clamped to stay within
 * the controls box width.
 *
 * @param bitmap The extracted frame, or null to show the time-only fallback.
 * @param positionLabel Formatted scrubbed position (e.g. "12:34") for the label.
 * @param thumbCenterRootX The slider thumb's center x in root-window pixels.
 * @param boxRootX The bottom-controls box's root x in pixels.
 * @param boxWidthPx The bottom-controls box's width in pixels (for clamping).
 */
@Composable
private fun ScrubPreviewBubble(
    bitmap: Bitmap?,
    positionLabel: String,
    thumbCenterRootX: Float,
    boxRootX: Float,
    boxWidthPx: Float,
) {
    val density = LocalDensity.current
    val bubbleWidthPx = with(density) { ScrubPreviewWidth.toPx() }
    val halfWidthPx = bubbleWidthPx / 2f
    // Thumb x relative to the controls box, then shift left by half the bubble
    // width so the bubble centers on the thumb. Clamp to keep it on screen.
    val rawLeft = (thumbCenterRootX - boxRootX) - halfWidthPx
    val maxLeft = (boxWidthPx - bubbleWidthPx).coerceAtLeast(0f)
    val xPx = rawLeft.coerceIn(0f, maxLeft).roundToInt()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .offset { IntOffset(x = xPx, y = 0) }
            .padding(bottom = 72.dp),
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.85f))
                .padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (bitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Scrub preview frame",
                    modifier = Modifier
                        .width(ScrubPreviewWidth)
                        .height(ScrubPreviewHeight)
                        .clip(RoundedCornerShape(4.dp)),
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.Low,
                )
            }
            // No fillMaxWidth: it stretched this Column to the full screen
            // width, and the frame above — centred by horizontalAlignment — then
            // rendered in the middle of *that*, roughly 320 px right of the
            // offset meant to place it under the thumb, and clipped off-screen.
            // Wrapping the frame keeps the bubble where the offset puts it.
            Text(
                text = positionLabel,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/** Loading state while the ABR ladder is being resolved. */
@Composable
private fun PlayerLoadingState(onClose: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = Color.White)
    }
}






/**
 * True when the HLS playlist tracker gave up on a live playlist rather than
 * hitting a genuine failure.
 *
 * A finished live event is the ordinary cause: the encoder stops, Wowza leaves
 * the last chunklist in place without an `#EXT-X-ENDLIST`, and the tracker —
 * which must keep reloading a playlist that has no end tag — raises
 * `PlaylistStuckException` once the snapshot has been unchanged for 3.5x the
 * target duration. `PlaylistResetException` is the same story with the media
 * sequence restarting underneath us. Neither is broken playback, so neither
 * should be reported as one.
 */
private val PlaybackException.isLiveStreamOver: Boolean
    get() = generateSequence(cause) { it.cause }.any {
        it is HlsPlaylistTracker.PlaylistStuckException ||
            it is HlsPlaylistTracker.PlaylistResetException
    }

@Composable
private fun PlayerErrorOverlay(
    error: PlaybackException,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    val liveStreamOver = error.isLiveStreamOver
    if (liveStreamOver) {
        Log.i(APP_TAG, "Live playlist stopped updating; the event has most likely ended.")
    } else {
        Log.e(APP_TAG, "Playback error", error)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = if (liveStreamOver) "This live event has ended" else "Playback error",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = if (liveStreamOver) {
                "The stream stopped publishing new video."
            } else {
                error.errorCodeName + ": " + (error.message ?: "")
            },
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onRetry) {
                Text(if (liveStreamOver) "Reload" else "Retry")
            }
            TextButton(onClick = onClose) { Text("Close") }
        }
    }
}

/**
 * Auto-hides the controls [CONTROLS_AUTO_HIDE_MS] after the last interaction,
 * only while playing. Paused/scrubbing keeps them visible.
 */
@Composable
private fun ControlsAutoHide(
    visible: Boolean,
    isScrubbing: Boolean,
    isPlaying: Boolean,
    onHide: () -> Unit,
) {
    if (!visible || isScrubbing || !isPlaying) return
    LaunchedEffect(visible, isScrubbing, isPlaying) {
        delay(CONTROLS_AUTO_HIDE_MS)
        onHide()
    }
}

/**
 * Hides system bars for an immersive player. Restores them on exit.
 */
@Composable
private fun ImmersiveSystemBars(active: Boolean) {
    val context = LocalContext.current
    DisposableEffect(active) {
        val activity = context.findActivity()
        val window = activity?.window
        val controller = window?.let {
            androidx.core.view.WindowCompat.getInsetsController(it, it.decorView)
        }
        controller?.let { it.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
        controller?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }
}

internal fun android.content.Context.findActivity(): Activity? {
    var ctx: android.content.Context = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/** Cycles FIT → FILL → ZOOM → FIT. */
private fun nextResizeMode(mode: ResizeMode): ResizeMode = when (mode) {
    ResizeMode.FIT -> ResizeMode.FILL
    ResizeMode.FILL -> ResizeMode.ZOOM
    ResizeMode.ZOOM -> ResizeMode.FIT
}

/** For portrait content, cycles FIT → ZOOM → FIT (FILL distorts badly). */
private fun nextPortraitResizeMode(mode: ResizeMode): ResizeMode = when (mode) {
    ResizeMode.FIT -> ResizeMode.ZOOM
    else -> ResizeMode.FIT
}


