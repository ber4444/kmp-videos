package com.livingpresence.inner.circle.squared.transcription

import java.util.Locale

/**
 * Android's effective locale. [Locale.getDefault] follows the per-app language on
 * Android 13+ (`LocaleManager` updates it when the user picks one in app settings) as
 * well as the system-wide setting, so it tracks whichever the user actually chose.
 *
 * `toLanguageTag()` rather than `language`: the latter still returns the pre-1989 codes
 * (`iw`, `in`) that [CaptionLanguage] would otherwise have to guess at.
 */
internal actual fun deviceLanguageTag(): String = Locale.getDefault().toLanguageTag()
