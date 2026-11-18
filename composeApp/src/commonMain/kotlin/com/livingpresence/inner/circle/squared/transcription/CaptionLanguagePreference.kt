package com.livingpresence.inner.circle.squared.transcription

import androidx.compose.runtime.Composable

/**
 * Where the viewer's caption-language choice persists between videos and app launches.
 *
 * Stores [CaptionMenuOption.selectionKey], not the option itself: the menu is rebuilt per
 * device from [CaptionLanguage.deviceTarget], so what survives is the Soniox language code
 * (or the "off"/"spoken language" sentinels `selectionKey` uses), resolved back to a row of
 * *this* device's menu by [optionForSelectionKey].
 */
internal interface CaptionLanguagePreference {

    /** Persists [selectionKey], replacing any previous one. */
    fun save(selectionKey: String?)

    /** The stored selection key, or null if there is none (or it could not be read). */
    fun load(): String?
}

/** The platform's [CaptionLanguagePreference]. */
@Composable
internal expect fun rememberCaptionLanguagePreference(): CaptionLanguagePreference

/**
 * A preference that forgets everything, for previews, tests, and any platform where
 * persistence is unavailable. Every video then starts on "No translation", same as before
 * this preference existed.
 */
internal object NoOpCaptionLanguagePreference : CaptionLanguagePreference {
    override fun save(selectionKey: String?) = Unit
    override fun load(): String? = null
}
