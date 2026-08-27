package com.livingpresence.inner.circle.squared

import com.livingpresence.mediakit.MediaKitConfig

/**
 * Where the feed's extra videos are listed.
 *
 * Mirrors `TranscriptionSecrets` / `DiscordConfig`: each platform host injects
 * the value at startup (Android `BuildConfig`, the wasm bundle's generated keys,
 * the iOS `Info.plist`) from the gitignored `secrets.properties`, so `commonMain`
 * stays free of build plumbing and a fork points at its own list.
 *
 * The manifest is a plain-text file — one playlist URL per line — hosted outside
 * the repository (a *secret* gist works well) so the list can change without an
 * app release. Empty disables the feature: the feed is then exactly the numbered
 * events.
 *
 * PRIVACY: a secret gist is unlisted, not access-controlled. Anyone with the raw
 * URL can read it, and the URL ships inside the app, where it is extractable.
 * That is fine for keeping recordings out of search results and out of this
 * repository's history; it is not a substitute for signed URLs or a backend that
 * authorizes each viewer.
 */
object FeedConfig {

    /**
     * Scheme and authority of the stream server, e.g. `https://your-host:443`.
     *
     * A pass-through to [MediaKitConfig.defaultHost], which is what actually
     * builds every playlist URL. It lives here so the platform hosts have one
     * place to inject build-time configuration — `:androidApp` sees only
     * `:composeApp`, not the SDK behind it.
     *
     * Empty until a host assigns it: probes then resolve nowhere and the feed
     * comes back empty, rather than reaching a stale hardcoded server.
     */
    var streamHost: String
        get() = MediaKitConfig.defaultHost
        set(value) {
            MediaKitConfig.defaultHost = value
        }

    /** Raw URL of the extras manifest. Empty → no extras are fetched. */
    var extraVideosManifestUrl: String = ""

    /** Whether this build was given a manifest to fetch. */
    val hasExtraVideos: Boolean get() = extraVideosManifestUrl.isNotBlank()
}
