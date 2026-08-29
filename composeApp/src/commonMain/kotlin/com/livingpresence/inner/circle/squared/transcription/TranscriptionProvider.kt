package com.livingpresence.inner.circle.squared.transcription

/**
 * The streaming ASR backends the user can switch between at runtime (see
 * [TranscriptionSettings]). Both are cloud websocket services; the choice is
 * surfaced in the player UI.
 *
 * - [SONIOX] — default. Cheapest, more accurate on this material (see the eval harness
 *   in `eval/`), bundles diarization, and translates in-band at no extra cost, so its
 *   captions follow the device language (see [CaptionLanguage]).
 * - [DEEPGRAM] — alternative. Mature, widely recognized (Nova-3), generous free tier.
 *   Captions are always English: its streaming API has no translation, and adding one
 *   would mean a second service between the socket and the overlay.
 */
enum class TranscriptionProvider(val label: String) {
    DEEPGRAM("Deepgram"),
    SONIOX("Soniox");
}
