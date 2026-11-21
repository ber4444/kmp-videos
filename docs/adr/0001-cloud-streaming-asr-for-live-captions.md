# ADR 0001 — Live captions use cloud streaming ASR, not on-device recognition

- **Status:** Accepted
- **Date:** 2026-07 (decided), documented 2026-09-08
- **Deciders:** maintainer + implementer
- **Supersedes:** nothing. **Amended by:** key handling in PR #74/#77, caption preference
  persistence in PR #82, and the 2026-11-21 vendor reevaluation in PR #110.

## Context

Live events needed real-time captions. The obvious first choice was on-device
recognition: no per-minute bill, no audio leaving the phone, no network dependency
in a feature that has to work while the video is already streaming. That is the
version anyone would prefer to ship, and it is the version this app tried first.

It was built — whisper.cpp via WhisperJNI — and measured on a Samsung Fold3
(Snapdragon 888), which is a fair mid-to-upper Android target rather than a weak one.

## What the attempt showed

- **`base.en` runs ~2× slower than real time**: roughly 7 s of compute per 4 s
  window. Audio arrives faster than it can be consumed, so about half of it is
  dropped on the floor. The captions that result are not merely late, they are
  *fragmentary* — chunks of a sentence with the middle missing, which is worse than
  no captions because a viewer cannot tell what was skipped.
- **Multi-threading did not rescue it.** It hit a native GGML barrier deadlock,
  a failure inside the inference library rather than in the calling code.
- **The debug build was a red herring worth naming**: the `-O0` native build ran
  ~25× too slow, which is easy to mistake for "just needs optimisation". It did not.
  The optimised build is the ~2× figure above.

Original conclusion: this optimized Whisper build could not keep up on the Fold3.
That result does not establish a limit for other recognition architectures or newer
hardware; the later Pixel measurements below narrow the original speed argument.

## Decision

**Live events use cloud streaming ASR.** Audio is streamed to a hosted recognizer
over a websocket and captions come back incrementally.

Two providers were wired behind a provider-agnostic interface so the choice stayed a
config change rather than a rewrite. Soniox was later measured to be clearly better on
this material (0.242 vs 0.350 normalized WER; 0.77 vs 0.49 Entity F1) and Deepgram was
switched off — see the eval harness in [`eval/`](../../eval). Deepgram's streaming API
also has no translation, so it could only ever caption in English.

## Consequences

**Accepted costs:**

- **Audio leaves the device.** This is the real price, and it is a user-visible one.
  It is disclosed in the README and in the
  [privacy policy](https://ber4444.github.io/kmp-videos/privacy/). Only the *media's*
  audio is sent, and only while captions are on; the app holds no microphone
  permission at all, which bounds the claim to something checkable.
- **It is metered.** PR #82 persists an explicit language or off choice across videos
  and launches, accepting that later videos may start billed sessions without another
  confirmation. With no saved choice, supported non-English device languages start
  captions; English and unsupported device languages start off. An explicit off choice
  remains off rather than reapplying the device-language default.
- **It needs the network**, in a feature used while already streaming video. The
  transcriber reconnects on a backoff schedule rather than dying at the first drop.

**Provided by the current cloud path:**

- **In-band translation.** Soniox returns translated tokens on the same socket at no
  extra cost, so a viewer reads captions in any of 22 languages off English audio,
  with no second vendor in the path. This turned out to be the feature's main value,
  Neither local ASR model evaluated below supplies that translated-caption feature itself.

**Amendment (PR #74/#77):** the original design shipped the API key in the app via
`secrets.properties` → `BuildConfig`/`Info.plist`. That is readable in a shipped
binary, so the key was effectively public. The app now holds only a token-service URL
and fetches a single-use key per session from [`:server`](../../server). Anything in
the older plan document describing an embedded key is superseded and must not be
reintroduced.

## Reevaluation on 2026-11-21

The [vendor refresh report](../../eval/VENDOR_REFRESH_RESULTS_2026.md) records all 21
verified clips. Soniox v6 batch mean WER is 0.268 versus historical v5's 0.242;
streaming WER improves from 0.309 to 0.293 while finalization latency worsens. V6
async HU/RU translation with the final app context scores 54.1/54.6 chrF against
existing DeepL references, versus 52.5/53.9 without context. Those are reference
agreement scores, not a human quality assessment. Historical translation requests lack
matched configuration provenance, so their differences are not controlled version deltas.

At the user's request, standalone native benchmarks used a Pixel 7a (Tensor G2,
Android 17) instead of the unavailable Fold3. Moonshine Medium Streaming and Whistle
ran all 21 clips twice, with warm median compute RTF 0.113 and 0.026. Throughput is
therefore no longer a blanket reason to reject local recognition on that phone.
Their unboosted mean WERs are 0.549 and 0.643, and neither model itself supplies the
22-language translated-caption feature. A one-clip paced streaming smoke is recorded
separately; no concurrent HLS/Compose test or Fold3 replication was performed.

The cloud-caption decision stands for the current product. Its justification is now
recognition quality on this material, translated captions, and the remaining integration
work, rather than a claim that every local recognizer must be too slow. The older oracle
context experiments remain diagnostic studies with reference leakage; they are not a
guaranteed quality ceiling or shippable input. See the [historical aggregate tables](../../eval/snapshots/v5/README.md).

## Alternatives considered

| Option | Why not |
|---|---|
| Larger Whisper model on the original Fold3 setup | The measured `base.en` already failed to keep up; other architectures need their own measurements |
| Wait for faster hardware | Does not help the devices the audience owns today |
| On-device as an offline tier | Kept in git history; a fragmentary-caption fallback was judged worse than an honest "captions unavailable" |
| Batch transcription for live | Not applicable — a live event has no file to batch. It *is* the right answer for VOD, tracked separately |
