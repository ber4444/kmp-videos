package com.livingpresence.inner.circle.squared.transcription

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.Foundation.NSUserDefaults

private const val DEFAULTS_KEY = "ics.captions.selectionKey"

/**
 * Stores the selection key in `NSUserDefaults`. Unlike `discord/DiscordSession.ios.kt`'s
 * Keychain-backed store, this holds no bearer credential — just a two-letter language
 * code — so the plist-backed, backed-up-with-the-app store that would be the wrong choice
 * for a refresh token is exactly right here.
 */
private object IosCaptionLanguagePreference : CaptionLanguagePreference {

    override fun save(selectionKey: String?) {
        val defaults = NSUserDefaults.standardUserDefaults
        if (selectionKey == null) {
            defaults.removeObjectForKey(DEFAULTS_KEY)
        } else {
            defaults.setObject(selectionKey, DEFAULTS_KEY)
        }
    }

    override fun load(): String? = NSUserDefaults.standardUserDefaults.stringForKey(DEFAULTS_KEY)
}

@Composable
internal actual fun rememberCaptionLanguagePreference(): CaptionLanguagePreference =
    remember { IosCaptionLanguagePreference }
