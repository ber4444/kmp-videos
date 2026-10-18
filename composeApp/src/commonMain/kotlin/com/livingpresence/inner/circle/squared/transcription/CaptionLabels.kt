package com.livingpresence.inner.circle.squared.transcription

/**
 * The text on the caption buttons.
 *
 * Kept out of the composables and free of Compose types so the wording — the part that is
 * actually easy to get wrong — can be asserted directly in a common test.
 */

/**
 * The label on the button that opens the caption language menu.
 *
 * A button that opens a menu reports state rather than promising an action, so it reads as
 * the row that is currently selected: "No translation" while captions are off, "Russian
 * translation" while they are on, "Italian" for a language picked over the device's own. The
 * old toggle said what tapping it would *do*, because tapping it did exactly one thing;
 * naming the action is meaningless now that the tap only opens a list. See
 * [captionMenuOptions] for the rows themselves.
 *
 * [status] is folded in as a trailing mark so a stream that is connecting, retrying or dead
 * is still visible without opening the menu. The healthy case stays unmarked — the captions
 * themselves are the evidence it is working, and a steady `●` next to a text label is noise
 * — and so does the off row, whose status is whatever the session that just ended left
 * behind and says nothing about a stream that is not running.
 */
internal fun captionMenuButtonLabel(
    selected: CaptionMenuOption,
    status: TranscriberStatus,
): String = selected.label + if (selected.captionsOn) status.mark() else ""

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
