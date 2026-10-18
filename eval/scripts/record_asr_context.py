"""Ask how much of the caption gap is fixable by telling Soniox the vocabulary in advance.

The translation arms established that the transcript is the bottleneck: every path lands at
53–58 chrF regardless of which engine translates, against a 66–68 ceiling, and the errors
are the domain words themselves ("uncreated light" comes back as "our crazy light"). The
open question is whether feeding Soniox better vocabulary fixes that, and how much better
the vocabulary would have to be.

That question has a cheap answer, because it is the same shape as the batch-ceiling trick:
bound the intervention before building it. Slide OCR is a real pipeline — frame sampling,
slide-change detection, an OCR engine, text cleanup — so rather than build it and find out,
these arms hand Soniox vocabulary drawn from the *verified reference* and measure what
perfect vocabulary would be worth. If the oracle barely moves WER, no OCR pipeline will.

Arms, all on the async API so they run as fast as it answers rather than at wall-clock pace:

- `none` — no context at all. The baseline the scorecard's 0.242 WER already reports.
- `glossary` — the app's own 49 accepted terms, i.e. what the shipping caption path sends.
  Also the arm that was previously broken: `boost` used to be a commented-out guess at a
  `speech_context` field the SDK does not have, so the "boosted" fixtures re-ran the
  baseline and the scorecard reported Soniox boosting as worthless.
- `oracle-terms` — the rare vocabulary of *this clip*, taken from its reference: words that
  appear in few other clips, which is what a slide would carry (headings, proper nouns,
  invented terms) without the function words around them. The honest slide proxy.
- `oracle-full` — the entire reference as free text. Unrealistically strong, since it
  contains the exact phrasing and not just the vocabulary, so it is the absolute ceiling on
  any context-injection scheme rather than a proposal.

The gap between `oracle-terms` and `oracle-full` is roughly the difference between a slide
that names the terms and a slide being read aloud verbatim.
"""
import os
import re
import sys
import json
import argparse
from collections import Counter

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))
import config
from app_context import load_app_context
from providers import SonioxProvider, TranscriptResult
from scoring.metrics import calculate_metrics, calculate_entity_metrics

ARMS = ("none", "glossary", "oracle-terms", "oracle-full")

FIXTURE_DIR = {
    "none": os.path.join(config.FIXTURES_DIR, "soniox"),
    "glossary": os.path.join(config.FIXTURES_DIR, "soniox-ctx-glossary"),
    "oracle-terms": os.path.join(config.FIXTURES_DIR, "soniox-ctx-oracle-terms"),
    "oracle-full": os.path.join(config.FIXTURES_DIR, "soniox-ctx-oracle-full"),
}

# Words too common to be worth boosting, and too common to appear on a slide as a term.
# Deliberately a short hand-written list rather than a dependency: the corpus-rarity filter
# below does most of the work, and this only catches what survives it.
STOPWORDS = set("""
the a an and or but if then that this these those there here what which who whom whose when
where why how all any both each few more most other some such only own same so than too very
can will just should now what's it's i'm we're you're they're don't doesn't didn't isn't
was were been being have has had having do does did doing would could may might must shall
about above after again against because before below between during into through under until
""".split())


def clip_reference(entry) -> str:
    path = os.path.join(config.GOLDEN_DIR, entry["ref"])
    if not os.path.exists(path):
        return ""
    with open(path, "r", encoding="utf-8") as f:
        return f.read().strip()


def _words(text):
    return re.findall(r"[a-z']+", text.lower())


