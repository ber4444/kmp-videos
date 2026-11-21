"""Async in-band Soniox translation, isolated from ASR/DeepL fixtures.

Official API contracts:
https://docs.soniox.com/translation/stt-translation/async-translation
https://docs.soniox.com/translation/stt-translation#token-format
Async results measure file translation quality, not real-time caption behavior.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import wave

from soniox.client import SonioxClient
from soniox.api.stt import CreateTranscriptionConfig

import config
from vendor_context import GLOSSARY_PATH, load_app_context

MODEL = "stt-async-v6"


def request_config(target, context_arm):
    if target not in {"hu", "ru"} or context_arm not in {"none", "app"}:
        raise ValueError("Unsupported translation target or context arm")
    request = {"model": MODEL, "language_hints": ["en"],
               "translation": {"type": "one_way", "target_language": target}}
    if context_arm == "app":
        request["context"] = load_app_context(target)
    return request


def split_tokens(tokens, target):
    original, translated = [], []
    for token in tokens:
        text = token["text"]
        if text in {"<end>", "<fin>"}:
            continue
        status = token.get("translation_status")
        if status in {"original", "none"}:
            original.append(text)
        elif status == "translation":
            # Soniox omits language tags unless identification was requested.
            if token.get("language") not in {None, target}:
                raise ValueError("Translated token language differs from requested target")
            translated.append(text)
        else:
            raise ValueError(f"Unexpected translation_status: {status!r}")
    asr_text, translation_text = "".join(original).strip(), "".join(translated).strip()
    if not asr_text or not translation_text:
        raise ValueError("Expected nonempty original and translated text; refusing a partial fixture")
    return asr_text, translation_text


def _valid_cache(path, identity):
    try:
        data = json.loads(path.read_text())
        if any(data.get(key) != value for key, value in identity.items()):
            raise ValueError("request/audio identity mismatch")
        asr, translated = split_tokens(data["raw_response"]["tokens"], identity["target_language"])
        if (data["asr_text"], data["translation_text"]) != (asr, translated):
            raise ValueError("text/token mismatch")
    except (ValueError, KeyError, TypeError) as error:
        raise ValueError(f"Invalid cache {path}: {error}. Move it aside explicitly before recording.") from error


def record(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", choices=["hu", "ru"], nargs="+", default=["hu", "ru"])
    parser.add_argument("--context", choices=["none", "app"], nargs="+", default=["none", "app"])
    parser.add_argument("--max-clips", type=int, help="Use only the first N manifest entries")
    parser.add_argument("--dry-run", action="store_true", help="Plan without network calls or writes")
    args = parser.parse_args(argv)
    if args.max_clips is not None and args.max_clips <= 0:
        parser.error("--max-clips must be positive")
    try:
        entries = json.loads(Path(config.MANIFEST_PATH).read_text())["entries"]
        if args.max_clips is not None:
            entries = entries[:args.max_clips]
        jobs = []
        for entry in entries:
            clip_id = entry["id"]
            if Path(clip_id).name != clip_id or clip_id in {".", ".."}:
                raise ValueError("Unsafe clip id in manifest")
            audio = Path(config.CLIPS_DIR) / f"{clip_id}.wav"
            with wave.open(str(audio), "rb") as wav:
                duration = wav.getnframes() / wav.getframerate()
            if duration <= 0:
                raise ValueError(f"Empty audio: {audio}")
            audio_hash = hashlib.sha256(audio.read_bytes()).hexdigest()
            for arm in dict.fromkeys(args.context):
                for target in dict.fromkeys(args.target):
                    request = request_config(target, arm)
                    identity = {"schema": "soniox-inband-v1", "model": MODEL, "mode": "async",
                                "clip_id": clip_id, "target_language": target, "context_arm": arm,
                                "request_config": request, "audio_sha256": audio_hash,
                                "audio_duration_s": duration,
                                "glossary_sha256": hashlib.sha256(GLOSSARY_PATH.read_bytes()).hexdigest() if arm == "app" else None}
                    out = Path(config.FIXTURES_DIR) / f"soniox-inband-{arm}" / f"{clip_id}.{target}.json"
                    if out.exists():
                        _valid_cache(out, identity)
                        print(f"Skipping valid cache: {out}")
                    else:
                        jobs.append((audio, out, identity))
        billed = sum(identity["audio_duration_s"] for _, _, identity in jobs)
        print(f"Pending recordings: {len(jobs)}; billed audio: {billed:g}s ({billed / 60:g} min).")
        for audio, out, _ in jobs:
            print(f"  {audio.name} -> {out}")
        if billed > config.MAX_BILLED_AUDIO_SECONDS:
            parser.error(f"Billed audio {billed:g}s exceeds cap of {config.MAX_BILLED_AUDIO_SECONDS}s")
    except (ValueError, KeyError, OSError, wave.Error) as error:
        parser.error(str(error))
    if args.dry_run or not jobs:
        return
    if not os.environ.get("SONIOX_API_KEY"):
        parser.error("SONIOX_API_KEY is not set")
    client = SonioxClient(api_key=os.environ["SONIOX_API_KEY"])
    for audio, out, identity in jobs:
        print(f"Recording {out}...", flush=True)
        try:
            request = dict(identity["request_config"])
            model = request.pop("model")
            response = client.stt.transcribe_and_wait_with_tokens(
                file=str(audio), model=model, config=CreateTranscriptionConfig(**request), wait_timeout_sec=600,
            )
            raw = response.model_dump(mode="json")
            asr, translated = split_tokens(raw["tokens"], identity["target_language"])
            result = {**identity, "recorded_at": datetime.now(timezone.utc).isoformat(),
                      "asr_text": asr, "translation_text": translated, "raw_response": raw}
            serialized = json.dumps(result, indent=2, ensure_ascii=False)
            out.parent.mkdir(parents=True, exist_ok=True)
            with out.open("x") as destination:
                destination.write(serialized)
        except Exception as error:
            # Fail fast. Retrying an unrecorded remote job may incur another charge.
            print(f"Failed recording {out}: {error}", flush=True)
            raise SystemExit(1) from error
