# STT Provider Eval Harness

A reproducible, pytest-driven harness that scores **Deepgram** and **Soniox** against
a domain-specific golden set of event-stream audio, producing a per-provider
[`reports/scorecard.md`](reports/scorecard.md): batch accuracy (WER/CER), domain-term
recall (Entity F1), keyterm-boosting impact, and real-time streaming realism (flicker
+ finalization latency).

## Setup

1. Create and activate a virtual environment, then install deps:
   ```bash
   python3 -m venv venv
   source venv/bin/activate
   pip install -r requirements.txt
   ```
2. Provide API keys via a gitignored `.env` (sourced by the scripts — use `export` so
   they reach the Python process):
   ```bash
   export DEEPGRAM_API_KEY=your_key
   export SONIOX_API_KEY=your_key
   ```

## Running the pipeline

Everything except the two spending scripts runs offline from `fixtures/` and costs $0.

| Step | Command | Cost | Notes |
|---|---|---|---|
| Fetch clips | `python scripts/fetch_clips.py` | free | pulls the manifest's segments from Wowza, decodes to 16 kHz mono WAV |
| Bootstrap refs | `python scripts/bootstrap_refs.py` | free | 2-of-3 consensus draft refs + disagreement report (human verifies) |
| Record **batch** | `python scripts/record.py` | 💲 live | writes `fixtures/{provider}[-boost]/` |
| Record **streaming** | `python scripts/record_stream.py [--max-clips N]` | 💲 live | real-time paced websocket sessions → `fixtures/{provider}-stream/` |
| Translate | `python scripts/translate.py [--target DE]` | 💲 live | DeepL-translates each hypothesis + the reference → `fixtures/translations/`; needs `DEEPL_API_KEY` |
| Record **in-band translation** | `python scripts/record_translate.py --targets hu[,ru] [--variant all]` | 💲 live | Soniox translating on the socket, as the app does → `fixtures/soniox-translate[-nocontext\|-batch\|-endpointed]/{lang}/`; `--variant all` adds the context ablation, the batch ceiling and the endpointing arm; needs `SONIOX_API_KEY` |
| **Calibrate the metric** | `python scripts/calibrate_metric.py --target hu` | 💲 cents | translates the reference with a second engine to measure how much of any score is engine resemblance rather than quality → `reports/metric_calibration.{lang}.md`; needs `GEMINI_API_KEY` |
| Score | `python scoring/scorecard.py` | free | regenerates `reports/scorecard.md` from fixtures |

`./run_eval.sh` chains the free steps + batch record + score, skipping work whose
fixtures already exist.

## Metric definitions

**Batch**
- **WER (Norm)** — word error rate after the Whisper English text normalizer (casing,
  punctuation, number forms); comparable to the HF Open ASR Leaderboard.
- **WER (Fmt)** — word error rate on the raw formatted text (punctuation/casing count).
- **CER (Norm)** — character error rate on normalized text.
- **Entity F1** — precision/recall/F1 over each clip's `domain_terms` (esoteric event
  vocabulary), reported alongside a baseline-vs-**keyterm-boosted** comparison.

**Streaming (live-caption realism)**
- **Streaming WER** — WER of the final streamed transcript (vs the batch WER).
- **Flicker** — fraction of already-shown caption characters that were later rewritten
  or retracted across successive partials. `0` = captions only ever append.
- **Finalization latency (med / p95)** — per word, wall-clock time from spoken to
  finalized. Measured against each provider's **self-reported** word timestamps;
  forced-alignment ground truth (plan Phase 5) is intentionally **not wired yet**.

**In-band translation (Soniox, per language)**
- What the player actually shows: Soniox translating on the same websocket, real-time paced,
  with the session `context` the app sends. That context is **parsed out of the app's own
  `CaptionGlossary.kt`** by `app_context.py` (domain sentence + boosted terms + accepted
  renderings) rather than restated here, so the eval cannot drift from what ships.
- Scored as chrF against the **same ideal** as everything else: DeepL's translation of the
  verified reference. Columns per language:
  - **in-band (context)** — what ships;
  - **in-band (no context)** — the same audio with the context withheld, so the glossary's
    contribution is measured rather than assumed (`--variant both` records the pair);
  - **batch (full context)** — the same model and context through the async API, which reads
    the whole clip before answering. **Δ streaming** is what committing a translation before
    the sentence ends costs, and it is the ceiling on *every* latency-buying mechanism there
    is: client-side sentence buffering, re-translating on sentence end, or the vendor's own
    endpointing knobs. Batch has the whole clip, so none of them can beat it. A small Δ means
    no client change will move this language. It is also the cheap arm: no real-time pacing;
  - **two-stage (cross-engine)** — the Soniox transcript translated by an engine that is *not*
    the one that wrote the ideal, so it carries the same handicap as in-band and the two can
    honestly be subtracted. **This is the column that decides whether a second MT vendor earns
    its cost**;
  - **different-engine floor** — a second engine translating the verified reference. Perfect
    input, no ASR error, so everything below 100 is the price of not being the engine that
    wrote the ideal. Read every other column against this, not against 100.
