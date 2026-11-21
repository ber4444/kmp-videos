# 2026 vendor refresh

PR #110 rechecks the Soniox evaluation and the earlier decision to abandon local ASR.
The [complete measured tables](VENDOR_REFRESH_RESULTS_2026.md) and
[aggregate JSON](VENDOR_REFRESH_RESULTS_2026.json) are committed; recordings, audio,
references and transcripts remain in the local gitignored archive. Both
[article drafts](../docs/articles/README.md) now distinguish these results from the
historical Soniox v5 and Fold3 studies.

## Results

- Soniox v6 batch mean normalized WER is **0.268**, versus the archived **0.242**.
  Corpus WER also worsens, **0.184 vs 0.159**, and Entity F1 is **0.680 vs 0.770**.
- Streaming WER improves, **0.293 vs 0.309**, while flicker worsens,
  **0.237 vs 0.228**. The mean per-clip median/p95 finalization times are
  **6.09/7.14 s**, versus **5.77/6.78 s**. These use provider timestamps, not alignment.
- V6 async translation with the final app context scores **54.1 HU / 54.6 RU chrF**
  against the existing DeepL references, versus **52.5/53.9** without context.
  Against existing Gemini references the respective scores are **56.4/59.8** and
  **54.8/57.9**. All four arms cover all 21 clips.
- On the user-approved **Pixel 7a / Tensor G2**, Moonshine Medium Streaming and
  Whistle have median warm compute RTF **0.113** and **0.026**, but mean per-clip
  WER **0.549** and **0.643**. They keep up in standalone native inference; these
  unboosted recognition results do not establish a replacement caption pipeline.
- The one-clip, real-time-paced smoke has warm WER **0.723/0.957** and compute RTF
  **0.293/0.192** for Moonshine/Whistle. This is not corpus streaming quality.

The original Fold3 was unavailable. Pixel measurements are labelled as a hardware
substitution, not a Snapdragon 888 replication. No HLS/Compose concurrent-load test
or production provider switch was made. The app remains on its existing configuration;
this PR upgrades the evaluation candidate.

## Protocol and provenance

The Soniox set contains 21 verified clips, about 21 minutes of audio. Recognition
recording includes 21 baseline batch, 12 term-boosted batch, and 21 paced streaming
calls. V6 batch recognition leaves language hints unset, matching the original v5
recording protocol; the new translation arms explicitly hint English. Batch recognition
has no full app context. Do not equate those rows with the quality of shipped captions.

Historical batch fixtures lack embedded model IDs; the v5 attribution follows the
previous provider code. Their recording configurations are not fully preserved.
Streaming recognition fixtures explicitly name their model. V5 requests will route to
v6 after the vendor's December 15, 2026 retirement, so preserve the old fixtures rather
than trying to reconstruct a v5 baseline later.

Translation uses `stt-async-v6`, one-way HU/RU translation, and either no context or the
merged PR #82 app context. The context source was frozen from `origin/main` **07aba75**:
`CaptionGlossary.DOMAIN` (284 characters), 49 `TERMS`, and the corresponding translation
map. Every new translation fixture stores the exact request, audio/glossary hashes,
model, mode, separate original/translated text and raw response. The extractor reuses
`app_context.py` and validates complete mappings. Set `EVAL_GLOSSARY_PATH` only to an
explicit reviewed snapshot when measuring from a checkout older than the shipped app.

The initial PR checkout predated that merge. Ten vocabulary-only translation responses
were preserved separately under `superseded-without-domain/` and excluded from the
reported shipped-context arms. The interrupted no-context response was retrieved from
Soniox rather than rebilled. A too-strict check of optional token language tags was also
fixed after the smoke, with its already-billed response recovered. One initial streaming
smoke was repeated after adding terminal-response validation. Including those extra
responses, nominal Soniox audio processed was about **149 minutes**; this is an audio
accounting total, not a provider invoice.

No DeepL or Gemini API was called. Their existing translations of the verified English
references were reused. The historical v5 async and real-time translation fixtures lack
matching context provenance, so they are shown as historical rows, not controlled v5→v6
translation deltas. The two-stage and oracle experiments were not rerun. Different-engine
reference agreement is a control, not a human quality ceiling.

Native tests used released Moonshine Voice 0.1.6 and Cactus Python 4.2.6's pinned engine
4.1.4. Both ran each golden clip twice with a resident model, sequentially by engine.
Warm results use the second run. Desktop sanity checks used one golden clip twice per
engine. See [native setup](native/README.md) for model revisions, compiler flags, sizes,
chunk pacing and repeat commands. The original Fold3 executable/audio/ADR are not archived
in this checkout; the old ~2× Whisper RTF remains a historical claim.

## Replay and repeat

From `eval/`, with its virtual environment and exported `.env` keys for billed recording:

```bash
export EVAL_FIXTURES_DIR="$PWD/fixtures/vendor-refresh-2026-v6"
export EVAL_REPORTS_DIR="$PWD/reports/vendor-refresh-2026-v6"

# Dry runs never call a provider or create recordings.
venv/bin/python scripts/record.py --provider soniox --max-clips 1 --baseline-only --dry-run
venv/bin/python scripts/record_stream.py --provider soniox --max-clips 1 --dry-run
venv/bin/python scripts/record_soniox_translation.py --dry-run

# Billed recording: valid cached outputs are skipped.
venv/bin/python scripts/record.py --provider soniox
venv/bin/python scripts/record_stream.py --provider soniox
venv/bin/python scripts/record_soniox_translation.py

# Offline, using the original local references and complete recordings.
venv/bin/python scripts/summarize_vendor_refresh.py
```

Do not replace historical fixture directories. Replay/scoring use the same explicit
fixture/report roots; unset the exports to return to the historical defaults. Recording
caps count pending calls, including boosted/translation arms, using WAV duration.
A provider failure stops the recorder with a nonzero status. Generic Soniox recording
rejects cached results from another model or with an invalid result schema. The new
translation recorder additionally rejects mismatched request/audio identities.

## Vendor facts checked

- [Soniox models](https://docs.soniox.com/stt/models) confirms the November 3 v6 release,
  active identifiers and v5 retirement. [Context](https://docs.soniox.com/stt/concepts/context)
  documents `text`, `terms` and `translation_terms`.
- [Moonshine](https://github.com/moonshine-ai/moonshine) documents incremental recognition
  and the English checkpoint's MIT license. Other language checkpoints exist under
  separate terms; this was an English Medium Streaming benchmark.
- [Whistle](https://www.cactuscompute.com/blog/whistle) and its
  [model card](https://huggingface.co/Cactus-Compute/whistle) document the 16.9 MB model,
  seven languages and Apache 2.0 license. Its noncausal encoder and windowed file path
  do not establish freedom from chunk-boundary errors.
- [Parakeet TDT v3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3) is one 600M-parameter,
  25-language checkpoint under CC-BY-4.0. It was outside this benchmark, along with
  other cloud providers and speech-to-speech products. That is a scope choice, not a
  renewed assessment of their capabilities.
