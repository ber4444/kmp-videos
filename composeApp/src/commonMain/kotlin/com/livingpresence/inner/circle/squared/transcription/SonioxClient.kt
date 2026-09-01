package com.livingpresence.inner.circle.squared.transcription

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Soniox streaming ASR over websocket. The protocol differs from Deepgram: the first
 * frame is a JSON **config** (carrying the API key and audio format), then raw
 * 16 kHz mono s16le PCM is streamed as binary frames. Results arrive as a stream of
 * tokens, each flagged `is_final`; final tokens are concatenated into a line that is
 * committed as a cue at sentence boundaries, while non-final tokens form the live tail.
 *
 * Because the key travels in the config frame (not a handshake header), Soniox works
 * unchanged on the browser transport. The connect/send/receive lifecycle — including the
 * reconnect loop — lives in [WebSocketTranscriber]; this subclass supplies the endpoint,
 * the config handshake, the keepalive, the `finalize` on end-of-stream, and Soniox's
 * token protocol.
 *
 * **Translation.** Given a [translateTo] language, the config frame asks for one-way
 * translation and the captions arrive in that language instead of the spoken one — the
 * streams are in English, so a Russian device reads Russian off the same English audio.
 * It costs nothing extra and adds no service to the path: Soniox emits translated tokens
 * on this same socket, chunk by chunk, without waiting for a sentence to end. The catch is
 * that it emits the *originals* too — see [selectCaptionText]. [CaptionLanguage] picks the
 * language; null leaves the stream untranslated.
 *
 * **Domain vocabulary.** The config frame also carries a `context` object built from
 * [CaptionGlossary]: `terms` pins the spelling of vocabulary a general model has no reason
 * to expect, and `translation_terms` pins the accepted rendering of those terms in the
 * language being translated into, so the caption says what the tradition says rather than
 * what a literal translation of the English would.
 *
 * **Idle timeouts.** Soniox closes a stream that receives neither audio nor a keepalive
 * for more than ~20 s, reporting `error_message: "Request timeout"` — which a paused video,
 * or any gap in the platform audio tap, reaches easily. [idleFrame] sends the documented
 * `{"type":"keepalive"}` control message whenever the audio stream goes quiet, and an error
 * that arrives anyway is reported through [failSession] so the base class reconnects instead
 * of leaving the transcript frozen for the rest of the video.
 *
 * NOTE: endpoint host and field names should be re-verified against current Soniox
 * docs (see docs/live-captions-plan.md) — they change and this hasn't been run against
 * the live service.
 */
