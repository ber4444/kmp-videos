package com.livingpresence.inner.circle.squared.discord

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.browser.window

/**
 * The page's own URL, minus any query or fragment. Discord requires the redirect
 * to match a registered URI exactly, so the served origin + path is registered
 * on the Discord application rather than a custom scheme (which a browser tab
 * has no way to land on).
 */
actual fun defaultDiscordRedirectUri(): String =
    window.location.origin + window.location.pathname

@Composable
actual fun rememberDiscordAuthLauncher(): (String) -> Unit = remember {
    // Navigate the current tab rather than opening a popup: the token comes back
    // in this page's fragment, and `Main.kt` reads it on the next load.
    { url -> window.location.href = url }
}

private const val AUTH_STATE_KEY = "ics.discord.authState"
private const val CODE_VERIFIER_KEY = "ics.discord.codeVerifier"

// Discord navigates the tab away and back, so both values must survive a full
// page load. sessionStorage is tab-scoped and cleared when the tab closes; it can
// also throw outright (Safari private browsing, blocked third-party storage), in
// which case the checks simply fail and the user is asked to retry.
private fun store(key: String, value: String?) {
    runCatching {
        if (value == null) window.sessionStorage.removeItem(key)
        else window.sessionStorage.setItem(key, value)
    }
}

private fun load(key: String): String? =
    runCatching { window.sessionStorage.getItem(key) }.getOrNull()

internal actual fun saveDiscordAuthState(state: String?) = store(AUTH_STATE_KEY, state)

internal actual fun loadDiscordAuthState(): String? = load(AUTH_STATE_KEY)

internal actual fun saveDiscordCodeVerifier(codeVerifier: String?) =
    store(CODE_VERIFIER_KEY, codeVerifier)

internal actual fun loadDiscordCodeVerifier(): String? = load(CODE_VERIFIER_KEY)
