package com.livingpresence.inner.circle.squared.transcription

/**
 * The rows of the caption language menu, and what each one does to the stream.
 *
 * The player used to carry a single toggle: captions off, or captions in the device's
 * language. That is the right default and a bad ceiling — a Hungarian speaker watching from
 * a German phone, or anyone whose system language is not the one they read comfortably, had
 * no way to say so. The menu keeps the default as the row directly under "off" and puts the
 * rest of the languages this audience actually uses behind it.
 *
 * Kept here rather than in the composable, and free of Compose types, so the wording and the
 * ordering — the parts that are easy to get wrong and impossible to see in a diff of a
 * `DropdownMenu` — can be asserted in a common test with no runtime. See
 * `CaptionLanguageMenu` for the UI that renders these.
 */

/**
 * One row of the menu.
 *
 * [captionsOn] is what separates the first row from the rest: "No translation" does not run
 * Soniox at all — no socket, no session key, no audio leaving the device — where every other
 * row starts a session. [translateTo] is the Soniox `target_language` for that session, and
 * is null on the spoken-language row, which transcribes without translating.
 */
internal data class CaptionMenuOption(
    val label: String,
    val translateTo: String?,
    val captionsOn: Boolean,
)

/**
 * The menu, in order, for a device whose caption language resolves to [deviceTarget] (see
 * [CaptionLanguage.deviceTarget]; null when the device speaks the source language or one
 * Soniox has no model for).
 *
 * 1. **No translation** — captions off entirely. First because it is the default and the way
 *    back out, and because it is the only row that costs nothing to run.
 * 2. **"<Language> translation"** — the device's own language, the choice the old toggle
 *    made. Absent when [deviceTarget] is null, since there is nothing to translate into that
 *    the next row does not already cover.
 * 3. **"English captions"** — the spoken language, transcribed and not translated. It is not
 *    a translation row, so it is labelled as captions rather than as a language, and it is
 *    what an English device gets in slot 2 instead of a translation of English into English.
 * 4. The rest of [CaptionLanguage.MENU_LANGUAGES], skipping the device's own so the same
 *    language is never offered twice under two different labels.
 */
internal fun captionMenuOptions(deviceTarget: String?): List<CaptionMenuOption> = buildList {
    add(CaptionMenuOption(NO_TRANSLATION, translateTo = null, captionsOn = false))
    deviceTarget?.let { code ->
        CaptionLanguage.displayName(code)?.let { name ->
            add(CaptionMenuOption("$name translation", translateTo = code, captionsOn = true))
        }
    }
    val spoken = CaptionLanguage.SPOKEN_LANGUAGES.first()
    add(
        CaptionMenuOption(
            label = "${CaptionLanguage.displayName(spoken) ?: spoken} captions",
            // Null, not "en": asking Soniox to translate English into English would pay a
            // round trip for the words it already sent, and doubles every token frame.
            translateTo = null,
            captionsOn = true,
        )
    )
    for (code in CaptionLanguage.MENU_LANGUAGES) {
        if (code == deviceTarget) continue
        val name = CaptionLanguage.displayName(code) ?: continue
        add(CaptionMenuOption(name, translateTo = code, captionsOn = true))
    }
}

/** The row a fresh player starts on: captions off until the viewer asks for them. */
internal fun defaultCaptionOption(options: List<CaptionMenuOption>): CaptionMenuOption =
    options.first()

/** Turning captions off, and the label of the row that does it. */
internal const val NO_TRANSLATION = "No translation"
