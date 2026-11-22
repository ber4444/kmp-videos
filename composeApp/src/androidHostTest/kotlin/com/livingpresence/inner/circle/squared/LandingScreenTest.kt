package com.livingpresence.inner.circle.squared

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.livingpresence.inner.circle.squared.discord.DiscordConnectionState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LandingScreenTest {
    @Test
    fun concertsAreAnAlternativeToConnectingToDiscord() = runComposeUiTest {
        var concerts = 0
        var connections = 0
        setContent {
            InnerCircleSquaredTheme {
                LandingScreen(
                    state = DiscordConnectionState.Disconnected,
                    onConnect = { connections++ },
                    onTryAgain = {},
                    onConcerts = { concerts++ },
                )
            }
        }

        onNodeWithText("Concerts").performClick()
        onNodeWithText("Connect to Discord").performClick()
        runOnIdle {
            assertEquals(1, concerts)
            assertEquals(1, connections)
        }
    }

    @Test
    fun rejectedDiscordLoginStillShowsTheApolloErrorAndOffersConcerts() = runComposeUiTest {
        var concerts = 0
        setContent {
            InnerCircleSquaredTheme {
                LandingScreen(
                    state = DiscordConnectionState.NotOnApolloServer,
                    onConnect = {},
                    onTryAgain = {},
                    onConcerts = { concerts++ },
                )
            }
        }

        onNodeWithText("User must be on the Apollo server").assertIsDisplayed()
        onNodeWithText("Try another account").assertIsDisplayed()
        onNodeWithText("Concerts").performClick()
        runOnIdle { assertEquals(1, concerts) }
    }
}
