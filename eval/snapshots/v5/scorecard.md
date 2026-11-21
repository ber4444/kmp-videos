# STT Provider Scorecard

## Overall Metrics

| Provider | WER (Norm) | WER (Fmt) | CER (Norm) | Entity F1 |
|---|---|---|---|---|
| deepgram | 0.350 | 0.507 | 0.212 | 0.488 |
| soniox | 0.242 | 0.269 | 0.146 | 0.770 |

## Metrics by Condition

### clean

| Provider | WER (Norm) |
|---|---|
| deepgram | 0.360 |
| soniox | 0.244 |


### music_bed

| Provider | WER (Norm) |
|---|---|
| deepgram | 0.253 |
| soniox | 0.232 |


## Keyterm Boosting Impact (Clips with Domain Terms)

| Clip ID | Provider | Baseline WER | Boosted WER | Baseline Entity F1 | Boosted Entity F1 |
|---|---|---|---|---|---|
| evt8-clip-1 | deepgram | 0.447 | 0.298 | 0.500 | 1.000 |
| evt8-clip-1 | soniox | 0.340 | 0.170 | 0.500 | 1.000 |
| evt8-clip-2 | deepgram | 0.346 | 0.309 | 0.667 | 0.667 |
| evt8-clip-2 | soniox | 0.185 | 0.160 | 0.750 | 0.889 |
| evt8-clip-3 | deepgram | 0.431 | 0.345 | 1.000 | 0.000 |
| evt8-clip-3 | soniox | 0.241 | 0.138 | 1.000 | 0.000 |
| evt8-clip-4 | deepgram | 0.351 | 0.324 | 0.857 | 0.857 |
| evt8-clip-4 | soniox | 0.270 | 0.162 | 0.857 | 1.000 |
| evt10-clip-1 | deepgram | 0.269 | 0.269 | 0.500 | 1.000 |
| evt10-clip-1 | soniox | 0.205 | 0.231 | 0.800 | 1.000 |
| evt10-clip-2 | deepgram | 0.349 | 0.302 | 0.000 | 1.000 |
| evt10-clip-2 | soniox | 0.302 | 0.270 | 0.000 | 0.667 |
| evt10-clip-3 | deepgram | 0.446 | 0.446 | 1.000 | 1.000 |
| evt10-clip-3 | soniox | 0.411 | 0.500 | 1.000 | 1.000 |
| evt10-clip-4 | deepgram | 0.355 | 0.355 | 0.000 | 0.500 |
| evt10-clip-4 | soniox | 0.177 | 0.145 | 0.667 | 1.000 |
| evt10-clip-5 | deepgram | 2.500 | 2.250 | 0.000 | 0.000 |
| evt10-clip-5 | soniox | 2.000 | 2.250 | 1.000 | 1.000 |
| evt13-clip-2 | deepgram | 0.132 | 0.132 | 0.667 | 0.667 |
| evt13-clip-2 | soniox | 0.044 | 0.044 | 0.667 | 1.000 |
| evt13-clip-4 | deepgram | 0.222 | 0.222 | 0.667 | 0.667 |
| evt13-clip-4 | soniox | 0.000 | 0.022 | 1.000 | 1.000 |
| evt13-clip-5 | deepgram | 0.188 | 0.125 | 0.000 | 1.000 |
| evt13-clip-5 | soniox | 0.000 | 0.000 | 1.000 | 1.000 |


## Streaming (Live-Caption Realism)

Real-time paced sessions. Flicker = fraction of already-shown characters later rewritten (0 = captions only append). Finalization latency is measured against each provider's **self-reported** word times (forced-alignment ground truth is out of scope).

| Provider | Clips | Streaming WER | Flicker | Final Latency (med) | Final Latency (p95) |
|---|---|---|---|---|---|
| deepgram | 21 | 0.316 | 0.358 | 1.44s | 3.12s |
| soniox | 21 | 0.309 | 0.228 | 5.77s | 6.78s |


## Translation Fidelity (ASR → DeepL, target = HU)

How much ASR error survives machine translation: each provider's transcript is translated with DeepL and compared to the translation of the **verified reference** (the ideal translation from perfect ASR). chrF is 0–100 (higher = closer to ideal); Post-MT WER is on raw target-language text, comparable to the source WER above.

