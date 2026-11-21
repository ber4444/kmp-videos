# Metric calibration — hu

Does the scorecard's two-stage advantage measure translation quality, or does it
measure resemblance to the engine that produced the ideal? Every cell is chrF.
The **control** row is a flawless translation of the reference by an engine that is
not DeepL: it carries no ASR error, so whatever it scores below 100 is the price of
being a different engine. Compare the in-band row to that floor, not to 100.

| Hypothesis | Ideal | chrF | n | |
|---|---|---:|---:|---|
| Gemini(reference) | DeepL(ref) | 68.1 | 21 | perfect input, wrong engine — the floor |
| Soniox in-band | DeepL(ref) | 54.6 | 5 | what the scorecard reports |
| Soniox in-band | Gemini(ref) | 55.8 | 5 | same caption, other ideal |
| DeepL(Soniox ASR) | DeepL(ref) | 74.3 | 21 | two-stage, same engine as ideal |
| Gemini(Soniox ASR) | Gemini(ref) | 76.6 | 21 | two-stage, same engine as ideal |
| Gemini(Soniox ASR) | DeepL(ref) | 57.2 | 21 | two-stage, cross-engine |

**Soniox in-band is +13.5 chrF from the different-engine floor (54.6 vs 68.1).**
The scorecard's two-stage lead over in-band is +19.7; the part of it explained by shared-engine phrasing rather than translation quality is up to 31.9.
