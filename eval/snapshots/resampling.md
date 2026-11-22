# PCM resampling experiment — invalid source match

**Task C recognition results are unavailable: the freshly extracted stream excerpts do not match the archived golden audio.**
Source identity check: 0/21 clips matched.
Peak normalized waveform correlation range: 0.0117–0.0669; match threshold 0.8.
Recorded responses: 87; submitted audio: 5219.43s (86.99 min).
The supplied Wowza host was fetched using the same manifest event/rendition/offset/duration and no -ar. All native files were 48 kHz.
The initial recordings completed before a waveform source check exposed the mismatch. The source-match failure invalidates use of the old references.
The user confirmed that these URLs serve videos replaced weekly; the original native recordings are unavailable.
The old reference text cannot score different audio. The initial WER/Entity F1 calculations are rejected; no recognition-quality or noise conclusion is supported.
No extra provider calls were made. Recording now requires a successful source match before any spending.

| Source Hz | Arm | Recorded clips | Normalized WER | Entity F1 |
|---|---|---:|---|---|
| 48000 | ref | 21 | n/a (reference mismatch; n=0) | n/a (reference mismatch; n=0) |
| 48000 | nearest | 21 | n/a (reference mismatch; n=0) | n/a (reference mismatch; n=0) |
| 48000 | avg | 21 | n/a (reference mismatch; n=0) | n/a (reference mismatch; n=0) |
| 44100 | nearest | 12 | n/a (reference mismatch; n=0) | n/a (reference mismatch; n=0) |
| 44100 | avg | 12 | n/a (reference mismatch; n=0) | n/a (reference mismatch; n=0) |

| Source Hz | Paired comparison | WER delta | Entity F1 delta | Valid paired n |
|---|---|---|---|---:|
| 48000 | nearest − avg | n/a (reference mismatch) | n/a (reference mismatch) | 0 |
| 48000 | avg − ref | n/a (reference mismatch) | n/a (reference mismatch) | 0 |
| 44100 | nearest − avg | n/a (reference mismatch) | n/a (reference mismatch) | 0 |

Protocol: stt-async-v6, recognition only, app English text + terms; scipy resample_poly reference, whole-clip conversion.
The supplemental 44.1 kHz pair used the first 12 verified manifest entries selected before results. Callback-buffer effects and provider repeatability were not tested.
Next step: recover the original native recordings or a stable archive that matches the verified golden excerpts, validate that match, and obtain approval for any additional recording spend.
