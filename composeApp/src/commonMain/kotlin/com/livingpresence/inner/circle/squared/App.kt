package com.livingpresence.inner.circle.squared

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.savedstate.read
import com.livingpresence.inner.circle.squared.discord.DiscordIdentity
import com.livingpresence.inner.circle.squared.discord.rememberDiscordSessionStore
import com.livingpresence.mediakit.EventInfo
import com.livingpresence.mediakit.ExtraVideoCatalog

private object AppRoute {
    const val Landing = "landing"
    const val Gallery = "gallery"
    const val PlayerEventNumberArg = "eventNumber"
    const val Player = "player/{$PlayerEventNumberArg}"

    fun player(eventNumber: Int): String = "player/$eventNumber"
}

@Composable
internal fun App() {
    InnerCircleSquaredTheme {
        val viewModelStoreOwner = remember {
            object : ViewModelStoreOwner {
                override val viewModelStore = ViewModelStore()
            }
        }
        DisposableEffect(viewModelStoreOwner) {
            onDispose {
                viewModelStoreOwner.viewModelStore.clear()
            }
        }

        CompositionLocalProvider(LocalViewModelStoreOwner provides viewModelStoreOwner) {
            // Navigation survives activity recreation; retain the feed choice
            // with it so an anonymous concert player can reload its manifest.
            var feedSourceName by rememberSaveable { mutableStateOf(FeedSource.DISCORD.name) }
            val mainViewModel = rememberMainViewModel(FeedSource.valueOf(feedSourceName))
            val uiState by mainViewModel.uiState.collectAsState()
            val navController = rememberNavController()

            NavHost(
                navController = navController,
                startDestination = AppRoute.Landing,
                modifier = Modifier.fillMaxSize(),
            ) {

                composable(route = AppRoute.Landing) {
                    LandingRoute(
                        onConnected = {
                            feedSourceName = FeedSource.DISCORD.name
                            mainViewModel.openFeed(FeedSource.DISCORD)
                            // Drop the landing page from the back stack: once the
                            // Apollo check has passed, backing into the gate again
                            // would only offer to re-authorize.
                            navController.navigate(AppRoute.Gallery) {
                                popUpTo(AppRoute.Landing) { inclusive = true }
                            }
                        },
                        onConcerts = {
                            feedSourceName = FeedSource.CONCERTS.name
                            mainViewModel.openFeed(FeedSource.CONCERTS)
                            navController.navigate(AppRoute.Gallery) {
                                popUpTo(AppRoute.Landing) { inclusive = true }
                            }
                        },
                    )
                }

                composable(route = AppRoute.Gallery) {
                    val sessionStore = rememberDiscordSessionStore()
                    GalleryScreen(
                        uiState = uiState,
                        onRetry = mainViewModel::retryLoadingVideos,
                        onPlayEvent = { event ->
                            mainViewModel.playVideo(event)
                            onEventClick(event.eventNumber) {
                                navController.navigate(AppRoute.player(event.eventNumber))
                            }
                        },
                        onExit = {
                            // Leaving public browsing preserves any saved login.
                            // Member sign-out clears both credentials and feed.
                            if (uiState.feedSource == FeedSource.DISCORD) {
                                sessionStore.clear()
                                DiscordIdentity.clear()
                            }
                            mainViewModel.clearFeed()
                            feedSourceName = FeedSource.DISCORD.name
                            // Drop the gallery so backing out of the landing page
                            // cannot return to the feed that was just cleared.
                            navController.navigate(AppRoute.Landing) {
                                popUpTo(AppRoute.Gallery) { inclusive = true }
                            }
                        },
                    )
                }

                composable(
                    route = AppRoute.Player,
                    arguments = listOf(
                        navArgument(AppRoute.PlayerEventNumberArg) {
                            type = NavType.IntType
                        },
                    ),
                ) { backStackEntry ->
                    val arguments = checkNotNull(backStackEntry.arguments)
                    val eventNumber = arguments.read { getInt(AppRoute.PlayerEventNumberArg) }

                    // The feed is what knows a manifest extra's URL: unlike a
                    // numbered event, it cannot be rebuilt from the number. After
                    // process death the back stack is restored before the feed
                    // reloads, so wait for it rather than play the wrong stream —
                    // and leave if the reload comes back without this entry.
                    val event = uiState.selectedEvent?.takeIf { it.eventNumber == eventNumber }
                        ?: uiState.availableEvents.firstOrNull { it.eventNumber == eventNumber }
                    val isUnresolvedExtra =
                        event == null && eventNumber >= ExtraVideoCatalog.EXTRA_EVENT_NUMBER_BASE

                    LaunchedEffect(isUnresolvedExtra, uiState.isLoadingVideos) {
                        if (isUnresolvedExtra && !uiState.isLoadingVideos) {
                            navController.popBackStack()
                        }
                    }

                    if (isUnresolvedExtra) {
                        Box(
                            modifier = Modifier.fillMaxSize().background(Color.Black),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = Color.White)
                        }
                    } else {
                        PlatformPlayerScreen(
                            url = event?.streamUrl ?: getUrl(eventNumber),
                            // Concerts never caption: no transcript is needed for
                            // music, and Soniox is metered per anonymous viewer.
                            captionsAvailable = captionsAvailableFor(uiState.feedSource, eventNumber),
                            onClose = navController::popBackStack,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberMainViewModel(initialFeedSource: FeedSource): MainViewModel {
    val videoRepository = remember {
        val httpClient = createHttpClient()
        // Catalogues are built after the viewer chooses the public concerts or
        // connects to Discord for the server-issued member feed.
        VideoRepository(
            httpClient = httpClient,
            policyClient = FeedPolicyClient(httpClient),
        )
    }
    // NOTE: previously used lifecycle-viewmodel-compose's `viewModel()` against a
    // manual ViewModelStoreOwner. That factory requires a SavedStateRegistryOwner
    // and threw an unrecoverable exception under Kotlin/Wasm (swallowed by the
    // coroutine handler → blank screen). MainViewModel holds no SavedState, so a
    // plain `remember` is correct and works across all targets.
    return remember(videoRepository) { MainViewModel(videoRepository, initialFeedSource) }
}

/** Brand red. Legible on either ground, so it is the same in both schemes. */
private val BrandRed = Color(0xFFEF5350)

@Composable
internal fun InnerCircleSquaredTheme(content: @Composable () -> Unit) {
    // Only `background`, `onBackground` and `error` are read anywhere, and the
    // first two only by GalleryScreen and the feed's empty state — so following
    // the system setting here moves the feed's ground and nothing else. It has
    // to move: the app draws edge to edge, and in dark mode the platform styles
    // the status bar icons light, which left a white clock on the white feed.
    // `primary`/`onPrimary` back the shared buttons and stay put across schemes.
    val colorScheme = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = BrandRed,
            onPrimary = Color.White,
            background = Color(0xFF121212),
            onBackground = Color(0xFF80CBC4),
        )
    } else {
        lightColorScheme(
            primary = BrandRed,
            onPrimary = Color.White,
            background = Color.White,
            onBackground = Color(0xFF00695C),
        )
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}

/**
 * Full-screen gallery of available events. Hosts the [LiveEventsGallery] feed,
 * with the offline fallback in front of it and [GalleryExitButton] over it.
 *
 * @param onExit Return to the landing page; member sessions are signed out after
 *   confirmation, while public browsing can be left immediately.
 */
@Composable
internal fun GalleryScreen(
    uiState: MainUiState,
    onRetry: () -> Unit,
    onPlayEvent: (EventInfo) -> Unit,
    onExit: () -> Unit,
) {
    val downloadController = rememberDownloadController()
    val downloadStates by downloadController.states.collectAsState()

    // A failed public manifest must not reveal members' downloads as its fallback.
    val visibleDownloads = remember(uiState.feedSource, downloadStates) {
        downloadStates.filterKeys { number ->
            (number >= FeedConfig.CONCERT_EVENT_NUMBER_BASE) ==
                (uiState.feedSource == FeedSource.CONCERTS)
        }
    }
    val offlineEvents = remember(uiState.videoLoadError, uiState.availableEvents, visibleDownloads) {
        val shouldFallback = uiState.videoLoadError != null || uiState.availableEvents.isEmpty()
        if (shouldFallback && downloadController.isSupported) {
            visibleDownloads.values.toOfflineEvents()
        } else {
            emptyList()
        }
    }

    val displayEvents = if (offlineEvents.isNotEmpty()) offlineEvents else uiState.availableEvents
    val displayError = if (offlineEvents.isNotEmpty()) null else uiState.videoLoadError

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LiveEventsGallery(
            events = displayEvents,
            isLoading = uiState.isLoadingVideos,
            error = displayError,
            onPlayEvent = onPlayEvent,
            onRetry = onRetry,
            modifier = Modifier.fillMaxSize(),
            downloadStates = if (downloadController.isSupported) visibleDownloads else null,
            onDownload = if (downloadController.isSupported) {
                { event -> downloadController.enqueue(event) }
            } else null,
            onRemoveDownload = if (downloadController.isSupported) downloadController::remove else null,
        )

        GalleryExitButton(
            onConfirm = onExit,
            isConcerts = uiState.feedSource == FeedSource.CONCERTS,
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}

/**
 * Sign-out, floating over the feed's top-end corner.
 *
 * It floats because there is nothing to hang it on: the feed is a full-bleed
 * grid that scrolls under the status bar, and giving it a top bar to host one
 * button would cost the edge-to-edge layout the rest of the app is built around.
 * It carries its own scrim for the same reason the landing screen does — what is
 * behind it is photographs.
 *
 * Confirmed rather than immediate. It is a small target directly above a grid of
 * tappable tiles, and the cost of a mis-tap is not a mis-navigation but a full
 * authorization round trip out to the browser and back.
 */
@Composable
private fun GalleryExitButton(
    onConfirm: () -> Unit,
    isConcerts: Boolean,
    modifier: Modifier = Modifier,
) {
    var confirming by remember { mutableStateOf(false) }

    TextButton(
        onClick = { if (isConcerts) onConfirm() else confirming = true },
        // The grid insets itself with content padding so its tiles can scroll
        // under the system bars. This must not: it is pinned, so it takes the
        // inset as real padding or it sits under the clock.
        modifier = modifier
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.45f)),
    ) {
        Text(
            text = if (isConcerts) "Back" else "Sign out",
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
        )
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Sign out of Discord?") },
            text = {
                Text(
                    "This erases your session from this device. You will need to " +
                        "connect to Discord again to reach the member feed. Videos you " +
                        "have downloaded stay on your device.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        onConfirm()
                    },
                ) {
                    Text("Sign out")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}
