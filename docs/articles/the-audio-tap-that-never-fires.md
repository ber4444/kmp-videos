# The Audio Tap That Never Fires

### Live captions on Android, iOS and the web from one Kotlin codebase, and the three places the platform lied to me

*A Compose Multiplatform video player, a streaming speech recognizer, and what it actually
takes to get decoded PCM out of three completely different media stacks.*

---

## Part 1: On-device recognition, measured twice

Every discussion of speech recognition on mobile starts in the same place, which is to do it
on device. No per-minute bill, no audio leaving the phone, and no network dependency in a
feature used while already streaming video. It is the version anyone would prefer to ship,
and the original implementation tried it twice.

**Attempt one was Vosk**, a small on-device recognizer fed by a PCM tap on the ExoPlayer
audio pipeline and decimated to 16 kHz. It worked well enough to reveal the real problems,
and accuracy was not among them.

- The model could not ship in the APK, so it downloaded `vosk-model-small-en-us-0.15` from a
  CDN on first use and unzipped it into app storage.
- Unzipping attacker-supplied archive entries into app storage is a **Zip Slip**, a
  directory-traversal write, and CodeQL said so. Two PRs went into constraining extraction
  paths for a model download nobody was attacking, on a feature that was already too slow.

**Attempt two was Whisper**, via whisper.cpp and WhisperJNI, and this is where the
original implementation stopped. Measured on a Samsung Fold3 (Snapdragon 888), a fair mid-to-upper Android
target rather than a weak one:

- **`base.en` runs about 2× slower than real time.** Roughly 7 seconds of compute per 4-second
  window. Audio arrives faster than it can be consumed, so about half of it is dropped.
- The captions come out **fragmentary** as well as late, chunks of sentences with the middles
  missing. That is *worse than no captions*, because a viewer cannot tell what was skipped.
- **Multi-threading did not rescue it.** It hit a native GGML barrier deadlock, a failure
  inside the inference library rather than in the calling code.
- **The debug build was a red herring worth naming.** The `-O0` native build ran ~25× too
  slow, which is very easy to mistake for something optimisation would fix. It would not
  have. The ~2× figure above *is* the optimised build.

All of this has to coexist with what the app is already doing on that CPU, namely decoding an
HLS video stream, compositing a 60 fps Compose UI and holding a foreground audio session.

That optimized Whisper build could not keep up on the Fold3, and its on-device path was
deleted. This was a decision about that engine and hardware, not proof that every future
recognizer would be too slow. The historical notes call the decision ADR 0001, but the
original ADR and benchmark executable are not archived in this checkout. Its cloud path
accepted a concrete cost:

> **Audio leaves the device.** This is the real price, and it is user-visible. Only the
> *media's* audio is sent, and only while captions are on. The app holds no microphone
> permission at all, which bounds the claim to something checkable.

That last clause is the part I would defend in a design review. Anyone can open the manifest
and confirm that no microphone permission is declared, which is more than a privacy paragraph
can offer.

The cloud path also supplied a feature neither of the tested local ASR engines provides.
The recognizer returns **translated tokens on the same socket**, so a viewer reads captions
in one of 22 languages off English audio, with no second vendor in the path. That turned out
to be the feature's main value.

### The 2026 Moonshine and Whistle follow-up

New engines deserved a new measurement. I checked **Moonshine Voice 0.1.6, English Medium
Streaming**, and **Cactus Whistle**, first on the desktop and then on a **Pixel 7a with
Tensor G2, Android 17**. The Fold3 was unavailable; this is a different-device comparison,
not a reproduction of its Snapdragon 888 result.

Both native engines decoded all 21 golden clips. Each clip ran twice with the model loaded;
the table uses the second run. RTF is compute time divided by audio duration, excluding
model loading. Below 1 means the standalone decoder keeps up.

| Engine | Mean per-clip normalized WER | Median warm RTF | Slowest warm RTF |
|---|---:|---:|---:|
| Moonshine Medium Streaming | 0.549 | 0.113 | 0.147 |
| Whistle | 0.643 | 0.026 | 0.130 |

