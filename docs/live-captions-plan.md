# Live Captions — Streaming ASR (Deepgram + Soniox) Implementation Plan

> **Living document.** Each step is checked off with a note when complete so another
> agent can resume. Read "Current status" first, then the step whose box is unchecked.

> **Superseded on key handling.** Steps 1.7/2.x below describe reading
> `SONIOX_API_KEY`/`DEEPGRAM_API_KEY` out of `secrets.properties` into `BuildConfig`,
> `Info.plist` and the wasm bundle. That is no longer how it works, and must not be
> reintroduced: those are all readable in a shipped binary, so the key was public. The
> app now holds only `SONIOX_TOKEN_URL` and fetches a single-use key per session from
> the `:server` module. See [server/README.md](../server/README.md).

## Context / why

On-device Whisper (whisper.cpp via WhisperJNI) was tried for real-time captions and
**abandoned for real-time use**: on a Samsung Fold3 (SD888) base.en runs ~2× slower
than real time (~7 s per 4 s window), so ~half the audio is dropped → fragmented,
low-quality captions; multi-thread hit a native GGML barrier deadlock; the debug
`-O0` native build was ~25× too slow. Full diagnosis is in git history of
`TranscriptionEngine.kt`. Conclusion: **on-device can't do good real-time captions on
this hardware.**

Decision (with the maintainer):
- **LIVE events → cloud streaming ASR.** Two providers wired with a **UI switch**:
  **Deepgram** (default; recognizable for a portfolio, mature SDK/docs, $200 free
  credit) and **Soniox** (cheapest at $0.12/hr, bundles translation/diarization).
  Behind a provider-agnostic interface so swapping is a config change.
- **VOD events → batch subtitles** (out of scope for this doc; see "VOD path" note at
  bottom). Not needed for the live streaming feature.
- **Translation**: optional. Soniox includes real-time translation in-band; DeepL can
  be added later as a post-process. Not mandatory for MVP. **Measured 2026-09-07 and the
  post-process is not worth building** — see "Translation quality: what was measured"
  below. In-band stays.
- **Platforms**: Android + iOS **mandatory**, web **optional**.
- **API keys**: gitignored config file → per-platform build wiring → runtime holder.
  Keys must never be committed and never hard-coded in shared code.

## Architecture

Most code is shared; only three things are per-platform.

**Shared (`composeApp/src/commonMain`)**
- `CaptionCue` (moved from androidMain)
- `TranscriptionProvider` enum (`DEEPGRAM`, `SONIOX`) + persisted selection
- `StreamingTranscriber` interface
- `DeepgramClient`, `SonioxClient` — Ktor websocket clients (Ktor is multiplatform)
- `TranscriptionSecrets` — runtime holder for API keys, set at startup per platform
- Caption overlay Composable (Compose Multiplatform)

**Per-platform**
| Piece | Android | iOS | Web |
|---|---|---|---|
| PCM tap | `TeeAudioProcessor` (exists) | `MTAudioProcessingTap` on AVPlayer | WebAudio `AudioWorklet` |
| Key provisioning | `androidApp` BuildConfig | xcconfig / Info.plist | build-time env |
| Ktor engine | `ktor-client-android` | `ktor-client-darwin` | `ktor-client-js` |

PCM contract fed to a `StreamingTranscriber`: **16 kHz, mono, signed 16-bit little-endian**.
Resampling from the source format (usually 48 kHz stereo) happens on the platform side
before `feedPcm` (reuse the anti-aliased resampler already in `TranscriptionEngine`).

### Provider protocol notes (for implementers)
- **Deepgram** (`wss://api.deepgram.com/v1/listen`): query params
  `model=nova-3&encoding=linear16&sample_rate=16000&channels=1&interim_results=true&punctuate=true&smart_format=true`.
  Auth header `Authorization: Token <KEY>`. Send raw PCM16 as binary frames. Receive
  JSON: `channel.alternatives[0].transcript` + top-level `is_final`. Finish by sending
  text frame `{"type":"CloseStream"}`.
- **Soniox** (`wss://stt-rt.soniox.com/transcribe-websocket`, verify current host in
  docs): first send a JSON **config** frame `{api_key, model:"stt-rt-v5",
  audio_format:"pcm_s16le", sample_rate:16000, num_channels:1, language_hints, ...}`,
  then stream PCM16 binary frames. Receive JSON `{tokens:[{text,is_final,...}]}`.
  Finish by sending `{"type":"finalize"}` then an empty frame to close. The config frame
  also takes an optional `context` object — see *Domain glossary* below.
  **Both endpoints/fields must be re-verified against current vendor docs before trusting.**

