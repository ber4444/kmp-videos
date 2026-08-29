package com.livingpresence.inner.circle.squared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import com.livingpresence.inner.circle.squared.transcription.CaptionLanguage
import com.livingpresence.inner.circle.squared.transcription.TranscriberStatus
import com.livingpresence.inner.circle.squared.transcription.TranscriptionProvider
import com.livingpresence.inner.circle.squared.transcription.TranscriptionSettings
import com.livingpresence.inner.circle.squared.transcription.captionLabel
import com.livingpresence.inner.circle.squared.transcription.captionToggleLabel
import kotlinx.coroutines.flow.StateFlow

/**
 * UI-facing controller for live captions: the CC toggle, the selected streaming
 * provider (Deepgram/Soniox), and the caption/status streams.
 *
 * Recognition runs in [CaptionAudioRouter] (a process singleton fed by the
 * service player's PCM tap); this holder just starts/stops it and switches the
 * provider. Cloud ASR needs no player position clock, so unlike the old on-device
 * engine there's no position wiring here.
 *
 * @param enabled whether the user has toggled CC on.
 * @param provider the active streaming ASR provider.
 * @param status the router's connection/lifecycle state.
 * @param error the router's last error (missing key, connection failure, …).
 * @param captions the router's caption stream.
 * @param onToggle flips [enabled].
 * @param onSelectProvider switches the streaming provider.
 */
internal class CaptionController(
    val enabled: Boolean,
    val provider: TranscriptionProvider,
    val status: StateFlow<TranscriberStatus>,
    val error: StateFlow<String?>,
    val captions: StateFlow<List<CaptionCue>>,
    val onToggle: () -> Unit,
    val onSelectProvider: (TranscriptionProvider) -> Unit,
)

/**
 * Remembers a [CaptionController]. Starts streaming to the selected provider on
 * enable (and when the provider changes); stops on disable or when the screen leaves.
 */
@Composable
internal fun rememberCaptionController(): CaptionController {
    val router = remember { CaptionAudioRouter.get() }
    var enabled by remember { mutableStateOf(false) }
    val provider by TranscriptionSettings.provider.collectAsState()

    // Enable → start (or switch to) the selected provider; disable → stop.
    // Re-runs when the provider changes while enabled, switching the live stream.
    LaunchedEffect(enabled, provider) {
        if (enabled) router.enable(provider) else router.disable()
    }

    // Stop streaming if the player screen leaves the composition.
    DisposableEffect(Unit) {
        onDispose { router.disable() }
    }

    return remember(router, enabled, provider) {
        CaptionController(
            enabled = enabled,
            provider = provider,
            status = router.status,
            error = router.error,
            captions = router.captions,
            onToggle = { enabled = !enabled },
            onSelectProvider = { TranscriptionSettings.select(it) },
        )
    }
}

/**
 * The caption toggle. It is labelled with the action it performs, not the state it is in:
 * the device's caption language while captions are off ("Russian" → tap for Russian
 * captions), and "No translation" while they are on. See [captionToggleLabel] for the
 * wording, including the state mark that replaces the old `CC…`/`CC↻`/`CC!` suffixes.
 *
 * The device language is read once per composition — it cannot change without the app
 * being recreated.
 */
@Composable
internal fun CaptionToggleButton(controller: CaptionController) {
    val status by controller.status.collectAsState()
    val translateTo = remember { CaptionLanguage.deviceTarget() }
    TextButton(onClick = controller.onToggle) {
        Text(
            text = captionToggleLabel(controller.enabled, translateTo, status),
            color = if (controller.enabled) Color.White else Color.White.copy(alpha = 0.7f),
        )
    }
}

/**
 * The shared top-right control cluster for every player: the caption toggle, followed by
 * an optional platform-specific [trailingControls] slot (PiP on iOS, Fullscreen on web).
 * Emitted into the `topRightControls`
 * [androidx.compose.foundation.layout.RowScope] of [PlayerControlsOverlay].
 *
 * Three buttons that used to live here are deliberately not rendered. Each slot is still
 * a parameter, and every platform still passes a working one, so restoring any of them is
 * a single line here rather than re-threading state through three platform screens:
 *
 * - **Quality.** The ladder already adapts on its own — Media3 ABR capped to the viewport
 *   on Android, hls.js auto-level on web, AVPlayer's own ABR on iOS — so the menu only
 *   ever *overrode* a working automatic choice. Nothing depends on it being reachable:
 *   with no override the player stays on auto, and `BackgroundAudioPolicy` re-enables the
 *   video track by itself when the surface comes back, so the audio-only tier used while
 *   backgrounded cannot strand a viewer. (On iOS the menu's callbacks were empty anyway.)
 * - **Stats.** A developer overlay, not something a viewer needs in the control bar.
 * - **The Deepgram/Soniox switcher.** Soniox is the only provider the UI offers, because
 *   it is the only one that can caption in the viewer's language. Deepgram still builds
 *   and still works — `TranscriptionSettings` can select it — but nothing reaches that
 *   path from here. See [CaptionProviderButton].
 *
 * @param onToggleStats flips the platform's stats overlay. Currently unreached.
 * @param qualityMenu the platform's rendition picker. Currently unreached.
 */
@Composable
internal fun PlayerTopRightControls(
    captionController: CaptionController,
    @Suppress("UNUSED_PARAMETER") onToggleStats: () -> Unit,
    @Suppress("UNUSED_PARAMETER") qualityMenu: @Composable () -> Unit,
    trailingControls: @Composable () -> Unit = {},
) {
    CaptionToggleButton(controller = captionController)
    trailingControls()
}

/**
 * Tap to cycle the live streaming provider. Soniox is labelled by what it does for this
 * viewer — "Translate to Russian" on a Russian device — rather than by its name; see
 * [captionLabel].
 *
 * **Not currently rendered.** [PlayerTopRightControls] dropped it when Soniox became the
 * only provider worth offering; kept intact so restoring the choice is a one-line change
 * rather than a rewrite.
 */
@Suppress("unused")
@Composable
internal fun CaptionProviderButton(controller: CaptionController) {
    val translateTo = remember { CaptionLanguage.deviceTarget() }
    TextButton(onClick = {
        val next = when (controller.provider) {
            TranscriptionProvider.DEEPGRAM -> TranscriptionProvider.SONIOX
            TranscriptionProvider.SONIOX -> TranscriptionProvider.DEEPGRAM
        }
        controller.onSelectProvider(next)
    }) {
        Text(
            text = controller.provider.captionLabel(translateTo),
            color = Color.White.copy(alpha = 0.85f),
        )
    }
}
