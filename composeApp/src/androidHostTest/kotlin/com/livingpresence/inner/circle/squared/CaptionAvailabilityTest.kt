package com.livingpresence.inner.circle.squared

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.livingpresence.inner.circle.squared.transcription.TranscriberStatus
import com.livingpresence.inner.circle.squared.transcription.TranscriptionProvider
import com.livingpresence.inner.circle.squared.transcription.captionMenuOptions
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A concert's player offers no caption menu at all; a Discord video's does. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CaptionAvailabilityTest {

    private fun controller(available: Boolean): CaptionController {
        val options = captionMenuOptions(deviceTarget = null)
        return CaptionController(
            options = options,
            selected = options.first(),
            provider = TranscriptionProvider.entries.first(),
            status = MutableStateFlow(TranscriberStatus.IDLE),
            error = MutableStateFlow(null),
            captions = MutableStateFlow(emptyList()),
            onSelect = {},
            onSelectProvider = {},
            available = available,
        )
    }

    @Test
    fun aDiscordVideoShowsTheCaptionMenu() = runComposeUiTest {
        setContent {
            PlayerTopRightControls(controller(available = true), onToggleStats = {}, qualityMenu = {})
        }

        onNodeWithText(captionMenuOptions(null).first().label).assertIsDisplayed()
    }

    @Test
    fun aConcertShowsNoCaptionMenu() = runComposeUiTest {
        setContent {
            PlayerTopRightControls(controller(available = false), onToggleStats = {}, qualityMenu = {})
        }

        onNodeWithText(captionMenuOptions(null).first().label).assertDoesNotExist()
    }
}
