package com.livingpresence.inner.circle.squared

import com.livingpresence.mediakit.EventInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests [MainViewModel] state transitions: gallery visibility,
 * video loading + error paths.
 */
@OptIn(ExperimentalCoroutinesApi::class) // Dispatchers.setMain / UnconfinedTestDispatcher
class MainViewModelTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun init_loadsEventsAndPopulatesState() = runTest {
        val events = listOf(
            EventInfo(eventNumber = 3, isLive = false, durationMs = 10_000),
            EventInfo(eventNumber = 1, isLive = true, durationMs = 0),
        )
        val vm = MainViewModel(videoRepositoryWith(events))

        assertEquals(events, vm.uiState.value.availableEvents)
        assertFalse(vm.uiState.value.isLoadingVideos)
        assertNull(vm.uiState.value.videoLoadError)
        assertTrue(vm.uiState.value.isGalleryVisible)
    }
    
    @Test
    fun playVideo_hidesGallery() = runTest {
        val vm = MainViewModel(videoRepositoryWith(emptyList()))
        assertTrue(vm.uiState.value.isGalleryVisible)

        vm.playVideo(EventInfo(eventNumber = 1, isLive = false, durationMs = 0))
        assertFalse(vm.uiState.value.isGalleryVisible)
    }

    @Test
    fun ensureVideosLoaded_isIdempotentWhenAlreadyPopulated() = runTest {
        val events = listOf(EventInfo(eventNumber = 1, isLive = false, durationMs = 1_000))
        var loadCount = 0
        val repo = object : VideoRepository(FakeHttpClient) {
            override suspend fun loadEvents(forceRefresh: Boolean): List<EventInfo> {
                loadCount++
                return events
            }
        }
        val vm = MainViewModel(repo) // triggers init -> ensureVideosLoaded

        vm.ensureVideosLoaded()
        vm.ensureVideosLoaded()
        vm.ensureVideosLoaded()

        // Idempotent: a non-empty cached list means subsequent calls are no-ops.
        assertEquals(1, loadCount)
    }

    @Test
    fun retryLoadingVideos_forcesRefresh() = runTest {
        val events = listOf(EventInfo(eventNumber = 1, isLive = false, durationMs = 1_000))
        var loadCount = 0
        val repo = object : VideoRepository(FakeHttpClient) {
            override suspend fun loadEvents(forceRefresh: Boolean): List<EventInfo> {
                loadCount++
                return events
            }
        }
        val vm = MainViewModel(repo) // load 1

        vm.ensureVideosLoaded() // no-op
        vm.retryLoadingVideos() // load 2

        assertEquals(2, loadCount)
    }

    @Test
    fun clearFeed_dropsTheSignedOutAccountsEvents() = runTest {
        val events = listOf(EventInfo(eventNumber = 1, isLive = false, durationMs = 1_000))
        val vm = MainViewModel(videoRepositoryWith(events))
        assertEquals(events, vm.uiState.value.availableEvents)

        vm.clearFeed()

        assertEquals(emptyList(), vm.uiState.value.availableEvents)
        assertFalse(vm.uiState.value.isLoadingVideos)
        assertNull(vm.uiState.value.videoLoadError)
    }

    /**
     * The property that makes sign-out worth more than a UI gesture: a load that
     * was already in flight belongs to the account that just left, so it must not
     * be allowed to land in the next account's feed.
     */
    @Test
    fun clearFeed_cancelsALoadInFlightSoItCannotRepopulate() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = object : VideoRepository(FakeHttpClient) {
            override suspend fun loadEvents(forceRefresh: Boolean): List<EventInfo> {
                gate.await()
                return listOf(EventInfo(eventNumber = 1, isLive = false, durationMs = 1_000))
            }
        }
        val vm = MainViewModel(repo) // init starts a load, which parks on the gate
        assertTrue(vm.uiState.value.isLoadingVideos)

        vm.clearFeed()
        gate.complete(Unit)

        assertEquals(emptyList(), vm.uiState.value.availableEvents)
        assertFalse(vm.uiState.value.isLoadingVideos)
    }

    @Test
    fun selectingConcertsCancelsTheMemberLoadAndClearsItsTilesAndHost() = runTest {
        val memberLoad = CompletableDeferred<Unit>()
        val concertLoad = CompletableDeferred<Unit>()
        val repo = object : VideoRepository(FakeHttpClient) {
            override suspend fun loadEvents(forceRefresh: Boolean): List<EventInfo> {
                val source = feedSource
                if (source == FeedSource.DISCORD) memberLoad.await() else concertLoad.await()
                return listOf(EventInfo(
                    eventNumber = if (source == FeedSource.DISCORD) 1 else 1_000_000,
                    isLive = false,
                    durationMs = 1_000,
                ))
            }
        }
        val vm = MainViewModel(repo)
        FeedConfig.streamHost = "https://members.example"

        vm.openFeed(FeedSource.CONCERTS)
        memberLoad.complete(Unit)

        assertEquals(FeedSource.CONCERTS, vm.uiState.value.feedSource)
        assertTrue(vm.uiState.value.availableEvents.isEmpty())
        assertEquals("", FeedConfig.streamHost)
        concertLoad.complete(Unit)
        assertEquals(listOf(1_000_000), vm.uiState.value.availableEvents.map { it.eventNumber })
    }

    @Test
    fun aRestoredConcertChoiceLoadsThePublicFeedImmediately() = runTest {
        val repo = object : VideoRepository(FakeHttpClient) {
            override suspend fun loadEvents(forceRefresh: Boolean): List<EventInfo> =
                if (feedSource == FeedSource.CONCERTS) {
                    listOf(EventInfo(eventNumber = 1_000_000, isLive = false, durationMs = 1_000))
                } else {
                    emptyList()
                }
        }

        val vm = MainViewModel(repo, initialFeedSource = FeedSource.CONCERTS)

        assertEquals(FeedSource.CONCERTS, vm.uiState.value.feedSource)
        assertEquals(listOf(1_000_000), vm.uiState.value.availableEvents.map { it.eventNumber })
    }

    @Test
    fun playingADownloadedConcertKeepsItsUrlWithoutARemoteFeed() = runTest {
        val vm = MainViewModel(videoRepositoryWith(emptyList()))
        val downloaded = EventInfo(
            eventNumber = 1_000_000,
            isLive = false,
            durationMs = 1_000,
            streamUrl = "https://public.example/live/event15_360p/playlist.m3u8?DVR",
        )

        vm.playVideo(downloaded)

        assertEquals(downloaded, vm.uiState.value.selectedEvent)
        assertTrue(vm.uiState.value.availableEvents.isEmpty())
    }

    private fun videoRepositoryWith(events: List<EventInfo>): VideoRepository =
        object : VideoRepository(FakeHttpClient) {
            override suspend fun loadEvents(forceRefresh: Boolean): List<EventInfo> = events
        }
}

/** A no-op Ktor client; the fake repo overrides loadEvents so it's never used. */
private val FakeHttpClient = io.ktor.client.HttpClient()
