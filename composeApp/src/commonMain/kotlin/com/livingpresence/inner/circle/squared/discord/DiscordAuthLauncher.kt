package com.livingpresence.inner.circle.squared.discord

import androidx.compose.runtime.Composable

/**
 * Opens Discord's consent page.
 *
 * Android and iOS hand the URL to the system browser and get the result back as
 * a deep link; the web build navigates the current tab, because the token comes
 * home in the page's own URL fragment and a popup would strand it in a window
 * this app no longer controls.
 */
@Composable
expect fun rememberDiscordAuthLauncher(): (String) -> Unit
