package com.livingpresence.inner.circle.squared.transcription

import platform.Foundation.NSLocale
import platform.Foundation.preferredLanguages

/**
 * The head of the user's preferred-language list (`"ru-RU"`, `"zh-Hans-CN"`).
 *
 * Deliberately not `NSLocale.currentLocale`: that resolves against the bundle's
 * localizations, and this app ships none, so it would report the development region —
 * English — on every device no matter what the user set. `preferredLanguages` is the
 * user's own ordered choice and is unaffected by what the app has been translated into.
 */
internal actual fun deviceLanguageTag(): String =
    NSLocale.preferredLanguages.firstOrNull() as? String ?: "en"