- Every Δ is a **per-clip mean over the clips both arms recorded**. Averaging two arms over
  different clip sets and subtracting the means reports a difference between samples as if it
  were an effect.
- **`Soniox → DeepL` is reported separately and never differenced against in-band.** It is
  DeepL output scored against a DeepL ideal, so it collects a same-engine bonus no other arm
  can — measured at **+19.4 chrF** on Hungarian. Subtracting it from in-band measures which
  engine wrote the answer key, not translation quality.
- **Endpoint detection** (`--variant endpointed`) is judged on **flicker and finalization
  latency, not chrF**, since the batch arm already bounds its effect on the words. Captions
  that stop rewriting themselves are worth having on their own.
- Needs `scripts/translate.py --target <LANG>` to have run for the same language first — that
  is where the ideal comes from — and `scripts/calibrate_metric.py --target <lang>` for the
  floor and two-stage columns. Soniox codes are lowercase (`hu`), DeepL's are not (`HU`);
  `config.deepl_target()` maps between them.
- chrF is character-n-gram based, so it does not punish an agglutinative language for
  inflecting differently the way BLEU would. **Absolute values are not comparable across
  languages** — the comparisons within one row are. For a metric without the same-engine
  bias at all, `scoring/semantic.py` adds COMET; it is opt-in (`pip install -r
  requirements-comet.txt`, `EVAL_COMET=1`) because it pulls torch and a 2.3 GB checkpoint.

**Translation fidelity (ASR → DeepL)**
- Each provider's transcript is translated with DeepL and compared to the translation
  of the **verified reference** (the ideal translation from perfect ASR). This measures
  how much ASR error actually survives machine translation for a given clip.
- **chrF vs ideal** — sacrebleu chrF (0–100, higher = closer to the ideal translation);
  language-agnostic and morphology-aware, no ML model.
- **Post-MT WER** — word error rate on the raw target-language text, directly
  comparable to the source-side WER so error amplification is visible.

## Record/replay & cost

- **Record/replay is the default posture.** Scoring, tests, and CI read only from
  `fixtures/` and never spend. Only `record.py` / `record_stream.py` (and `pytest -m
  live`) hit provider APIs.
- Hard cap of **90 minutes** of billed audio per invocation without `--force`
  (`config.MAX_BILLED_AUDIO_SECONDS`). Streaming is real-time paced, so wall-clock ≈
  billed audio ≈ audio duration × providers.
- Never commit `clips/`, `refs/`, `fixtures/`, `align/`, or `.env` (all gitignored).

## Honesty convention

Only report numbers from runs that actually executed. If a step could not run (missing
key, missing clip), the scorecard cell reads `n/a (not run)` — never an estimate.
Anything aspirational in docs gets one explicit "not wired yet" sentence.

## Transcript headline (real run, n=21)

**Telling Soniox the vocabulary in advance is worth more than every translation-side lever
combined.** Same audio, same model, only the session `context` changes
([`scripts/record_asr_context.py`](scripts/record_asr_context.py)):

| Context | WER | Caption chrF (hu) |
|---|---|---|
| none | 0.242 | 54.9 |
| the app's 49-term glossary | 0.205 | 57.0 (+2.0) |
| this clip's distinctive vocabulary | 0.192 | 59.7 (+4.7) |
| this clip's full reference prose | 0.103 | 63.7 (+8.8) |

Against a different-engine floor of 68.1, the last row closes about two-thirds of the gap —
where latency, engine choice and the static glossary are each worth 2–3. The `oracle-*` rows
are not shippable (their vocabulary comes from the reference); they bound what any advance
supply of vocabulary could do, slide OCR included. Note that a *word list* gets only a third
of it: the rest comes from knowing the phrasing.

Also fixed here: Soniox keyterm boosting was a commented-out guess at a `speech_context`
field the SDK does not have, so the `-boost` arm silently re-ran the baseline and the
scorecard reported Soniox boosting as worthless. It is worth −0.037 WER.

## Translation headline (real run, n=21 per language)

**Every lever is small; the transcript is the bottleneck.** In-band ships at 54.4 (hu) /
53.2 (ru) against a different-engine floor of 68.1 / 66.2. Of that ~13-point gap, the
glossary is worth +2.5 (hu) but only +0.6 (ru), *every* latency-buying scheme is capped at
+2.1/+2.0 (the batch ceiling bounds client buffering and vendor endpointing alike), and
switching translation engine is worth +2.8/+5.1. The ~+19.7 that once appeared to favour a two-stage path was the
same-engine bonus, priced at +19.4 (hu) and +18.1 (ru) by
[`scripts/calibrate_metric.py`](scripts/calibrate_metric.py). Endpoint detection moved
flicker by 0.000 — the translated arm never flickers, because Soniox sends translated tokens
only as final.

## Current headline (real run)

