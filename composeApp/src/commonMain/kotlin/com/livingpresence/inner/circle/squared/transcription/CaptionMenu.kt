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

/** The off row itself — "No translation" — regardless of why a player landed on it. */
internal fun defaultCaptionOption(options: List<CaptionMenuOption>): CaptionMenuOption =
    options.first()

/**
 * The row a never-touched install starts on, before [CaptionLanguagePreference] has
 * anything recorded: the device's own language, if it has one, rather than silence — someone
 * who hasn't said anything yet is better served by captions in a language they read than by
 * a menu they have to find first. An English device has nothing to offer there (no second row
 * is a translation; the one that sits there instead is the spoken-language row, which the
 * viewer's own first language already covers), so it falls back to [defaultCaptionOption].
 *
 * Only for that one moment — before any choice exists. The instant the viewer picks anything,
 * [selectionKey] starts persisting it, including picking "No translation" itself, and that
 * recorded choice is what every later video restores; it does not fall back through here again.
 */
internal fun defaultCaptionOptionForFreshInstall(options: List<CaptionMenuOption>): CaptionMenuOption =
    options.getOrNull(1)?.takeIf { it.captionsOn && it.translateTo != null } ?: defaultCaptionOption(options)

/** Turning captions off, and the label of the row that does it. */
internal const val NO_TRANSLATION = "No translation"

/**
 * The key that identifies a row for [CaptionLanguagePreference], so the choice survives past
 * this menu's own [CaptionMenuOption] instances being rebuilt per device.
 *
 * [CaptionMenuOption.translateTo] alone can't do this: it is null on both "No translation" and
 * the untranslated spoken-language row, which are opposite choices — one costs nothing and the
 * other starts a session. And "No translation" needs a key of its own rather than persisting
 * as null, even though [optionForSelectionKey] treats the two alike: null means nothing has
 * been recorded, which is what lets [defaultCaptionOptionForFreshInstall] run exactly once, on
 * an install that has never chosen anything. An explicit "off" has to persist as a real choice
 * — [OFF_SELECTION_KEY] — or turning captions off would be indistinguishable from never having
 * touched the control, and the next video would offer the device's language again instead of
 * staying off until the viewer reopens the menu themselves.
 */
internal fun selectionKey(option: CaptionMenuOption): String =
    if (!option.captionsOn) OFF_SELECTION_KEY
    else option.translateTo ?: SPOKEN_LANGUAGE_SELECTION_KEY

/**
 * The reverse of [selectionKey]: resolves a persisted key back to a row of *this* device's
 * [options]. Falls back to [defaultCaptionOption] — not [defaultCaptionOptionForFreshInstall]
 * — for a null or explicitly-off key, and for a key naming a row this device's menu doesn't
 * carry (restoring on a different device, or a device locale that has since changed); the
 * fresh-install default is a one-time policy decided before anything is stored, not something
 * this generic resolver should reapply every time a lookup comes up empty.
 */
internal fun optionForSelectionKey(options: List<CaptionMenuOption>, key: String?): CaptionMenuOption =
    when (key) {
        null, OFF_SELECTION_KEY -> defaultCaptionOption(options)
        SPOKEN_LANGUAGE_SELECTION_KEY -> options.firstOrNull { it.captionsOn && it.translateTo == null }
        else -> options.firstOrNull { it.translateTo == key }
    } ?: defaultCaptionOption(options)

/** Persisted for an explicit "No translation" pick. Not a real Soniox code. */
private const val OFF_SELECTION_KEY = "off"

/**
 * Not a real Soniox code either — "en" is the spoken language, never a translation target
 * (see [CaptionLanguage.SPOKEN_LANGUAGES]) — so it can't collide with a
 * [CaptionMenuOption.translateTo].
 */
private const val SPOKEN_LANGUAGE_SELECTION_KEY = "en"
