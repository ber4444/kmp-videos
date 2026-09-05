package com.livingpresence.inner.circle.squared

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.livingpresence.inner.circle.squared.discord.DiscordAuthBroker
import com.livingpresence.inner.circle.squared.discord.DiscordConfig
import com.livingpresence.inner.circle.squared.transcription.TranscriptionSecrets
import kotlinx.browser.document
import kotlinx.browser.window

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    document.title = "Apollo Videos"
    // Where captions get their per-session Soniox key. A web bundle is public by
    // construction, so what is baked in here is a URL and never a credential — the
    // Soniox key lives in :server. Empty when unset: the caption clients then
    // report themselves unconfigured and the CC button shows `!`.
    TranscriptionSecrets.sonioxTokenEndpoint = TranscriptionKeys.SONIOX_TOKEN_URL

    // Discord OAuth config for the landing screen's Apollo gate, from the same
    // generated constants. Empty client id disables the gate.
    DiscordConfig.clientId = TranscriptionKeys.DISCORD_CLIENT_ID

    // No stream host and no manifest URL are read here any more. Both are issued
    // per account by :server once someone has connected — see FeedConfig — which
    // matters most on this target, where the bundle is served to anyone who opens
    // the page and "view source" is the whole extraction toolchain.

    captureDiscordRedirect()

    ComposeViewport(document.body!!) {
        App()
    }
}

/**
 * On the web the OAuth redirect *is* this page's next load: Discord sends the
 * browser back to the app URL with `?code=...&state=...`. Read it before Compose
 * mounts, then strip it from the address bar — a one-time authorization code
 * still should not sit in a URL the user can copy, bookmark, or leak via
 * `Referer`.
 */
private fun captureDiscordRedirect() {
    val search = window.location.search
    if (!search.contains("code=") && !search.contains("error=")) {
        return
    }
    DiscordAuthBroker.deliver(window.location.href)
    runCatching {
        window.history.replaceState(
            data = null,
            title = document.title,
            url = window.location.origin + window.location.pathname,
        )
    }
}
