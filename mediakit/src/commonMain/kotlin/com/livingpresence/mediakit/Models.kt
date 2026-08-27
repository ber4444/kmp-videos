package com.livingpresence.mediakit

/**
 * The unadvertised sibling renditions the Wowza server exposes per event, keyed
 * by the URL suffix appended to `live/event{i}`. Measured 2026-07-03 against the
 * production server:
 *
 * | tier   | suffix  | resolution | bandwidth | codecs             |
 * |--------|---------|------------|-----------|--------------------|
 * | P720   | *(none)*| 1280×720   | ~1.0 Mbps | avc1.42c01f + mp4a |
 * | P360   | `_360p` | 640×360    | ~507 kbps | avc1.4d401e + mp4a |
 * | P160   | `_160p` | 284×160    | ~262 kbps | avc1.42c015 + mp4a |
 * | AUDIO  | `_aac`  | audio-only | ~51 kbps  | mp4a.40.2          |
 *
 * The base master playlist advertises only P720; [LadderSynthesizer] turns the
 * four siblings into a real ABR ladder client-side.
 */
public enum class RenditionTier(public val urlSuffix: String) {
    P720(""),
    P360("_360p"),
    P160("_160p"),
    AUDIO("_aac"),
}

/**
 * One entry in the feed. [isLive] is derived from playlist inspection (absence
 * of `#EXT-X-ENDLIST`); when `false` the playlist is bounded and the entry is
 * downloadable.
 *
 * Two kinds of entry share this shape:
 *  - **Numbered events** from [EventCatalog], whose [streamUrl] follows the
 *    `…/live/event{n}` scheme and therefore has the unadvertised rendition
 *    ladder [LadderResolver] can synthesize.
 *  - **Extra videos** from [ExtraVideoCatalog]'s remote manifest, which are
 *    arbitrary playlist URLs with no sibling renditions ([hasRenditionLadder]
 *    is `false`) and a synthetic [eventNumber] from
 *    [ExtraVideoCatalog.EXTRA_EVENT_NUMBER_BASE] up.
 *
 * [eventNumber] stays the stable identity for both — download ids, thumbnail
 * cache keys and the player route are all keyed by it — while [streamUrl] is
 * the single source of truth for what to actually play.
 */
public data class EventInfo(
    public val eventNumber: Int,
    public val isLive: Boolean,
    public val durationMs: Long,
    public val title: String = "Event $eventNumber",
    public val streamUrl: String = MediaKitConfig.Default.eventUrl(eventNumber),
) {
    /**
     * Whether [streamUrl] is a numbered mediakit event, and so has the
     * `_360p`/`_160p`/`_aac` siblings the ABR ladder and the download tiers are
     * built from. `false` for manifest extras, which are played and downloaded
     * from [streamUrl] as-is.
     */
    public val hasRenditionLadder: Boolean
        get() = MediaKitConfig.eventNumberIn(streamUrl) != null
}

/**
 * A rendition that has been probed from the server: its resolved chunklist URI
 * (rotating `w`-tokens mean this is captured just-in-time) plus the attributes
 * needed to emit a multivariant playlist.
 */
public data class ProbedRendition(
    public val tier: RenditionTier,
    public val bandwidthBitsPerSecond: Int,
    public val width: Int,
    public val height: Int,
    public val codecs: String,
    public val chunklistUri: String,
) {
    /** `true` for the audio-only `_aac` tier. */
    public val isAudioOnly: Boolean get() = tier == RenditionTier.AUDIO

    /** Resolution string for `RESOLUTION=` attribute, or null for audio-only. */
    public val resolutionAttribute: String?
        get() = if (isAudioOnly) null else "${width}x${height}"
}
