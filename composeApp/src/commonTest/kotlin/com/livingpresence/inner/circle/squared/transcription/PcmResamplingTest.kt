package com.livingpresence.inner.circle.squared.transcription

import kotlin.test.Test
import kotlin.test.assertContentEquals

internal class PcmResamplingTest {
    @Test
    internal fun fixed48SampleVectorMatchesPythonPort() {
        val input = shortArrayOf(
            -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6,
            32767, -32768, 0, -7, 1, 1, 7, -1, -1, -32768, -32768, -32768,
            32767, 32767, 32767, 0, 0, 0, 100, 200, 300, -100, -200, -301,
            17, 18, 19, -17, -18, -20, 1000, -1000, 2, -1000, 1000, -2,
        )
        // Same literal bytes as eval/tests/test_resampling.py; negative remainders
        // deliberately distinguish Kotlin truncation from Python floor division.
        val expectedHex = "fcffffff020005000000ffff01000080ff7f0000c80038ff1200eeff00000000"
        val expected = expectedHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertContentEquals(expected, input.resampleTo16kMonoS16(48_000))
    }

    @Test
    internal fun nonIntegerStepTruncatesNegativeAveragesTowardZero() {
        val input = shortArrayOf(-7, 1, 1, 1, 2, 3, 4, 5, 6, 7, 8)
        assertContentEquals(
            byteArrayOf(-1, -1, 2, 0, 6, 0),
            input.resampleTo16kMonoS16(44_100),
        )
    }
}
