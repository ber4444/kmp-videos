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
 * Blank endpoint means captions are unconfigured; the clients report that rather
 * than connecting.
 */
object TranscriptionSecrets {

    /**
     * Base URL of the temporary-key service — scheme and authority, no path
     * (`https://apollo-videos-tokens.fly.dev`). `SonioxKeyProvider` appends the
     * route.
     */
    var sonioxTokenEndpoint: String = ""

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
