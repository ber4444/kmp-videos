package com.livingpresence.inner.circle.squared.transcription

import kotlinx.browser.window

/**
 * The browser's UI language (`navigator.language`) — `"ru"`, `"pt-BR"`, `"en-US"`. This is
 * the first entry of `navigator.languages`, i.e. the one the user ranked highest.
 */
internal actual fun deviceLanguageTag(): String = window.navigator.language
