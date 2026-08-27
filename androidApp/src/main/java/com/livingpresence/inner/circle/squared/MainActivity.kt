package com.livingpresence.inner.circle.squared

import android.Manifest
import android.app.PictureInPictureParams
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowCompat
import com.livingpresence.inner.circle.squared.discord.DiscordAuthBroker
import com.livingpresence.inner.circle.squared.discord.DiscordConfig
import com.livingpresence.inner.circle.squared.transcription.TranscriptionSecrets
import java.util.concurrent.atomic.AtomicReference

class MainActivity : ComponentActivity() {

    /** The aspect ratio of the video currently playing, for PiP sizing. */
    private val pipAspectRatio = AtomicReference<Rational?>(null)

    /** The video's on-screen bounds, for the PiP source-rect hint (smooth transition). */
    internal val pipSourceRect = AtomicReference<android.graphics.Rect?>(null)

    /** Whether the user is actively playing video (gates auto-PiP on leave). */
    private val isPlayingVideo = AtomicReference(false)

    /**
     * POST_NOTIFICATIONS is a runtime permission from API 33. Without it both
     * foreground services still run, but the system silently drops their
     * notifications: background playback loses its lock-screen transport
     * controls and a download shows no progress. Asking is best-effort — a
     * denial costs the notifications, not the features — so the result is
     * deliberately ignored and nothing is gated on it.
     */
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private val trimCallback = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) {}
        override fun onLowMemory() = HostBridge.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        override fun onTrimMemory(level: Int) = HostBridge.onTrimMemory(level)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        HostBridge.isDebug = { BuildConfig.DEBUG }

        // The landing background lives in this module's res/drawable rather than
        // as a Compose resource: the AGP KMP library plugin does not package
        // composeResources for the Android target (it does for iOS and wasm), so
        // the shared copy is missing at runtime. See HostBridge for details.
        HostBridge.backgroundDrawableResId = R.drawable.background_image

        // Streaming-ASR keys from the gitignored secrets.properties (via BuildConfig).
        // Empty when unset — the caption clients then surface a "missing key" error.
        TranscriptionSecrets.deepgramApiKey = BuildConfig.DEEPGRAM_API_KEY
        TranscriptionSecrets.sonioxApiKey = BuildConfig.SONIOX_API_KEY

        // Discord OAuth wiring for the landing screen's Apollo gate. Neither value
        // is a secret (the client id is public, the guild id is a snowflake), but
        // both come from the gitignored secrets.properties so forks configure
        // their own Discord application. Empty client id disables the gate.
        DiscordConfig.clientId = BuildConfig.DISCORD_CLIENT_ID
        DiscordConfig.apolloGuildId = BuildConfig.APOLLO_GUILD_ID
        FeedConfig.extraVideosManifestUrl = BuildConfig.EXTRA_VIDEOS_URL

        // The launching Intent may itself be the OAuth redirect (the browser deep
        // links straight into a cold-started task), so check it before composing.
        deliverDiscordRedirect(intent)

        ensureNotificationPermission()

        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            val pipController = object : PipController {
                override fun updateVideoSize(width: Int, height: Int) =
                    updatePipAspectRatio(width, height)
                override fun setPlaying(playing: Boolean) = setPlayingVideo(playing)
                override fun updateSourceBounds(left: Int, top: Int, right: Int, bottom: Int) {
                    val bounds = android.graphics.Rect(left, top, right, bottom)
                    // onGloballyPositioned fires on every layout pass; only
                    // re-publish when the bounds actually moved, since
                    // setPictureInPictureParams is a binder call to the system.
                    if (pipSourceRect.getAndSet(bounds) != bounds) {
                        updatePipParams()
                    }
                }
            }
            HostBridge.HostApp(pipController)
        }
    }

    /**
     * The activity is `singleTask`, so a redirect arriving while the app is
     * already running is delivered here rather than starting a new instance.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deliverDiscordRedirect(intent)
    }

    /**
     * Forwards a Discord OAuth redirect deep link to the landing screen. Anything
     * that is not our registered redirect URI is ignored — the same activity also
     * handles the ordinary LAUNCHER intent.
     */
    private fun deliverDiscordRedirect(intent: Intent?) {
        val uri = intent?.data?.toString() ?: return
        if (DiscordAuthBroker.isAuthRedirect(uri)) {
            DiscordAuthBroker.deliver(uri)
        }
    }

    override fun onStart() {
        super.onStart()
        registerComponentCallbacks(trimCallback)
    }

    override fun onStop() {
        super.onStop()
        unregisterComponentCallbacks(trimCallback)
    }

    /**
     * Update the PiP aspect ratio as the active video's size becomes known.
     * Called from the player screen via [LocalPipController].
     */
    internal fun updatePipAspectRatio(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            pipAspectRatio.set(safePipRatio(width, height))
            updatePipParams()
        }
    }

    internal fun setPlayingVideo(playing: Boolean) {
        isPlayingVideo.set(playing)
        updatePipParams()
    }

    /**
     * Registers the current PiP parameters with the system *while the video is
     * playing*, rather than waiting until the user leaves.
     *
     * On API 31+ this is the whole mechanism: `setAutoEnterEnabled` only works if
     * the system already holds the params when the transition starts, so it has
     * to be published ahead of time. Setting it inside `onUserLeaveHint` — as
     * this used to — is doubly broken: too late for the system to auto-enter, yet
     * early enough that it then refuses the manual call with
     * "Skip client enterPictureInPictureMode request while pausing,
     * auto-enter-pip is enabled". Each path disabled the other and PiP never
     * happened.
     *
     * Tied to [isPlayingVideo] so a paused video does not shrink into PiP when
     * the user leaves for unrelated reasons.
     */
    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        if (!packageManager.hasSystemFeature(
                android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE,
            )
        ) {
            return
        }
        val params = PictureInPictureParams.Builder()
        pipAspectRatio.get()?.let { aspectRatio ->
            params.setAspectRatio(aspectRatio)
        }
        // Source-rect hint: tells the platform the exact on-screen video bounds so
        // the PiP enter animation is seamless (no crop/flash from mismatched rects).
        pipSourceRect.get()?.let { sourceRect ->
            runCatching { params.setSourceRectHint(sourceRect) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setAutoEnterEnabled(isPlayingVideo.get())
        }
        // Throws if the activity is finishing or does not support PiP.
        runCatching { setPictureInPictureParams(params.build()) }
    }

    /**
     * Enters PiP on API 26–30, which have no auto-enter and so still need the
     * explicit call when the user navigates away while playing.
     *
     * API 31+ deliberately returns early: [updatePipParams] has already told the
     * system to auto-enter, and calling in here as well is what previously broke
     * PiP outright — the system rejects a manual request from a pausing activity
     * that has auto-enter set.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        ) {
            return
        }
        if (!isPlayingVideo.get() || !packageManager.hasSystemFeature(
                android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE,
            )
        ) {
            return
        }
        val params = PictureInPictureParams.Builder()
        // The aspect ratio is clamped to the platform's 1:2.39–2.39:1 range
        // (matters for vertical video — 9:16 is clamped, not clipped).
        pipAspectRatio.get()?.let { aspectRatio ->
            params.setAspectRatio(aspectRatio)
        }
        pipSourceRect.get()?.let { sourceRect ->
            runCatching { params.setSourceRectHint(sourceRect) }
        }
        runCatching { enterPictureInPictureMode(params.build()) }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        HostBridge.inPipState.value = isInPictureInPictureMode
    }

    /**
     * Clamp the ratio to Android's supported PiP range (1:2.39 .. 2.39:1).
     * Vertical video (e.g. 9:16) would otherwise be rejected by the platform.
     */
    private fun safePipRatio(width: Int, height: Int): Rational {
        val raw = Rational(width, height)
        val maxWide = Rational(239, 100)
        val maxTall = Rational(100, 239)
        return when {
            raw > maxWide -> maxWide
            raw < maxTall -> maxTall
            else -> raw
        }
    }
}
