package com.livingpresence.inner.circle.squared.transcription

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The bug guarded here: Soniox reports an unsupported `target_language` (or any other
 * config-validation problem) as an `error_message` frame that looks identical to a routine,
 * retryable one — a dropped socket or a request timeout. Treating it like a dropped socket
 * means [WebSocketTranscriber] reconnects forever with the same rejected config instead of
 * giving up, and [SonioxClient.onTerminalFailure]'s default would also leave an error line
 * frozen in the caption strip for the rest of the video. Soniox's own error-code families are
 * what separate the two: 400 is "this config is wrong," 408/429/503 are "try again."
 */
class SonioxErrorHandlingTest {

    @Test
    fun aConfigErrorIsRecognizedAsTerminal() {
        val message = sonioxFailureMessage(400, "Invalid language in translation.target_language.")
        assertTrue(isSonioxConfigError(message), "a 400 is Soniox's config-validation class")
    }

    @Test
    fun aRequestTimeoutIsNotTreatedAsAConfigError() {
        val message = sonioxFailureMessage(408, "Request timeout.")
        assertFalse(isSonioxConfigError(message), "408 is routine on a long idle stream")
    }

    @Test
    fun aRateLimitIsNotTreatedAsAConfigError() {
        val message = sonioxFailureMessage(429, "Concurrent requests limit has been exceeded.")
        assertFalse(isSonioxConfigError(message))
    }

    @Test
    fun aServiceUnavailableErrorIsNotTreatedAsAConfigError() {
        val message = sonioxFailureMessage(503, "Cannot continue request.")
        assertFalse(isSonioxConfigError(message))
    }

    @Test
    fun aMissingErrorCodeIsNotTreatedAsAConfigError() {
        // Defense in depth: an unrecognized or absent error_code should retry like any other
        // provider blip, not silently give up on a code this function has never seen.
        val message = sonioxFailureMessage(null, "Something Soniox hasn't documented yet.")
        assertFalse(isSonioxConfigError(message))
    }
}
