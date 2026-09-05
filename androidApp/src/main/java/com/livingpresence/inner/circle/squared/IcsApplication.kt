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

        // No stream host and no manifest URL are injected here any more. Both are
        // issued per account by :server once someone has connected — see
        // FeedConfig — so an account that is not an Apollo member is never told
        // where the streams are, rather than being shown a gallery built from
        // addresses this build was carrying all along.

        // Where captions get their per-session Soniox key. The app holds no
        // provider key of its own: a BuildConfig string is a readable constant in
        // the shipped dex, so this is a URL, not a credential. Empty when unset —
        // captions then report themselves unconfigured rather than connecting.
        TranscriptionSecrets.sonioxTokenEndpoint = BuildConfig.SONIOX_TOKEN_URL

        // Discord OAuth wiring for the landing screen's Apollo gate. Not a secret
        // — the client id is public by design — but it comes from the same file so
        // forks configure their own Discord application. Empty disables the gate.
        // The guild snowflake is not here: :server makes the membership call.
        DiscordConfig.clientId = BuildConfig.DISCORD_CLIENT_ID
    }
}
