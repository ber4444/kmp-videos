package com.livingpresence.inner.circle.squared.transcription

import com.livingpresence.inner.circle.squared.CaptionCue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlin.time.TimeSource

/**
 * Shared websocket plumbing for streaming ASR clients (Deepgram, Soniox, …): key
 * validation, the connect/send/receive lifecycle, PCM buffering, automatic reconnection,
 * and status/error/caption flow wiring. Subclasses supply only the parts that genuinely
 * differ — the endpoint, auth, optional handshake/keep-alive frames, and message parsing.
 *
 * **Reconnection.** A cloud ASR socket does not survive a feature-length video: Soniox
 * closes an idle stream with "Request timeout" (its server drops a stream that goes >20 s
 * without audio or a keepalive — a paused video is enough), providers recycle backends, and
 * networks blink. [start] therefore runs a *session loop*, not a single session: whenever a
 * session ends — clean close, socket error, or a protocol error a subclass reports through
 * [failSession] — it reconnects on the [ReconnectPolicy] schedule and keeps going, so
 * captions resume on their own while the video plays. Only [stop] (or a failure the client
 * calls terminal, such as a rejected API key) ends the loop.
 *
 * Each attempt gets a fresh PCM channel: audio captured while the socket was down is stale
 * by the time a new one opens, so it is dropped rather than replayed into the transcript.
 *
 * The transport ([WsTransport]) is platform-specific — Ktor on native, a raw browser
 * `WebSocket` on web — so subclasses declare auth as [headers] (used where the platform
 * can set them) plus [subprotocols] (used by the browser transport for `token` auth),
 * and never touch the socket directly.
 */