## Current status

**Phases 0, 1, 2 DONE** — shared core + full Android wiring compile and install on device;
the router receives tapped audio. **End-to-end captions unverified pending a real API key.**
**Next: Phase 3 (iOS).** Nothing committed yet — all uncommitted on `feature/platform-parity`
alongside the on-device Whisper pipeline (kept as an optional offline tier / "why cloud" story).

Build commands used (set `ANDROID_HOME=~/Library/Android/sdk`):
`./gradlew :composeApp:compileCommonMainKotlinMetadata` (shared), `:androidApp:installDebug` (device).

## Steps

### Phase 0 — Secrets / config (gitignored keys)
- [x] 0.1 `secrets.properties.example` created (repo root).
- [x] 0.2 `secrets.properties` created (gitignored, empty keys).
- [x] 0.3 `secrets.properties` added to root `.gitignore` (verified via `git check-ignore`).
- [x] 0.4 `androidApp/build.gradle.kts` reads `secrets.properties` → BuildConfig fields
  `DEEPGRAM_API_KEY` / `SONIOX_API_KEY`. NOTE: needed `import java.util.Properties` at top
  (bare `java.util.X` collides with Gradle's `java` extension accessor in `.kts`).
- [x] 0.5 Push keys into `TranscriptionSecrets` at Android startup — done in Phase 2 below,
  where this line's own note said it would be. Left checked in both places rather than
  deleted here, so the Phase 0 list still reads as a complete account of Phase 0.
  (MainActivity/Application: `TranscriptionSecrets.deepgramApiKey = BuildConfig.DEEPGRAM_API_KEY`, etc.).

### Phase 1 — Shared core (`commonMain`) — DONE
- [x] 1.1 Catalog: added `ktor-client-websockets` + `kotlinx-serialization-json` libs and
  `kotlinx-serialization` plugin (reused existing `ktor`/`kotlinx-serialization` versions).
  Did NOT need content-negotiation (WS frames parsed with `Json` directly).
- [x] 1.2 Added to `composeApp` commonMain deps; applied `libs.plugins.kotlinx.serialization`.
- [x] 1.3 `CaptionCue` moved to `commonMain` (same package, so androidMain refs unchanged);
  deleted `androidMain/.../CaptionCue.kt`.
- [x] 1.4 `TranscriptionProvider` enum + `TranscriptionSettings` (in-memory `StateFlow`).
  Persistence still TODO (DataStore/NSUserDefaults/localStorage).
- [x] 1.5 `TranscriptionSecrets` holder.
- [x] 1.6 `StreamingTranscriber` interface + `TranscriberStatus` + shared `CaptionAccumulator`
  (uses `MutableStateFlow.update`, NOT `synchronized` — the latter is JVM-only, breaks common).
- [x] 1.7 `DeepgramClient` — Ktor WS, `nova-3`, `Authorization: Token` header, binary PCM,
  parses `channel.alternatives[0].transcript` + `is_final`.
- [x] 1.8 `SonioxClient` — Ktor WS, JSON config handshake then binary PCM, token stream with
  `is_final`; commits a cue at sentence end / >80 chars. Endpoint and field shapes are
  **verified against the live service** — the eval harness has since run several hundred
  calls through both the socket and the async API, including the `context`, `translation`
  and token-`translation_status` fields this client sends and reads.
- [ ] 1.9 Shared caption overlay — DEFERRED. Android `CaptionOverlay.kt` (androidMain) still
  works for Android; move to commonMain when doing iOS (Phase 3) so both share it.

All of `commonMain` compiles (`:composeApp:compileCommonMainKotlinMetadata`). `HttpClient { install(WebSockets) }`
resolves the per-platform engine (android/darwin/js already in deps) with no engine arg.

### Phase 2 — Android wiring — DONE (compiles + installs; needs a key to verify captions)
- [x] 2.1 `CaptionAudioRouter` (androidMain singleton, implements new `PcmTapSink`) owns the
  active `StreamingTranscriber`, mirrors its captions/status/error, and switches provider.
  `TranscriptionRenderersFactory` + `CaptionAudioBufferSink` now feed `PcmTapSink` (not the
  Whisper engine); `PlaybackService` passes `CaptionAudioRouter.get()`. On-device Whisper is
  intentionally OUT of this path (kept as dead code / offline-tier story).
- [x] 2.2 Resampler in `CaptionAudioRouter.resampleTo16kMonoS16` (anti-aliased averaging
  decimation) → 16 kHz mono s16le `ByteArray` → `client.feedPcm`.
- [x] 2.3 UI: rewrote `CaptionController` to drive router + `TranscriptionSettings`.
  `CaptionToggleButton` now uses `status`/`error` (CC / CC… / CC● / CC!); added
  `CaptionProviderButton` (shown when enabled) that cycles Deepgram ↔ Soniox.
- [x] 0.5 Keys pushed in `MainActivity.onCreate`:
  `TranscriptionSecrets.deepgramApiKey = BuildConfig.DEEPGRAM_API_KEY` (+ Soniox).
- [~] 2.4 Verified: `:androidApp:installDebug` builds+installs; `CaptionAudioRouter` receives
  the PCM tap (logcat `AudioSink flush` under its tag). NOT yet verified end-to-end with a
  real key + live audio (empty keys → clients emit "Missing … API key" and CC shows "CC!").
  **TO FINISH: put a real key in `secrets.properties`, rebuild, enable CC on a LIVE source,
  confirm captions + the Deepgram/Soniox switch. Also re-verify the Soniox endpoint/fields
  against current docs — SonioxClient is written from research, not run against the service.**

### Phase 3 — iOS wiring (structural; verify in Xcode)
- Shared refactor done (helps iOS/web): provider selection + caption mirroring hoisted into
  `commonMain` `LiveTranscriber`; `CaptionAudioRouter` is now just Android capture+resample
  delegating to it. iOS/web instantiate `LiveTranscriber`, do their own tap, call `feedPcm`.
- **NOTE:** `:composeApp:compileKotlinIosSimulatorArm64` currently FAILS, but on
  **pre-existing** errors in `iosMain/Platform.ios.kt` (`DisposableEffect`/`onDispose`/
  `AVURLAsset`/`AVAssetImageGenerator` unresolved) that are UNRELATED to this feature (I never
  touched that file). The shared `transcription/` code compiled past those (no errors reference
  it). Fix the iOS player build first, then this feature's shared code is iOS-ready.
- [ ] 3.1 `MTAudioProcessingTap` on the AVPlayerItem audio mix → resample to 16k mono s16 →
  `LiveTranscriber.feedPcm`.
- [ ] 3.2 Key provisioning via xcconfig/Info.plist → `TranscriptionSecrets` (set at app launch).
- [ ] 3.3 Caption overlay over the AVPlayer view (move `CaptionOverlay` to commonMain, step 1.9).

### Phase 4 — Web (optional)
- [ ] 4.1 WebAudio `AudioWorklet` PCM tap → `feedPcm` via `ktor-client-js`.

### Phase 5 — Docs / cleanup
- [ ] 5.1 Update README: captions are cloud-streamed for live (audio leaves device);
  correct the "on-device / no network round-trip" claim.
- [ ] 5.2 Short ADR capturing the on-device→cloud decision (the portfolio story).

## Handoff notes (append as you go)
- **New files (commonMain `transcription/`):** `TranscriptionProvider`, `TranscriptionSettings`,
  `TranscriptionSecrets`, `StreamingTranscriber` (+`TranscriberStatus`), `CaptionAccumulator`,
  `DeepgramClient`, `SonioxClient`. `CaptionCue` moved to `commonMain` root package.
- **New files (androidMain):** `CaptionAudioRouter` (+ `PcmTapSink` interface).
- **Changed (androidMain):** `TranscriptionRenderersFactory`, `PlaybackService`,
  `CaptionController` (full rewrite), `Platform.android.kt` (toggle + provider button).
- **Changed (build):** `gradle/libs.versions.toml`, `composeApp/build.gradle.kts` (serialization
  plugin + ws/json deps), `androidApp/build.gradle.kts` (secrets→BuildConfig), `MainActivity.kt`.
- **Gotchas learned:**
  - `.kts`: `java.util.X` collides with Gradle's `java` accessor → `import java.util.Properties`.
  - `synchronized` is JVM-only — don't use in commonMain (`MutableStateFlow.update` instead).
  - `HttpClient { install(WebSockets) }` with NO engine arg resolves the per-platform Ktor engine.
- **Known limitations to address:** provider selection not persisted (in-memory only);
  switching providers creates a new client without closing the old `HttpClient` (minor leak,
  rare); cloud cues use `startMs/endMs = 0` (no content-position stamping — fine for live).
- **Phase 3 (iOS) starting point:** look at `composeApp/src/iosMain` for the AVPlayer path;
  need an `MTAudioProcessingTap` on the AVPlayerItem's `audioMix` → resample to 16k mono s16
  → `StreamingTranscriber.feedPcm`. Reuse the shared clients + `CaptionAudioRouter`-equivalent
  logic (consider hoisting the router's provider-mirroring into a shared `commonMain` class so
  iOS/web reuse it — only the PCM tap stays platform-specific). Keys via xcconfig/Info.plist →
  `TranscriptionSecrets`. Caption overlay: move `CaptionOverlay` to commonMain (step 1.9).

## Resilience: reconnection + keepalive (added after live testing)
A cloud ASR socket does not survive a feature-length video, and before this the first
failure ended captions for the rest of the playback — Soniox's `error_message: "Request
timeout"` (its server drops a stream that goes >20 s without audio or a keepalive, which a
paused video reaches easily) left the error text frozen in the transcript while the video
played on.

