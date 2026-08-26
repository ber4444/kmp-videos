package com.livingpresence.inner.circle.squared.discord

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalUriHandler

/**
 * Discord's mandated mobile deep link. The host Xcode project must declare the
 * matching `discord-<APP_ID>` scheme under `CFBundleURLTypes` and forward the
 * opened URL to [DiscordAuthBroker.deliver] (see `iosApp/ics_ios/ICSApp.swift`).
 * Single slash and the `discord-` prefix are both required by Discord.
 */
actual fun defaultDiscordRedirectUri(): String =
    "${discordRedirectScheme(DiscordConfig.clientId)}:/authorize/callback"

@Composable
actual fun rememberDiscordAuthLauncher(): (String) -> Unit {
    val uriHandler = LocalUriHandler.current
    return remember(uriHandler) { { url -> uriHandler.openUri(url) } }
}

// The app stays resident while Safari is in front, so these only have to
// outlive backgrounding — no storage needed.
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
