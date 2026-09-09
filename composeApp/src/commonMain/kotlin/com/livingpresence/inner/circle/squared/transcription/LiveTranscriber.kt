package com.livingpresence.inner.circle.squared.transcription

import com.livingpresence.inner.circle.squared.CaptionCue
import com.livingpresence.inner.circle.squared.createHttpClient
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Provider-agnostic live-caption coordinator, shared across platforms. Owns the
 * currently-selected [StreamingTranscriber] (Deepgram/Soniox), mirrors its
 * captions/status/error, and switches providers on demand. Platform code only has
 * to: capture audio, resample it to 16 kHz mono s16le, and call [feedPcm]; then
 * observe [captions]/[status]/[error] for the overlay/UI.
 *
 * The events are spoken in English, but the captions follow the language the viewer chose in
 * the caption menu — the device's own by default: on Soniox they come back already
 * translated, so a Russian phone reads Russian off English audio. See [CaptionLanguage] for
 * how the locale is resolved and when translation is skipped, and [captionMenuOptions] for
 * the rows the viewer picks from. Deepgram has no translation on its streaming API, so it
 * stays English-only whatever is selected.
 *
 * This is the piece the Android `CaptionAudioRouter` and the iOS/web taps all reuse —
 * only the audio capture differs per platform.
 */
class LiveTranscriber(
    /**
     * Where session keys come from. Defaulted rather than injected at the call site
     * because the only production caller is a process singleton
     * ([com.livingpresence.inner.circle.squared.CaptionAudioRouter]); tests
     * substitute it.
     */
    private val keys: SonioxKeyProvider = SonioxKeyProvider(createHttpClient()),
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var active: StreamingTranscriber? = null
    @Volatile private var activeProvider: TranscriptionProvider? = null
    /** The `target_language` the running session was opened with; null = not translating. */
    @Volatile private var activeTarget: String? = null
    private var mirrorJob: Job? = null

    private val _captions = MutableStateFlow<List<CaptionCue>>(emptyList())
    val captions: StateFlow<List<CaptionCue>> = _captions.asStateFlow()

    private val _status = MutableStateFlow(TranscriberStatus.IDLE)
    val status: StateFlow<TranscriberStatus> = _status.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * Starts (or switches to) [provider], writing captions in [translateTo] — the language
     * the viewer picked in the caption menu, or null to leave them in the spoken language.
     *
     * Idempotent for a session that is already running with the same provider *and* the same
     * target: changing the language means a new session, because Soniox is told the target in
     * the config frame that opens the socket and a live stream cannot be re-pointed.
     */
    fun enable(provider: TranscriptionProvider, translateTo: String?) {
        if (activeProvider == provider && activeTarget == translateTo && active != null) return
        stopActive()
        val client = createClient(provider, translateTo)
        active = client
        activeProvider = provider
        activeTarget = translateTo
        mirrorJob = scope.launch {
            launch { client.captions.collect { _captions.value = it } }
            launch { client.status.collect { _status.value = it } }
            launch { client.error.collect { _error.value = it } }
        }
        client.start()
    }

    /** Feeds 16 kHz mono s16le PCM to the active client (no-op if disabled). */
    fun feedPcm(pcm16: ByteArray) {
        active?.feedPcm(pcm16)
    }

    fun disable() {
        stopActive()
        _captions.value = emptyList()
        _status.value = TranscriberStatus.IDLE
        _error.value = null
    }

    private fun stopActive() {
        mirrorJob?.cancel()
        mirrorJob = null
        active?.stop()
        active = null
        activeProvider = null
        activeTarget = null
    }

    private fun createClient(
        provider: TranscriptionProvider,
        translateTo: String?,
    ): StreamingTranscriber = when (provider) {
        // Unreachable from the UI, and no longer carries a key: the app ships none.
        TranscriptionProvider.DEEPGRAM ->
            DeepgramClient(apiKey = { TranscriptionSecrets.DEEPGRAM_UNCONFIGURED })
        // Built per session rather than once per process, so switching language takes effect
        // on the next session — and so that each session gets its own single-use key
        // from :server.
        TranscriptionProvider.SONIOX -> SonioxClient(
            apiKey = { keys.fetch() },
            languageHints = CaptionLanguage.SPOKEN_LANGUAGES,
            translateTo = translateTo,
            // What the lectures are, then their vocabulary: the domain sentence and `terms`
            // on every session, the accepted translations only for the language this one is
            // actually writing in.
            domain = CaptionGlossary.DOMAIN,
            terms = CaptionGlossary.TERMS,
            translationTerms = CaptionGlossary.translationTermsFor(translateTo),
        )
    }
}
