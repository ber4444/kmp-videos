package com.livingpresence.inner.circle.squared.transcription

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The user's currently-selected streaming ASR [TranscriptionProvider], shared
 * across platforms and observed by the UI switch and the transcription coordinator.
 *
 * Defaults to Soniox: it wins the eval harness on this material, and it is the only
 * provider that can write the captions in the viewer's own language — leaving Deepgram
 * as the default would have meant English captions on a Russian device unless the user
 * knew to go and flip the provider.
 *
 * In-memory for now (resets each launch); persistence (DataStore on Android,
 * NSUserDefaults on iOS, localStorage on web) is a follow-up — see
 * docs/live-captions-plan.md step 1.4.
 */
object TranscriptionSettings {
    private val _provider = MutableStateFlow(TranscriptionProvider.SONIOX)
    val provider: StateFlow<TranscriptionProvider> = _provider.asStateFlow()

    fun select(provider: TranscriptionProvider) {
        _provider.value = provider
    }
}
