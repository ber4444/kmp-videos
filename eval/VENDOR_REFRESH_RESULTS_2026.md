# Vendor refresh measurements

Recorded November 20–21, 2026. Offline recomputation from the same 21 verified golden clips.

## Soniox recognition

| Metric | Historical v5 | v6 |
|---|---:|---:|
| Mean per-clip normalized WER | 0.242 | 0.268 |
| Corpus normalized WER | 0.159 | 0.184 |
| Mean Entity F1 (12 clips with terms) | 0.770 | 0.680 |
| Streaming WER (21 clips) | 0.309 | 0.293 |
| Flicker | 0.228 | 0.237 |
| Mean per-clip median final latency, seconds | 5.77 | 6.09 |
| Mean per-clip p95 final latency, seconds | 6.78 | 7.14 |

Batch v6 is worse on both WER aggregations; streaming WER improves while flicker and finalization latency worsen. Latency uses provider timestamps and token finalizations, not forced alignment. Legacy batch fixtures lack stored model provenance; the historical v5 label follows the previous provider code. Streaming fixtures explicitly record v5/v6.

## Async in-band translation

| Target | No context, DeepL ideal | App context, DeepL ideal | No context, Gemini ideal | App context, Gemini ideal |
|---|---:|---:|---:|---:|
| hu (n=21) | 52.5 | 54.1 | 54.8 | 56.4 |
| ru (n=21) | 53.9 | 54.6 | 57.9 | 59.8 |

App context is the merged PR #82 DOMAIN text, 49 terms, and target-language mappings, read directly from Kotlin. Existing DeepL and Gemini reference translations were reused; no reference engine was called. These are whole-file async measurements, not real-time caption quality or latency.

| Historical arm | HU chrF vs DeepL | RU chrF vs DeepL |
|---|---:|---:|
| soniox-translate-batch (n=21 each) | 56.4 | 55.1 |
| soniox-translate (n=21 each) | 54.4 | 53.2 |

Historical async requests did not preserve their context; the real-time arm used different/partly missing session configuration. These rows are historical comparisons, not controlled v5→v6 deltas. Different-engine reference overlap is not a human quality ceiling.

## Pixel 7a standalone recognition

| Model | Mean normalized WER | Corpus WER | Median warm compute RTF | Maximum warm compute RTF |
|---|---:|---:|---:|---:|
| moonshine (n=21) | 0.549 | 0.463 | 0.113 | 0.147 |
| whistle (n=21) | 0.643 | 0.476 | 0.026 | 0.130 |

Pixel 7a, Tensor G2/GS201, Android 17, arm64. Two runs per clip with the model resident; the second supplies WER and warm RTF. Engines ran sequentially. RTF excludes load and pacing sleeps. Neither an HLS/Compose concurrency test nor a Fold3 repeat was performed.

| Real-time-paced smoke on evt8-clip-1 | Run 1 WER / compute RTF | Run 2 WER / compute RTF |
|---|---:|---:|
| moonshine (one 60-second clip) | 0.723 / 0.291 | 0.723 / 0.293 |
| whistle (one 60-second clip) | 0.957 / 0.194 | 0.957 / 0.192 |

A streaming smoke verifies chunk feeding and final flush on one clip; it is not a corpus streaming WER or a word-finalization latency estimate. See [native benchmark setup](native/README.md) for exact runtime, model, compiler and pacing settings.

## Reproduce

Run `venv/bin/python scripts/summarize_vendor_refresh.py` from `eval/` with the original local fixtures and complete candidate recordings. Missing inputs fail rather than yielding a partial full-set table. Raw audio, transcripts and responses remain gitignored; this document and its JSON companion contain aggregate measurements only.

Manifest SHA-256: `47ec4f19ae7b5f67eea1a234694bcf3c5644493bbef7950e6c3da3fd46574282`.

Input fingerprint: `39a09fc01b368372f8cee41a45562391e43634ddaa543906b5b3542bade5a224`.
