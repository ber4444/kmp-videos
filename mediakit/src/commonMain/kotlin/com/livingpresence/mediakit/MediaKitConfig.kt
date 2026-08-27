package com.livingpresence.mediakit

/**
 * Configuration for the playback SDK. The base host is injectable so the
 * hardcoded production server is no longer sprinkled through app code and the
 * URL builder is unit-testable.
 *
 * @param baseStreamUrl  Absolute base that ends in `/live/event`, e.g.
 *   `https://host:443/live/event`. The event number and rendition suffix are
 *   appended, then `/playlist.m3u8?DVR`.
 * @param maxEventNumber The largest event number to probe (inclusive). The
 *   production server currently serves events 1..20.
 */
public data class MediaKitConfig(
    public val baseStreamUrl: String,
    public val maxEventNumber: Int = DEFAULT_MAX_EVENT_NUMBER,
) {
    public constructor(
        host: String,
    ) : this(
        baseStreamUrl = "$host/live/event",
        maxEventNumber = DEFAULT_MAX_EVENT_NUMBER,
    )

    /** The host portion (scheme + authority), without a trailing slash. */
    public val host: String
        get() = baseStreamUrl.substringBefore("/live/event")

    /**
     * The playlist URL for [eventNumber] at rendition [tier]. `DVR` query keeps
     * the Wowza nDVR window for seekable live/recorded playback.
     */
    public fun renditionUrl(eventNumber: Int, tier: RenditionTier): String =
        "$baseStreamUrl${eventNumber}${tier.urlSuffix}/playlist.m3u8?DVR"

    /** Convenience: the base (720p) playlist URL for [eventNumber]. */
    public fun eventUrl(eventNumber: Int): String =
        renditionUrl(eventNumber, RenditionTier.P720)

    public companion object {
        /**
         * Scheme and authority of the stream server, e.g. `https://host:443`.
         *
         * Not compiled into the SDK: the host is the one value every playlist URL
         * is built from, and it is not published in the repository. Each platform
         * host assigns it at startup from its own gitignored source — Android
         * `BuildConfig`, the wasm bundle's generated constants, the iOS
         * `Info.plist` — exactly as `TranscriptionSecrets` and `DiscordConfig`
         * are filled in.
         *
         * Empty until then, which makes every probe resolve nowhere and the feed
         * come back empty — a louder, safer failure than a stale hardcoded server.
         *
         * SECURITY: a host a client streams from cannot be a secret in that
         * client; it is on the wire and in the binary. Keeping it out of the
         * source keeps it out of a public repository and its history, which is a
         * different (and achievable) goal.
         */
        public var defaultHost: String = ""

        /** Events 1..20 are served by the production server. */
        public const val DEFAULT_MAX_EVENT_NUMBER: Int = 20

        /**
         * A [MediaKitConfig] pointed at [defaultHost].
         *
         * Computed per read rather than cached: the host is not known when this
         * class is initialized, only once a platform host has injected it.
         */
        public val Default: MediaKitConfig get() = MediaKitConfig(defaultHost)

        /**
         * The event number in [url], or null when [url] is not a numbered event
         * stream (a manifest extra, say).
         *
         * Matches the `/live/event{n}` segment of the mediakit URL scheme, so it
         * works for master, rendition and segment URLs alike. The `/live/`
         * prefix is load-bearing: without it an arbitrary VOD path containing
         * the word "event" would be mistaken for an event and played from the
         * wrong stream.
         */
        public fun eventNumberIn(url: String): Int? =
            EVENT_SEGMENT.find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()

        private val EVENT_SEGMENT = Regex("""/live/event(\d+)""")
    }
}
// CodeQL trigger
