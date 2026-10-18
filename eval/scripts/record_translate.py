"""Record Soniox's in-band translation to fixtures/soniox-translate[-nocontext|-batch]/{lang}/.

This is a spending script: the real-time arms stream each clip at wall-clock pace, so cost ≈
audio duration × languages × arms. Scoring replays the fixtures for free.

Three variants, because the interesting question is not only *how good* the translation is
but *what moves it*:

- `context` (default) — the session `context` the app actually sends, read out of
  `CaptionGlossary.kt` by `app_context.py`: the domain sentence, the boosted terms, and the
  accepted renderings for this language.
- `nocontext` — the same audio with no context at all. The gap between the two is what the
  glossary and the domain sentence are worth for that language, which is the only way to
  know whether adding terms is a fix worth continuing.
- `batch` — the same context through the **async** API, which has the whole clip before it
  answers. Real-time translation must emit Hungarian before the clause is finished; batch
  never does. The gap between this arm and `context` is therefore the streaming penalty on
  its own, separated from what the model can do with this language at all — the difference
  between "captions could be better if we bought latency" and "no client change will fix
  this". It is also the cheap arm: no real-time pacing, so it runs as fast as the API answers.
- `endpointed` — real time, with Soniox's own endpoint detection on so it finalizes at
  utterance boundaries instead of mid-clause. Measured on **flicker and finalization
  latency, not chrF**: `batch` already bounds what any latency-buying mechanism can do for
  quality, and on Hungarian that bound was ~1 chrF. Captions that stop rewriting themselves
  are worth having anyway, and this is the cheapest way to get them — one config field
  instead of a client-side buffering design.

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

VARIANTS = ("context", "nocontext", "batch", "endpointed")

# Every arm but batch is paced at wall-clock; batch answers as fast as it answers.
STREAMED_VARIANTS = ("context", "nocontext", "endpointed")

FIXTURE_DIRS = {
    "context": lambda: config.INBAND_DIR,
    "nocontext": lambda: config.INBAND_NOCONTEXT_DIR,
    "batch": lambda: config.INBAND_BATCH_DIR,
    "endpointed": lambda: config.INBAND_ENDPOINTED_DIR,
}


def fixture_dir(variant: str, lang: str) -> str:
    path = os.path.join(FIXTURE_DIRS[variant](), lang)
    os.makedirs(path, exist_ok=True)
    return path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--targets", default="hu",
                        help="Comma-separated Soniox language codes, e.g. hu,ru,es")
    parser.add_argument("--variant", default="context", choices=(*VARIANTS, "both", "all"),
                        help="Which session config to record; 'both' = the real-time A/B, "
                             "'all' adds the batch ceiling")
    parser.add_argument("--max-clips", type=int, default=None)
    parser.add_argument("--force", action="store_true", help="Bypass the billed-audio cap")
    parser.add_argument("--endpoint-config", default=None,
                        help="JSON overriding config.ENDPOINTING_CONFIG for the endpointed arm, "
                             "e.g. '{\"enable_endpoint_detection\": true, "
                             "\"max_endpoint_delay_ms\": 3000}'")
    args = parser.parse_args()

    endpoint_config = json.loads(args.endpoint_config) if args.endpoint_config \
        else config.ENDPOINTING_CONFIG

    if not os.environ.get("SONIOX_API_KEY"):
        print("Error: SONIOX_API_KEY is not set. Add it to eval/.env.")
        sys.exit(1)

    os.environ["EVAL_LIVE"] = "1"
    langs = [c.strip() for c in args.targets.split(",") if c.strip()]
    variants = {
        "both": STREAMED_VARIANTS,
        "all": VARIANTS,
    }.get(args.variant, (args.variant,))

    with open(config.MANIFEST_PATH, "r") as f:
        entries = json.load(f)["entries"]
    if args.max_clips is not None:
        entries = entries[:args.max_clips]

    clip_seconds = sum(e["source"]["dur_s"] for e in entries)
    total_billed = clip_seconds * len(langs) * len(variants)
    paced = clip_seconds * len(langs) * len([v for v in variants if v in STREAMED_VARIANTS])
    print(f"Recording {len(entries)} clips × {len(langs)} languages × {len(variants)} variant(s).")
    print(f"Billed audio: {total_billed}s. Real-time paced: {paced}s (~{paced/60:.0f} min wall-clock); "
          f"the rest is batch and runs as fast as the API answers.")
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

                pacing = "real time" if variant in STREAMED_VARIANTS else "batch"
                print(f"Recording {lang}/{variant} for {clip_id} ({entry['source']['dur_s']}s of audio, {pacing})...")
                try:
                    if variant == "batch":
                        result = provider.transcribe_batch_translated(
                            wav_path, target_lang=lang, context=context)
                        caption, detail = result.text, "batch"
                    else:
                        result = provider.transcribe_stream_translated(
                            wav_path,
                            target_lang=lang,
                            # The nocontext arm is the ablation: same audio, nothing told.
                            # The endpointed arm keeps the context and changes only when
                            # Soniox decides a caption is finished.
                            context=None if variant == "nocontext" else context,
                            endpointing=endpoint_config if variant == "endpointed" else None,
                        )
                        caption, detail = result.final_text, f"{len(result.events)} events"
                    with open(fix_path, "w") as f:
                        json.dump(result.model_dump(), f, indent=2, ensure_ascii=False)
                    print(f"  -> {len(caption)} chars, {detail}")
                except Exception as e:
                    print(f"  FAILED {lang}/{variant} for {clip_id}: {e}")


if __name__ == "__main__":
    main()
