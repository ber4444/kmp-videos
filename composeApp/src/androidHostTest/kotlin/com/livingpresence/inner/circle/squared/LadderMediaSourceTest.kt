package com.livingpresence.inner.circle.squared

import androidx.test.core.app.ApplicationProvider
import com.livingpresence.mediakit.LadderResolver
import com.livingpresence.mediakit.MediaKitConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LadderMediaSourceTest {
    @Test
    fun aFailedLadderProbeKeepsTheSelectedDownloadedConcertRendition() = runTest {
        val config = MediaKitConfig("https://public.example")
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })
        val builder = LadderMediaSourceBuilder(ApplicationProvider.getApplicationContext(), config)

        val result = builder.resolveForEvent(
            eventNumber = 15,
            resolver = LadderResolver(client, config),
            fallbackUrl = "https://public.example/live/event15_360p/playlist.m3u8?DVR",
        )

        assertEquals(
            "https://public.example/live/event15_360p/playlist.m3u8?DVR",
            result.mediaItemUri,
        )
        client.close()
    }
}
