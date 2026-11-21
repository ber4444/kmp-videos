# Native ASR benchmark helpers

These standalone executables measure English recognition on Android. They do not
integrate an engine into Apollo Videos or start an HLS/Compose workload. The input
is little-endian float32 mono PCM at 16 kHz; the host runner converts the golden WAVs.
Both helpers keep a model loaded for two runs. `batch` transcribes the whole file;
`stream` paces chunks against a monotonic clock and flushes the final tail.

`rtf = inference compute seconds / audio seconds`, excluding model load and pacing
sleeps. A value below 1 means this standalone workload can keep up on this device.
It is not caption finalization latency or evidence of app frame-rate headroom.
Moonshine takes 200 ms chunks and updates every 600 ms; Whistle takes 1 s chunks
through its own streaming API. Streaming smoke tests use only the first golden clip.

## Runtime versions used

- Moonshine Voice **0.1.6**, English Medium Streaming, C API **30000**. Android shared
  libraries came from Maven Central's `ai.moonshine:moonshine-voice:0.1.6` AAR,
  `jni/arm64-v8a/libmoonshine.so` and `libonnxruntime.so`. Header source:
  [moonshine d8d8fa29](https://github.com/moonshine-ai/moonshine/tree/d8d8fa29d05ca67441a7719e70099f9f48917a93/core).
  Weights: `medium-streaming-en/quantized_26_11_02`, obtained with
  `moonshine_voice.get_model_for_language("en", ModelArch.MEDIUM_STREAMING)`.
  This checkpoint's downloaded model directory totals 269,306,511 bytes.
- Python `cactus-compute` **4.2.6** selected engine **4.1.4**, revision
  `f4ab2741203a282c6e7b89983de2b5e5b2e805a8`. Use
  `cactus.agent.fetch.download_platform("android-arm64", destination, generation="whistle")`
  for `cactus.h` and `libcactus.a`. Whistle weights: Hugging Face
  `Cactus-Compute/whistle`, revision `88cb00eeb8928ddbcd410b491584f938ba030b07`,
  `whistle.cact` (16,919,407 bytes). Four worker threads, English forced, word
  timestamps enabled for batch. No keyterms. Disable Python runtime telemetry with
  `CACTUS_TELEMETRY=0` for desktop checks.

## Build and run

Use NDK **28.2.13676358** and its `aarch64-linux-android26-clang++`. Set `CXX` to that
compiler, `MOONSHINE_CORE` to the pinned header directory, `MOONSHINE_LIBS` to the
extracted AAR libraries, and `CACTUS_ANDROID` to the downloaded Android platform:

```bash
"$CXX" -O3 -std=c++17 -static-libstdc++ -I"$MOONSHINE_CORE" \
  moonshine_bench.cpp -L"$MOONSHINE_LIBS" -lmoonshine \
  -Wl,-rpath,'$ORIGIN' -o moonshine-bench
"$CXX" -O3 -std=c++17 -static-libstdc++ -I"$CACTUS_ANDROID" \
  whistle_bench.cpp "$CACTUS_ANDROID/libcactus.a" -llog -lm -o whistle-bench
```

With an explicitly chosen `adb -s "$DEVICE"`, create
`/data/local/tmp/apollo-pr110/`. Copy the two helpers there, make them executable,
copy Moonshine's two `.so` files alongside them, put its model files directly in
`moonshine-model/`, and put `whistle.cact` at the root. Do not install or replace
Apollo Videos. Run from `eval/`, with a fresh, gitignored output directory:

```bash
venv/bin/python scripts/bench_device.py moonshine batch --device "$DEVICE" \
  --output-dir fixtures/vendor-refresh-2026-v6/device-repeat
venv/bin/python scripts/bench_device.py whistle batch --device "$DEVICE" \
  --output-dir fixtures/vendor-refresh-2026-v6/device-repeat
venv/bin/python scripts/bench_device.py moonshine stream --max-clips 1 --device "$DEVICE" \
  --output-dir fixtures/vendor-refresh-2026-v6/device-repeat
venv/bin/python scripts/bench_device.py whistle stream --max-clips 1 --device "$DEVICE" \
  --output-dir fixtures/vendor-refresh-2026-v6/device-repeat
```

Run engines sequentially. The optional `--keep-awake` flag refreshes input activity
between clips with a tap at the top display edge. It cannot unlock a secure keyguard.
Record the hardware explicitly: this refresh used a **Pixel 7a, Tensor G2 / GS201,
Android 17**, substituted at the user's request. It did not reproduce the older
Fold3/Snapdragon 888 experiment, whose original measurements are described in [ADR 0001](../../docs/adr/0001-cloud-streaming-asr-for-live-captions.md). The exact original audio and executable are unavailable.
The second inference run per clip supplies the reported warm-run WER and RTF.
