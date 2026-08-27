package com.livingpresence.inner.circle.squared.transcription

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.coroutines.coroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Tests that a dropped ASR websocket recovers on its own.
 *
 * The bug these cover: Soniox reports `error_message: "Request timeout"` (routine — its
 * server drops a stream that goes ~20 s without audio or a keepalive), the socket ends, and
 * before the reconnect loop the client simply stopped transcribing for the rest of the video
 * while the error text sat frozen in the transcript.
 *
 * A fake [WsTransport] stands in for the network so the whole session loop — connect,
 * protocol error, backoff, reconnect — runs on virtual time. Note that a live session never
 * goes idle (the audio pump re-arms its keepalive timer forever), so these advance the clock
 * deliberately instead of using `advanceUntilIdle`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WebSocketTranscriberReconnectTest {

    @Test
    fun reconnectsAfterAProtocolErrorAndKeepsTranscribing() = runTest {
        val transport = FakeTransport(
            listOf(
                FakeSession(frames = listOf(ERROR_FRAME)),
                FakeSession(frames = listOf("hello again")),
            )
        )
        val client = transcriber(transport)

        client.start()
        runCurrent()
        assertEquals(1, transport.runs)
        assertEquals(TranscriberStatus.RECONNECTING, client.status.value, "the error ended session 1")

        advanceTimeBy(RETRY_MS.milliseconds + 1.milliseconds)
        runCurrent()
        assertEquals(2, transport.runs)
        assertEquals(TranscriberStatus.LISTENING, client.status.value)
        assertNull(client.error.value, "a recovered socket clears the error badge")
        assertEquals(listOf("hello again"), client.transcribed)

        client.stop()
    }

    @Test
    fun reconnectsAfterASocketErrorAndAfterACleanClose() = runTest {
        val transport = FakeTransport(
            listOf(
                FakeSession(connectFailure = "Software caused connection abort"),
                FakeSession(frames = listOf("first"), holdOpen = false),
                FakeSession(frames = listOf("second")),
            )
        )
        val client = transcriber(transport)

        client.start()
        advanceTimeBy((10 * RETRY_MS).milliseconds)
        runCurrent()

        assertEquals(3, transport.runs, "a throwing connect and a clean close both retry")
        assertEquals(TranscriberStatus.LISTENING, client.status.value)
        assertEquals(listOf("first", "second"), client.transcribed)

        client.stop()
    }

    @Test
    fun stopEndsTheLoop() = runTest {
        val transport = FakeTransport(listOf(FakeSession(frames = listOf(ERROR_FRAME))))
        val client = transcriber(transport)

        client.start()
        runCurrent()
        client.stop()
        advanceTimeBy((20 * RETRY_MS).milliseconds)
        runCurrent()

        assertEquals(1, transport.runs, "no reconnect once the user turns captions off")
        assertEquals(TranscriberStatus.IDLE, client.status.value)
    }

    @Test
    fun aRejectedKeyStopsInsteadOfRetryingForever() = runTest {
        val transport = FakeTransport(listOf(FakeSession(connectFailure = "401 Unauthorized")))
        val client = transcriber(transport)

        client.start()
        advanceTimeBy((20 * RETRY_MS).milliseconds)
        runCurrent()

        assertEquals(1, transport.runs)
        assertEquals(TranscriberStatus.ERROR, client.status.value)
        assertTrue(client.error.value.orEmpty().contains("401"))

        client.stop()
    }

    @Test
    fun theErrorTextNeverSticksInTheTranscript() = runTest {
        val transport = FakeTransport(
            listOf(
                FakeSession(frames = listOf("a real caption", ERROR_FRAME)),
                FakeSession(frames = listOf("captions are back")),
            )
        )
        val client = transcriber(transport)

        client.start()
        advanceTimeBy(RETRY_MS.milliseconds + 1.milliseconds)
        runCurrent()

        val rendered = client.captions.value.joinToString("\n") { it.text }
        assertFalse(rendered.contains("Request timeout"), "provider errors are transient, not transcript")
        assertTrue(rendered.contains("a real caption"))
        assertTrue(rendered.contains("captions are back"))

        client.stop()
    }

    private fun TestScope.transcriber(transport: FakeTransport) = TestTranscriber(
        transport = transport,
        dispatcher = StandardTestDispatcher(testScheduler),
        policy = ReconnectPolicy(initialDelayMs = RETRY_MS, maxDelayMs = RETRY_MS, healthySessionMs = 15_000),
    )

    private companion object {
        const val RETRY_MS = 100L
        const val ERROR_FRAME = "ERROR: Request timeout"
    }
}

/**
 * Minimal client over the fake transport: plain text frames are captions, and an `ERROR:`
 * frame stands in for a provider reporting a fatal stream error as a message (Soniox's
 * `error_message`), which must end the session and trigger a reconnect.
 */
private class TestTranscriber(
    transport: WsTransport,
    dispatcher: CoroutineDispatcher,
    policy: ReconnectPolicy,
) : WebSocketTranscriber(
    apiKey = { "test-key" },
    json = Json { ignoreUnknownKeys = true },
    reconnect = policy,
    transportFactory = { transport },
    dispatcher = dispatcher,
) {
    override val url = "wss://test.invalid/stream"
    override val providerName = "Test"

    val transcribed = mutableListOf<String>()

    override fun handleMessage(text: String) {
        if (text.startsWith("ERROR: ")) {
            failSession(text.removePrefix("ERROR: "))
        } else {
            transcribed += text
            accumulator.appendFinal(text)
        }
    }
}

/** One scripted websocket session for [FakeTransport]. */
private data class FakeSession(
    /** Non-null: the connect attempt throws with this message instead of opening. */
    val connectFailure: String? = null,
    /** Text frames delivered to the client right after the session opens. */
    val frames: List<String> = emptyList(),
    /** Close reason returned once the session ends (null = clean close). */
    val closeReason: String? = null,
    /** Whether the session stays open until the client closes it, or ends on its own. */
    val holdOpen: Boolean = true,
)

/** Scripted stand-in for the real websocket transports; runs one [FakeSession] per connect. */
private class FakeTransport(private val script: List<FakeSession>) : WsTransport {

    var runs = 0
        private set

    override suspend fun run(
        url: String,
        subprotocols: List<String>,
        headers: Map<String, String>,
        onText: (String) -> Unit,
        session: suspend CoroutineScope.(WsSession) -> Unit,
    ): String? {
        val step = script.getOrElse(runs) { script.last() }
        runs++
        step.connectFailure?.let { throw RuntimeException(it) }

        val closedByClient = CompletableDeferred<Unit>()
        val ws = object : WsSession {
            override suspend fun sendBinary(bytes: ByteArray) = Unit
            override suspend fun sendText(text: String) = Unit
            override suspend fun close() {
                closedByClient.complete(Unit)
            }
        }
        val sessionScope = CoroutineScope(coroutineContext + Job())
        sessionScope.session(ws)
        return try {
            step.frames.forEach(onText)
            if (step.holdOpen) closedByClient.await()
            step.closeReason
        } finally {
            sessionScope.cancel()
        }
    }
}
