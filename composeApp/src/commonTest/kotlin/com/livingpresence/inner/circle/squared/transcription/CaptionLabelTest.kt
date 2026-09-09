package com.livingpresence.inner.circle.squared.transcription

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the caption buttons read. The provider name is an implementation detail; the choice
 * the viewer is actually making is what language they read the captions in, so the controls
 * say that instead — the (unrendered) provider button by naming what it would translate
 * into, and the menu button by naming the row the viewer is on.
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

    // --- The caption menu button, which is what the player actually shows. ---

    private fun button(
        selected: CaptionMenuOption,
        status: TranscriberStatus = TranscriberStatus.LISTENING,
    ) = captionMenuButtonLabel(selected, status)

    private val off = captionMenuOptions("ru")[0]
    private val russian = captionMenuOptions("ru")[1]
    private val english = captionMenuOptions("ru")[2]
    private val italian = captionMenuOptions("ru").first { it.translateTo == "it" }

    @Test
    fun theButtonNamesTheRowTheViewerIsOn() {
        // It opens a menu, so it reports the current selection rather than promising what a
        // tap will do — the tap only opens the list.
        assertEquals("No translation", button(off, status = TranscriberStatus.IDLE))
        assertEquals("Russian translation", button(russian))
        assertEquals("English captions", button(english))
        assertEquals("Italian", button(italian))
    }

    @Test
    fun theStreamStateIsStillVisibleOnTheButton() {
        assertEquals("Russian translation …", button(russian, TranscriberStatus.CONNECTING))
        assertEquals("Russian translation ↻", button(russian, TranscriberStatus.RECONNECTING))
        assertEquals("Russian translation !", button(russian, TranscriberStatus.ERROR))
        assertEquals("Russian translation", button(russian, TranscriberStatus.LISTENING))
    }

    @Test
    fun theOffRowIsNeverMarkedWithAStaleState() {
        // status lingers at ERROR after a rejected key; "No translation" describes a stream
        // that is not running, and has no state to report.
        assertEquals("No translation", button(off, TranscriberStatus.ERROR))
        assertEquals("No translation", button(off, TranscriberStatus.RECONNECTING))
    }
}