def build_oracle_terms(entries, max_terms=60):
    """Per clip, the vocabulary that is distinctive to it.

    Rarity across the corpus stands in for "would be printed rather than spoken in passing":
    a word used in one lecture and no other is the kind of thing a slide names. Computed from
    the references rather than from any transcript, so the arm is an oracle by construction.
    """
    doc_freq = Counter()
    per_clip = {}
    for entry in entries:
        words = set(_words(clip_reference(entry)))
        per_clip[entry["id"]] = words
        doc_freq.update(words)

    rare_threshold = max(2, len(entries) // 7)
    oracle = {}
    for entry in entries:
        candidates = [
            w for w in per_clip[entry["id"]]
            if len(w) >= 4 and w not in STOPWORDS and doc_freq[w] <= rare_threshold
        ]
        # Longest first: multi-syllable rare words are the ones the model actually misses.
        candidates.sort(key=lambda w: (-len(w), w))
        terms = candidates[:max_terms]
        # The clip's own labelled domain terms belong here whatever their corpus frequency —
        # they are the vocabulary a slide would certainly carry.
        for t in entry.get("domain_terms", []):
            if t not in terms:
                terms.append(t)
        oracle[entry["id"]] = terms
    return oracle


def context_for(arm, entry, glossary_terms, oracle_terms):
    if arm == "none":
        return None
    if arm == "glossary":
        return {"terms": glossary_terms}
    if arm == "oracle-terms":
        return {"terms": oracle_terms[entry["id"]]}
    if arm == "oracle-full":
        return {"text": clip_reference(entry)}
    raise ValueError(arm)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--arms", default="glossary,oracle-terms,oracle-full",
                        help=f"Comma-separated subset of {','.join(ARMS)}")
    parser.add_argument("--max-clips", type=int, default=None)
    parser.add_argument("--score-only", action="store_true",
                        help="Score existing fixtures without calling Soniox")
    parser.add_argument("--translate", default=None, metavar="LANG",
                        help="Also record the batch *translated* arm under each context, so "
                             "the WER win can be read as caption quality (chrF) rather than "
                             "extrapolated from it. Async, so still fast.")
    args = parser.parse_args()

    arms = [a.strip() for a in args.arms.split(",") if a.strip()]
    for arm in arms:
        if arm not in ARMS:
            print(f"Unknown arm {arm!r}; choose from {ARMS}")
            sys.exit(1)

    with open(config.MANIFEST_PATH, "r") as f:
        entries = [e for e in json.load(f)["entries"] if e.get("verified", False)]
    if args.max_clips is not None:
        entries = entries[:args.max_clips]

    glossary_terms = load_app_context()["terms"]
    oracle_terms = build_oracle_terms(entries)

    if not args.score_only:
        if not os.environ.get("SONIOX_API_KEY"):
            print("Error: SONIOX_API_KEY is not set. Add it to eval/.env.")
            sys.exit(1)
        os.environ["EVAL_LIVE"] = "1"
        provider = SonioxProvider()
        billed = len(entries) * len([a for a in arms if a != "none"]) * 60
        print(f"{len(entries)} clips × {len(arms)} arm(s). Billed audio ~{billed}s, "
              f"async so wall-clock is however fast the API answers.\n")

        for arm in arms:
            os.makedirs(FIXTURE_DIR[arm], exist_ok=True)
            for entry in entries:
                clip_id = entry["id"]
                wav = os.path.join(config.CLIPS_DIR, f"{clip_id}.wav")
                out = os.path.join(FIXTURE_DIR[arm], f"{clip_id}.json")
                if not os.path.exists(wav):
                    print(f"Skipping {clip_id}, audio not found.")
                    continue
                if os.path.exists(out):
                    print(f"Skipping {arm}/{clip_id}, fixture exists.")
                    continue
                ctx = context_for(arm, entry, glossary_terms, oracle_terms)
                size = len(json.dumps(ctx, ensure_ascii=False)) if ctx else 0
                print(f"Recording {arm}/{clip_id} (context {size} chars)...")
                try:
                    res = provider._transcribe_batch(wav, entry.get("domain_terms", []),
                                                     boost=False, context=ctx)
                    with open(out, "w", encoding="utf-8") as f:
                        json.dump(res.model_dump(), f, indent=2, ensure_ascii=False)
                    print(f"  -> {len(res.text)} chars")
                except Exception as e:
                    print(f"  FAILED {arm}/{clip_id}: {e}")

    # --- Optional: the same contexts through the translated batch path ---
    # A WER win is only interesting if it reaches the captions. Rather than extrapolate from
    # WER to chrF with an assumed exchange rate, record the translation under each context
    # and read the caption-quality delta directly.
    if args.translate and not args.score_only:
        lang = args.translate
        for arm in arms:
            out_dir = os.path.join(config.FIXTURES_DIR, f"soniox-ctx-{arm}-translate", lang)
            os.makedirs(out_dir, exist_ok=True)
            for entry in entries:
                clip_id = entry["id"]
                wav = os.path.join(config.CLIPS_DIR, f"{clip_id}.wav")
                out = os.path.join(out_dir, f"{clip_id}.json")
                if not os.path.exists(wav) or os.path.exists(out):
                    continue
                ctx = context_for(arm, entry, glossary_terms, oracle_terms)
                print(f"Translating {arm}/{clip_id} -> {lang}...")
                try:
                    res = provider.transcribe_batch_translated(wav, target_lang=lang, context=ctx)
                    with open(out, "w", encoding="utf-8") as f:
                        json.dump(res.model_dump(), f, indent=2, ensure_ascii=False)
                except Exception as e:
                    print(f"  FAILED {arm}/{clip_id}: {e}")

    # --- Score every arm that has fixtures, paired per clip ---
    rows = {}
    for arm in ARMS:
        per_clip = {}
        for entry in entries:
            path = os.path.join(FIXTURE_DIR[arm], f"{entry['id']}.json")
            ref = clip_reference(entry)
            if not os.path.exists(path) or not ref:
                continue
            with open(path, "r", encoding="utf-8") as f:
                hyp = TranscriptResult(**json.load(f)).text
            m = calculate_metrics(ref, hyp)
            e = calculate_entity_metrics(ref, hyp, entry.get("domain_terms", []))
            per_clip[entry["id"]] = (m["wer_norm"], e.get("entity_f1", 0.0))
        if per_clip:
            rows[arm] = per_clip

    baseline = rows.get("none", {})

    md = ["# Can better vocabulary fix the transcript?", "",
          "Every arm is the same audio through the same async model; only the session",
          "`context` changes. WER is normalized (Whisper text normalizer), Entity F1 is over",
          "each clip's labelled domain terms. **Δ WER is a per-clip mean against the",
          "no-context arm**, negative = better.", "",
          "`oracle-*` arms draw their vocabulary from the verified reference, so they are not",
          "shippable — they measure what perfect foreknowledge of the vocabulary is worth, and",
          "so bound any real scheme for supplying it (slide OCR, a per-lecture term list, a",
          "speaker-supplied glossary). `oracle-full` hands over the exact phrasing too, so it",
          "is a ceiling rather than a proposal.", "",
          "| Arm | WER | Δ WER vs none | Entity F1 | n |",
          "|---|---|---|---|---|"]

    print(f"\n{'arm':<16}{'WER':>8}{'ΔWER':>9}{'EntF1':>8}{'n':>4}")
    for arm in ARMS:
        if arm not in rows:
            continue
        per_clip = rows[arm]
        wer = sum(v[0] for v in per_clip.values()) / len(per_clip)
        f1 = sum(v[1] for v in per_clip.values()) / len(per_clip)
        shared = sorted(set(per_clip) & set(baseline))
        if shared and arm != "none":
            d = sum(per_clip[c][0] - baseline[c][0] for c in shared) / len(shared)
            delta = f"{d:+.3f} (n={len(shared)})"
        else:
            delta = "—"
        print(f"{arm:<16}{wer:>8.3f}{delta.split(' ')[0]:>9}{f1:>8.3f}{len(per_clip):>4}")
        md.append(f"| {arm} | {wer:.3f} | {delta} | {f1:.3f} | {len(per_clip)} |")

    # Caption quality under each context, if the translated arms were recorded. Scored
    # against the same ideal as every other translation arm: DeepL on the verified reference.
    if args.translate:
        from scoring.metrics import calculate_translation_fidelity
        lang = args.translate
        deepl_lang = config.deepl_target(lang)
        md += ["", f"## What the WER win is worth in captions ({lang})", "",
               "The same contexts through the translated batch path, chrF against DeepL's",
               "translation of the verified reference — the ideal every other translation arm",
               "in this harness uses. This is the exchange rate between a better transcript and",
               "a better caption, measured rather than assumed.", "",
               "| Arm | chrF | Δ vs none | n |", "|---|---|---|---|"]
        print(f"\n{'arm':<16}{'chrF':>8}{'Δ':>9}{'n':>4}   (captions, {lang})")
        trans = {}
        for arm in ARMS:
            per_clip = {}
            for entry in entries:
                p = os.path.join(config.FIXTURES_DIR, f"soniox-ctx-{arm}-translate", lang,
                                 f"{entry['id']}.json")
                ideal_p = os.path.join(config.TRANSLATIONS_DIR, "ref",
                                       f"{entry['id']}.{deepl_lang}.json")
                if not (os.path.exists(p) and os.path.exists(ideal_p)):
                    continue
                with open(p, encoding="utf-8") as f:
                    hyp = TranscriptResult(**json.load(f)).text
                with open(ideal_p, encoding="utf-8") as f:
                    ideal = json.load(f)["text"]
                per_clip[entry["id"]] = calculate_translation_fidelity(ideal, hyp)["trans_chrf"]
            if per_clip:
                trans[arm] = per_clip
        base_t = trans.get("none", {})
        for arm, per_clip in trans.items():
            mean = sum(per_clip.values()) / len(per_clip)
            shared = sorted(set(per_clip) & set(base_t))
            d = (f"{sum(per_clip[c] - base_t[c] for c in shared)/len(shared):+.1f} (n={len(shared)})"
                 if shared and arm != "none" else "—")
            print(f"{arm:<16}{mean:>8.1f}{d.split(' ')[0]:>9}{len(per_clip):>4}")
            md.append(f"| {arm} | {mean:.1f} | {d} | {len(per_clip)} |")

    out_path = os.path.join(config.REPORTS_DIR, "asr_context.md")
    os.makedirs(config.REPORTS_DIR, exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as f:
        f.write("\n".join(md) + "\n")
    print(f"\nWritten to {out_path}")


if __name__ == "__main__":
    main()
