package com.livingpresence.inner.circle.squared.discord

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.browser.window

private const val REFRESH_TOKEN_KEY = "ics.discord.refreshToken"
private const val DISPLAY_NAME_KEY = "ics.discord.displayName"

/**
 * Stores the session in `localStorage` — unlike the `sessionStorage` used for the
 * in-flight PKCE values, this has to outlive the tab.
 *
 * A refresh token in `localStorage` is readable by any script running on this
 * origin, so it is only as safe as the page is from XSS. The alternative for the
 * web is a backend holding the token in an HttpOnly cookie; that is the right
 * answer if this app ever grows a server, and the wrong amount of machinery
 * before then. `localStorage` also throws outright in some privacy modes, in
 * which case the user simply signs in each visit.
 */
private object WasmDiscordSessionStore : DiscordSessionStore {

    override fun save(session: DiscordSession) {
        runCatching {
            window.localStorage.setItem(REFRESH_TOKEN_KEY, session.refreshToken)
            window.localStorage.setItem(DISPLAY_NAME_KEY, session.displayName)
        }
    }

    override fun load(): DiscordSession? {
        val refreshToken = runCatching { window.localStorage.getItem(REFRESH_TOKEN_KEY) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return DiscordSession(
            refreshToken = refreshToken,
            displayName = runCatching { window.localStorage.getItem(DISPLAY_NAME_KEY) }
                .getOrNull()
                .orEmpty(),
        )
    }

    override fun clear() {
        runCatching {
            window.localStorage.removeItem(REFRESH_TOKEN_KEY)
            window.localStorage.removeItem(DISPLAY_NAME_KEY)
        }
    }
}

@Composable
actual fun rememberDiscordSessionStore(): DiscordSessionStore = remember { WasmDiscordSessionStore }
