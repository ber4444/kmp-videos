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

## Current headline (real run)

Deepgram is **meaningfully worse than Soniox** on this material — ~45% higher normalized
WER (0.350 vs 0.242) and much lower Entity F1 (0.49 vs 0.77), i.e. it misses the domain
vocabulary far more often. Keyterm boosting narrows the gap on individual clips but does
not close it. See [`reports/scorecard.md`](reports/scorecard.md) for the full breakdown.