- `WebSocketTranscriber.start()` now runs a **session loop**: every session that ends —
  clean close, socket error, or a protocol error reported by a subclass through
  `failSession` — is followed by a reconnect on the `ReconnectPolicy` schedule
  (0.5 s → 10 s exponential, reset after any session that stayed up ≥15 s, so the common
  "ran fine for ten minutes, then timed out" case recovers almost immediately).
  Only `stop()` or a terminal failure (missing/rejected key) ends the loop.
- Each attempt gets a **fresh PCM channel** — audio captured while the socket was down is
  stale by the time a new one opens, so it is dropped rather than replayed.
- `WsSession.close()` was added (both transports) because providers report protocol errors
  as an inbound *message*, not a close; the transcriber ends the socket itself.
- `SonioxClient` sends the documented `{"type":"keepalive"}` control message whenever the
  audio stream goes quiet for 5 s (base-class `idleFrame`), which prevents most of those
  timeouts in the first place; an unparsed frame is now logged, not treated as fatal.
- UI: `TranscriberStatus.RECONNECTING` (`CC↻`) is distinct from `ERROR` (`CC!`), and provider
  errors surface as a transient partial cue ("… reconnecting") that the next result replaces,
  never as a finalized line stuck in the rolling transcript.
- Tests: `ReconnectPolicyTest` (commonTest) for the schedule, and
  `WebSocketTranscriberReconnectTest` (androidHostTest) which drives the whole loop over a
  fake transport on virtual time.

