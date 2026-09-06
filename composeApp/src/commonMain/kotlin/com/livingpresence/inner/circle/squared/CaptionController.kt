package com.livingpresence.inner.circle.squared

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import com.livingpresence.inner.circle.squared.transcription.CaptionLanguage
import com.livingpresence.inner.circle.squared.transcription.CaptionMenuOption
import com.livingpresence.inner.circle.squared.transcription.TranscriberStatus
import com.livingpresence.inner.circle.squared.transcription.TranscriptionProvider
import com.livingpresence.inner.circle.squared.transcription.TranscriptionSettings
import com.livingpresence.inner.circle.squared.transcription.captionLabel
import com.livingpresence.inner.circle.squared.transcription.captionMenuButtonLabel
import com.livingpresence.inner.circle.squared.transcription.captionMenuOptions
import com.livingpresence.inner.circle.squared.transcription.defaultCaptionOption
import kotlinx.coroutines.flow.StateFlow

/**
 * UI-facing controller for live captions: the caption language menu, the selected
 * streaming provider (Deepgram/Soniox), and the caption/status streams.
 *
 * Recognition runs in [CaptionAudioRouter] (a process singleton fed by the
 * service player's PCM tap); this holder just starts/stops it, tells it which
 * language to write in, and switches the provider. Cloud ASR needs no player
 * position clock, so unlike the old on-device engine there's no position wiring here.
 *
 * @param options every row of the caption menu, in display order.
 * @param selected the row the viewer is on; the first one ("No translation") means captions
 *   are off, which is where every player starts.
 * @param provider the active streaming ASR provider.
 * @param status the router's connection/lifecycle state.
 * @param error the router's last error (missing key, connection failure, …).
 * @param captions the router's caption stream.
 * @param onSelect moves to another row — off, the device language, or any other language.
 * @param onSelectProvider switches the streaming provider.
 */
internal class CaptionController(
    val options: List<CaptionMenuOption>,
    val selected: CaptionMenuOption,
    val provider: TranscriptionProvider,
    val status: StateFlow<TranscriberStatus>,
    val error: StateFlow<String?>,
    val captions: StateFlow<List<CaptionCue>>,
    val onSelect: (CaptionMenuOption) -> Unit,
    val onSelectProvider: (TranscriptionProvider) -> Unit,
) {
    /**
     * Whether a caption session is running. The platform screens render the caption overlay
     * behind this, and it is a property of the selected row rather than a separate flag so
     * the two can't disagree.
     */
    val enabled: Boolean get() = selected.captionsOn
}

/**
 * Remembers a [CaptionController]. Starts streaming on any row that has captions on (and
 * restarts it when the chosen language or the provider changes, since the target language
 * travels in Soniox's config frame and a live session cannot be re-pointed); stops on "No
 * translation" or when the screen leaves.
 *
 * **Every video starts on "No translation", including for a viewer who picked a language on
 * the last one.** Captions are metered — a session streams the whole soundtrack to Soniox for
 * as long as it runs — so a choice must not follow the viewer from video to video and quietly
 * bill them for one they never asked to have captioned. That is what [videoKey] is for: pass
 * whatever identifies the video on this screen (its URL), and the selection resets when it
 * changes, rather than depending on the player screen happening to leave the composition
 * between videos. Re-picking a language is one tap; an hour of unwatched captions is not.
 */
@Composable
internal fun rememberCaptionController(videoKey: Any?): CaptionController {
    val router = remember { CaptionAudioRouter.get() }
    // The device language is read once per composition — it cannot change without the app
    // being recreated — and decides only which row sits second, not what the menu offers.
    val options = remember { captionMenuOptions(CaptionLanguage.deviceTarget()) }
    var selected by remember(videoKey) { mutableStateOf(defaultCaptionOption(options)) }
    val provider by TranscriptionSettings.provider.collectAsState()

    // A row with captions on → start (or re-point) the stream; "No translation" → stop.
    // Re-runs when the row or the provider changes, switching the live stream.
    LaunchedEffect(selected, provider) {
        if (selected.captionsOn) router.enable(provider, selected.translateTo) else router.disable()
    }

    // Stop streaming if the player screen leaves the composition.
    DisposableEffect(Unit) {
        onDispose { router.disable() }
    }

    return remember(router, options, selected, provider) {
        CaptionController(
            options = options,
            selected = selected,
            provider = provider,
            status = router.status,
            error = router.error,
            captions = router.captions,
            onSelect = { selected = it },
            onSelectProvider = { TranscriptionSettings.select(it) },
        )
    }
}

/**
 * The caption language menu: "No translation" (nothing running), the device's own language,
 * plain captions in the spoken language, then the rest of the languages this audience reads.
 * See [captionMenuOptions] for the rows and [captionMenuButtonLabel] for what the closed
 * button says, including the state mark that reports a connecting or failed stream.
 *
 * @param onCaptionsShown called when a selection leaves captions running, so a platform whose
 *   controls cover the caption strip can get out of the way. See
 *   [dismissesControlsOnCaptionSelection].
 * @param onMenuOpenChange reports the menu opening and closing, so a platform that auto-hides
 *   its controls can hold them up while the viewer reads a list this long.
 */
