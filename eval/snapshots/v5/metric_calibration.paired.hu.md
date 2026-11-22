# Metric calibration — hu

Does the scorecard's two-stage advantage measure translation quality, or does it
measure resemblance to the engine that produced the ideal? Every cell is chrF.
The **control** row is a flawless translation of the reference by an engine that is
not DeepL: it carries no ASR error, so whatever it scores below 100 is the price of
being a different engine. Compare the in-band row to that floor, not to 100.

| Hypothesis | Ideal | chrF | n | |
|---|---|---:|---:|---|
| Gemini(reference) | DeepL(ref) | 68.08 | 21 | perfect input, wrong engine — the floor |
| Soniox in-band | DeepL(ref) | 54.36 | 21 | what the scorecard reports |
| Soniox in-band | Gemini(ref) | 57.48 | 21 | same caption, other ideal |
| DeepL(Soniox ASR) | DeepL(ref) | 74.33 | 21 | two-stage, same engine as ideal |
| Gemini(Soniox ASR) | Gemini(ref) | 76.58 | 21 | two-stage, same engine as ideal |
| Gemini(Soniox ASR) | DeepL(ref) | 57.20 | 21 | two-stage, cross-engine |

All differences are means of per-clip differences on the shared clips.
Positive counts exclude ties. Same-engine lead is diagnostic, not a quality verdict.

| Paired comparison | chrF delta | n | Positive clips |
|---|---:|---:|---:|
| Two-stage lead (same engine) | +19.97 | 21 | 19 |
| Key-swap gap | +19.39 | 21 | 19 |
| Cross-engine lead | +2.83 | 21 | 12 |
| Control gap | +13.71 | 21 | 20 |
| In-band key-swap gap | +3.11 | 21 | 15 |
