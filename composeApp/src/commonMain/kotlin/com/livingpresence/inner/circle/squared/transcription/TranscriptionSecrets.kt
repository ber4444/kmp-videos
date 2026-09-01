package com.livingpresence.inner.circle.squared.transcription

/**
 * Runtime transcription configuration. Each platform host fills this in at startup
 * from its own gitignored source, exactly as it does for `FeedConfig` and
 * `DiscordConfig`.
 *
 * **There is no provider API key here, and that is the point.** This object used to
 * hold the Soniox and Deepgram keys, which were compiled into every build — Android
 * `BuildConfig`, the iOS `Info.plist`, and the wasmJs bundle — putting a long-lived,
 * billable credential in the hands of anyone who could unzip an APK or open
 * devtools. The app now holds only [sonioxTokenEndpoint]: the URL of a service
 * (`:server`) that holds the real key and mints 60-second, single-use,
 * transcription-scoped keys against it. See `SonioxKeyProvider`.
 *
 * A build that sets no endpoint falls back to [DEFAULT_SONIOX_TOKEN_URL], so
 * captions work in a fresh clone without any local configuration.
 */
object TranscriptionSecrets {

    /**
     * The token service this project deploys, used when a build supplies no
     * endpoint of its own.
     *
     * Hardcoding this is safe in a way the provider key never was, because the URL
     * mints nothing by itself: every request must carry a Discord access token
     * that `:server` re-verifies against the Apollo guild snowflake, it fails
     * closed, and it is rate limited per caller. Publishing the hostname grants no
     * more than knowing where to be refused — and it ships inside every binary
     * anyway (dex, `Info.plist`, the JS bundle), so a default costs no exposure a
     * released build did not already have.
     *
     * A fork points at its own service by setting `SONIOX_TOKEN_URL` in
     * `secrets.properties`, which overrides this.
     */
    const val DEFAULT_SONIOX_TOKEN_URL: String = "https://apollo-videos-tokens.fly.dev"

    /**
     * Base URL of the temporary-key service — scheme and authority, no path.
     * `SonioxKeyProvider` appends the route.
     *
     * Reads back [DEFAULT_SONIOX_TOKEN_URL] when nothing was set. The fallback
     * lives in the getter rather than the initializer because all three platform
     * hosts assign this unconditionally from their build config, so an unset
     * `SONIOX_TOKEN_URL` arrives here as `""` — which has to mean "use the
     * default", not "captions are off".
     */
    var sonioxTokenEndpoint: String = ""
        get() = field.ifBlank { DEFAULT_SONIOX_TOKEN_URL }

    /**
     * Deepgram is not reachable from the UI — the provider switcher is not
     * rendered (see `CaptionLabels.captionLabel`) and the README records Soniox as
     * the only provider the app reaches — so its key is no longer shipped either.
     * Selecting it in code yields the "not configured" path rather than a stream.
     *
     * The evaluation harness in `eval/` still scores both providers; it reads its
     * own keys from its own environment and never went through this object.
     */
    const val DEEPGRAM_UNCONFIGURED: String = ""
}
