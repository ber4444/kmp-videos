package com.livingpresence.inner.circle.squared

import androidx.compose.ui.window.ComposeUIViewController
import com.livingpresence.inner.circle.squared.discord.DiscordAuthBroker
import com.livingpresence.inner.circle.squared.discord.DiscordConfig
import com.livingpresence.inner.circle.squared.transcription.TranscriptionSecrets
import platform.Foundation.NSBundle

/**
 * iOS app entry point. The host Xcode project (SwiftUI `App`) calls
 * [MainViewControllerKt].mainViewController to obtain the `UIViewController`
 * hosting the shared [App] composable.
 *
 * Mirrors how the wasmJs target's `Main.kt` mounts `App()` into the DOM; here
 * the framework is embedded in an `iosApp` Xcode project (not in this repo).
 */
fun mainViewController() = ComposeUIViewController {
    val info = NSBundle.mainBundle.infoDictionary
    TranscriptionSecrets.deepgramApiKey = info?.get("DEEPGRAM_API_KEY") as? String ?: ""
    TranscriptionSecrets.sonioxApiKey = info?.get("SONIOX_API_KEY") as? String ?: ""
    // Discord OAuth config for the landing screen's Apollo gate. Empty client id
    // disables the gate.
    DiscordConfig.clientId = info?.get("DISCORD_CLIENT_ID") as? String ?: ""
    DiscordConfig.apolloGuildId = info?.get("APOLLO_GUILD_ID") as? String ?: ""
    // Extra videos appended to the feed, listed in a manifest hosted outside the
    // repo. Empty → the feed is exactly the numbered events.
    FeedConfig.extraVideosManifestUrl = info?.get("EXTRA_VIDEOS_URL") as? String ?: ""
    App()
}

/**
 * Entry point for the Swift host's `onOpenURL` / `application(_:open:)` hook: the
 * Discord OAuth redirect comes back as an `icsquared://` deep link, and the Swift
 * side has no other way to reach the shared landing screen.
 *
 * Unrelated deep links are ignored, so the host can forward every URL it gets.
 */
fun handleDeepLink(url: String) {
    if (DiscordAuthBroker.isAuthRedirect(url)) {
        DiscordAuthBroker.deliver(url)
    }
}