## Domain context: `context.text` + `terms` + `translation_terms` (added after translation shipped)
The talks are full of vocabulary a general model has never been trained to expect, and the
failure is three-sided: the ASR resolves ordinary words (*the work*, *school*, *centers*,
*essence*) in their everyday sense, mis-hears the invented ones outright, and even when it
hears one correctly the translator renders it literally instead of using the term the
tradition already settled on. Soniox takes all three fixes in one optional `context` object on
the config frame (verified against soniox.com/docs/stt/concepts/context):

```json
{ "context": {
    "text": "These recordings are lectures on the Fourth Way, the esoteric teaching of Peter Ouspensky…",
    "terms": ["Uncreated light", "Influence C", "Four wordless breaths"],
    "translation_terms": [{ "source": "Uncreated light", "target": "Несотворённый Свет" }]
} }
```

- `text` — free text naming what the recordings *are*: the Fourth Way, the teaching of Peter
  Ouspensky. One sentence (`CaptionGlossary.DOMAIN`), the same on **every** session, since the
  whole library is one subject. It disambiguates rather than describes — naming the teaching is
  a cheaper fix than boosting every ordinary word used here in a technical sense, and it carries
  into the translation, where the same ambiguity would otherwise come back.
- `terms` — uncommon/invented words, pinning spelling and casing. Sent on **every** session,
  translated or not; getting the English right is the precondition for translating it.
- `translation_terms` — `{source, target}` pairs, only meaningful when translating, and only
  for the one language the session is writing in.
- The remaining section (`general` key-values) is unused so far.
- Whole object caps at ~8,000 tokens (~10,000 chars); over that the API **rejects the
  session**, which `WebSocketTranscriber` would see as an error and retry forever. The domain
  sentence counts against that cap and `CaptionGlossaryTest` counts it in.

