"""Measure the scorecard's same-engine bias before trusting its two-stage verdict.

The in-band translation table scores everything against one ideal: DeepL's translation of
the verified reference. That makes two of its columns structurally unlike each other:

- `via DeepL` is DeepL(Soniox transcript) vs DeepL(reference). Both sides are the same
  engine, differing only by ASR error.
- `in-band` is Soniox's own translation vs DeepL(reference). It differs by ASR error *and*
  by every valid-but-different word an engine that is not DeepL would choose.

chrF is character-n-gram overlap, so the first pairing gets a bonus the second cannot: two
DeepL outputs of near-identical English share phrasing, register and punctuation. The
+19.7 the scorecard reports for the two-stage path is therefore an unknown mixture of "the
translation is better" and "the metric recognises its own handwriting", and it is
currently the whole business case for adding a second vendor.

This script separates them with a control: translate the *verified reference* — perfect
input, no ASR error at all — with a second engine, and score that against the DeepL ideal.
A flawless translation from the wrong engine is exactly the floor the in-band arm should
be compared to. If Soniox's in-band score sits near that floor, it is already close to as
good as a non-DeepL engine can score here and the gap was mostly artifact; if the floor is
high and in-band is far below it, the gap is real translation quality and the two-stage
path is worth its cost.

Gemini is given no glossary and no domain context, because DeepL got none either. The
control is only fair if both engines are handicapped identically.

Spends a little (Gemini, per character; roughly a cent at this size). Rerunning is free —
every translation is cached as a fixture like every other arm in this harness.
"""
import os
import sys
import json
import argparse
from collections import defaultdict

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))
import config
from providers import TranscriptResult
from scoring.metrics import calculate_translation_fidelity
from scoring.gemini import GEMINI_KEY_ENV, resolve_model, translate_cached as gemini_cached

# Fixture kinds under fixtures/translations/. The first two already exist (written by
# scripts/translate.py); this script adds the two Gemini ones.
DEEPL_REF, DEEPL_SONIOX = "ref", "soniox"
GEMINI_REF, GEMINI_SONIOX = "ref-gemini", "soniox-gemini"


def _load(kind, clip_id, lang):
    path = os.path.join(config.TRANSLATIONS_DIR, kind, f"{clip_id}.{lang}.json")
    if not os.path.exists(path):
        return None
    with open(path, "r") as f:
        return json.load(f).get("text", "")


def _inband(clip_id, soniox_lang):
    path = os.path.join(config.INBAND_DIR, soniox_lang, f"{clip_id}.json")
    if not os.path.exists(path):
        return None
    with open(path, "r") as f:
        d = json.load(f)
    return d.get("final_text") or d.get("text") or ""


