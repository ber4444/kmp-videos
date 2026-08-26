package com.livingpresence.inner.circle.squared.discord

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalUriHandler

/**
 * Discord's mandated mobile deep link, caught by `MainActivity`'s BROWSABLE
 * intent filter (see `androidApp/src/main/AndroidManifest.xml`, whose scheme is
 * generated from the same client id). Single slash and the `discord-` prefix are
 * both required — Discord's portal rejects any other shape.
 */
actual fun defaultDiscordRedirectUri(): String =
    "${discordRedirectScheme(DiscordConfig.clientId)}:/authorize/callback"

@Composable
actual fun rememberDiscordAuthLauncher(): (String) -> Unit {
    val uriHandler = LocalUriHandler.current
    return remember(uriHandler) { { url -> uriHandler.openUri(url) } }
}

// The process stays alive while the browser is in front, so these only have to
// outlive Activity recreation — no storage needed.
private var authState: String? = null
private var pkceVerifier: String? = null

internal actual fun saveDiscordAuthState(state: String?) {
    authState = state
}

internal actual fun loadDiscordAuthState(): String? = authState

internal actual fun saveDiscordCodeVerifier(codeVerifier: String?) {
    pkceVerifier = codeVerifier
}

internal actual fun loadDiscordCodeVerifier(): String? = pkceVerifier
