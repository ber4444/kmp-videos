@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.livingpresence.inner.circle.squared

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.get
import kotlin.math.roundToInt

/**
 * iOS PCM ingestion for [CaptionAudioRouter]: [CaptionSegmentFeeder] decodes the
 * audio-only rendition's AAC segments to interleaved float samples; downmix to
 * mono 16-bit and hand off to the shared [CaptionAudioRouter.feedMono] (which
 * resamples + streams to the provider).
 *
 * The samples arrive already downmixed (`channels == 1`), so the mix below is a
 * pass-through today; it stays general because the contract is interleaved PCM,
 * not mono PCM.
 */
internal fun CaptionAudioRouter.onPcm(pcmData: CPointer<FloatVar>?, numFrames: Int, channels: Int, sampleRate: Int) {
    if (pcmData == null || numFrames <= 0 || channels <= 0) return
    val mono = downmixFloatToMono16(numFrames, channels) { i -> pcmData[i] }
    feedMono(mono, sampleRate)
}