Deepgram is **meaningfully worse than Soniox** on this material — ~45% higher normalized
WER (0.350 vs 0.242) and much lower Entity F1 (0.49 vs 0.77), i.e. it misses the domain
vocabulary far more often. Keyterm boosting narrows the gap on individual clips but does
not close it. See [`reports/scorecard.md`](reports/scorecard.md) for the full breakdown.

## Model refresh recording

For a model refresh, keep the historical fixtures and reports intact. From `eval/`:

```bash
export EVAL_FIXTURES_DIR="$PWD/fixtures/vendor-refresh-2026-v6"
export EVAL_REPORTS_DIR="$PWD/reports/vendor-refresh-2026-v6"
python scripts/record.py --provider soniox --max-clips 1 --baseline-only --dry-run
python scripts/record_stream.py --provider soniox --max-clips 1 --dry-run
```

Remove `--dry-run` to make the billed calls. `--provider` accepts one or both
providers; `--max-clips` selects the first N manifest entries; batch recording also
offers `--baseline-only`. Existing outputs are skipped and only pending calls count
toward the audio cap, including boosted calls. A provider error stops recording with
a nonzero exit status. Keep the same directory exports when replaying, translating,
or scoring that run; unset both to return to the historical defaults. See
[`VENDOR_REFRESH_2026.md`](VENDOR_REFRESH_2026.md) for scope and measured results.

## PCM resampling experiment

`eval/scripts/eval_resampling.py` compares the app's averaging decimator with
nearest-sample decimation and `scipy.signal.resample_poly`. It re-fetches the
verified manifest segments at their native sample rate: the existing 16 kHz
golden WAVs cannot measure the app's conversion. From the repo root:

```bash
# Use eval/venv/bin/python, with dependencies in requirements.txt plus:
python -m pip install -r eval/requirements-resampling.txt
# Export WOWZA_HOST (scheme excluded) and SONIOX_API_KEY from local configuration.
python eval/scripts/eval_resampling.py fetch
python eval/scripts/eval_resampling.py prepare
# prepare checks the native/reference-resampled waveforms against the archived golden audio.
# Recheck an existing prepared run with: python eval/scripts/eval_resampling.py validate
python eval/scripts/eval_resampling.py record --dry-run
python eval/scripts/eval_resampling.py record
python eval/scripts/eval_resampling.py score
```

Preparation requires a waveform source match against the archived golden clips.
A URL that now serves replacement footage cannot reuse the old references: the
run is invalid, recording is refused, and scoring prints unavailable metrics.

Only `record` calls Soniox (`stt-async-v6`, recognition only, the app's English
context parsed by `app_context.py`). Before any call, it validates all cached
response identities and sums the actual duration of every pending arm against
the same 90-minute cap as `recording.py`; it has no force override. Responses
include the audio SHA-256, model, context, source rate and arm, and a provider
failure stops the run. Repeating `record` skips matching cached responses.
Native audio, prepared arms and responses live in gitignored
`eval/fixtures/resampling/`; `--root` selects a separate run. The report goes to
`eval/reports/resampling.md`, with paired per-clip deltas, win counts and
exploratory paired bootstrap intervals. It contains aggregate values only. The attempted November 2026 run is recorded in
[`snapshots/resampling.md`](snapshots/resampling.md): all 87 calls completed within the
cap, but none of the 21 fresh native excerpts matched the old golden audio. The
URLs serve videos replaced weekly, so quality metrics and deltas are unavailable.
The validation gate was added after detecting this failed source match. The
committed timing correction is in
[`snapshots/translation_timing.md`](snapshots/translation_timing.md); generated
reports remain under the gitignored `reports/` directory.

If the native source is 48 kHz, preparation also creates a reference-resampled
44.1 kHz source for the first 12 verified manifest entries, then compares avg
with nearest on that source. This preselected supplemental sample keeps the
whole experiment at about 87 billed minutes (21 × 3 + 12 × 2 one-minute clips).
Use `--supplemental-clips` to change it; the cap still applies to all pending
jobs together. The fixed 48-sample signed PCM vector is pinned to the same
expected bytes in Python and `PcmResamplingTest.kt`. Each arm resamples the
entire clip in one call, while the app converts individual callback buffers;
callback-boundary rounding and provider repeatability are not measured here.
Use a fresh `--root` when manifest offsets or renditions change: native WAV reuse
currently checks duration, not source-coordinate provenance.

The run stores its historical model and context in `preparation.json`, so offline
scoring continues to work after the app glossary or model changes. Live resume
rejects such configuration drift. Entity F1 uses only clips with labelled domain
terms, with its own n; WER uses every recorded clip.

Translated final words with no provider timestamp have `start_s=null`.
Finalization latency excludes them and reads `n/a` if none remain. Replay
also discards all-zero starts in unversioned Soniox translation fixtures: those
zeros were invented by the old recorder, rather than spoken word times. New
recordings retain genuine zero starts through an explicit timing schema version.
