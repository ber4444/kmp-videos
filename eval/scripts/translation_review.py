"""Build a human-review sheet for translation fidelity (offline; no API).

For each clip it lays out the English reference, the DeepL translation of that
verified reference (the "ideal"), and each provider's translated hypothesis, with
chrF vs ideal — sorted worst-Soniox-first so a native reader spends time where the
risk is. Fill in the Verdict column: FAITHFUL / MINOR / BROKEN.
"""
import os
import sys
import json
import argparse

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))
import config
from scoring.metrics import calculate_translation_fidelity


def load(kind, clip_id, lang):
    path = os.path.join(config.TRANSLATIONS_DIR, kind, f"{clip_id}.{lang}.json")
    if not os.path.exists(path):
        return None
    with open(path) as f:
        return json.load(f)["text"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--target", default=config.TRANSLATE_TARGET_LANG)
    args = ap.parse_args()
    lang = args.target

    with open(config.MANIFEST_PATH) as f:
        manifest = json.load(f)

    rows = []
    for e in manifest["entries"]:
        cid = e["id"]
        ref_en_path = os.path.join(config.GOLDEN_DIR, e["ref"])
        ideal = load("ref", cid, lang)
        if ideal is None or not os.path.exists(ref_en_path):
            continue
        with open(ref_en_path) as f:
            ref_en = f.read().strip()
        sx, dg = load("soniox", cid, lang), load("deepgram", cid, lang)
        sx_chrf = calculate_translation_fidelity(ideal, sx)["trans_chrf"] if sx else None
        dg_chrf = calculate_translation_fidelity(ideal, dg)["trans_chrf"] if dg else None
        rows.append((cid, sx_chrf, dg_chrf, ref_en, ideal, sx, dg))

    # Worst Soniox chrF first (highest chance of a broken translation).
    rows.sort(key=lambda r: (r[1] if r[1] is not None else 999))

    out = [f"# Translation Review — target {lang}\n",
           "Protocol: read **Ideal** (DeepL of the verified reference = correct meaning), then judge "
           "**Soniox** against it. Verdict: `FAITHFUL` (meaning preserved), `MINOR` (small loss, still "
           "usable), or `BROKEN` (meaning changed/lost). Repeat for Deepgram to confirm the gap. Worst "
           "Soniox chrF is listed first.\n",
           "| # | Clip | Soniox chrF | Deepgram chrF | Soniox verdict | Deepgram verdict |",
           "|---|---|---|---|---|---|"]
    for i, (cid, sxf, dgf, *_ ) in enumerate(rows, 1):
        out.append(f"| {i} | {cid} | {sxf:.1f} | {dgf:.1f} |  |  |")
    out.append("")

    for i, (cid, sxf, dgf, ref_en, ideal, sx, dg) in enumerate(rows, 1):
        out.append(f"## {i}. {cid}  (Soniox chrF {sxf:.1f}, Deepgram chrF {dgf:.1f})\n")
        out.append(f"**EN source (verified ref):** {ref_en}\n")
        out.append(f"**Ideal (DeepL of ref):** {ideal}\n")
        out.append(f"**Soniox → {lang}:** {sx}\n")
        out.append(f"**Deepgram → {lang}:** {dg}\n")

    os.makedirs(config.REPORTS_DIR, exist_ok=True)
    path = os.path.join(config.REPORTS_DIR, f"translation_review.{lang}.md")
    with open(path, "w") as f:
        f.write("\n".join(out))
    print(f"Review sheet written to {path} ({len(rows)} clips)")


if __name__ == "__main__":
    main()
