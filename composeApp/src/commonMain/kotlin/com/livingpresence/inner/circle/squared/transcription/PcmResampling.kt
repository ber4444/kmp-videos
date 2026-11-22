package com.livingpresence.inner.circle.squared.transcription

/** Target PCM sample rate the streaming ASR providers require (16 kHz mono s16le). */
internal const val TARGET_SAMPLE_RATE_HZ = 16_000

/**
 * Averaging-decimates this mono 16-bit PCM signal from [inRate] to
 * [TARGET_SAMPLE_RATE_HZ] and packs it as signed 16-bit little-endian bytes — the
 * format Deepgram/Soniox expect (see [StreamingTranscriber.feedPcm]). This is a
 * simple averaging filter: for 48 kHz input, three-sample averages attenuate
 * 8 kHz by about 3.5 dB and 12 kHz by about 10 dB.
 *
 * Down-mixing to mono is the caller's responsibility, since the source buffer
 * layout (Core Media float pointer / JS `FloatArray` / Android `ByteBuffer`) is
 * platform-specific; only this rate-conversion + byte-packing is shared.
 */
internal fun ShortArray.resampleTo16kMonoS16(inRate: Int): ByteArray {
    val outFrames = if (inRate == TARGET_SAMPLE_RATE_HZ) size
        else (size.toLong() * TARGET_SAMPLE_RATE_HZ / inRate).toInt()
    if (outFrames <= 0) return ByteArray(0)

    val out = ShortArray(outFrames)
    if (inRate == TARGET_SAMPLE_RATE_HZ) {
        copyInto(out, endIndex = outFrames)
    } else {
        val step = size.toDouble() / outFrames
        for (i in 0 until outFrames) {
            val start = (i * step).toInt()
            val end = ((i + 1) * step).toInt().coerceIn(start + 1, size)
            var sum = 0
            for (j in start until end) sum += this[j].toInt()
            out[i] = (sum / (end - start)).toShort()
        }
    }

    val result = ByteArray(out.size * 2)
    for (i in out.indices) {
        val s = out[i].toInt()
        result[i * 2] = (s and 0xFF).toByte()
        result[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
    }
    return result
}
