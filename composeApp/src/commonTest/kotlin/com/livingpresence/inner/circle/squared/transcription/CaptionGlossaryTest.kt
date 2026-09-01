package com.livingpresence.inner.circle.squared.transcription

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The glossary's invariants, and the wire shape it turns into.
 *
 * These are cheap guards on a table that is meant to be edited by hand and grown a language
 * at a time. The failure they exist to catch is a term or a language added to one map and
 * forgotten in the others — which produces no error at runtime, just a caption that quietly
 * reverts to a literal translation on some devices and not others.
 */
class CaptionGlossaryTest {

    @Test
    fun everyLanguageRendersExactlyTheBoostedTerms() {
        // Not "contains": a language carrying a term the others don't is the same editing
        // slip as one missing a term, and just as invisible at runtime.
        for (language in CaptionGlossary.LANGUAGES) {
            assertEquals(
                CaptionGlossary.TERMS.toSet(),
                CaptionGlossary.translationTermsFor(language).keys,
                "$language does not render exactly the terms in TERMS",
            )
        }
    }

    @Test
    fun russianAndHungarianAreBothPresent() {
        assertEquals(setOf("ru", "hu"), CaptionGlossary.LANGUAGES)
        // The term the captions most obviously get wrong without a glossary.
        assertEquals("Несотворённый Свет", CaptionGlossary.translationTermsFor("ru")["Uncreated light"])
        assertEquals("Teremtetlen fény", CaptionGlossary.translationTermsFor("hu")["Uncreated light"])
    }

    @Test
    fun termsAreUniqueAndNonBlank() {
        assertEquals(CaptionGlossary.TERMS.size, CaptionGlossary.TERMS.toSet().size)
        assertTrue(CaptionGlossary.TERMS.all { it.isNotBlank() })
        assertTrue(CaptionGlossary.LANGUAGES.all { language ->
            CaptionGlossary.translationTermsFor(language).values.all { it.isNotBlank() }
        })
    }

    /**
     * Soniox rejects a session whose context exceeds ~8,000 tokens (~10,000 characters).
     * Counted generously — characters, not tokens — so the check trips well before the API
     * does, leaving room to trim rather than debugging a rejected session in the field.
     */
    @Test
    fun theGlossaryStaysUnderSonioxContextLimit() {
        for (language in CaptionGlossary.LANGUAGES) {
            val terms = CaptionGlossary.translationTermsFor(language)
            val chars = CaptionGlossary.TERMS.sumOf { it.length } +
                terms.entries.sumOf { it.key.length + it.value.length }
            assertTrue(chars < 8_000, "$language glossary is $chars chars, close to the cap")
        }
    }

    @Test
    fun anUnknownOrAbsentLanguageGetsNoTranslationTerms() {
        // Most of CaptionLanguage.SUPPORTED has no glossary yet; those sessions still get
        // `terms`, and Soniox translates the vocabulary itself.
        assertTrue(CaptionGlossary.translationTermsFor("ja").isEmpty())
        assertTrue(CaptionGlossary.translationTermsFor(null).isEmpty())
    }

    @Test
    fun contextCarriesBothSectionsWhenTranslating() {
        val context = sonioxContext(
            terms = listOf("Uncreated light"),
            translationTerms = mapOf("Uncreated light" to "Несотворённый Свет"),
        )
        assertEquals(listOf("Uncreated light"), context?.terms)
        assertEquals(
            listOf(SonioxTranslationTerm("Uncreated light", "Несотворённый Свет")),
            context?.translationTerms,
        )
    }

    @Test
    fun contextOmitsTheTranslationSectionWhenThereIsNoGlossary() {
        val context = sonioxContext(terms = CaptionGlossary.TERMS, translationTerms = emptyMap())
        assertEquals(CaptionGlossary.TERMS, context?.terms)
        // Absent rather than empty: an empty array is a section Soniox has to parse and
        // ignore on every untranslated session.
        assertNull(context?.translationTerms)
    }

    @Test
    fun contextIsAbsentWhenThereIsNothingToSay() {
        assertNull(sonioxContext(terms = emptyList(), translationTerms = emptyMap()))
    }
}
