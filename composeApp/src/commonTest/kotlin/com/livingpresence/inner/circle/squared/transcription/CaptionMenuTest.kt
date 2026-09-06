package com.livingpresence.inner.circle.squared.transcription

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The caption language menu: which rows it offers, in what order, and what each one does to
 * the stream.
 *
 * Worth pinning because the menu is where two quiet failures would land. A row naming a
 * language Soniox has no model for sends a `target_language` the service rejects, and the
 * `error_message` looks like any other dropped socket to [WebSocketTranscriber] — so it
 * reconnects and fails again for the whole video rather than surfacing anything. And a row
 * that says "translation" while carrying no target (or the reverse) would silently give the
 * viewer captions in a language they did not ask for.
 */
class CaptionMenuTest {

    private val russianDevice = captionMenuOptions("ru")
    private val englishDevice = captionMenuOptions(null)

    @Test
    fun theFirstRowTurnsCaptionsOffEntirely() {
        // Not "captions in the spoken language": no session, no socket, no audio leaving
        // the device. It is also where every video starts — captions are metered, so the
        // default has to be the row that costs nothing, on the second video as on the first.
        val off = russianDevice.first()
        assertEquals("No translation", off.label)
        assertEquals(false, off.captionsOn)
        assertNull(off.translateTo)
        assertEquals(off, defaultCaptionOption(russianDevice))
        assertEquals(englishDevice.first(), defaultCaptionOption(englishDevice))
    }

    @Test
    fun theDeviceLanguageIsTheRowUnderIt() {
        // The choice the old toggle made, still one tap away from where it used to be.
        val device = russianDevice[1]
        assertEquals("Russian translation", device.label)
        assertEquals("ru", device.translateTo)
        assertTrue(device.captionsOn)
    }

    @Test
    fun theSpokenLanguageIsOfferedAsCaptionsRatherThanATranslation() {
        val spoken = russianDevice[2]
        assertEquals("English captions", spoken.label)
        assertTrue(spoken.captionsOn)
        assertNull(
            spoken.translateTo,
            "translating English into English pays a round trip for the same words",
        )
    }

    @Test
    fun anEnglishDeviceGetsPlainCaptionsInSlotTwo() {
        // deviceTarget() is null on an English device — there is nothing to translate — so
        // the translation row is skipped rather than reading "English translation", and the
        // second row is still the one that just turns captions on.
        assertEquals(listOf("No translation", "English captions"), englishDevice.take(2).map { it.label })
        assertEquals(listOf("Russian", "Italian"), englishDevice.drop(2).take(2).map { it.label })
    }

    @Test
    fun theDeviceLanguageIsNotOfferedTwice() {
        // It already has the "<Language> translation" row; listing it again further down
        // under a bare name would look like a second, different option.
        assertEquals(1, russianDevice.count { it.translateTo == "ru" })
        assertEquals(
            listOf("No translation", "Russian translation", "English captions", "Italian", "Spanish"),
            russianDevice.take(5).map { it.label },
        )
    }

    @Test
    fun everyLanguageRowCarriesTheCodeItsLabelNames() {
        for (option in russianDevice.drop(3)) {
            assertEquals(
                option.label,
                CaptionLanguage.displayName(option.translateTo.orEmpty()),
                "${option.label} must send the code Soniox spells that language with",
            )
            assertTrue(option.captionsOn, "${option.label} has to actually run a session")
        }
    }

    @Test
    fun everyOfferedLanguageIsOneSonioxCanTranslateInto() {
        // The guard against the reconnect loop: an unsupported target is not a visible
        // error, it is a video with no captions at all.
        val unsupported = CaptionLanguage.MENU_LANGUAGES.filterNot { it in CaptionLanguage.SUPPORTED }
        assertEquals(emptyList(), unsupported, "Soniox has no model for these")
    }

    @Test
    fun theListIsFreeOfDuplicates() {
        assertEquals(
            CaptionLanguage.MENU_LANGUAGES.distinct(),
            CaptionLanguage.MENU_LANGUAGES,
        )
        assertEquals(
            russianDevice.map { it.label }.distinct(),
            russianDevice.map { it.label },
        )
    }

    @Test
    fun theSpokenLanguageIsNotAlsoATranslationTarget() {
        // "English" as a translation row would open a session that translates English into
        // English, next to a row that does the same thing for free.
        assertTrue(CaptionLanguage.SPOKEN_LANGUAGES.none { it in CaptionLanguage.MENU_LANGUAGES })
    }

    @Test
    fun theMainLanguagesTheAudienceReadsAreAllOffered() {
        // The list the menu was built from, minus Armenian, which Soniox cannot translate
        // into — see CaptionLanguage.MENU_LANGUAGES.
        val expected = listOf(
            "Russian", "Italian", "Spanish", "German", "French", "Portuguese", "Gujarati",
            "Hindi", "Marathi", "Chinese", "Dutch", "Arabic", "Greek", "Romanian",
            "Hungarian", "Turkish", "Japanese", "Ukrainian", "Slovenian", "Belarusian",
            "Czech", "Hebrew",
        )
        assertEquals(expected, CaptionLanguage.MENU_LANGUAGES.map { CaptionLanguage.displayName(it) })
    }

    @Test
    fun aDeviceLanguageOutsideTheOfferedListStillGetsItsOwnRow() {
        // Korean is not on the short list, but a Korean device must still find Korean at
        // the top rather than having to settle for one of the twenty-two.
        val korean = captionMenuOptions("ko")
        assertEquals("Korean translation", korean[1].label)
        assertEquals("ko", korean[1].translateTo)
        assertEquals(
            russianDevice.size + 1,
            korean.size,
            "nothing is dropped for it: Korean is one row on top of the full list",
        )
    }
}
