package com.livingpresence.inner.circle.squared

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

    /** Raw URL of the extras manifest. Empty → no extras are fetched. */
    var extraVideosManifestUrl: String = ""

    /** Whether this build was given a manifest to fetch. */
    val hasExtraVideos: Boolean get() = extraVideosManifestUrl.isNotBlank()
}
