package com.livingpresence.inner.circle.squared.transcription

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Token selection for translated captions.
 *
 * The bug guarded here: with translation enabled Soniox returns the English original and
 * the translation over the same socket, interleaved, distinguished only by
 * `translation_status`. Concatenating every token — which is what an untranslated session
 * correctly does — renders both languages jammed into one caption line.
 */
class SonioxCaptionTextTest {

    /** A frame as Soniox sends it while translating: originals first, translation after. */
    private val mixed = listOf(
        SonioxToken(text = "Good ", isFinal = true, translationStatus = "original"),
        SonioxToken(text = "morning.", isFinal = true, translationStatus = "original"),
        SonioxToken(text = "Доброе ", isFinal = true, translationStatus = "translation"),
        SonioxToken(text = "утро.", isFinal = true, translationStatus = "translation"),
    )

    @Test
    fun translatingKeepsOnlyTheTranslatedTokens() {
        val caption = selectCaptionText(mixed, translating = true)
        assertEquals("Доброе утро.", caption.finalized)
        assertEquals("", caption.partial)
    }

    @Test
    fun notTranslatingKeepsEveryToken() {
        val tokens = listOf(
            SonioxToken(text = "Good ", isFinal = true),
            SonioxToken(text = "morning.", isFinal = true),
        )
        assertEquals("Good morning.", selectCaptionText(tokens, translating = false).finalized)
    }

    @Test
    fun finalAndInterimTokensAreSplit() {
        val tokens = listOf(
            SonioxToken(text = "Доброе ", isFinal = true, translationStatus = "translation"),
            SonioxToken(text = "утро", isFinal = false, translationStatus = "translation"),
        )
        val caption = selectCaptionText(tokens, translating = true)
        assertEquals("Доброе ", caption.finalized, "only final tokens are committed")
        assertEquals("утро", caption.partial, "the rest is the live tail")
    }

    @Test
    fun aFrameOfOnlyOriginalsProducesNoCaption() {
        // Translation lags the original by a chunk, so frames arrive with nothing to show.
        val caption = selectCaptionText(mixed.take(2), translating = true)
        assertEquals("", caption.finalized)
        assertEquals("", caption.partial)
    }
}
