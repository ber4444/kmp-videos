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
    apiKey: () -> String,
    private val sampleRate: Int = 16_000,
    private val languageHints: List<String> = listOf("en"),
) : WebSocketTranscriber(apiKey, json = Json { ignoreUnknownKeys = true; encodeDefaults = true }) {

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
        )
        ws.sendText(json.encodeToString(config))
    }

    override suspend fun onAudioDrained(ws: WsSession) {
        // Empty audio frame / finalize signals end-of-stream to Soniox.
        runCatching { ws.sendText("{\"type\":\"finalize\"}") }
    }

    override fun onMissingKey() {
        val msg = "Missing Soniox API key"
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
        val partial = StringBuilder()
        for (token in resp.tokens) {
            if (token.isFinal) lineBuffer.append(token.text) else partial.append(token.text)
        }
        val line = lineBuffer.toString()
        val committed = line.isNotBlank() &&
            (line.trimEnd().let { it.endsWith(".") || it.endsWith("?") || it.endsWith("!") } || line.length > 80)
        if (committed) {
            accumulator.appendFinal(line)
            lineBuffer.clear()
        } else {
            accumulator.setPartial(line + partial.toString())
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
    )

    @Serializable
    private data class SonioxResponse(
        val tokens: List<SonioxToken> = emptyList(),
        val finished: Boolean = false,
        @SerialName("error_code") val errorCode: Int? = null,
        @SerialName("error_message") val errorMessage: String? = null,
    )

    @Serializable
    private data class SonioxToken(
        val text: String = "",
        @SerialName("is_final") val isFinal: Boolean = false,
    )
}
