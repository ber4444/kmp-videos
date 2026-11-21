# Can better vocabulary fix the transcript?

Every arm is the same audio through the same async model; only the session
`context` changes. WER is normalized (Whisper text normalizer), Entity F1 is over
each clip's labelled domain terms. **Δ WER is a per-clip mean against the
no-context arm**, negative = better.

`oracle-*` arms draw their vocabulary from the verified reference, so they are not
shippable — they measure what perfect foreknowledge of the vocabulary is worth, and
so bound any real scheme for supplying it (slide OCR, a per-lecture term list, a
speaker-supplied glossary). `oracle-full` hands over the exact phrasing too, so it
is a ceiling rather than a proposal.

| Arm | WER | Δ WER vs none | Entity F1 | n |
|---|---|---|---|---|
| none | 0.242 | — | 0.869 | 21 |
| glossary | 0.205 | -0.037 (n=21) | 0.915 | 21 |
| oracle-terms | 0.192 | -0.050 (n=21) | 0.922 | 21 |
| oracle-full | 0.103 | -0.140 (n=21) | 1.000 | 21 |

## What the WER win is worth in captions (hu)

The same contexts through the translated batch path, chrF against DeepL's
translation of the verified reference — the ideal every other translation arm
in this harness uses. This is the exchange rate between a better transcript and
a better caption, measured rather than assumed.

| Arm | chrF | Δ vs none | n |
|---|---|---|---|
| none | 54.9 | — | 21 |
| glossary | 57.0 | +2.0 (n=21) | 21 |
| oracle-terms | 59.7 | +4.7 (n=21) | 21 |
| oracle-full | 63.7 | +8.8 (n=21) | 21 |
