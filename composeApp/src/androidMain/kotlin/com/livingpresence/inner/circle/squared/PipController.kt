package com.livingpresence.inner.circle.squared

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Lets the player screen report its video size + playing state to the host
 * [MainActivity] so PiP params can use the correct (clamped) aspect ratio and
 * auto-PiP on leave only triggers while actually playing.
 */
public interface PipController {
    public fun updateVideoSize(width: Int, height: Int)
    public fun setPlaying(playing: Boolean)
    /** Report the video's on-screen bounds (for the PiP source-rect hint). */
    public fun updateSourceBounds(left: Int, top: Int, right: Int, bottom: Int)
}

internal val LocalPipController = staticCompositionLocalOf<PipController?> { null }
