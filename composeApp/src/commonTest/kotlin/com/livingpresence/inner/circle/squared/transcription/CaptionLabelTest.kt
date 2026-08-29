package com.livingpresence.inner.circle.squared.transcription

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the caption provider button reads. The provider name is an implementation detail;
 * the choice the viewer is actually making is English captions vs captions in their own
 * language, so the button says so.
 */
class CaptionLabelTest {

    @Test
    fun sonioxIsLabelledByTheLanguageItTranslatesInto() {
        assertEquals("Translate to Russian", TranscriptionProvider.SONIOX.captionLabel("ru"))
        assertEquals("Translate to Hungarian", TranscriptionProvider.SONIOX.captionLabel("hu"))
    }

    @Test
    fun withoutATargetSonioxKeepsItsName() {
        // An English device, or one set to a language Soniox has no model for: nothing is
        // translated, so promising a translation would be a lie.
        assertEquals("Soniox", TranscriptionProvider.SONIOX.captionLabel(null))
    }

    @Test
    fun anUnknownCodeDoesNotProduceAHalfWrittenLabel() {
        // Guards against "Translate to null" / "Translate to " if a code ever escapes
        // normalize() without a name in the table.
        assertEquals("Soniox", TranscriptionProvider.SONIOX.captionLabel("xx"))
    }

    @Test
    fun deepgramKeepsItsNameBecauseItCannotTranslate() {
        assertEquals("Deepgram", TranscriptionProvider.DEEPGRAM.captionLabel("ru"))
        assertEquals("Deepgram", TranscriptionProvider.DEEPGRAM.captionLabel(null))
    }

    // --- The caption toggle, which is what the player actually shows. ---

    private fun toggle(
        enabled: Boolean,
        translateTo: String? = "ru",
        status: TranscriberStatus = TranscriberStatus.LISTENING,
    ) = captionToggleLabel(enabled, translateTo, status)

    @Test
    fun offTheToggleOffersTheDeviceLanguage() {
        assertEquals("Russian", toggle(enabled = false, status = TranscriberStatus.IDLE))
        assertEquals("Hungarian", toggle(enabled = false, translateTo = "hu", status = TranscriberStatus.IDLE))
    }

    @Test
    fun onTheToggleOffersToStopTranslating() {
        assertEquals("No translation", toggle(enabled = true))
    }

    @Test
    fun withNothingToTranslateTheWordingDropsToPlainCaptions() {
        // An English device: captions are English either way, so "No translation" would
        // describe the on state and the off state equally well, i.e. not at all.
        assertEquals("Captions", toggle(enabled = false, translateTo = null, status = TranscriberStatus.IDLE))
        assertEquals("No captions", toggle(enabled = true, translateTo = null))
    }

    @Test
    fun theStreamStateIsStillVisibleOnTheButton() {
        assertEquals("No translation …", toggle(enabled = true, status = TranscriberStatus.CONNECTING))
        assertEquals("No translation ↻", toggle(enabled = true, status = TranscriberStatus.RECONNECTING))
        assertEquals("No translation !", toggle(enabled = true, status = TranscriberStatus.ERROR))
        assertEquals("No translation", toggle(enabled = true, status = TranscriberStatus.LISTENING))
    }

    @Test
    fun theOffLabelIsNeverMarkedWithAStaleState() {
        // status lingers at ERROR after a rejected key; the off label is an invitation to
        // turn captions on, not a report on the session that just died.
        assertEquals("Russian", toggle(enabled = false, status = TranscriberStatus.ERROR))
    }
}
