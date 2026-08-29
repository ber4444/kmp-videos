package com.livingpresence.inner.circle.squared.transcription

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The locale → Soniox `target_language` mapping behind device-language captions.
 *
 * These matter because the failure modes are quiet: an unmapped tag sends a
 * `target_language` Soniox rejects, and the resulting `error_message` looks like any other
 * transient stream error to [WebSocketTranscriber] — so it reconnects and fails again for
 * the whole video rather than surfacing anything. Falling back to null (no translation)
 * keeps captions working in English instead.
 */
class CaptionLanguageTest {

    private val spoken = listOf("en")

    @Test
    fun regionAndScriptSubtagsAreDropped() {
        assertEquals("ru", CaptionLanguage.normalize("ru-RU"))
        assertEquals("pt", CaptionLanguage.normalize("pt-BR"))
        assertEquals("zh", CaptionLanguage.normalize("zh-Hans-CN"))
        assertEquals("es", CaptionLanguage.normalize("es_MX"), "java.util.Locale uses underscores")
        assertEquals("ru", CaptionLanguage.normalize("RU"), "case is not significant in BCP-47")
    }

    @Test
    fun bareTagsPassThrough() {
        assertEquals("ru", CaptionLanguage.normalize("ru"))
        assertEquals("de", CaptionLanguage.normalize("de"))
    }

    @Test
    fun platformSpellingsAreFoldedOntoSoniox() {
        // java.util.Locale.getLanguage() still returns the pre-1989 ISO codes.
        assertEquals("he", CaptionLanguage.normalize("iw-IL"))
        assertEquals("id", CaptionLanguage.normalize("in-ID"))
        // Soniox has one Norwegian; Android/iOS report the written standard.
        assertEquals("no", CaptionLanguage.normalize("nb-NO"))
        assertEquals("no", CaptionLanguage.normalize("nn-NO"))
        // Filipino is Tagalog.
        assertEquals("tl", CaptionLanguage.normalize("fil-PH"))
        // Chinese macrolanguage members.
        assertEquals("zh", CaptionLanguage.normalize("yue-HK"))
    }

    @Test
    fun unsupportedLanguagesResolveToNoTranslation() {
        assertNull(CaptionLanguage.normalize("ga-IE"), "Irish is not in Soniox's table")
        assertNull(CaptionLanguage.normalize("zu-ZA"), "Zulu is not in Soniox's table")
        assertNull(CaptionLanguage.normalize(""))
        assertNull(CaptionLanguage.normalize("not-a-language"))
    }

    @Test
    fun aDeviceSpeakingTheSourceLanguageIsNotTranslated() {
        assertNull(CaptionLanguage.targetFor("en-US", spoken))
        assertNull(CaptionLanguage.targetFor("en-GB", spoken))
        assertNull(CaptionLanguage.targetFor("en", spoken))
    }

    @Test
    fun aRussianDeviceAsksForRussian() {
        assertEquals("ru", CaptionLanguage.targetFor("ru-RU", spoken))
    }

    @Test
    fun theSupportedSetCoversTheLanguagesItClaims() {
        assertEquals(60, CaptionLanguage.SUPPORTED.size, "Soniox's table lists 60 languages")
        assertEquals(
            emptySet(),
            CaptionLanguage.SUPPORTED.filterNot { it.length == 2 }.toSet(),
            "Soniox addresses every language by its two-letter code",
        )
    }
}
