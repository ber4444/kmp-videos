package com.livingpresence.inner.circle.squared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SavedDownloadsTest {

    private val concert = SavedDownload(
        eventNumber = 1_000_000,
        streamUrl = "https://public.example/live/event15_360p/playlist.m3u8?DVR",
        relativePath = "Library/com.apple.UserManagedAssets.X/Concert_1.movpkg",
    )
    private val extra = SavedDownload(
        eventNumber = 10_000,
        streamUrl = "https://vod.example/concert/playlist.m3u8",
        relativePath = "Library/com.apple.UserManagedAssets.X/Extra_1.movpkg",
    )

    @Test
    fun savedDownloadsSurviveARoundTrip() {
        val downloads = listOf(concert, extra)

        assertEquals(downloads, SavedDownloads.decode(SavedDownloads.encode(downloads)))
    }

    @Test
    fun missingOrCorruptDataReadsAsNoDownloads() {
        assertEquals(emptyList(), SavedDownloads.decode(null))
        assertEquals(emptyList(), SavedDownloads.decode("not json"))
    }

    @Test
    fun theMasterUrlOfANumberedStreamFindsItsDownloadedRendition() {
        val match = SavedDownloads.matching(
            "https://public.example/live/event15/playlist.m3u8?DVR",
            listOf(extra, concert),
        )

        assertEquals(concert, match)
    }

    @Test
    fun anotherStreamNumberOrHostDoesNotMatch() {
        assertNull(
            SavedDownloads.matching("https://public.example/live/event1/playlist.m3u8?DVR", listOf(concert)),
        )
        assertNull(
            SavedDownloads.matching("https://other.example/live/event15/playlist.m3u8?DVR", listOf(concert)),
        )
    }

    @Test
    fun aManifestPlaylistMatchesOnlyItsOwnUrl() {
        assertEquals(extra, SavedDownloads.matching(extra.streamUrl, listOf(extra)))
        assertNull(SavedDownloads.matching("https://vod.example/other/playlist.m3u8", listOf(extra)))
    }

    @Test
    fun progressIsAPercentageOfTheExpectedDuration() {
        assertEquals(0f, downloadPercent(emptyList(), 120.0))
        assertEquals(50f, downloadPercent(listOf(30.0, 30.0), 120.0))
        assertEquals(100f, downloadPercent(listOf(200.0), 120.0))
        assertEquals(0f, downloadPercent(listOf(30.0), Double.NaN))
    }
}
