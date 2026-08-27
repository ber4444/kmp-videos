package com.livingpresence.inner.circle.squared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.livingpresence.mediakit.EventInfo
import com.livingpresence.mediakit.ExtraVideoCatalog
import com.livingpresence.mediakit.MediaKitConfig

private object AppRoute {
    const val Landing = "landing"
    const val Gallery = "gallery"
    const val PlayerEventNumberArg = "eventNumber"
    const val Player = "player/{$PlayerEventNumberArg}"

    fun player(eventNumber: Int): String = "player/$eventNumber"
}

@Composable
fun App() {
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
            val mainViewModel = rememberMainViewModel()
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
                            // Drop the landing page from the back stack: once the
                            // Apollo check has passed, backing into the gate again
                            // would only offer to re-authorize.
                            navController.navigate(AppRoute.Gallery) {
                                popUpTo(AppRoute.Landing) { inclusive = true }
                            }
                        },
                    )
                }

                composable(route = AppRoute.Gallery) {
                    GalleryScreen(
                        uiState = uiState,
                        onRetry = mainViewModel::retryLoadingVideos,
                        onPlayEvent = { event ->
                            mainViewModel.playVideo(event.eventNumber)
                            onEventClick(event.eventNumber) {
                                navController.navigate(AppRoute.player(event.eventNumber))
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
                    val event = uiState.availableEvents.firstOrNull { it.eventNumber == eventNumber }
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
                            onClose = navController::popBackStack,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberMainViewModel(): MainViewModel {
    val manifestStore = rememberManifestStore()
    val videoRepository = remember(manifestStore) {
        val httpClient = createHttpClient()
        VideoRepository(
            httpClient = httpClient,
            // No manifest configured for this build → the feed is exactly the
            // numbered events, with no extra request made.
            extras = if (FeedConfig.hasExtraVideos) {
                ExtraVideoCatalog(
                    httpClient = httpClient,
                    manifestUrl = FeedConfig.extraVideosManifestUrl,
                    store = manifestStore,
                )
            } else {
                null
            },
        )
    }
    // NOTE: previously used lifecycle-viewmodel-compose's `viewModel()` against a
    // manual ViewModelStoreOwner. That factory requires a SavedStateRegistryOwner
    // and threw an unrecoverable exception under Kotlin/Wasm (swallowed by the
    // coroutine handler → blank screen). MainViewModel holds no SavedState, so a
    // plain `remember` is correct and works across all targets.
    return remember(videoRepository) { MainViewModel(videoRepository) }
}

@Composable
fun InnerCircleSquaredTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFFEF5350),
            onPrimary = Color.White,
            background = Color.White,
            onBackground = Color(0xFF00695C),
        ),
        content = content,
    )
}

/**
 * Full-screen gallery of available events. Hosts the [LiveEventsGallery] feed
 * with a top bar offering a back/close affordance.
 */
@Composable
fun GalleryScreen(
    uiState: MainUiState,
    onRetry: () -> Unit,
    onPlayEvent: (EventInfo) -> Unit,
) {
    val downloadController = rememberDownloadController()
    val downloadStates by downloadController.states.collectAsState()

    val offlineEvents = remember(uiState.videoLoadError, uiState.availableEvents, downloadStates) {
        val shouldFallback = uiState.videoLoadError != null || uiState.availableEvents.isEmpty()
        if (shouldFallback && downloadController.isSupported) {
            downloadStates.values
                .filter { it.state == DownloadStatus.COMPLETED }
                .map { state ->
                    // What was downloaded is a specific rendition URL, and for an
                    // extra it is the only way back to the stream — the feed that
                    // named it is exactly what failed to load here.
                    val url = state.streamUrl.ifEmpty {
                        MediaKitConfig.Default.eventUrl(state.eventNumber)
                    }
                    EventInfo(
                        eventNumber = state.eventNumber,
                        isLive = false,
                        durationMs = 0L,
                        title = MediaKitConfig.eventNumberIn(url)
                            ?.let { "Event $it" }
                            ?: ExtraVideoCatalog.titleFromUrl(url),
                        streamUrl = url,
                    )
                }
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
            downloadStates = if (downloadController.isSupported) downloadStates else null,
            onDownload = if (downloadController.isSupported) {
                { event -> downloadController.enqueue(event) }
            } else null,
            onRemoveDownload = if (downloadController.isSupported) downloadController::remove else null,
        )
    }
}
