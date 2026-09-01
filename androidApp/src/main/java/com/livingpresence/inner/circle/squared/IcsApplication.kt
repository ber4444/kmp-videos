package com.livingpresence.inner.circle.squared

import android.app.Application
import com.livingpresence.inner.circle.squared.discord.DiscordConfig
import com.livingpresence.inner.circle.squared.transcription.TranscriptionSecrets

/**
 * Pushes this build's configuration into the shared module before anything can
 * read it.
 *
 * The values come from the gitignored `secrets.properties` via `BuildConfig`, and
 * `commonMain` deliberately knows nothing about that plumbing — it reads plain
 * objects that a host fills in (see [FeedConfig],
 * [DiscordConfig], [TranscriptionSecrets]).
 *
 * This has to be `Application`, not `MainActivity`: `PlaybackService` and
 * `DownloadsService` can both start a process on their own — WorkManager resuming
 * a download after a reboot, a media button reviving playback — and would then
 * build URLs against an unset host. `Application.onCreate` is the one callback
 * that runs first in every one of those paths.
 */
class IcsApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        HostBridge.isDebug = { BuildConfig.DEBUG }

        // The stream host. Not published in this repository: it is the one value
        // every playlist URL is built from, so it lives in secrets.properties and
        // is injected here. Empty → probes resolve nowhere and the feed is empty,
        // which beats pointing at a stale hardcoded server.
        FeedConfig.streamHost = BuildConfig.STREAM_HOST

        // Extra videos appended to the feed, listed in a manifest hosted outside
        // the repo. Empty → the feed is exactly the numbered events.
        FeedConfig.extraVideosManifestUrl = BuildConfig.EXTRA_VIDEOS_URL

        // Where captions get their per-session Soniox key. The app holds no
        // provider key of its own: a BuildConfig string is a readable constant in
        // the shipped dex, so this is a URL, not a credential. Empty when unset —
        // captions then report themselves unconfigured rather than connecting.
        TranscriptionSecrets.sonioxTokenEndpoint = BuildConfig.SONIOX_TOKEN_URL

        // Discord OAuth wiring for the landing screen's Apollo gate. Neither value
        // is a secret (the client id is public, the guild id is a snowflake), but
        // both come from the same file so forks configure their own Discord
        // application. Empty client id disables the gate.
        DiscordConfig.clientId = BuildConfig.DISCORD_CLIENT_ID
        DiscordConfig.apolloGuildId = BuildConfig.APOLLO_GUILD_ID
    }
}
