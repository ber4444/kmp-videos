package com.livingpresence.inner.circle.squared.transcription

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for the ASR reconnect schedule.
 *
 * The behaviour that matters on a long video: a socket that ran fine for minutes and then
 * timed out must come back almost immediately, while a provider that refuses every attempt
 * must be backed off — both are decided here, not in the websocket loop.
 */
class ReconnectPolicyTest {

    private val policy = ReconnectPolicy(initialDelayMs = 500, maxDelayMs = 10_000, healthySessionMs = 15_000)

    @Test
    fun backsOffExponentiallyUpToTheCeiling() {
        assertEquals(500L, policy.delayFor(1))
        assertEquals(1_000L, policy.delayFor(2))
        assertEquals(2_000L, policy.delayFor(3))
        assertEquals(4_000L, policy.delayFor(4))
        assertEquals(8_000L, policy.delayFor(5))
        assertEquals(10_000L, policy.delayFor(6))
        assertEquals(10_000L, policy.delayFor(50))
    }

    @Test
    fun treatsAZerothRetryAsTheFirstDelay() {
        assertEquals(500L, policy.delayFor(0))
        assertEquals(500L, policy.delayFor(-3))
    }

    @Test
    fun neverExceedsTheCeilingEvenWhenItIsBelowTheInitialDelay() {
        val tight = ReconnectPolicy(initialDelayMs = 5_000, maxDelayMs = 1_000)
        assertEquals(1_000L, tight.delayFor(1))
        assertEquals(1_000L, tight.delayFor(4))
    }

    @Test
    fun aHealthySessionResetsTheSchedule() {
        // Four failures deep, then a session that stayed up: the next failure retries fast.
        assertEquals(1, policy.failuresAfter(previousFailures = 4, sessionDurationMs = 15_000))
        assertEquals(500L, policy.delayFor(policy.failuresAfter(4, sessionDurationMs = 40 * 60 * 1000L)))
    }

    @Test
    fun consecutiveShortFailuresKeepEscalating() {
        assertEquals(1, policy.failuresAfter(previousFailures = 0, sessionDurationMs = 40))
        assertEquals(2, policy.failuresAfter(previousFailures = 1, sessionDurationMs = 40))
        assertEquals(5, policy.failuresAfter(previousFailures = 4, sessionDurationMs = 14_999))
    }
}
