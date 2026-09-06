"""Record Soniox's in-band translation to fixtures/soniox-translate[-nocontext]/{lang}/.

This is a spending script: it opens live real-time sessions, so wall-clock cost ≈ audio
duration × languages × variants. Scoring replays the fixtures for free.

Two variants, because the interesting question is not only *how good* the translation is but
*what moves it*:

- `context` (default) — the session `context` the app actually sends, read out of
  `CaptionGlossary.kt` by `app_context.py`: the domain sentence, the boosted terms, and the
  accepted renderings for this language.
- `nocontext` — the same audio with no context at all. The gap between the two is what the
  glossary and the domain sentence are worth for that language, which is the only way to
  know whether adding terms is a fix worth continuing.

Scoring against `translate(verified reference)` happens in scoring/scorecard.py; that
DeepL "ideal" has to exist for the same language first (`scripts/translate.py --target HU`).
"""
import os
import sys
import json
import argparse

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))
import config
from app_context import soniox_context
from providers import SonioxProvider

VARIANTS = ("context", "nocontext")


def fixture_dir(variant: str, lang: str) -> str:
    base = config.INBAND_DIR if variant == "context" else config.INBAND_NOCONTEXT_DIR
    path = os.path.join(base, lang)
    os.makedirs(path, exist_ok=True)
    return path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--targets", default="hu",
                        help="Comma-separated Soniox language codes, e.g. hu,ru,es")
    parser.add_argument("--variant", default="context", choices=(*VARIANTS, "both"),
                        help="Which session config to record; 'both' does the A/B")
    parser.add_argument("--max-clips", type=int, default=None)
    parser.add_argument("--force", action="store_true", help="Bypass the billed-audio cap")
    args = parser.parse_args()

    if not os.environ.get("SONIOX_API_KEY"):
        print("Error: SONIOX_API_KEY is not set. Add it to eval/.env.")
        sys.exit(1)

    os.environ["EVAL_LIVE"] = "1"
    langs = [c.strip() for c in args.targets.split(",") if c.strip()]
    variants = VARIANTS if args.variant == "both" else (args.variant,)

    with open(config.MANIFEST_PATH, "r") as f:
        entries = json.load(f)["entries"]
    if args.max_clips is not None:
        entries = entries[:args.max_clips]

    total_billed = sum(e["source"]["dur_s"] for e in entries) * len(langs) * len(variants)
    print(f"Streaming {len(entries)} clips × {len(langs)} languages × {len(variants)} variant(s).")
    print(f"Billed audio: {total_billed}s (~{total_billed/60:.0f} min wall-clock, real-time paced).")
    if total_billed > config.MAX_BILLED_AUDIO_SECONDS and not args.force:
        print(f"Error: billed {total_billed}s exceeds cap {config.MAX_BILLED_AUDIO_SECONDS}s. Use --force.")
        sys.exit(1)

    provider = SonioxProvider()
    for lang in langs:
        # Built once per language, from the app's own glossary: a language with no
        # renderings still gets the domain sentence and the boosted terms.
        context = soniox_context(lang)
        pairs = len(context.get("translation_terms", []))
        print(f"\n=== {lang} — context: {len(context['terms'])} terms, {pairs} accepted renderings ===")

        for entry in entries:
            clip_id = entry["id"]
            wav_path = os.path.join(config.CLIPS_DIR, f"{clip_id}.wav")
            if not os.path.exists(wav_path):
                print(f"Skipping {clip_id}, audio not found.")
                continue

            for variant in variants:
                fix_path = os.path.join(fixture_dir(variant, lang), f"{clip_id}.json")
                if os.path.exists(fix_path):
                    print(f"Skipping {lang}/{variant} for {clip_id}, fixture exists.")
                    continue

                print(f"Streaming {lang}/{variant} for {clip_id} (~{entry['source']['dur_s']}s real time)...")
                try:
                    result = provider.transcribe_stream_translated(
                        wav_path,
                        target_lang=lang,
                        context=context if variant == "context" else None,
                    )
                    with open(fix_path, "w") as f:
                        json.dump(result.model_dump(), f, indent=2, ensure_ascii=False)
                    print(f"  -> {len(result.final_text)} chars, {len(result.events)} events")
                except Exception as e:
                    print(f"  FAILED {lang}/{variant} for {clip_id}: {e}")


if __name__ == "__main__":
    main()