def _chrf(ideal, hyp):
    return calculate_translation_fidelity(ideal, hyp)["trans_chrf"]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--target", default="hu", help="Soniox language code, e.g. hu")
    parser.add_argument("--model", default=None, help="Gemini model id; default = best on this key")
    parser.add_argument("--max-clips", type=int, default=None)
    parser.add_argument("--score-only", action="store_true",
                        help="Score existing fixtures without calling Gemini")
    args = parser.parse_args()

    lang = args.target
    deepl_lang = config.deepl_target(lang)

    if not args.score_only:
        if not os.environ.get(GEMINI_KEY_ENV):
            print(f"Error: {GEMINI_KEY_ENV} is not set. Add it to eval/.env "
                  f"(export {GEMINI_KEY_ENV}=...).")
            sys.exit(1)
        os.environ["EVAL_LIVE"] = "1"

    with open(config.MANIFEST_PATH, "r") as f:
        entries = [e for e in json.load(f)["entries"] if e.get("verified", False)]
    if args.max_clips is not None:
        entries = entries[:args.max_clips]

    # --- Record the second engine's translations (reference + Soniox transcript) ---
    if not args.score_only:
        model = resolve_model(args.model)
        print(f"Second engine: gemini:{model} → {deepl_lang}\n")
        for entry in entries:
            clip_id = entry["id"]
            ref_path = os.path.join(config.GOLDEN_DIR, entry["ref"])
            jobs = []
            if os.path.exists(ref_path):
                with open(ref_path, "r") as f:
                    jobs.append((GEMINI_REF, f.read()))
            asr_path = os.path.join(config.FIXTURES_DIR, "soniox", f"{clip_id}.json")
            if os.path.exists(asr_path):
                with open(asr_path, "r") as f:
                    jobs.append((GEMINI_SONIOX, TranscriptResult(**json.load(f)).text))

            for kind, text in jobs:
                out = os.path.join(config.TRANSLATIONS_DIR, kind, f"{clip_id}.{deepl_lang}.json")
                if os.path.exists(out):
                    print(f"Skipping {kind}/{clip_id}, fixture exists.")
                    continue
                print(f"Translating {kind}/{clip_id} ({len(text)} chars)...")
                try:
                    gemini_cached(kind, clip_id, text, deepl_lang,
                                  config.TRANSLATIONS_DIR, args.model)
                except Exception as e:
                    print(f"  FAILED {kind}/{clip_id}: {e}")

    # --- Score the matrix ---
    # Every cell is chrF against one of two ideals: DeepL(reference) — the one the
    # scorecard uses — or Gemini(reference), the same reference through the other engine.
    # Reading a row pair tells you how much of a score is the engine and how much is the
    # translation.
    cells = defaultdict(list)
    for entry in entries:
        clip_id = entry["id"]
        ideal_deepl = _load(DEEPL_REF, clip_id, deepl_lang)
        ideal_gemini = _load(GEMINI_REF, clip_id, deepl_lang)
        inband = _inband(clip_id, lang)
        two_deepl = _load(DEEPL_SONIOX, clip_id, deepl_lang)
        two_gemini = _load(GEMINI_SONIOX, clip_id, deepl_lang)

        if ideal_deepl and ideal_gemini:
            # The control: perfect input, wrong engine. No ASR error in this number at all.
            cells["control"].append(_chrf(ideal_deepl, ideal_gemini))
        if ideal_deepl and inband:
            cells["inband_vs_deepl"].append(_chrf(ideal_deepl, inband))
        if ideal_gemini and inband:
            cells["inband_vs_gemini"].append(_chrf(ideal_gemini, inband))
        if ideal_deepl and two_deepl:
            cells["deepl2_vs_deepl"].append(_chrf(ideal_deepl, two_deepl))
        if ideal_gemini and two_gemini:
            cells["gemini2_vs_gemini"].append(_chrf(ideal_gemini, two_gemini))
        if ideal_deepl and two_gemini:
            cells["gemini2_vs_deepl"].append(_chrf(ideal_deepl, two_gemini))

    def avg(key):
        vals = cells.get(key) or []
        return (sum(vals) / len(vals), len(vals)) if vals else (None, 0)

    rows = [
        ("Gemini(reference)", "DeepL(ref)", "control", "perfect input, wrong engine — the floor"),
        ("Soniox in-band", "DeepL(ref)", "inband_vs_deepl", "what the scorecard reports"),
        ("Soniox in-band", "Gemini(ref)", "inband_vs_gemini", "same caption, other ideal"),
        ("DeepL(Soniox ASR)", "DeepL(ref)", "deepl2_vs_deepl", "two-stage, same engine as ideal"),
        ("Gemini(Soniox ASR)", "Gemini(ref)", "gemini2_vs_gemini", "two-stage, same engine as ideal"),
        ("Gemini(Soniox ASR)", "DeepL(ref)", "gemini2_vs_deepl", "two-stage, cross-engine"),
    ]

    md = [f"# Metric calibration — {lang}", "",
          "Does the scorecard's two-stage advantage measure translation quality, or does it",
          "measure resemblance to the engine that produced the ideal? Every cell is chrF.",
          "The **control** row is a flawless translation of the reference by an engine that is",
          "not DeepL: it carries no ASR error, so whatever it scores below 100 is the price of",
          "being a different engine. Compare the in-band row to that floor, not to 100.", "",
          "| Hypothesis | Ideal | chrF | n | |",
          "|---|---|---:|---:|---|"]
    print(f"\n{'Hypothesis':<22}{'Ideal':<14}{'chrF':>7}{'n':>4}  note")
    for hyp, ideal, key, note in rows:
        val, n = avg(key)
        shown = f"{val:.1f}" if val is not None else "n/a"
        print(f"{hyp:<22}{ideal:<14}{shown:>7}{n:>4}  {note}")
        md.append(f"| {hyp} | {ideal} | {shown} | {n} | {note} |")

    control, _ = avg("control")
    inband, _ = avg("inband_vs_deepl")
    two, _ = avg("deepl2_vs_deepl")

    md.append("")
    if control is not None and inband is not None:
        headroom = control - inband
        md.append(f"**Soniox in-band is {headroom:+.1f} chrF from the different-engine floor "
                  f"({inband:.1f} vs {control:.1f}).**")
        print(f"\nIn-band vs the different-engine floor: {inband:.1f} vs {control:.1f} "
              f"({headroom:+.1f})")
        if two is not None:
            md.append(f"The scorecard's two-stage lead over in-band is "
                      f"{two - inband:+.1f}; the part of it explained by shared-engine "
                      f"phrasing rather than translation quality is up to "
                      f"{100 - control:.1f}.")
            print(f"Two-stage lead: {two - inband:+.1f}. Same-engine bonus available to it: "
                  f"up to {100 - control:.1f}.")

    out_path = os.path.join(config.REPORTS_DIR, f"metric_calibration.{lang}.md")
    os.makedirs(config.REPORTS_DIR, exist_ok=True)
    with open(out_path, "w") as f:
        f.write("\n".join(md) + "\n")
    print(f"\nWritten to {out_path}")


if __name__ == "__main__":
    main()
