"""Shared batch/stream recording controls; planning never calls a provider."""
import argparse
import json
import os
from pathlib import Path
import wave

import config
from providers import DeepgramProvider, SonioxProvider, TranscriptResult, StreamResult
from providers.soniox import BATCH_MODEL, STREAM_MODEL


def record(*, streaming=False):
    parser = argparse.ArgumentParser()
    parser.add_argument("--force", action="store_true", help="Bypass the billed-audio cap")
    parser.add_argument("--provider", choices=["deepgram", "soniox"], nargs="+",
                        default=["deepgram", "soniox"], help="Record only these providers")
    parser.add_argument("--max-clips", type=int, help="Only record the first N manifest clips")
    parser.add_argument("--dry-run", action="store_true", help="Print pending jobs and cost without calling APIs")
    if not streaming:
        parser.add_argument("--baseline-only", action="store_true", help="Skip the keyterm-boosted arm")
    args = parser.parse_args()
    if args.max_clips is not None and args.max_clips <= 0:
        parser.error("--max-clips must be positive")

    with open(config.MANIFEST_PATH) as f:
        entries = json.load(f)["entries"]
    if args.max_clips is not None:
        entries = entries[:args.max_clips]

    jobs = []
    missing = []
    audio_durations = {}
    for entry in entries:
        clip_id = entry["id"]
        wav_path = Path(config.CLIPS_DIR) / f"{clip_id}.wav"
        for name in dict.fromkeys(args.provider):
            boosts = [False] if streaming or args.baseline_only else [False, True]
            for boost in boosts:
                if boost and not entry.get("domain_terms"):
                    continue
                suffix = "-stream" if streaming else "-boost" if boost else ""
                out = Path(config.FIXTURES_DIR) / f"{name}{suffix}" / f"{clip_id}.json"
                if out.exists():
                    if name == "soniox":
                        expected_model = STREAM_MODEL if streaming else BATCH_MODEL
                        try:
                            cached = json.loads(out.read_text())
                            cached_model = cached.get("model")
                        except (OSError, ValueError, AttributeError) as error:
                            parser.error(f"Invalid fixture {out}: {error}")
                        if cached_model != expected_model:
                            parser.error(f"Cached model {cached_model!r} differs from {expected_model!r}: {out}. Select a separate EVAL_FIXTURES_DIR for the new run.")
                        try:
                            (StreamResult if streaming else TranscriptResult).model_validate(cached)
                        except ValueError as error:
                            parser.error(f"Invalid fixture {out}: {error}")
                    print(f"Skipping {out}, fixture exists.")
                    continue
                if not wav_path.exists():
                    missing.append(str(wav_path))
                    continue
                if clip_id not in audio_durations:
                    try:
                        with wave.open(str(wav_path), "rb") as audio:
                            audio_durations[clip_id] = audio.getnframes() / audio.getframerate()
                    except (OSError, wave.Error) as error:
                        parser.error(f"Invalid audio {wav_path}: {error}")
                    if audio_durations[clip_id] <= 0:
                        parser.error(f"Empty audio: {wav_path}")
                jobs.append((entry, name, boost, wav_path, out))

    if missing:
        parser.error("Missing audio: " + ", ".join(dict.fromkeys(missing)))
    total_billed = sum(audio_durations[entry["id"]] for entry, *_ in jobs)
    print(f"Pending recordings: {len(jobs)}; billed audio: {total_billed:g}s.")
    for _, name, boost, wav_path, out in jobs:
        print(f"  {name} (boost={boost}): {wav_path.name} -> {out}")
    if total_billed > config.MAX_BILLED_AUDIO_SECONDS and not args.force:
        parser.error(f"Billed audio {total_billed:g}s exceeds cap of {config.MAX_BILLED_AUDIO_SECONDS}s. Use --force to override.")
    if args.dry_run or not jobs:
        return

    providers = {"deepgram": DeepgramProvider, "soniox": SonioxProvider}
    for name in {name for _, name, *_ in jobs}:
        if not os.environ.get(f"{name.upper()}_API_KEY"):
            parser.error(f"{name.upper()}_API_KEY is not set")
    os.environ["EVAL_LIVE"] = "1"
    for entry, name, boost, wav_path, out in jobs:
        print(f"Recording {out}...", flush=True)
        provider = providers[name]()
        try:
            result = (provider.transcribe_stream(str(wav_path)) if streaming else
                      provider.transcribe(str(wav_path), entry.get("domain_terms", []), boost))
        except Exception as e:
            # Stop rather than spend on the remainder after a broken smoke test.
            print(f"Failed to record {name}: {e}", flush=True)
            raise SystemExit(1) from e
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(result.model_dump(), indent=2))
