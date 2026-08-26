package com.livingpresence.inner.circle.squared.discord

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

private const val PREFS_NAME = "discord_session"
private const val KEY_REFRESH_TOKEN = "refresh_token"
private const val KEY_DISPLAY_NAME = "display_name"

/**
 * Stores the session in app-private `SharedPreferences`.
 *
 * Not encrypted at the app layer: Jetpack Security's `EncryptedSharedPreferences`
 * is deprecated and unmaintained, and there is no drop-in successor. What the
 * refresh token does get is app-private storage plus file-based encryption at
 * rest, so it is readable only by this app, by root, or on an unlocked
 * device with debugging enabled — the same protection every other app's session
 * gets. If this ever guards something more valuable than a video feed, the
 * upgrade path is the platform keystore, not a hand-rolled cipher.
 */
private class AndroidDiscordSessionStore(context: Context) : DiscordSessionStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun save(session: DiscordSession) {
        prefs.edit()
            .putString(KEY_REFRESH_TOKEN, session.refreshToken)
            .putString(KEY_DISPLAY_NAME, session.displayName)
            .apply()
    }

    override fun load(): DiscordSession? {
        val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null)?.takeIf { it.isNotBlank() }
            ?: return null
        return DiscordSession(
            refreshToken = refreshToken,
            displayName = prefs.getString(KEY_DISPLAY_NAME, null).orEmpty(),
        )
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }
}

@Composable
actual fun rememberDiscordSessionStore(): DiscordSessionStore {
    val context = LocalContext.current
    return remember(context) { AndroidDiscordSessionStore(context) }
}
