# 2026 Vendor Refresh

Tracking doc for re-checking two conclusions that predate a year of vendor releases:
the Soniox numbers in [`scorecard.md`](reports/scorecard.md) and
[`the-metric-was-the-bug.md`](../docs/articles/the-metric-was-the-bug.md) were measured
against `stt-rt-v5` / `stt-async-v5`, and the on-device conclusion in
[`the-audio-tap-that-never-fires.md`](../docs/articles/the-audio-tap-that-never-fires.md) (which cites an "ADR 0001" at
`docs/adr/0001-cloud-streaming-asr-for-live-captions.md` that isn't actually committed to this
repo — a pre-existing dead link in the drafts, flagged here rather than fixed, since it's not
this doc's job) was measured against Vosk and whisper.cpp on a Fold3, before Moonshine v2 and
Whistle existed.

Following the harness's own honesty convention: a blank is never a guess. Cells not yet
measured read **n/a (not run)**, same as the scorecard.

## Goal

1. **Rerun the Soniox arm against v6** (`stt-rt-v6` / `stt-async-v6`, released 2026-11-03) and
   see whether the chrF/WER table in the scorecard moves enough to change anything the articles
   concluded.
2. **Bench Moonshine v2 and Whistle** on the same Fold3 (Snapdragon 888) that killed Whisper in
   ADR 0001, to see whether either changes the on-device conclusion.

## Out of scope (checked, no action needed)

- **Deepgram** — Nova-3 Multilingual improved WER substantially, but still has no translation
  API. The second reason it was switched off (no in-band translation) hasn't changed, so a
  quality-only re-bench wouldn't change the decision.
- **ElevenLabs Scribe v3 Live** — transcription-only by design. Using it would reintroduce the
  two-stage architecture `the-metric-was-the-bug.md` already priced out.
- **Gemini Live Translate / DeepL Voice-to-Voice** — both output synthesized speech, not text.
  Different product shape than live text captions; not comparable to this feature.
- **NVIDIA Parakeet TDT** — chunked streaming rather than native, 0.6B params. Likely still too
  heavy to coexist with HLS decode + 60fps Compose on the hardware budget the article already
  established as tight. Worth a line in the table below for completeness, not worth a device
  bench yet.

## Part 1 — Soniox v5 (shipped) vs v6 (candidate)

| Metric | v5 (shipped, current scorecard) | v6 (candidate) |
|---|---:|---:|
| WER (Norm), overall | 0.242 | n/a (not run) |
| Entity F1, overall | 0.770 | n/a (not run) |
| Streaming WER | 0.309 | n/a (not run) |
| Flicker | 0.228 | n/a (not run) |
| Final latency (med / p95) | 5.77s / 6.78s | n/a (not run) |
| In-band chrF, hu (context) | 54.4 (n=21) | n/a (not run) |
| In-band chrF, ru (context) | 53.2 (n=21) | n/a (not run) |
| Two-stage (cross-engine) chrF, hu | 57.2 (n=21) | n/a (not run) |
| Different-engine floor, hu | 68.1 (n=21) | n/a (not run) |

v6's own release notes claim better alphanumeric preservation (dates, currencies, IDs) and
better handling of mid-sentence language switching — both are exactly the axis
`asr_context.md` found worth the most points (phrasing/register context), so there's a
specific reason to expect the context-vs-no-context delta to move, not just the raw WER.

**Pros of rerunning now:** same harness, same fixtures, same golden set — this is a version
string in two places (`providers/soniox.py`) plus a record/score cycle, not new plumbing.

**Cons:** `record.py` / `record_stream.py` / `translate.py` are billed. A full 21-clip rerun
across batch + streaming + translation is the same spend as the original run that produced the
current scorecard.

## Part 2 — On-device ASR

| Model | RTF on Fold3 (Snapdragon 888) | WER | Multilingual | License | Notes |
|---|---:|---:|---|---|---|
| whisper.cpp `base.en` (measured, ADR 0001) | ~2.0× (real time = 1.0×) | n/a (not measured on this set) | No | MIT | Fragmentary output from 30s fixed windows; this is the number the on-device path was killed on. |
| Moonshine v2 (medium, streaming) | n/a (not run) | n/a (not run) | **No** (English-first) | MIT | Vendor claims ~107ms latency vs Whisper's ~11s on desktop hardware, native variable-length streaming instead of fixed windows — the specific thing that made Whisper's output fragmentary. Desktop number, not a Fold3 number. English-only means it could at most unlock an on-device *English transcript* tier, not the 22-language translated-caption feature. |
| Whistle (Cactus Compute) | n/a (not run) | n/a (not run) | **Yes** (7 European languages) | Apache 2.0 | 16.9MB, single file, no dependencies. The one on-device candidate that doesn't share Moonshine's English-only limit — worth benching for that reason even though it's newer and less proven. |
| NVIDIA Parakeet TDT 0.6B v3 | n/a (not run, not planned) | n/a | No (per-language models) | CC-BY-4.0 | Accuracy/throughput leader on server hardware; chunked streaming, not native, and the 0.6B size is likely still too heavy next to HLS decode + Compose compositing. Listed for completeness only. |

**Pros of benching Moonshine v2 / Whistle:** both are architected against the exact failure
mode the article documented (fixed-window chunking causing fragmentary captions), not just a
faster version of the same architecture Whisper used. A desktop sanity check is free (CPU-only,
no device needed) before spending Fold3 time.

**Cons:** neither is wired into this repo today — there's no provider adapter, no `sherpa-onnx`
dependency, and the Fold3 bench needs an APK or adb-pushed binary, not just a Python script.
This is new plumbing, not a rerun of existing plumbing, so it's a bigger lift than Part 1.
Moonshine's English-only limitation also means a "win" here doesn't replace Soniox — it would
only matter for a scenario where translation isn't needed.

## Execution plan — small steps, in order

Each step is meant to be its own session. Steps 1–2 cost nothing. Steps 3+ spend either API
credits (Soniox/DeepL) or device time; confirm before running them.

- [x] **Step 0.** Open this doc + a draft PR stating the two goals. *(this PR)*
- [x] **Step 1 (free).** Bump `model="stt-async-v5"` → `stt-async-v6` and
      `model="stt-rt-v5"` → `stt-rt-v6` in `eval/providers/soniox.py`. *(this PR — no API call
      made, just the version string; the SDK accepting it is still unverified.)*
- [ ] **Step 2 (free).** Smoke-test: one clip through `record.py` restricted to Soniox only, to
      confirm v6 returns a well-formed response before spending on the full set.
- [ ] **Step 3 (billed, small).** `record.py` + `record_stream.py` for Soniox v6 across the full
      21-clip golden set. Regenerate `scorecard.md`'s overall WER/Entity F1/streaming rows.
- [ ] **Step 4 (billed, small).** `translate.py` rerun against the v6 fixtures. Regenerate the
      In-Band Translation Quality table (hu, ru).
- [ ] **Step 5 (free).** Diff v6 numbers against this doc's v5 column. If nothing material
      moved, say so in the scorecard and close this out. If something moved past the floor
      established in `the-metric-was-the-bug.md`, flag it before touching the article text.
- [ ] **Step 6 (free).** Desktop sanity check: run one golden clip through Moonshine v2
      (`sherpa-onnx`, CPU) and Whistle locally, no device involved. Confirms both actually run
      on this audio before spending device time.
- [ ] **Step 7 (device time).** Push Moonshine v2 to the Fold3, measure RTF and WER on the same
      clip(s) ADR 0001 used for whisper.cpp.
- [ ] **Step 8 (device time).** Repeat Step 7 for Whistle.
- [ ] **Step 9 (free).** Fold whichever numbers came back into this doc, and only then decide
      whether ADR 0001 or either article needs a correction.
