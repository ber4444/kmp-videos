# Metric calibration — ru

Does the scorecard's two-stage advantage measure translation quality, or does it
measure resemblance to the engine that produced the ideal? Every cell is chrF.
The **control** row is a flawless translation of the reference by an engine that is
not DeepL: it carries no ASR error, so whatever it scores below 100 is the price of
being a different engine. Compare the in-band row to that floor, not to 100.

| Hypothesis | Ideal | chrF | n | |
|---|---|---:|---:|---|
| Gemini(reference) | DeepL(ref) | 66.2 | 21 | perfect input, wrong engine — the floor |
| Soniox in-band | DeepL(ref) | n/a | 0 | what the scorecard reports |
| Soniox in-band | Gemini(ref) | n/a | 0 | same caption, other ideal |
| DeepL(Soniox ASR) | DeepL(ref) | 70.3 | 21 | two-stage, same engine as ideal |
| Gemini(Soniox ASR) | Gemini(ref) | 76.4 | 21 | two-stage, same engine as ideal |
| Gemini(Soniox ASR) | DeepL(ref) | 58.3 | 21 | two-stage, cross-engine |
