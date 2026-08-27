package com.livingpresence.inner.circle.squared

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers [DownloadCenter.downloadState] — the mapping the feed tiles render.
 *
 * The distinction that matters here is QUEUED vs WAITING: a download held back
 * by an unmet requirement never advances, and reporting it as a 0% transfer is
 * exactly the "stuck at 0%" symptom this mapping exists to avoid.
 */
@UnstableApi
class DownloadStateMappingTest {

    @Test
    fun downloading_mapsToDownloading() {
        assertEquals(
            DownloadCenter.DownloadState.DOWNLOADING,
            DownloadCenter.downloadState(Download.STATE_DOWNLOADING, isWaitingForRequirements = false),
        )
    }

    @Test
    fun terminalStates_mapThrough() {
        assertEquals(
            DownloadCenter.DownloadState.COMPLETED,
            DownloadCenter.downloadState(Download.STATE_COMPLETED, isWaitingForRequirements = false),
        )
        assertEquals(
            DownloadCenter.DownloadState.FAILED,
            DownloadCenter.downloadState(Download.STATE_FAILED, isWaitingForRequirements = false),
        )
        assertEquals(
            DownloadCenter.DownloadState.REMOVING,
            DownloadCenter.downloadState(Download.STATE_REMOVING, isWaitingForRequirements = false),
        )
    }

    @Test
    fun queued_withRequirementsMet_isQueued() {
        assertEquals(
            DownloadCenter.DownloadState.QUEUED,
            DownloadCenter.downloadState(Download.STATE_QUEUED, isWaitingForRequirements = false),
        )
    }

    @Test
    fun queued_withUnmetRequirements_isWaiting() {
        assertEquals(
            DownloadCenter.DownloadState.WAITING,
            DownloadCenter.downloadState(Download.STATE_QUEUED, isWaitingForRequirements = true),
        )
    }

    @Test
    fun stoppedAndRestarting_followTheRequirementState() {
        assertEquals(
            DownloadCenter.DownloadState.QUEUED,
            DownloadCenter.downloadState(Download.STATE_RESTARTING, isWaitingForRequirements = false),
        )
        assertEquals(
            DownloadCenter.DownloadState.WAITING,
            DownloadCenter.downloadState(Download.STATE_STOPPED, isWaitingForRequirements = true),
        )
    }

    /**
     * A download that has reached a terminal state is never "waiting" — the
     * requirement flag must not leak into states that have already resolved.
     */
    @Test
    fun terminalStates_ignoreUnmetRequirements() {
        assertEquals(
            DownloadCenter.DownloadState.COMPLETED,
            DownloadCenter.downloadState(Download.STATE_COMPLETED, isWaitingForRequirements = true),
        )
        assertEquals(
            DownloadCenter.DownloadState.DOWNLOADING,
            DownloadCenter.downloadState(Download.STATE_DOWNLOADING, isWaitingForRequirements = true),
        )
    }
}
