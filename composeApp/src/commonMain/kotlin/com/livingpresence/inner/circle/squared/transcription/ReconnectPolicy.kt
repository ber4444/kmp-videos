package com.livingpresence.inner.circle.squared.transcription

/**
 * Backoff schedule for a streaming-ASR websocket that dropped mid-video.
 *
 * Cloud ASR sockets fail routinely on a long stream — Soniox closes an idle stream with
 * "Request timeout", networks hiccup, providers recycle backends — and the video keeps
 * playing regardless, so the only useful response is to reconnect and carry on. The
 * schedule is exponential (so a provider that is genuinely down isn't hammered) but
 * resets after any session that stayed up for [healthySessionMs], which is the common
 * case: a socket that ran for minutes and then timed out reconnects almost immediately
 * rather than inheriting the backoff of an unrelated failure ten minutes earlier.
 *
 * @param initialDelayMs delay before the first retry.
 * @param maxDelayMs ceiling for the exponential growth.
 * @param healthySessionMs how long a session must last to count as healthy; failures
 *   after one of those restart the schedule from [initialDelayMs].
 */
class ReconnectPolicy(
    val initialDelayMs: Long = 500L,
    val maxDelayMs: Long = 10_000L,
    val healthySessionMs: Long = 15_000L,
) {

    /**
     * The consecutive-failure count after a session that lasted [sessionDurationMs] ended,
     * given [previousFailures] before it. A healthy session resets the count to 1 (this
     * failure), so the next retry is a short one.
     */
    fun failuresAfter(previousFailures: Int, sessionDurationMs: Long): Int =
        if (sessionDurationMs >= healthySessionMs) 1 else previousFailures + 1

    /** How long to wait before retry number [failures] (1-based), capped at [maxDelayMs]. */
    fun delayFor(failures: Int): Long {
        if (failures <= 1) return initialDelayMs.coerceAtMost(maxDelayMs)
        var delay = initialDelayMs
        repeat(failures - 1) {
            if (delay >= maxDelayMs) return maxDelayMs
            delay *= 2
        }
        return delay.coerceAtMost(maxDelayMs)
    }
}
