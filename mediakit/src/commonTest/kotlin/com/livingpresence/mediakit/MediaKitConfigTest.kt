package com.livingpresence.mediakit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MediaKitConfigTest {

    private val config = MediaKitConfig(host = "https://example.test:443")

    @Test
    fun eventUrl_buildsBaseRenditionUrl() {
        assertEquals(
            "https://example.test:443/live/event7/playlist.m3u8?DVR",
            config.eventUrl(7),
        )
    }

    @Test
    fun renditionUrl_appendsSuffixPerTier() {
        assertEquals(
            "https://example.test:443/live/event3/playlist.m3u8?DVR",
            config.renditionUrl(3, RenditionTier.P720),
        )
        assertEquals(
            "https://example.test:443/live/event3_360p/playlist.m3u8?DVR",
            config.renditionUrl(3, RenditionTier.P360),
        )
        assertEquals(
            "https://example.test:443/live/event3_160p/playlist.m3u8?DVR",
            config.renditionUrl(3, RenditionTier.P160),
        )
        assertEquals(
            "https://example.test:443/live/event3_aac/playlist.m3u8?DVR",
            config.renditionUrl(3, RenditionTier.AUDIO),
        )
    }

    @Test
    fun host_extractsSchemeAndAuthority() {
        assertEquals("https://example.test:443", config.host)
    }

    @Test
    fun eventNumberIn_readsTheNumberFromEveryUrlShape() {
        assertEquals(7, MediaKitConfig.eventNumberIn(config.eventUrl(7)))
        assertEquals(3, MediaKitConfig.eventNumberIn(config.renditionUrl(3, RenditionTier.P360)))
        assertEquals(
            12,
            MediaKitConfig.eventNumberIn("https://example.test:443/live/event12_160p/media_1234.ts"),
        )
    }

    @Test
    fun eventNumberIn_isNullForUrlsOutsideTheEventScheme() {
        // A feed extra: no ladder behind it, so nothing to resolve.
        assertNull(MediaKitConfig.eventNumberIn("https://example.test/vod/thudin-8-20-26/playlist.m3u8?DVR"))
        // The `/live/` prefix is what stops a VOD slug that merely contains the
        // word "event" from being played as event 2026.
        assertNull(MediaKitConfig.eventNumberIn("https://example.test/vod/event2026-recap/playlist.m3u8"))
    }

    @Test
    fun defaultConfig_pointsAtProductionServer() {
        assertEquals("https://stream-host.example:443", MediaKitConfig.Default.host)
        assertEquals(20, MediaKitConfig.Default.maxEventNumber)
    }
}
