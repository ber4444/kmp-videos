package com.livingpresence.inner.circle.squared.transcription

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

private const val PREFS_NAME = "caption_language"
private const val KEY_SELECTION = "selection_key"

/**
 * Stores the selection key in app-private `SharedPreferences`. Unlike
 * `discord/DiscordSession.android.kt`'s store, this holds no credential — just a
 * two-letter language code — so there is nothing here worth more protection than
 * any other app setting.
 */
private class AndroidCaptionLanguagePreference(context: Context) : CaptionLanguagePreference {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun save(selectionKey: String?) {
        prefs.edit().putString(KEY_SELECTION, selectionKey).apply()
    }

    override fun load(): String? = prefs.getString(KEY_SELECTION, null)
}

@Composable
internal actual fun rememberCaptionLanguagePreference(): CaptionLanguagePreference {
    val context = LocalContext.current
    return remember(context) { AndroidCaptionLanguagePreference(context) }
}
