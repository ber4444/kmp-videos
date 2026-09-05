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
    // Where captions get their per-session Soniox key. Info.plist ships in cleartext
    // inside the .app, so this is a URL and never a credential — the Soniox key
    // lives in :server. Empty → captions report themselves unconfigured.
    TranscriptionSecrets.sonioxTokenEndpoint = info?.get("SONIOX_TOKEN_URL") as? String ?: ""
    // Discord OAuth config for the landing screen's Apollo gate. Empty client id
    // disables the gate.
    DiscordConfig.clientId = info?.get("DISCORD_CLIENT_ID") as? String ?: ""
    // No stream host and no manifest URL are read here any more. Both are issued
    // per account by :server once someone has connected — see FeedConfig — which
    // also keeps them out of Info.plist, a file that ships in cleartext inside
    // the .app and that `unzip` on an IPA reads.
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
