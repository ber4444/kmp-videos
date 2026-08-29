package com.livingpresence.inner.circle.squared.transcription

/**
 * The device's UI language as a BCP-47 tag — `"ru-RU"`, `"pt-BR"`, `"en"`. Read from
 * `Locale.getDefault()` on Android, `NSLocale.preferredLanguages` on iOS and
 * `navigator.language` on web.
 */
internal expect fun deviceLanguageTag(): String

/**
 * Decides what language the live captions should be *written* in.
 *
 * The streams are spoken in English, so the audio side never changes — what changes is the
 * text the viewer reads. Soniox can translate in-band (see [SonioxClient]), so a device set
 * to Russian gets Russian captions off the same English audio, on the same socket, with no
 * second service in the path. This object turns the platform's locale into the language code
 * that config frame wants, or into `null` when the stream should not be translated at all.
 *
 * Two cases produce `null`, and both matter:
 *
 * - **The device already speaks the source language.** An English device asking for an
 *   English translation would pay latency for a round trip to the same words.
 * - **Soniox has no such language.** An unsupported `target_language` is rejected with an
 *   `error_message`, which [WebSocketTranscriber] treats as a routine failure and retries —
 *   so a Welsh-but-not-really locale would loop forever instead of just showing English.
 *   Falling back to untranslated captions is the graceful failure here.
 */
internal object CaptionLanguage {

    /**
     * Every language in Soniox's supported-languages table. Transcription and translation
     * share the list, and translation works between any pair of them.
     */
    val SUPPORTED = setOf(
        "af", "sq", "ar", "az", "eu", "be", "bn", "bs", "bg", "ca", "zh", "hr", "cs", "da",
        "nl", "en", "et", "fi", "fr", "gl", "de", "el", "gu", "he", "hi", "hu", "id", "it",
        "ja", "kn", "kk", "ko", "lv", "lt", "mk", "ms", "ml", "mr", "no", "fa", "pl", "pt",
        "pa", "ro", "ru", "sr", "sk", "sl", "es", "sw", "sv", "tl", "ta", "te", "th", "tr",
        "uk", "ur", "vi", "cy",
    )

    /**
     * The language to translate captions into for this device, or null to leave them in the
     * source language. [sourceLanguages] is what the audio is expected to be — the same
     * `language_hints` sent in the Soniox config frame.
     */
    fun deviceTarget(sourceLanguages: List<String>): String? =
        targetFor(deviceLanguageTag(), sourceLanguages)

    /** [deviceTarget] with the locale supplied rather than read from the platform. */
    fun targetFor(tag: String, sourceLanguages: List<String>): String? =
        normalize(tag)?.takeIf { it !in sourceLanguages }

    /**
     * Reduces a BCP-47 tag to the bare code Soniox uses, or null if it names a language
     * Soniox does not have. Region and script subtags are dropped (`"pt-BR"` → `"pt"`,
     * `"zh-Hans-CN"` → `"zh"`) since Soniox models the language, not the locale.
     */
    fun normalize(tag: String): String? {
        val primary = tag.substringBefore('-').substringBefore('_').lowercase()
        return (ALIASES[primary] ?: primary).takeIf { it in SUPPORTED }
    }

    /** Language codes the platforms emit that Soniox spells differently, or not at all. */
    private val ALIASES = mapOf(
        // Soniox has one Norwegian; the OS reports the written standard.
        "nb" to "no",
        "nn" to "no",
        // Filipino is the standardised register of Tagalog.
        "fil" to "tl",
        // Pre-1989 ISO 639 codes, still what java.util.Locale.getLanguage() returns.
        "iw" to "he",
        "in" to "id",
        // Chinese macrolanguage members; Soniox exposes a single "zh".
        "cmn" to "zh",
        "yue" to "zh",
    )
}
