# Articles

Long-form drafts about the live-caption pipeline. The historical studies use the local,
gitignored evaluation archive; the [2026 vendor refresh](../../eval/VENDOR_REFRESH_RESULTS_2026.md)
commits aggregate results and methods without publishing audio or transcripts. Both drafts
include the final PR #82 preference/menu behavior and the PR #110 vendor measurements.

| Article | Subject | Primary sources |
|---|---|---|
| [The Metric Was the Bug](the-metric-was-the-bug.md) | The evaluation harness. Same-engine bias in chrF, the different-engine floor, the batch ceiling, and why domain context beat every other lever | [`eval/`](../../eval), [`eval/reports/metric_calibration.hu.md`](../../eval/reports/metric_calibration.hu.md), [`eval/reports/scorecard.md`](../../eval/reports/scorecard.md), [`eval/reports/asr_context.md`](../../eval/reports/asr_context.md) |
| [The Audio Tap That Never Fires](the-audio-tap-that-never-fires.md) | The multiplatform media pipeline. Historical Fold3 ASR and new Pixel 7a Moonshine/Whistle measurements, PCM taps on Android/iOS/web, the HLS workaround, cinterop, and the ephemeral-key service | `composeApp/src/*Main`, `composeApp/native/avplayer/cinterop`, [`server/`](../../server), [2026 native benchmarks](../../eval/native/README.md) |

Read them in that order. The first establishes *why* the system is shaped the way it is, and
the second covers how it was built.
