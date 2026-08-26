package com.livingpresence.inner.circle.squared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf

/**
 * The bridge between the pure Android application module (:androidApp) and the
 * shared Kotlin Multiplatform UI module (:composeApp).
 */
object HostBridge {
    var isDebug: () -> Boolean = { false }

    /**
     * Drawable res id for the landing-screen background, supplied by the host
     * application module.
     *
     * The shared `background_image` Compose resource cannot be used on Android:
     * the AGP KMP library plugin assembles composeResources for the iOS and wasm
     * targets but not for Android, so `Res.drawable.background_image` resolves at
     * compile time and then throws `MissingResourceException` at runtime. The
     * host owns a real `res/drawable` copy instead and passes its id here.
     *
     * 0 means unset — [loginBackgroundModifier] falls back to a gradient.
     */
    var backgroundDrawableResId: Int = 0

    /** Whether the activity is currently in PiP, observed by the player UI. */
    val inPipState = mutableStateOf(false)
    
    private var frameEngineForTrim: PreviewFrameEngine? = null

    @Composable
    fun HostApp(
        pipController: PipController
    ) {
        val previewFrameEngine = rememberPreviewFrameEngine()
        frameEngineForTrim = previewFrameEngine
        CompositionLocalProvider(
            LocalPreviewFrameEngine provides previewFrameEngine,
            LocalPipController provides pipController,
        ) {
            App()
        }
    }

    fun onTrimMemory(level: Int) {
        frameEngineForTrim?.let {
            MemoryGovernor.onTrim(level, it.asTrimTarget())
        }
    }
}