abstract class WebSocketTranscriber internal constructor(
    private val apiKey: () -> String,
    protected val json: Json,
    private val reconnect: ReconnectPolicy,
    private val transportFactory: () -> WsTransport,
    dispatcher: CoroutineDispatcher,
) : StreamingTranscriber {

    constructor(
        apiKey: () -> String,
        json: Json = Json { ignoreUnknownKeys = true },
        reconnect: ReconnectPolicy = ReconnectPolicy(),
    ) : this(apiKey, json, reconnect, { createWsTransport() }, Dispatchers.Default)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val transport by lazy { transportFactory() }
    protected val accumulator = CaptionAccumulator()

    private val _status = MutableStateFlow(TranscriberStatus.IDLE)
    private val _error = MutableStateFlow<String?>(null)
    override val captions: StateFlow<List<CaptionCue>> = accumulator.captions
    override val status: StateFlow<TranscriberStatus> = _status.asStateFlow()
    override val error: StateFlow<String?> = _error.asStateFlow()

    private var job: Job? = null
    @Volatile private var pcm: Channel<ByteArray>? = null

    /** Completed by [failSession] to tear down the session it belongs to. Null between sessions. */
    @Volatile private var sessionFailure: CompletableDeferred<Unit>? = null
    @Volatile private var sessionFailureMessage: String? = null

    /** The `wss://` endpoint to connect to. */
    protected abstract val url: String

    /** Human-readable provider name, used in default error/log messages. */
    protected abstract val providerName: String

    /** Handshake headers (e.g. `Authorization`). Ignored by the browser transport. */
    protected open fun headers(apiKey: String): Map<String, String> = emptyMap()

    /** WebSocket subprotocols (e.g. Deepgram's `["token", key]`). Used by the browser transport. */
    protected open fun subprotocols(apiKey: String): List<String> = emptyList()

    /** Parses one inbound text frame and feeds the [accumulator]. */
    protected abstract fun handleMessage(text: String)

    /** Optional first frame(s) sent right after connect, before audio (e.g. a config handshake). */
    protected open suspend fun onOpen(ws: WsSession, apiKey: String) {}

    /** Optional periodic frame loop for the session lifetime (e.g. keep-alives). Cancelled on close. */
    protected open suspend fun keepAlive(ws: WsSession) {}

    /**
     * Optional frame sent when no audio has been sent for [idleFrameIntervalMs] — for providers
     * that drop a stream which goes quiet (a paused video, silence trimmed upstream). Null = none.
     */
    protected open val idleFrame: String? = null

    /** How long the audio stream must be idle before [idleFrame] is sent (and then resent). */
    protected open val idleFrameIntervalMs: Long = 5_000L

    /** Optional end-of-stream frame sent once the audio channel drains. */
    protected open suspend fun onAudioDrained(ws: WsSession) {}

    /** Optional handling of the close reason (null when the socket closed cleanly). */
    protected open fun onClosed(reason: String?) {}

    /** Called when the API key is blank. Default reports a standard "missing key" error. */
    protected open fun onMissingKey() = setError("Missing $providerName API key")

    /** Called on a connection-level exception; e.g. surface it as a partial cue. */
    protected open fun onConnectException(e: Throwable) {}

    /**
     * Whether [message] describes a failure that reconnecting cannot fix, ending the session
     * loop instead of retrying forever. Auth rejections are the default case — retrying a bad
     * key just hammers the provider — while timeouts, closes and network errors are retried.
     */
    protected open fun isTerminalFailure(message: String): Boolean {
        val m = message.lowercase()
        return "401" in m || "403" in m || "unauthorized" in m || "forbidden" in m ||
            "invalid api key" in m || "authentication" in m
    }

    /**
     * Called before each reconnect attempt, [delayMs] ahead of it. The default marks the
     * gap in the transcript with a transient partial cue, which the next result replaces —
     * unlike a finalized cue, a stale error line never sticks in the rolling transcript.
     */
    protected open fun onReconnecting(message: String, attempt: Int, delayMs: Long) {
        accumulator.setPartial("… reconnecting")
    }

    /** Called when the loop gives up ([isTerminalFailure]); captions stay stopped until re-enabled. */
    protected open fun onTerminalFailure(message: String) {
        accumulator.setPartial("$providerName captions stopped: $message")
    }

    /** Subclass cleanup on [stop] (e.g. clearing a line buffer). */
    protected open fun onStop() {}

    /** Moves to the ERROR state with [message]. */
    protected fun setError(message: String) {
        _error.value = message
        _status.value = TranscriberStatus.ERROR
    }

    /**
     * Reports a fatal *protocol* error — one the provider sent as a message rather than a
     * socket close, such as Soniox's `error_message`. The current session is torn down and
     * the loop reconnects (unless [isTerminalFailure] claims the message); outside a session
     * this degrades to [setError].
     */
    protected fun failSession(message: String) {
        val signal = sessionFailure
        if (signal == null) {
            setError(message)
            return
        }
        sessionFailureMessage = message
        signal.complete(Unit)
    }

    final override fun start() {
        // A job left over from a terminal failure is finished, not running: don't let it
        // block a restart (re-enabling CC, switching provider back).
        if (job?.isActive == true) return
        val key = apiKey()
        if (key.isBlank()) {
            onMissingKey()
            return
        }
        _error.value = null
        _status.value = TranscriberStatus.CONNECTING
        job = scope.launch { runSessions(key) }
    }

    /**
     * Connects, streams until the session ends, then reconnects — until the job is cancelled
     * by [stop] or a failure turns out to be terminal.
     */
    private suspend fun runSessions(key: String) {
        var failures = 0
        while (coroutineContext.isActive) {
            _status.value = if (failures == 0) TranscriberStatus.CONNECTING else TranscriberStatus.RECONNECTING
            val startedAt = TimeSource.Monotonic.markNow()
            val failure = runSession(key)
            val sessionMs = startedAt.elapsedNow().inWholeMilliseconds
            if (!coroutineContext.isActive) return

            val message = failure ?: "$providerName stream ended"
            println("$providerName session ended after ${sessionMs}ms: $message")
            if (isTerminalFailure(message)) {
                setError(message)
                onTerminalFailure(message)
                return
            }
            failures = reconnect.failuresAfter(failures, sessionMs)
            val delayMs = reconnect.delayFor(failures)
            _error.value = message
            _status.value = TranscriberStatus.RECONNECTING
            onReconnecting(message, failures, delayMs)
            delay(delayMs)
        }
    }

    /**
     * Runs one websocket session to completion. Returns the failure that ended it, or null
     * if the socket closed cleanly (which still ends the session — the loop reconnects).
     */
    private suspend fun runSession(key: String): String? {
        val channel = Channel<ByteArray>(capacity = AUDIO_CHANNEL_CAPACITY)
        val failSignal = CompletableDeferred<Unit>()
        pcm = channel
        sessionFailureMessage = null
        sessionFailure = failSignal
        return try {
            val reason = transport.run(
                url = url,
                subprotocols = subprotocols(key),
                headers = headers(key),
                onText = { handleMessage(it) },
            ) { ws ->
                onOpen(ws, key)
                _status.value = TranscriberStatus.LISTENING
                _error.value = null
                launch { pumpAudio(ws, channel) }
                launch { keepAlive(ws) }
                launch {
                    // A protocol error arrives as a message, not a close: end the socket
                    // ourselves so the session loop can start a fresh one.
                    failSignal.await()
                    runCatching { ws.close() }
                }
            }
            onClosed(reason)
            sessionFailureMessage ?: reason
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onConnectException(e)
            e.message ?: "$providerName connection failed"
        } finally {
            sessionFailure = null
            pcm = null
            channel.close()
        }
    }

    /**
     * Streams buffered PCM to [ws], sending [idleFrame] whenever the audio goes quiet for
     * [idleFrameIntervalMs] — the receive timeout doubles as the keepalive clock, so the two
     * never race over a shared "last sent" timestamp.
     */
    private suspend fun pumpAudio(ws: WsSession, channel: Channel<ByteArray>) {
        try {
            while (true) {
                // null result = the receive timed out (audio has gone quiet); a result that
                // holds nothing = the channel closed (stop() drained it).
                val received = withTimeoutOrNull(idleFrameIntervalMs) { channel.receiveCatching() }
                if (received == null) {
                    idleFrame?.let { ws.sendText(it) }
                    continue
                }
                val chunk = received.getOrNull()
                if (chunk == null) {
                    onAudioDrained(ws)
                    return
                }
                if (chunk.isNotEmpty()) ws.sendBinary(chunk)
            }
        } catch (_: CancellationException) {
            // Session scope cancelled by the transport once the socket closed.
        } catch (_: Throwable) {
            // Connection closing under us; the session loop handles the reconnect.
        }
    }

    final override fun feedPcm(pcm16: ByteArray) {
        pcm?.trySend(pcm16)
    }

    final override fun stop() {
        job?.cancel()
        job = null
        pcm?.close()
        pcm = null
        sessionFailure = null
        sessionFailureMessage = null
        onStop()
        accumulator.clear()
        if (_status.value != TranscriberStatus.ERROR) _status.value = TranscriberStatus.IDLE
    }

    private companion object {
        /** ~4 s of 16 kHz mono s16le at the tap's chunk size; older audio is dropped, not queued. */
        const val AUDIO_CHANNEL_CAPACITY = 128
    }
}