class SonioxClient(
    apiKey: suspend () -> String,
    private val sampleRate: Int = 16_000,
    private val languageHints: List<String> = listOf("en"),
    private val translateTo: String? = null,
    private val terms: List<String> = emptyList(),
    private val translationTerms: Map<String, String> = emptyMap(),
) : WebSocketTranscriber(
    apiKey,
    // encodeDefaults so the config frame carries the model/format fields Soniox needs;
    // explicitNulls=false so the optional `translation` and `context` blocks are *absent*
    // rather than null when captions aren't being translated, or when there is no glossary.
    json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false },
) {

    override val providerName = "Soniox"

    override val url = "wss://stt-rt.soniox.com/transcribe-websocket"

    /** Final tokens accumulated for the current (not-yet-committed) caption line. */
    private val lineBuffer = StringBuilder()

    override fun headers(apiKey: String) = mapOf("Authorization" to "Bearer $apiKey")

    /** Soniox's documented keepalive control message; sent only while no audio is flowing. */
    override val idleFrame = "{\"type\":\"keepalive\"}"

    /** Well inside Soniox's ~20 s idle limit, and the vendor's recommended 5–10 s cadence. */
    override val idleFrameIntervalMs = 5_000L

    override suspend fun onOpen(ws: WsSession, apiKey: String) {
        lineBuffer.clear()
        val config = SonioxConfig(
            apiKey = apiKey,
            sampleRate = sampleRate,
            languageHints = languageHints,
            translation = translateTo?.let { SonioxTranslation(targetLanguage = it) },
            context = sonioxContext(terms, translationTerms),
        )
        ws.sendText(json.encodeToString(config))
    }

    override suspend fun onAudioDrained(ws: WsSession) {
        // Empty audio frame / finalize signals end-of-stream to Soniox.
        runCatching { ws.sendText("{\"type\":\"finalize\"}") }
    }

    override fun onMissingKey() {
        // Reached when no token endpoint is configured for this build, which is the
        // only way the app can now be short of a key — it no longer ships one.
        val msg = "Captions are not configured for this build"
        setError(msg)
        accumulator.setPartial(msg)
    }

    override fun onStop() {
        lineBuffer.clear()
    }

    override fun handleMessage(text: String) {
        val resp = runCatching { json.decodeFromString<SonioxResponse>(text) }.getOrNull()
        if (resp == null) {
            // Acks and other frames we don't model: log, but never stop transcribing over one.
            println("Soniox: unparsed frame: $text")
            return
        }
        if (resp.errorMessage != null) {
            // Soniox sends the error, then closes: end this session and let the base class
            // reconnect. "Request timeout" in particular is routine on a long stream.
            failSession("Soniox: ${resp.errorMessage}")
            return
        }
        val caption = selectCaptionText(resp.tokens, translating = translateTo != null)
        lineBuffer.append(caption.finalized)
        val line = lineBuffer.toString()
        val committed = line.isNotBlank() &&
            (line.trimEnd().lastOrNull() in SENTENCE_ENDINGS || line.length > MAX_LINE_CHARS)
        if (committed) {
            accumulator.appendFinal(line)
            lineBuffer.clear()
        } else {
            accumulator.setPartial(line + caption.partial)
        }
    }

    @Serializable
    private data class SonioxConfig(
        @SerialName("api_key") val apiKey: String,
        val model: String = "stt-rt-v5",
        @SerialName("audio_format") val audioFormat: String = "pcm_s16le",
        @SerialName("sample_rate") val sampleRate: Int,
        @SerialName("num_channels") val numChannels: Int = 1,
        @SerialName("language_hints") val languageHints: List<String> = listOf("en"),
        val translation: SonioxTranslation? = null,
        val context: SonioxContext? = null,
    )

    /**
     * One-way translation: everything Soniox hears is rendered into [targetLanguage],
     * whatever language it was spoken in. (The two-way mode is for conversations, where
     * each side is translated into the other's language.)
     */
    @Serializable
    private data class SonioxTranslation(
        val type: String = "one_way",
        @SerialName("target_language") val targetLanguage: String,
    )

    @Serializable
    private data class SonioxResponse(
        val tokens: List<SonioxToken> = emptyList(),
        val finished: Boolean = false,
        @SerialName("error_code") val errorCode: Int? = null,
        @SerialName("error_message") val errorMessage: String? = null,
    )

    private companion object {
        /**
         * Sentence terminators across the languages captions can now be written in: the
         * Latin trio, their full-width CJK forms, the Arabic question mark and the Urdu
         * full stop, and the Devanagari danda. Without these a Japanese or Hindi caption
         * would never hit a boundary and would only commit on [MAX_LINE_CHARS].
         */
        val SENTENCE_ENDINGS = setOf('.', '?', '!', '。', '？', '！', '؟', '۔', '।')

        /** Hard cap so a speaker who never pauses still gets committed lines. */
        const val MAX_LINE_CHARS = 80
    }
}

/** One result frame's text, split into what can be committed and the tail still being revised. */
internal data class SonioxCaption(val finalized: String, val partial: String)

@Serializable
internal data class SonioxToken(
    val text: String = "",
    @SerialName("is_final") val isFinal: Boolean = false,
    /** `"original"` or `"translation"`; absent when the session isn't translating. */
    @SerialName("translation_status") val translationStatus: String? = null,
)

/**
 * Picks the tokens that belong in the caption and splits them into finalized text and the
 * still-changing tail.
 *
 * With translation on, Soniox sends the original *and* the translation over the same socket,
 * interleaved and distinguished only by `translation_status` — so treating every token as
 * caption text would splice English and Russian into a single line. When [translating],
 * only translated tokens are captions; otherwise no token carries the field and all of them
 * are.
 */
internal fun selectCaptionText(tokens: List<SonioxToken>, translating: Boolean): SonioxCaption {
    val finalized = StringBuilder()
    val partial = StringBuilder()
    for (token in tokens) {
        if (translating && token.translationStatus != TRANSLATION_STATUS_TRANSLATION) continue
        if (token.isFinal) finalized.append(token.text) else partial.append(token.text)
    }
    return SonioxCaption(finalized.toString(), partial.toString())
}

private const val TRANSLATION_STATUS_TRANSLATION = "translation"

/**
 * The session `context` Soniox accepts alongside the audio settings. Only the two sections
 * this app has anything to say are modelled; the API also takes `general` key-values and a
 * free-text `text` block.
 */
@Serializable
internal data class SonioxContext(
    val terms: List<String>? = null,
    @SerialName("translation_terms") val translationTerms: List<SonioxTranslationTerm>? = null,
)

/** One accepted rendering: [source] as it is spoken, [target] as the caption should read. */
@Serializable
internal data class SonioxTranslationTerm(val source: String, val target: String)

/**
 * Builds the context block, or null when there is nothing to say — an empty `terms` array
 * would otherwise be sent on every session, and the two sections are independently empty:
 * a device with no glossary for its language still gets the transcription terms.
 */
internal fun sonioxContext(
    terms: List<String>,
    translationTerms: Map<String, String>,
): SonioxContext? {
    if (terms.isEmpty() && translationTerms.isEmpty()) return null
    return SonioxContext(
        terms = terms.takeIf { it.isNotEmpty() },
        translationTerms = translationTerms
            .map { (source, target) -> SonioxTranslationTerm(source, target) }
            .takeIf { it.isNotEmpty() },
    )
}
