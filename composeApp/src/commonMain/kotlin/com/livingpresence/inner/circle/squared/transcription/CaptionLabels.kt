package com.livingpresence.inner.circle.squared.transcription

/**
 * The text on the caption buttons.
 *
 * Kept out of the composables and free of Compose types so the wording — the part that is
 * actually easy to get wrong — can be asserted directly in a common test.
 */

/**
 * The caption toggle's label, which states what tapping it will *do* rather than what is
 * currently happening: the device's caption language while captions are off ("Russian"),
 * and "No translation" while they are on.
 *
 * [translateTo] is the resolved target from [CaptionLanguage.deviceTarget]; null means
 * nothing would be translated — an English device, or a language Soniox does not cover. The
 * wording drops to plain captions there, because a device reading English captions off
 * English audio is not translating and "No translation" would describe both states equally.
 *
 * [status] is folded in as a trailing mark so a stream that is connecting, retrying or dead
 * is still visible on the button. It replaces the old `CC…`/`CC↻`/`CC!` suffixes, and the
 * healthy case stays unmarked: the captions themselves are the evidence it is working, and a
 * steady `●` next to a text label is noise.
 */
internal fun captionToggleLabel(
    enabled: Boolean,
    translateTo: String?,
    status: TranscriberStatus,
): String {
    val language = translateTo?.let { CaptionLanguage.displayName(it) }
    if (!enabled) return language ?: CAPTIONS_ON
    val off = if (language != null) NO_TRANSLATION else CAPTIONS_OFF
    return off + status.mark()
}

/**
 * The provider button's label — currently unreachable, since the Deepgram/Soniox switcher
 * is not rendered (see `PlayerTopRightControls`). Kept with the button it belongs to.
 */
internal fun TranscriptionProvider.captionLabel(translateTo: String?): String = when (this) {
    TranscriptionProvider.DEEPGRAM -> label
    TranscriptionProvider.SONIOX ->
        translateTo?.let { CaptionLanguage.displayName(it) }?.let { "Translate to $it" } ?: label
}

/**
 * Trailing state mark. [TranscriberStatus.RECONNECTING] is routine on a long stream — the
 * socket drops and the client recovers on its own — so it reads as "working on it" rather
 * than as a failure; only [TranscriberStatus.ERROR] means captions have actually stopped.
 */
private fun TranscriberStatus.mark(): String = when (this) {
    TranscriberStatus.CONNECTING -> " …"
    TranscriberStatus.RECONNECTING -> " ↻"
    TranscriberStatus.ERROR -> " !"
    TranscriberStatus.IDLE, TranscriberStatus.LISTENING -> ""
}

/** Turning captions on when nothing will be translated. */
private const val CAPTIONS_ON = "Captions"

/** Turning them off again in that same case, where "No translation" would say nothing. */
private const val CAPTIONS_OFF = "No captions"

private const val NO_TRANSLATION = "No translation"
