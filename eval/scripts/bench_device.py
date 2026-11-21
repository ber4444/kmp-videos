"""Run already-staged native ASR helpers on one explicitly selected Android device.

See ../native/README.md for runtime/model preparation. Never calls a cloud API.
"""
import argparse
import array
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import wave

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import config


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("provider", choices=["moonshine", "whistle"])
    parser.add_argument("mode", choices=["batch", "stream"])
    parser.add_argument("--device", required=True, help="Explicit adb serial; never auto-select a phone")
    parser.add_argument("--remote-root", default="/data/local/tmp/apollo-pr110")
    parser.add_argument("--output-dir", type=Path, default=Path(config.FIXTURES_DIR) / "device-bench")
    parser.add_argument("--max-clips", type=int)
    parser.add_argument("--keep-awake", action="store_true", help="Touch the top display edge between clips to refresh inactivity")
    args = parser.parse_args()
    if args.max_clips is not None and args.max_clips <= 0:
        parser.error("--max-clips must be positive")
    adb = ["adb", "-s", args.device]
    def shell(*command):
        return subprocess.run(adb + ["shell", *command], check=True, capture_output=True, text=True, timeout=300).stdout
    hardware = {"device_model": shell("getprop", "ro.product.model").strip(), "soc": shell("getprop", "ro.soc.model").strip()}
    entries = json.loads(Path(config.MANIFEST_PATH).read_text())["entries"][:args.max_clips]
    root = args.remote_root.rstrip("/")
    shell("mkdir", "-p", root + "/clips")
    for entry in entries:
        clip_id = entry["id"]
        audio = Path(config.CLIPS_DIR) / f"{clip_id}.wav"
        audio_hash = hashlib.sha256(audio.read_bytes()).hexdigest()
        out = args.output_dir / args.provider / args.mode / f"{clip_id}.json"
        # A benchmark invocation is always a new measurement; choose a new output
        # directory instead of silently reusing results from another runtime.
        if out.exists():
            parser.error(f"Output already exists: {out}. Select a fresh --output-dir.")
        with wave.open(str(audio)) as wav:
            if (wav.getnchannels(), wav.getsampwidth(), wav.getframerate()) != (1, 2, 16000):
                parser.error(f"Expected 16kHz mono s16le: {audio}")
            samples = array.array("h", wav.readframes(wav.getnframes()))
        if sys.byteorder != "little":
            samples.byteswap()
        floats = array.array("f", (sample / 32768 for sample in samples))
        if sys.byteorder != "little":
            floats.byteswap()
        with tempfile.TemporaryDirectory() as temporary:
            pcm = Path(temporary) / f"{clip_id}.f32"
            pcm.write_bytes(floats.tobytes())
            subprocess.run(adb + ["push", str(pcm), root + "/clips/" + pcm.name], check=True, capture_output=True)
        if args.keep_awake:
            shell("input", "tap", "1", "1")
        model = root + ("/moonshine-model" if args.provider == "moonshine" else "/whistle.cact")
        print(f"{args.provider} {args.mode}: {clip_id}", flush=True)
        raw = shell("LD_LIBRARY_PATH=" + root, root + "/" + args.provider + "-bench", model, root + "/clips/" + clip_id + ".f32", args.mode)
        result = json.loads(raw)
        if len(result["runs"]) != 2:
            raise ValueError("Expected two inference runs")
        result.update(hardware, provider=args.provider, mode=args.mode, clip_id=clip_id, audio_sha256=audio_hash)
        out.parent.mkdir(parents=True, exist_ok=True)
        with out.open("x") as destination:
            json.dump(result, destination, indent=2)


if __name__ == "__main__":
    main()
