package com.livingpresence.inner.circle.squared.transcription

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.browser.window

private const val STORAGE_KEY = "ics.captions.selectionKey"

/**
 * Stores the selection key in `localStorage`, wrapped in `runCatching` the same way
 * `discord/DiscordSession.wasmJs.kt` is: some privacy modes throw on any access to it,
 * in which case the viewer just re-picks their language every visit.
 */
private object WasmCaptionLanguagePreference : CaptionLanguagePreference {

    override fun save(selectionKey: String?) {
        runCatching {
            if (selectionKey == null) {
                window.localStorage.removeItem(STORAGE_KEY)
            } else {
                window.localStorage.setItem(STORAGE_KEY, selectionKey)
            }
        }
    }

    override fun load(): String? = runCatching { window.localStorage.getItem(STORAGE_KEY) }.getOrNull()
}

@Composable
internal actual fun rememberCaptionLanguagePreference(): CaptionLanguagePreference =
    remember { WasmCaptionLanguagePreference }