Soniox v6's batch WER on the same set was 0.268. Local throughput is no longer the blanket
reason to reject on-device recognition on this Pixel. Accuracy on this vocabulary is the
larger obstacle in these unboosted runs. Moonshine's downloaded quantized model files total
about 269 MB; Whistle's weights are 16.9 MB. Those are file sizes, not measured resident
memory. The English Moonshine checkpoint is MIT-licensed; Whistle is Apache 2.0. Other
Moonshine language checkpoints have separate licensing terms.

Whole-file throughput also does not prove that live captions work. A real-time-paced
60-second smoke clip, repeated twice, finished with WER **0.723** for Moonshine and **0.957**
for Whistle. Warm streaming compute RTF was **0.293** and **0.192** respectively. Moonshine
received 200 ms chunks with transcript updates every 600 ms; Whistle received 1 s chunks
through its streaming API. Both flushed their final tail. These are one-clip results, not
corpus streaming WER or word-finalization latency.

Moonshine caches incremental work. Whistle's underlying encoder is noncausal and its
whole-file path windows inputs beyond 30 seconds; its streaming API is not evidence that
chunk boundaries cease to matter. The smoke result is a reason to investigate that path,
not to describe the old fragmentation problem as solved.

The benchmarks did not run beside an HLS/Compose workload, and neither ASR model itself
provides this app's 22-language translated captions. The result therefore narrows the old
claim: optimized Whisper failed on the Fold3; newer engines keep up on the Pixel, but these
recognition results do not yet justify replacing the caption pipeline. The
[refresh report](../../eval/VENDOR_REFRESH_RESULTS_2026.md) and
[native harness](../../eval/native/README.md) record the measurements and exact setup.
Vendor details: [Moonshine](https://github.com/moonshine-ai/moonshine),
[Whistle](https://www.cactuscompute.com/blog/whistle).

## Part 2: One pipeline, three completely different front ends

Cloud streaming ASR wants one thing, forever, which is **16 kHz mono signed-16-bit
little-endian PCM, streamed as binary WebSocket frames.**

Getting from "a video is playing" to that, on three platforms, is the entire engineering
problem. The design principle was to make the platform-specific part as thin as it can
possibly be:

```
Android  ByteBuffer (Media3 TeeAudioProcessor) ─┐
iOS      float* (decoded AAC segment)          ─┼─► downmixFloatToMono16 / mono16
Web      FloatArray (WebAudio)                 ─┘        │
                                                          ▼
                                        CaptionAudioRouter.feedMono(ShortArray, inRate)
                                                          │
                                          resampleTo16kMonoS16(inRate)   ← commonMain
                                                          │
                                          LiveTranscriber → SonioxClient → WebSocket
```

Everything below `feedMono` is `commonMain` Kotlin, covering rate conversion, byte packing,
provider selection, the protocol, the reconnect loop, the caption buffer and the language
menu. Each platform contributes exactly one `onPcm` extension function of 5 to 15 lines,
whose only job is to turn its native buffer type into interleaved samples.

Two details in the shared half are easy to underrate.

**Averaging decimation rather than nearest-sample.** `resampleTo16kMonoS16` averages each
source window instead of picking a representative sample. Nearest-sample decimation from
48 kHz to 16 kHz aliases, and aliasing does not sound like noise to a recognizer. It sounds
like different phonemes. This is a five-line difference in the code and a visible difference
in word error rate.

**The router is a process-wide singleton.** It outlives the player composable so recognition
survives navigation, because caption state belongs to the session and not to the UI tree that
happens to be displaying it.

## Part 3: Android, the easy one, and why it is easy

```kotlin
override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, …): AudioSink {
    val transcriptionTap = TeeAudioProcessor(CaptionAudioBufferSink(sink))
    return DefaultAudioSink.Builder(context)
        .setAudioProcessors(arrayOf<AudioProcessor>(transcriptionTap))
        .setEnableFloatOutput(enableFloatOutput)
        .build()
}
```

Media3 hands you a supported, documented, non-mutating tap. `TeeAudioProcessor` copies each
buffer out and returns it unchanged, so playback audio is bit-identical. It is installed on
the **audio sink**, which is the single choke point for all decoded PCM, so the tap comes out
codec-agnostic and rendition-agnostic for free. Switch quality mid-playback, switch from AAC
to anything else, and the tap does not notice.

Two things here are worth copying.

- The sink's `flush(sampleRate, channelCount, encoding)` callback is the format-change
  notification. Cache the format there rather than guessing it, and skip any encoding that
  is neither `PCM_16BIT` nor `PCM_FLOAT` instead of misinterpreting the bytes.
- The renderers factory leaves the **built-in text renderer enabled**. Streaming ASR is the
  primary path because the production server carries no embedded captions, but if a stream
  ever does carry CEA-608/708, those still land on `currentCues`. Adding a capability should
  not remove one.

This took an afternoon. I include it mainly as the control condition for what follows.

## Part 4: iOS, the API that accepts your callback and never calls it

The equivalent on iOS is `MTAudioProcessingTap`. You build an `AVAudioMix`, attach it to the
`AVPlayerItem`'s audio track, and your process callback receives PCM.

**`MTAudioProcessingTap` is not supported for HTTP Live Streaming.**

It does not return an error and it does not throw. The audio mix is **accepted without
complaint and the process callback is simply never invoked**, with no exception, no log line,
no `NSError` and no deprecation warning. Your code is correct, your object graph is correct,
and nothing happens, which is the single most expensive failure mode an API can have, because
there is nothing to search for.

I confirmed it was not a configuration mistake by testing the muxed variant and the
audio-only rendition played on its own. Neither fires. The tap approach cannot be made to
work on HLS, so it can only be abandoned.

### The bypass: read the audio from the other end

An HLS master playlist advertises renditions. This server publishes an **audio-only** one at
roughly **51 kbps**. If the player will not give me its audio, I can fetch the same audio
myself, over plain HTTP, and decode it independently.

1. **Fetch and parse the media playlist.** Walk `#EXTINF` durations and their segment URIs,
   accumulating a start offset per segment, so a playhead position maps onto a segment index.
   (`:mediakit` already parses these playlists but reports only *total* duration, and
   widening a binary-compatibility-validated public API for one internal caller is a worse
   trade than 20 lines of local parsing.)
2. **Decode each segment.** HLS audio-only segments are "packed audio", an ID3 header
   followed by raw ADTS AAC. No demuxer is required, so the bridge strips the ID3 tag and
   hands the ADTS to AVFoundation.
3. **Downmix and hand back mono float.** `AVAudioFile.processingFormat` is always
   deinterleaved float32, so the Objective-C bridge averages the channels and calls back with
   a single channel. The Kotlin side then treats it exactly like any other PCM source.

One wart is worth admitting. `AVAudioFile` reads from a URL and not from memory, so each
segment is written to a temp file first, and **the file extension matters**, because that is
how `AudioFile` selects the ADTS parser. `.aac` or nothing works.

### Pacing, because a fetch loop is not a tap

A tap is naturally rate-limited by playback. An HTTP fetch loop is not, and left alone it
would download and transcribe the whole event in about ninety seconds, bill for all of it,
and produce captions for words nobody has heard yet.

So the feeder is paced against the playhead.

| Knob | Value | Why |
|---|---|---|
| `LEAD_SEC` | 6.0 s | stay a little ahead of the playhead, never far ahead |
| `RESYNC_THRESHOLD_SEC` | 10.0 s | a playhead/feed gap this large is a **seek** rather than drift, so re-anchor the cursor |
| `PACING_DELAY_MS` | 250 ms | poll interval while waiting for the playhead to catch up |
| `REFRESH_DELAY_MS` | 2000 ms | re-fetch the playlist when the cursor runs off the end |

That last row is the live/VOD distinction. Running out of segments means "the live window has
not slid yet, wait and re-fetch" for a live event and "this recording is over" for a bounded
playlist. The same loop handles both by checking whether a refresh actually produced more
segments.

The whole thing is a `suspend fun` that runs until its coroutine is cancelled, and
cancellation is wired to the caption menu selection. Turning captions off is structured
concurrency doing its job, with no manual teardown, no leaked socket and no half-stopped
feeder.

### And then the build system

The bridge that decodes those segments is Objective-C, which means cinterop, which was its
own multi-day excavation. There were three separate defects, stacked on top of each other.

**1. AVPlayer's category methods do not survive cinterop.** With the Xcode 26.5 SDK,
Kotlin/Native's cinterop fails to merge `AVPlayer`'s Objective-C *category* methods
(`play`, `pause`, `rate`, `seek` and the rest) onto the generated class, so they are
unresolvable from Kotlin. The fix is to wrap them in a plain `NSObject` whose methods
cinterop does merge correctly. That is `AVPlayerBridge`, a workaround that then became the
natural home for the segment decoder.

**2. cinterop parses `sources =` and does not compile it.** Kotlin 2.3.x reads the `.def`
file's `sources` entry and then does not build the Objective-C implementation into the klib
or the framework. `_OBJC_CLASS_$_AVPlayerBridge` stays undefined until link time, where it
fails. The documented escape hatches, `-force_load` and `-ObjC` linker options, are
**silently dropped** by Kotlin/Native's `lld` invocation when producing a *static* framework.

The workaround is to do the compiler's job by hand. Compile `AVPlayerBridge.m` to LLVM
bitcode with Konan's own clang, then inject the `.bc` into the cinterop klib's `natives/`
directory, which is the one place Kotlin/Native reliably links from when building a static
framework.

**3. Hardcoding the path to that clang breaks on the next toolchain bump.** It duly broke on
the next bump, so the fix resolves the path at build time:

```kotlin
val konanDeps = file("${System.getProperty("user.home")}/.konan/dependencies")
val clang = konanDeps.listFiles()?.asSequence()
    ?.filter { it.isDirectory && it.name.startsWith("llvm-") }
    ?.sortedByDescending { it.name }
    ?.map { it.resolve("bin/clang") }
    ?.firstOrNull { it.exists() }
    ?: error("Konan clang not found in $konanDeps — install Kotlin/Native dependencies first")
```

Newest LLVM first, existence-checked, with an error message that tells you what to do. The
same PR replaced hardcoded debug and simulator framework paths in the Xcode project with
build settings that resolve per SDK and per configuration (`iphoneos` → `iosArm64`, Release →
`linkReleaseFrameworkIosArm64`), since the clang path had only ever been hardcoded because
nobody had built this configuration on a machine other than mine. CI is what found all
three.

None of this is glamorous, and all of it is the difference between a KMP demo and a KMP app
in TestFlight.

## Part 5: Web

WebAudio hands over interleaved `FloatArray`s, the same `downmixFloatToMono16` runs, the same
resampler runs, the same client streams. The web front end is 15 lines because the other two
platforms paid for the abstraction.

The one platform-shaped constraint lives on the server. The wasm build is a **cross-origin**
caller of the token service, so `ALLOWED_ORIGINS` must list it or the browser refuses the
request before it is ever sent. The default is empty, meaning no browser origin at all,
because a native-only deployment should not be reachable from any web page.

## Part 6: Designing for a socket that will drop

A caption socket on a 90-minute event will drop. The provider recycles a backend, the network
hiccups, or the stream goes idle and the vendor hangs up. The video keeps playing regardless,
so a transient failure should reconnect and carry on.

**Idle is the common case.** Soniox closes a stream that receives neither audio nor a
keepalive for about 20 seconds with `error_message: "Request timeout"`, which a paused video,
or any gap in the platform audio tap, reaches easily. The client sends
`{"type":"keepalive"}` every 5 seconds whenever audio is not flowing, and treats a timeout
frame that arrives anyway as end-of-session so the reconnect loop runs, instead of leaving
the transcript frozen for the rest of the video.

**A rejected configuration is terminal.** The client preserves Soniox's `error_code` rather
than treating every `error_message` as a dropped socket. Code 400 means a configuration
rejection, such as an unsupported `target_language`: sending the same config again would
only repeat the failure. The caption session stops without leaving an error line in the
caption strip, and the video keeps playing. Codes 408, 429 and 503 remain retryable for
timeouts, rate limits and service unavailability.

**Backoff that resets on health.** The schedule is exponential from 500 ms up to a 10 s
ceiling, and any session that stayed up for 15 seconds resets the counter:

```kotlin
fun failuresAfter(previousFailures: Int, sessionDurationMs: Long): Int =
    if (sessionDurationMs >= healthySessionMs) 1 else previousFailures + 1
```

Without that reset, a socket that ran perfectly for twenty minutes and then timed out
inherits the backoff of an unrelated failure an hour earlier, and the viewer stares at a dead
caption strip for ten seconds. Naive exponential backoff is correct for a request and wrong
for a long-lived session, because it counts consecutive failures while a long session
produces merely eventual ones.

The policy is a pure function of `(previousFailures, sessionDurationMs)` in `commonMain`,
with no clock and no coroutines in it, so the schedule is unit-tested exhaustively without a
device, a network or a delay.

## Part 7: The key that shipped in the binary

The original design put the vendor API key in `secrets.properties`, which Gradle baked into
`BuildConfig` on Android and `Info.plist` on iOS. This is the default advice in half the
integration guides on the internet, and it is easy to say what is wrong with it.

**A string in a shipped binary is a public string.** `strings`, a plist dump, or the network
inspector in a browser's dev tools will each produce it. No obfuscation survives someone who
wants it, and the key in question bills per minute.

The replacement is a small Ktor service (`:server`, deployed on Fly) that holds the real key
and mints short-lived ones. The client ships **only a URL**.

**Server side**, three request options do the bounding, and all three earn their place.

| Option | What it prevents |
|---|---|
| `usage_type: "transcribe_websocket"` | a leaked key cannot be spent on text-to-speech |
| `single_use: true` | one WebSocket connection per key, so grabbing it off the wire buys nothing |
| `max_session_duration_seconds: 3600` | caps a session that *does* get established |

That third one exists because **expiry gates the connect and not the stream that follows
it.** The TTL is 60 seconds, enough to cover "app got the response, app dialled Soniox", but
without a session cap a key spent in that 60-second window could hold a socket open
indefinitely. The two controls answer to two different clocks.

One subtlety cost me a debugging session. The request is serialised with
`encodeDefaults = true`, because kotlinx omits default values and `usage_type` and
`single_use` are *both* defaults here. Dropping them does not fail. Soniox applies its own
defaults instead, and **the minted key comes back broader than the code appears to ask for.**
A security control that silently degrades to "off" is worse than no control, because the code
reads as if it is on.

**Authorisation is server-side, and it fails closed.** The endpoint mints only for members of
the community's Discord guild, verified by asking Discord about the caller's token, which
restates server-side a check the client also does. The client's version decides what the UI
shows, and that is no constraint at all on anyone calling the endpoint directly. A rejected
token, a token that sees no membership, **and a Discord outage** all deny. Failing open during
an outage would be friendlier to users and would also mean anyone could mint keys during it.
Matching is on the guild snowflake only, because guild names are not unique and anyone can
create one with the right name.

Rate limiting is per client, 30 keys per 300 seconds, in **two separate buckets**. The caption
route and the feed route each get their own, so a gallery that reloads on every launch cannot
eat the allowance a long video's reconnects need.

And the service **refuses to boot** without the API key or the guild id:

> Deliberately fatal rather than degrading to a 503 route. A service whose only job is to
> hold one secret, booting happily without it, is a silent misconfiguration that looks
> healthy in the dashboard and only shows up as broken captions. A failed deploy rolls back,
> which is the signal that should reach whoever ran it.

**Client side**, there is one non-obvious consequence. The key is fetched **per session
rather than per app launch**, because the keys are single-use, so caching one would work
exactly once and then fail every reconnect for the rest of the video. What looks like an
obvious optimisation is a bug that only appears twenty minutes into a stream.

The client also distinguishes refusals by status, because they mean different things to the
reconnect loop. **403 is terminal**, since no amount of retrying produces a sign-in, while
429 and 5xx fall through to the ordinary backoff. The service's error body is never quoted
into a user-visible transcript or a log, because the status carries the meaning and a body
that can echo the request is not something to forward.

## Part 8: The UI is part of the architecture

Three decisions that look cosmetic have architectural consequences.

**The caption choice follows the viewer.** A selected language persists across videos and
app launches. This accepts a real cost: cloud ASR is metered, so later videos can start
billable caption sessions without the viewer confirming the choice again. Re-picking before
every video was judged the worse experience.

With no saved choice, the player starts captions in the device's language if it is a
supported translation target; English and unsupported device languages start off. Choosing
"No translation" stores an explicit off value, so the next video stays off instead of
reapplying the device-language default. `CaptionLanguagePreference` stores a language code
or an off/spoken-language sentinel, resolved against the current menu. Android uses
`SharedPreferences`, iOS uses `NSUserDefaults`, and web uses `localStorage` when available.

**The language menu is a Compose-free function.** The rows, their wording and their order
live in `captionMenuOptions(deviceTarget: String?)` in `commonMain`, returning plain data
classes, so ordering and labels are asserted in a common test with no runtime, instead of
being buried in a `DropdownMenu` where a diff cannot show them. It encodes three real
constraints.

- "No translation" is the first row and the only one that starts nothing at all, with no
  socket, no session key and no audio leaving the device.
- The English row passes `translateTo = null` rather than `"en"`. Asking the engine to
  translate English into English pays a round trip for words it already sent, and doubles
  every token frame.
- Every offered language code must be one the vendor supports. Armenian was on the
  requested list and is deliberately left off because the provider has no model for it.
  The terminal-error handling above is a fallback if an unsupported target reaches the
  socket anyway; it is no reason to offer a menu row that cannot produce captions.

**Running captions have a direct off button.** While captions are on, the menu becomes one
turn-off button labelled in the active caption language. Close, Play and Pause use the same
language. Changing languages takes two steps: turn captions off, then reopen the menu and
choose another language. The new target starts a new session, because it is fixed in the
socket's opening config frame.

On Android and iOS, choosing a language also dismisses the controls so they stop covering
the caption strip; choosing off leaves the controls visible. Android also holds the controls'
auto-hide timer while the menu is open. These details matter most when playback is paused, when
waiting for an auto-hide timer would otherwise leave the new captions invisible.

## What generalises

- **Measure the engine on the target hardware, and revisit the result.** Optimized Whisper
  failed to keep up on the Fold3; Moonshine and Whistle keep up on a Pixel 7a. Neither
  result establishes recognition quality, streaming behavior or app workload headroom.
- **Silent no-ops are the expensive bugs.** `MTAudioProcessingTap` on HLS, a cinterop
  `sources =` that is parsed and ignored, `-force_load` dropped by the linker, a security
  flag omitted by the serialiser. In every case the code looked right and did nothing. Build
  the check that proves the thing you configured was actually applied.
- **Make the platform-specific layer as thin as it can be, then make it thinner.** Three
  `onPcm` functions of 5 to 15 lines each, and one shared pipeline. The web platform was
  effectively free because the other two paid for the abstraction.
- **Push decisions into pure functions in shared code.** Backoff schedule, menu construction,
  resampling and glossary consistency are all tested without a device, an emulator or a
  network.
- **A key in a binary is a public key.** Obfuscation does not help. Ship a URL instead, and
  mint a scoped, single-use, short-lived credential behind an identity check that fails
  closed.
- **Structured concurrency does real work here.** "Stop captions" is a cancellation, and that
  is the whole teardown.

---

*Source: `composeApp/src/{commonMain,androidMain,iosMain,wasmJsMain}` (caption pipeline),
`composeApp/native/avplayer/cinterop` (Objective-C bridge), [`server/`](../../server) (token
service), and the [2026 refresh report](../../eval/VENDOR_REFRESH_RESULTS_2026.md) for the new
on-device measurements. The original Fold3 ADR is not archived in this checkout. Caption
preferences, controls and configuration-error handling follow the final merge of [PR #82](https://github.com/ber4444/kmp-videos/pull/82).*

[GitHub](https://github.com/ber4444/kmp-videos) · [Google Play](https://play.google.com/store/apps/details?id=com.livingpresence.inner.circle.squared) · [Apple TestFlight](https://testflight.apple.com/join/YBHu9W7V)
