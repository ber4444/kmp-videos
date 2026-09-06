package com.livingpresence.inner.circle.squared

import com.livingpresence.mediakit.MediaKitConfig

/**
 * Where the feed's streams live, for the account currently connected.
 *
 * Unlike `TranscriptionSecrets` and `DiscordConfig`, nothing here is injected at
 * startup by a platform host. Both of this object's former build-time values —
 * the stream host and the extras manifest URL — are issued by `:server` per
 * account, and `VideoRepository` assigns what it was given once a policy has been
 * read.
 *
 * That is the Apollo membership check. It used to be a client-side comparison
 * against a guild snowflake, deciding what the UI *showed* while these addresses
 * shipped inside every build, extractable with `unzip` by anyone regardless of
 * whether they were a member. Withholding an address is a decision a patched
 * client cannot reverse; hiding a tile was not.
 *
 * A review account is issued a manifest and no host, so for it the numbered
 * events are unreachable rather than merely unlisted.
 *
 * PRIVACY: a manifest hosted in a secret gist is unlisted, not access-controlled
 * — anyone with the raw URL can read it. It no longer ships inside the app, only
 * reaching an account that has been admitted, but recordings that need real
 * privacy still want signed URLs or a backend that authorizes each viewer.
 */
object FeedConfig {

    /**
     * Scheme and authority of the stream server, e.g. `https://your-host:443`.
     *
     * A pass-through to [MediaKitConfig.defaultHost], which is what actually
     * builds every playlist URL. It lives here so one place tracks what the
     * connected account was issued — `:androidApp` sees only `:composeApp`, not
     * the SDK behind it.
     *
     * Empty until `:server` names one, and set back to empty for an account
     * issued none: probes then resolve nowhere and the numbered events come back
     * empty, rather than reaching a server this account was never given.
     */
    var streamHost: String
        get() = MediaKitConfig.defaultHost
        set(value) {
            MediaKitConfig.defaultHost = value
        }
}
