# ADR 0001 — Live captions use cloud streaming ASR, not on-device recognition

- **Status:** Accepted
- **Date:** 2026-07 (decided), documented 2026-09-08
- **Deciders:** maintainer + implementer
- **Supersedes:** nothing. **Amended by:** the key-handling change in PR #74/#77 (below).

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

Conclusion: this hardware cannot do good real-time captions on-device. Not "not yet
tuned" — the gap is a factor of two on the *optimised* build of the *small* model,
and the models that would be more accurate are larger and slower.

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
- **It is metered.** That shaped the UI: captions default to off and reset to off on
  every video, so a language picked once cannot quietly keep billing on everything
  watched afterwards. Persisting the choice is deliberately *not* implemented until
  that billing surprise is solved with it.
- **It needs the network**, in a feature used while already streaming video. The
  transcriber reconnects on a backoff schedule rather than dying at the first drop.

**Unlocked, and not available on-device at any speed:**

- **In-band translation.** Soniox returns translated tokens on the same socket at no
  extra cost, so a viewer reads captions in any of 22 languages off English audio,
  with no second vendor in the path. This turned out to be the feature's main value,
  and no on-device design was going to offer it.

**Amendment (PR #74/#77):** the original design shipped the API key in the app via
`secrets.properties` → `BuildConfig`/`Info.plist`. That is readable in a shipped
binary, so the key was effectively public. The app now holds only a token-service URL
and fetches a single-use key per session from [`:server`](../../server). Anything in
the older plan document describing an embedded key is superseded and must not be
reintroduced.

## What later measurement said about this decision

The eval harness (n=21 paired clips, Hungarian and Russian) found the remaining caption
quality problem is **not** the cloud/on-device axis and not the translation: ~13 chrF
points are lost to speech-recognition error, against ~2–5 for every translation-side
choice combined. Supplying the vocabulary in advance is worth up to +8.8 — more than
every other lever together. See the plan document's measurement sections.

That does not reopen this ADR (on-device would be worse at exactly the thing that
matters, recognition accuracy), but it does mean the next investment is in *what the
recognizer is told*, not in *which recognizer* or *where it runs*.

## Alternatives considered

| Option | Why not |
|---|---|
| Larger on-device model | Strictly slower than `base.en`, which is already 2× too slow |
| Wait for faster hardware | Does not help the devices the audience owns today |
| On-device as an offline tier | Kept in git history; a fragmentary-caption fallback was judged worse than an honest "captions unavailable" |
| Batch transcription for live | Not applicable — a live event has no file to batch. It *is* the right answer for VOD, tracked separately |