| Provider | Clips | chrF vs ideal | Post-MT WER | Source WER (Norm) |
|---|---|---|---|---|
| deepgram | 21 | 57.0 | 0.663 | 0.350 |
| soniox | 21 | 74.3 | 0.420 | 0.242 |


## In-Band Translation Quality (Soniox, per language)

What the player actually renders: Soniox translating on the same socket, streamed and paced in real time, with the app's own session context (domain sentence + glossary, read from `CaptionGlossary.kt`). Every column is chrF against DeepL's translation of the verified reference.

**Read every number against the floor, not against 100.** The ideal is one engine's output, so an arm is scored partly on resembling *that engine* rather than on being a good translation. The **floor** column measures exactly that: a second engine translating the verified reference — perfect input, no ASR error — so whatever it scores below 100 is the price of not being DeepL. Measured at ~68 for Hungarian, i.e. a flawless translation scores 68, not 100.

- **In-band (context)** — what ships today.
- **In-band (no context)** — same audio, context withheld. The gap is what the glossary and domain sentence are worth for this language.
- **Batch (full context)** — the same model and context through the async API, which reads the whole clip before answering. **Δ streaming** is the price of committing a translation before the sentence ends, and it is also the ceiling on every latency-buying trick there is: holding the tail, re-translating on sentence end, or the vendor's own endpointing knobs. A small Δ means no client change will move this language.
- **Two-stage (cross-engine)** — the Soniox transcript translated by a *different* engine than the one that wrote the ideal, so it carries the same handicap as in-band and the two can be subtracted. **This is the column that decides whether a second MT vendor is worth its cost**, and on Hungarian it came out level with in-band, not ahead.

| Target | In-band (context) | In-band (no context) | Δ context | Batch (full context) | Δ streaming | Two-stage (cross-engine) | Δ two-stage | Different-engine floor |
|---|---|---|---|---|---|---|---|---|
| hu | 54.4 (n=21) | 51.9 (n=21) | +2.5 (n=21) | 56.4 (n=21) | +2.1 (n=21) | 57.2 (n=21) | +2.8 (n=21) | 68.1 (n=21) |
| ru | 53.2 (n=21) | 52.6 (n=21) | +0.6 (n=21) | 55.1 (n=21) | +2.0 (n=21) | 58.3 (n=21) | +5.1 (n=21) | 66.2 (n=21) |

chrF is 0–100, higher is better; it is character-n-gram based, so it does not punish a morphologically rich language for inflecting differently than the reference the way BLEU would. Absolute values are not comparable across languages (a chrF of 55 means different things in Hungarian and Spanish) — the comparisons within a row are. Every Δ is a per-clip mean over the clips both arms recorded, never a difference of two independently-averaged arms.

*COMET (semantic scoring): not run (set EVAL_COMET=1 to enable). chrF alone carries a same-engine bias, which is what the floor column is for.*

### Not comparable: Soniox → DeepL

DeepL translating the Soniox transcript, scored against DeepL's own translation of the reference. Both sides are the same engine and differ only by ASR error, so this collects a same-engine bonus that no other arm can — measured at **+19.4 chrF** on Hungarian by scoring one hypothesis against two different engines' ideals (`scripts/calibrate_metric.py`). It is reported because it is a real path you could ship, and kept out of the table because differencing it against in-band measures engine agreement, not quality. Use the **two-stage (cross-engine)** column for that decision instead.

| Target | Soniox → DeepL chrF |
|---|---|
| hu | 74.3 (n=21) |
| ru | 70.3 (n=21) |

### Endpoint detection (flicker, not quality)

The same real-time arm with Soniox's `enable_endpoint_detection` on, so it finalizes at utterance boundaries instead of mid-clause. Judged on **flicker** — the fraction of already-displayed characters later rewritten — because the batch arm above already bounds what any latency-buying mechanism can do for the words themselves. Lower flicker is a real improvement to what a viewer sees even when chrF does not move; higher finalization latency is what it costs.

| Target | Flicker (default) | Flicker (endpointed) | Δ flicker | Final latency med (default) | Final latency med (endpointed) | chrF Δ |
|---|---|---|---|---|---|---|
| hu | 0.000 | 0.000 | +0.000 (n=5) | 32.04s | 31.57s | -0.1 (n=5) |

Config actually sent: `{'enable_endpoint_detection': True}`. If the Δ columns are all zero, check this is non-empty before reading it as a negative result — an ignored field and an ineffective one look identical here.
