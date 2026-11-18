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

/**
 * While captions are running, the button that used to open the language menu instead just
 * turns them off — the menu itself would require reading it, and anyone who picked a
 * translation did so because they don't read the menu's language (English) comfortably. So
 * the one thing offered here is named in [selected]'s own language rather than in English:
 * "Feliratok le" for a viewer who chose Hungarian, not "Captions off". The stream-status mark
 * [captionMenuButtonLabel] adds is dropped on purpose — pairing a connection-state glyph with
 * an action label in a language the glyph's meaning was never translated into would be noise,
 * and the captions on screen are already the evidence a working stream gives.
 *
 * Covers every code in [CaptionLanguage.SUPPORTED], including [selected] having none (the
 * spoken-language row, which is captioned but not translated): that row's viewer reads
 * English, so the English label is also the correct one, not just the fallback.
 */
internal fun captionOffButtonLabel(selected: CaptionMenuOption): String =
    selected.translateTo?.let { CAPTIONS_OFF_LABEL[it] } ?: CAPTIONS_OFF_LABEL_EN

private const val CAPTIONS_OFF_LABEL_EN = "Captions off"

/**
 * "Captions off" in every language [CaptionLanguage.SUPPORTED] lists. These are this model's
 * own best-effort translations, not sourced from a native speaker or a reviewed glossary —
 * fine for a two-word UI label, but **the entries below marked with a trailing comment should
 * get a native-speaker check before this ships**, same as any machine translation would need.
 * The major-language entries (French, German, Spanish, Russian, Chinese, Japanese, Korean,
 * Portuguese, Italian, Dutch, Polish, Turkish, Ukrainian, the Nordics…) carry the common,
 * well-established phrasing video UIs already use and are lower-risk.
 */
private val CAPTIONS_OFF_LABEL: Map<String, String> = mapOf(
    "af" to "Onderskrifte af", // review
    "sq" to "Titrat e çaktivizuara", // review
    "ar" to "الترجمة متوقفة",
    "az" to "Altyazılar deaktiv", // review
    "eu" to "Azpidatziak itzalita", // review
    "be" to "Субтытры выключаны", // review
    "bn" to "ক্যাপশন বন্ধ",
    "bs" to "Titlovi isključeni",
    "bg" to "Субтитрите изключени",
    "ca" to "Subtítols desactivats",
    "zh" to "字幕已关闭",
    "hr" to "Titlovi isključeni",
    "cs" to "Titulky vypnuté",
    "da" to "Undertekster fra",
    "nl" to "Ondertiteling uit",
    "en" to CAPTIONS_OFF_LABEL_EN,
    "et" to "Subtiitrid väljas",
    "fi" to "Tekstitys pois",
    "fr" to "Sous-titres désactivés",
    "gl" to "Subtítulos desactivados",
    "de" to "Untertitel aus",
    "el" to "Υπότιτλοι ανενεργοί", // review
    "gu" to "કૅપ્શન બંધ", // review
    "he" to "כתוביות כבויות",
    "hi" to "कैप्शन बंद",
    "hu" to "Feliratok le",
    "id" to "Subtitel nonaktif",
    "it" to "Sottotitoli disattivati",
    "ja" to "字幕オフ",
    "kn" to "ಶೀರ್ಷಿಕೆಗಳು ಆಫ್", // review
    "kk" to "Субтитрлер өшірілген", // review
    "ko" to "자막 꺼짐",
    "lv" to "Subtitri izslēgti",
    "lt" to "Subtitrai išjungti",
    "mk" to "Титловите се исклучени", // review
    "ms" to "Sari kata dimatikan", // review
    "ml" to "അടിക്കുറിപ്പുകൾ ഓഫ്", // review
    "mr" to "सबटायटल्स बंद", // review
    "no" to "Undertekster av",
    "fa" to "زیرنویس خاموش", // review
    "pl" to "Napisy wyłączone",
    "pt" to "Legendas desativadas",
    "pa" to "ਕੈਪਸ਼ਨ ਬੰਦ", // review
    "ro" to "Subtitrări dezactivate",
    "ru" to "Субтитры выключены",
    "sr" to "Титлови искључени", // review
    "sk" to "Titulky vypnuté",
    "sl" to "Podnapisi izklopljeni",
    "es" to "Subtítulos desactivados",
    "sw" to "Manukuu yamezimwa", // review
    "sv" to "Undertexter av",
    "tl" to "Naka-off ang caption", // review
    "ta" to "வசன வரிகள் ஆஃப்", // review
    "te" to "క్యాప్షన్లు ఆఫ్", // review
    "th" to "คำบรรยายปิด",
    "tr" to "Altyazılar kapalı",
    "uk" to "Субтитри вимкнено",
    "ur" to "کیپشنز بند", // review
    "vi" to "Phụ đề đã tắt",
    "cy" to "Capsiynau i ffwrdd", // review
)
