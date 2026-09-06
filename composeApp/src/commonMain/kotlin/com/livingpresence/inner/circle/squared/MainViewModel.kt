package com.livingpresence.inner.circle.squared

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livingpresence.mediakit.EventInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val DEFAULT_VIDEO_LOAD_ERROR = "Unable to load videos"

data class MainUiState(
    val isGalleryVisible: Boolean = true,
    val availableEvents: List<EventInfo> = emptyList(),
    val isLoadingVideos: Boolean = false,
    val videoLoadError: String? = null,
)

class MainViewModel(
    private val videoRepository: VideoRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    /** The load in flight, so a forced refresh can supersede it. */
    private var loadJob: Job? = null

    init {
        ensureVideosLoaded()
    }

    fun retryLoadingVideos() {
        loadVideos(forceRefresh = true)
    }

    /**
     * Ensures the available-events list has been fetched at least once. Idempotent —
     * a no-op when a non-empty list is already cached or a load is in flight.
     */
    fun ensureVideosLoaded() {
        loadVideos()
    }

    fun playVideo(eventNumber: Int) {
        _uiState.update { it.copy(isGalleryVisible = false) }
    }

    /**
     * Drops the feed on sign-out, so the next account never sees the last one's.
     *
     * [loadVideos] deliberately keeps [MainUiState.availableEvents] until a reload
     * succeeds — a refresh should not blank the grid it is refreshing — which is
     * exactly wrong across a change of account: the reload the landing screen
     * fires on connect would draw the previous account's tiles behind the new
     * account's spinner. Whose feed is on screen is not a cosmetic question here,
     * since a review account and a member are shown different catalogues.
     *
     * Any load in flight is cancelled for the same reason: its result was fetched
     * for the account that is leaving.
     */
    fun clearFeed() {
        loadJob?.cancel()
        loadJob = null
        _uiState.value = MainUiState()
    }

    private fun loadVideos(forceRefresh: Boolean = false) {
        val currentState = _uiState.value
        if (!forceRefresh && (currentState.isLoadingVideos || currentState.availableEvents.isNotEmpty())) {
            return
        }

        // A forced refresh replaces whatever is in flight rather than deferring to
        // it. Returning early here would drop the reload the landing screen fires
        // on connect whenever the first load has not finished yet — leaving a
        // just-connected account looking at the feed built before anyone was
        // signed in, which for a restricted account is the wrong feed entirely.
        loadJob?.cancel()

        _uiState.update {
            it.copy(
                isLoadingVideos = true,
                videoLoadError = null,
            )
        }

        loadJob = viewModelScope.launch {
            // Pass the flag through: a pull-to-refresh has to bypass the SDK's
            // caches (event probes, and the extras manifest's day-long TTL), or
            // it redraws the same list it already had.
            runCatching { videoRepository.loadEvents(forceRefresh) }
                .onSuccess { events ->
                    _uiState.update {
                        it.copy(
                            availableEvents = events,
                            isLoadingVideos = false,
                            videoLoadError = null,
                        )
                    }
                }
                .onFailure { error ->
                    // A cancellation is this method superseding itself; the load
                    // that replaced it owns the state from here.
                    if (error is CancellationException) return@launch
                    _uiState.update {
                        it.copy(
                            isLoadingVideos = false,
                            videoLoadError = error.message ?: DEFAULT_VIDEO_LOAD_ERROR,
                        )
                    }
                }
        }
    }
}
