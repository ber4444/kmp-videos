"""PCM experiment helpers. The averaging path mirrors PcmResampling.kt exactly."""
from array import array
import math
from pathlib import Path
import sys
import wave

TARGET_RATE = 16000


def pack_s16(samples):
    data = array('h', samples)
    if sys.byteorder != 'little':
        data.byteswap()
    return data.tobytes()


def decimate(samples, in_rate, arm):
    if in_rate < TARGET_RATE or arm not in ('avg', 'nearest'):
        raise ValueError('Expected downsampling with avg or nearest')
    frames = len(samples) * TARGET_RATE // in_rate
    if frames == 0:
        return b''
    if in_rate == TARGET_RATE:
        return pack_s16(samples)
    step = len(samples) / frames
    out = []
    for i in range(frames):
        start = int(i * step)
        if arm == 'nearest':
            out.append(samples[start])
        else:
            end = min(len(samples), max(start + 1, int((i + 1) * step)))
            # Kotlin Int arithmetic wraps before division and truncates toward zero.
            total = (sum(samples[start:end]) + 2**31) % 2**32 - 2**31
            out.append(int(total / (end - start)))
    return pack_s16(out)


def read_pcm(path):
    with wave.open(str(path), 'rb') as w:
        if w.getnchannels() != 1 or w.getsampwidth() != 2 or not w.getnframes():
            raise ValueError('Expected nonempty mono signed 16-bit WAV')
        rate = w.getframerate()
        samples = array('h')
        samples.frombytes(w.readframes(w.getnframes()))
    if sys.byteorder != 'little':
        samples.byteswap()
    return samples, rate


def write_pcm(path, data, rate=TARGET_RATE):
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(data)


def reference_pcm(samples, in_rate, out_rate=TARGET_RATE):
    # Import only for preparation; offline score/tests do not require scipy.
    import numpy as np
    from scipy.signal import resample_poly
    divisor = math.gcd(in_rate, out_rate)
    values = resample_poly(np.asarray(samples, dtype=np.float64), out_rate // divisor, in_rate // divisor)
    return pack_s16(np.clip(np.rint(values), -32768, 32767).astype(np.int16))


def source_similarity(original, candidate):
    """Peak normalized waveform correlation, allowing decoder/seek offset drift.

    Inspect every eighth 16 kHz sample (2 kHz comparison). This is a conservative
    identity check for an existing excerpt, not a perceptual quality metric.
    """
    import numpy as np
    from scipy.signal import correlate
    a = np.asarray(original, dtype=np.float64)[::8]
    b = np.asarray(candidate, dtype=np.float64)[::8]
    if not len(a) or not len(b):
        return 0.0
    a = a - a.mean()
    b = b - b.mean()
    denominator = np.linalg.norm(a) * np.linalg.norm(b)
    return float(np.max(np.abs(correlate(a, b, method='fft'))) / denominator) if denominator else 0.0
