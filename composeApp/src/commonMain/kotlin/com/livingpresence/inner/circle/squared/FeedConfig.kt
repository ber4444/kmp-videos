package com.livingpresence.inner.circle.squared

import com.livingpresence.inner.circle.squared.transcription.TranscriptionSecrets
import com.livingpresence.mediakit.MediaKitConfig

/** Public browsing and the independently authorized Discord feed. */
internal enum class FeedSource { DISCORD, CONCERTS }

/**
 * Where the public concert list is, and the current member stream host.
 *
 * Concerts come from `:server`, which serves the list and the streams on it.
 * The member host and member manifest come only from the authenticated
 * feed-policy service.
 */
internal object FeedConfig {

    /** The public concert list. Mirrors `CONCERTS_MANIFEST_PATH` in `:server`. */
    val concertsManifestUrl: String
        get() = TranscriptionSecrets.sonioxTokenEndpoint.trimEnd('/') + "/v1/concerts/manifest.txt"

    // Downloads and thumbnails are keyed by event number on every platform.
    // Keep public concerts separate from members' manifest extras (10_000+).
    const val CONCERT_EVENT_NUMBER_BASE: Int = 1_000_000

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