`CaptionGlossary` (commonMain) holds all three: the `DOMAIN` sentence, one `TERMS` list, and
one source→target map per language (`hu`, `ru` today), wired in by `LiveTranscriber.createClient`
from the same target the caption menu selected (the device's language by default — see the
caption menu section below). A language with no glossary still gets `text`, `terms` and
Soniox's own translation. `CaptionGlossaryTest` guards
the invariants that hand-editing breaks — every language rendering exactly the terms in
`TERMS`, and the size staying under the cap.

The renderings are the community's accepted terms (49 entries from a 40-row list; rows that
give two English forms, such as *Transforming friction / Suffering* or *Steward (Steward's
work)*, are split into one entry each). Where a row offers a second acceptable wording the
primary is used and the alternate kept in a `//` comment — Soniox takes exactly one target
per source. Correct these against the accepted-terms list, not by ear; the three entries that
deliberately depart from it (Hungarian `Felsőbb` rather than `Magasabb`, to pair with the
list's own `Alsóbb én`) carry the list's wording in a comment.

## Translation quality: what was measured (2026-09-07)

Hungarian and Russian captions read poorly, and there were three candidate explanations.
All three were measured in `eval/` at n=21 paired clips per language. **All three levers are
small, and roughly the same size.**

| | Hungarian | Russian |
|---|---|---|
| In-band, what ships | **54.4** | **53.2** |
| Glossary / domain context (`Δ context`) | +2.5 | +0.6 |
| Every latency-buying scheme (`Δ streaming`) | +2.1 | +2.0 |
| Switching translation engine (`Δ two-stage`) | +2.8 | +5.1 |
| Different-engine floor | **68.1** | **66.2** |

The one lever that is *not* consistent across languages is the glossary: worth +2.5 in
Hungarian and +0.6 in Russian, on the same audio with the same 49 accepted renderings. So
"extend `TERMS`" is not a general answer — it has to be justified per language, and in
Russian it currently earns almost nothing.

**Buying latency will not help.** A real-time translator must emit the target language before
the clause is finished, which costs more in a language that resolves meaning late. The
`batch` arm removes that constraint entirely — the async API reads the whole clip before
answering — and it is worth ~2 chrF in both languages. Batch has strictly more context than
any endpointing or buffering scheme can offer, so that ~2 is the **ceiling** on all of them:
holding the non-final tail, re-translating on sentence end, or Soniox's
`enable_endpoint_detection`. Measured directly, endpoint detection changed flicker by 0.000
and chrF by −0.1.

**There is also no flicker to fix.** The translated arm's flicker is **0.000**: Soniox sends
translated tokens only as `is_final`, so the caption never rewrites what it already showed.
(The untranslated stream flickers at 0.228 — the difference is the translation, not the
measurement.) A client-side no-flicker buffering design would be solving a problem this
configuration does not have.

**Switching translation engine buys ~3–5 points, not the ~20 the scorecard used to claim.**
That figure was an artifact: every arm is scored against DeepL's translation of the
reference, and the Soniox→DeepL arm *was* DeepL output, so it was rewarded for sharing an
engine with the answer key. Scoring one hypothesis against two different engines' ideals
prices the bonus at **+19.4** in Hungarian and **+18.1** in Russian — essentially the whole
reported lead, reproduced in two unrelated languages. A second MT vendor costs per-character
billing, a key path through `:server` and a privacy-policy line; ~3–5 chrF does not buy that.

**What is left is the transcript** — and unlike the levers above, it moves. A flawless
translation of the *perfect* reference scores only 66–68 when its engine does not match the
ideal's, and every ASR-fed path lands at 53–58 regardless of which engine translates. So ~13
points are lost to ASR error against ~2–5 for every translation-side choice combined. **The
words Soniox hears are the bottleneck, not the words it picks when translating them.**

## Fixing the transcript: what vocabulary is worth (2026-09-08)

Same audio, same async model, only the session `context` changes. WER is normalized; chrF is
the Hungarian caption through the translated batch path, against the usual DeepL ideal.

| Context given to Soniox | WER | Caption chrF | Δ chrF |
|---|---|---|---|
| Nothing | 0.242 | 54.9 | — |
| The app's 49-term glossary | 0.205 | 57.0 | **+2.0** |
| This clip's distinctive vocabulary (`oracle-terms`) | 0.192 | 59.7 | **+4.7** |
| This clip's full reference prose (`oracle-full`) | 0.103 | 63.7 | **+8.8** |

The `oracle-*` arms draw their vocabulary from the verified reference, so they are not
shippable — they bound what *any* scheme for supplying vocabulary in advance could achieve,
including slide OCR, a per-lecture term list, or a speaker-supplied glossary.

Three things follow:

1. **Telling Soniox the words is worth more than everything else combined.** +8.8 chrF
   against +2.0/+2.1/+2.8 for glossary, latency and engine choice. It closes roughly
   two-thirds of the 13-point gap to the floor, and Entity F1 reaches 1.000.
2. **Terminology alone is not where most of it lives.** Going from the generic 49-term
   glossary to this clip's own rare vocabulary is only +2.7; going the rest of the way to
   the actual prose is another +4.0. A word list gets a third of the available win — the
   rest comes from knowing the *phrasing*, not just the terms.
3. **So slide OCR pays in proportion to how much of the slide is read aloud.** Slides that
   carry headings and terminology land near `oracle-terms` (+4.7); passages read verbatim
   from the screen approach `oracle-full` (+8.8). Both beat every alternative already
   measured, so this is the lever worth building.

Reproduce free from fixtures: `python scripts/record_asr_context.py --score-only`.

Caveats: the ideals are machine translations, not human ones, so absolute values are soft —
a human reference would settle those. The *comparisons* are paired per-clip and consistent
across two languages. An earlier n=5 read of these deltas had two of them at different
magnitudes and one at the opposite sign; 5 clips of 60s audio is not enough here.
`scripts/calibrate_metric.py --score-only` regenerates the calibration free from fixtures.

## Caption language menu (replaced the CC toggle)
The player button was a toggle between "off" and "captions in the device's language", which
is the right default and a bad ceiling: anyone whose system language is not the one they read
comfortably — a Hungarian speaker on a German phone — had no way to say so. It is now a
dropdown (`CaptionLanguageMenu`, commonMain, next to the other player controls):

1. **No translation** — captions off entirely. No socket, no session key, no audio leaving the
   device. Where every player starts.
2. **"<Language> translation"** — the device's own language, i.e. what the old toggle did.
   Skipped on a device whose language is the spoken one or one Soniox has no model for.
3. **"English captions"** — the spoken language, transcribed and not translated (`translateTo`
   null, not `"en"`: translating English into English pays a round trip for the same words).
   This is what an English device gets in slot 2.
4. The rest of `CaptionLanguage.MENU_LANGUAGES` — 22 languages, in audience order, the device's
   own skipped so it is never offered twice.

- The rows are built in `captionMenuOptions` (commonMain, Compose-free) so the wording and the
  ordering are asserted in `CaptionMenuTest` rather than buried in a `DropdownMenu`.
- Every offered code must be in `CaptionLanguage.SUPPORTED`; a target Soniox rejects comes back
  as an `error_message` that `WebSocketTranscriber` cannot tell from a dropped socket, so it
  would reconnect and fail for the whole video. `CaptionMenuTest` pins that. **Armenian is on
  the requested list and is not offered for exactly this reason** — Soniox has no model for it.
- The target now travels from the menu through `CaptionAudioRouter.enable(provider, translateTo)`
  into `LiveTranscriber.enable`, which restarts the session when the language changes: Soniox is
  told the target in the config frame that opens the socket, so a live stream cannot be
  re-pointed. `CaptionLanguage.deviceTarget()` still picks the default row, and nothing else
  reads it any more.
- **The selection resets to "No translation" on every video** — `rememberCaptionController` is
  keyed by the video's URL, so it does not even survive playing a second video on a reused
  player screen. This is deliberate and is not the missing-persistence follow-up in step 1.4:
  a caption session streams the whole soundtrack to a metered service, so a language picked
  once must not quietly keep billing on every video after it. Re-picking is one tap.
- Android holds the controls up while the menu is open (`ControlsAutoHide(menuOpen = …)`) — the
  menu is drawn inside the control bar, and three seconds is not enough to read twenty-odd rows.

## VOD path (separate, not in this doc)
Recorded events should get **batch** transcription (once per asset) → WebVTT served as
an HLS subtitle rendition; Media3's built-in text renderer (already enabled in
`TranscriptionRenderersFactory`) renders it with ~zero client work. Use Soniox async
($0.10/hr) or self-hosted whisper large-v3. Track separately.