@Composable
internal fun CaptionLanguageMenu(
    controller: CaptionController,
    onCaptionsShown: () -> Unit = {},
    onMenuOpenChange: (Boolean) -> Unit = {},
) {
    val status by controller.status.collectAsState()
    var expanded by remember { mutableStateOf(false) }

    fun setExpanded(open: Boolean) {
        expanded = open
        onMenuOpenChange(open)
    }

    // The menu can also go away without being dismissed — the controls are torn down when the
    // player enters PiP, and this composable goes with them. Report that too, or the platform
    // would hold its controls up for the rest of the video waiting for a close that never comes.
    DisposableEffect(Unit) {
        onDispose { onMenuOpenChange(false) }
    }

    Box {
        TextButton(onClick = { setExpanded(true) }) {
            Text(
                text = captionMenuButtonLabel(controller.selected, status),
                color = if (controller.enabled) Color.White else Color.White.copy(alpha = 0.7f),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { setExpanded(false) }) {
            controller.options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    // The closed button already names the selection; inside the list a mark
                    // is what tells the viewer where they are in twenty-odd rows.
                    trailingIcon = if (option == controller.selected) {
                        { Text("✓") }
                    } else {
                        null
                    },
                    onClick = {
                        setExpanded(false)
                        controller.onSelect(option)
                        if (dismissesControlsOnCaptionSelection(option.captionsOn)) onCaptionsShown()
                    },
                )
            }
        }
    }
}

/**
 * Whether choosing a caption row should also dismiss the player controls.
 *
 * Only when the row leaves captions running. Android and iOS render the caption overlay
 * behind `captionController.enabled && !showVideoControls` — the controls and the captions
 * occupy the same strip, so captions are literally not drawn while the control bar is up.
 * Picking a language therefore appeared to do nothing until the auto-hide timer expired,
 * and did nothing at all while paused, since the timer only runs during playback.
 *
 * "No translation" must not dismiss anything: the user is working in the control bar, and
 * yanking it away as a side effect of turning captions *off* is the kind of thing that makes
 * a control feel broken. That asymmetry is the whole content of this function, which is why
 * it is named and tested rather than inlined at the call site.
 */
internal fun dismissesControlsOnCaptionSelection(captionsNowEnabled: Boolean): Boolean =
    captionsNowEnabled

/**
 * The shared top-right control cluster for every player: the caption language menu, followed
 * by an optional platform-specific [trailingControls] slot (PiP on iOS, Fullscreen on web).
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
 * @param onCaptionsShown called when a selection leaves captions running, so a platform
 *   whose controls cover the caption strip can get out of the way. Defaults to a no-op
 *   for the web player, which has no controls-visibility state to change.
 * @param onCaptionMenuOpenChange reports the caption menu opening and closing, so a platform
 *   that auto-hides its controls can hold them up while the menu is on screen. Defaults to a
 *   no-op for the players that never hide their controls on a timer.
 */
@Composable
internal fun PlayerTopRightControls(
    captionController: CaptionController,
    @Suppress("UNUSED_PARAMETER") onToggleStats: () -> Unit,
    @Suppress("UNUSED_PARAMETER") qualityMenu: @Composable () -> Unit,
    trailingControls: @Composable () -> Unit = {},
    onCaptionsShown: () -> Unit = {},
    onCaptionMenuOpenChange: (Boolean) -> Unit = {},
) {
    CaptionLanguageMenu(
        controller = captionController,
        onCaptionsShown = onCaptionsShown,
        onMenuOpenChange = onCaptionMenuOpenChange,
    )
    trailingControls()
}

/**
 * Tap to cycle the live streaming provider. Soniox is labelled by what it does for this
 * viewer — "Translate to Russian" while Russian is the chosen caption language — rather than
 * by its name; see [captionLabel].
 *
 * **Not currently rendered.** [PlayerTopRightControls] dropped it when Soniox became the
 * only provider worth offering; kept intact so restoring the choice is a one-line change
 * rather than a rewrite.
 */
@Suppress("unused")
@Composable
internal fun CaptionProviderButton(controller: CaptionController) {
    TextButton(onClick = {
        val next = when (controller.provider) {
            TranscriptionProvider.DEEPGRAM -> TranscriptionProvider.SONIOX
            TranscriptionProvider.SONIOX -> TranscriptionProvider.DEEPGRAM
        }
        controller.onSelectProvider(next)
    }) {
        Text(
            text = controller.provider.captionLabel(controller.selected.translateTo),
            color = Color.White.copy(alpha = 0.85f),
        )
    }
}
