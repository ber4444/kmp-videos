# Metric calibration — ru

Does the scorecard's two-stage advantage measure translation quality, or does it
measure resemblance to the engine that produced the ideal? Every cell is chrF.
The **control** row is a flawless translation of the reference by an engine that is
not DeepL: it carries no ASR error, so whatever it scores below 100 is the price of
being a different engine. Compare the in-band row to that floor, not to 100.

| Hypothesis | Ideal | chrF | n | |
|---|---|---:|---:|---|
| Gemini(reference) | DeepL(ref) | 66.21 | 21 | perfect input, wrong engine — the floor |
| Soniox in-band | DeepL(ref) | 53.18 | 21 | what the scorecard reports |
| Soniox in-band | Gemini(ref) | 59.15 | 21 | same caption, other ideal |
| DeepL(Soniox ASR) | DeepL(ref) | 70.31 | 21 | two-stage, same engine as ideal |
| Gemini(Soniox ASR) | Gemini(ref) | 76.44 | 21 | two-stage, same engine as ideal |
| Gemini(Soniox ASR) | DeepL(ref) | 58.28 | 21 | two-stage, cross-engine |

All differences are means of per-clip differences on the shared clips.
Positive counts exclude ties. Same-engine lead is diagnostic, not a quality verdict.

| Paired comparison | chrF delta | n | Positive clips |
|---|---:|---:|---:|
| Two-stage lead (same engine) | +17.12 | 21 | 19 |
| Key-swap gap | +18.15 | 21 | 20 |
| Cross-engine lead | +5.10 | 21 | 16 |
| Control gap | +13.02 | 21 | 19 |
| In-band key-swap gap | +5.97 | 21 | 14 |
