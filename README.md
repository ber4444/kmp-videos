# Apollo Videos

A Compose Multiplatform (Android + iOS + wasmJs) app for streaming live and
recorded HLS event streams, built on **Media3/ExoPlayer** (Android) and
**AVPlayer** (iOS) with a standalone KMP playback SDK (`:mediakit`).

**Live on Google Play:**
[Apollo Videos](https://play.google.com/store/apps/details?id=com.livingpresence.inner.circle.squared)

It plays live/recorded HLS event streams from a Wowza nDVR server and turns four
*unadvertised* sibling renditions into a genuine client-side ABR ladder.

## Features

- **Discord-gated landing page.** The app opens on a photo landing screen whose
  only action is *Connect to Discord*. Authorization uses the OAuth2
  authorization-code grant with PKCE (mandatory for Discord mobile deep links,
  and no client secret ever reaches the device), then `/users/@me/guilds` decides
  access: members of the Apollo server land in the events feed, everyone else is
  told *"User must be on the Apollo server."* The session persists across
  launches via a refresh token — stored in `SharedPreferences` on Android, the
  Keychain on iOS, `localStorage` on web — and membership is **re-verified on
  every launch**, so leaving Apollo revokes access at the next start rather than
  never. See [Configuration](#configuration).
- **Adaptive streaming.** Genuine client-side ABR synthesized from four
  unadvertised sibling renditions, with viewport-aware track selection so the
  chosen quality matches the surface size.
- **Live & recorded playback.** Live-vs-VOD is inferred from playlist inspection;
  live events expose a LIVE badge and jump-to-live, and the seek bar tracks a
  growing Wowza nDVR window without drift.
- **Thumbnail feed with scrub preview.** Poster tiles and a scrub-preview bubble 
  are powered by an ExoPlayer frame engine on Android, and native `AVAssetImageGenerator` 
  on iOS, avoiding the overhead of N full per-tile players.
- **Flexible presentation.** fit/fill/zoom resize matrix for both horizontal and
  vertical (9:16) video, rotate-to-fullscreen, auto-hiding controls,
  buffering/error UX, and a stats overlay + quality menu.
- **Background & Picture-in-Picture.** Playback is owned by a `MediaSession`
  service (surviving config changes), with PiP aspect-clamped for vertical video
  and background audio constrained to the low-bitrate audio-only tier.
- **Remotely-curated extras.** Beyond the numbered events the server exposes, the
  feed appends videos listed in a plain-text manifest fetched from outside the
  repository (a secret gist). The body is cached for a day — on disk, so it
  survives launches — and a pull-to-refresh re-reads it immediately. See
  [Extra videos](#extra-videos).
- **Offline downloads.** Bounded (VOD) events download via WorkManager (Android) 
  and `AVAssetDownloadURLSession` (iOS) into a cache shared with playback; 
  truly-live events get no download affordance.
- **Cross-platform Parity.** A seamless unified experience across Android, iOS, and Wasm. 
  The app features native in-app Web navigation, unified UI aesthetics across all targets, 
  hardware-accelerated thumbnail extraction on iOS via `AVAssetImageGenerator`, and robust 
  native iOS background HLS downloading using `AVAssetDownloadURLSession`.

## Feature Matrix

| Feature | Android | iOS | Web (Wasm) |
| :--- | :---: | :---: | :---: |
| **Live STT Captions** (Soniox WebSockets, keys minted by [`:server`](./server)) | ✅ | ✅ | ✅ |
| **Device-Language Captions** (Soniox in-band translation) | ✅ | ✅ | ✅ |
| **ABR Ladder Synthesis** (from sibling renditions) | ✅ | ✅ | ✅ |
| **Viewport-Aware ABR** (auto-caps to screen size) | ✅ | ✅ | ✅ |
| **Manual Quality Override** (Auto, 720p, audio, etc) | ✅ | ✅ | ✅ |
| **Scrub Frame Previews** (floating thumbnail) | ✅ | ✅ | ✅ |
| **Live Event DVR** (drifting seekbar & jump-to-live) | ✅ | ✅ | ✅ |
| **Pillarboxing & Orientation Handling** | ✅ | ✅ | ✅ |
| **Background Audio** (auto-shifts to audio-only tier) | ✅ | ✅ | ✅ |
| **Picture-in-Picture (PiP)** | ✅ | ✅ | ✅ |
| **Remote Extras Manifest** (cached 24h between launches) | ✅ | ✅ | ✅ |
| **Offline HLS Downloads** | ✅ | ✅ | ❌ |
| **Preview Disk Caching** (persisted between sessions) | ✅ | ✅ | ❌ |
| **Memory Governor** (OOM prevention during PiP/bg) | ✅ | ❌ | ❌ |

## Caption provider evaluation

The live-caption provider choice is backed by a reproducible, record/replay eval
harness in [`eval/`](./eval) that scores Deepgram and Soniox against a domain-specific
golden set of event audio — batch WER/CER, domain-term recall (Entity F1),
keyterm-boosting impact, and real-time streaming realism (flicker + finalization
latency). On this material **Soniox clearly outperforms Deepgram** (0.242 vs 0.350
normalized WER; 0.77 vs 0.49 Entity F1), which is why **Deepgram is now switched off**:
Soniox is the only provider the app reaches at runtime. The harness still scores both —
re-running the comparison is the point of keeping it. Run
[`eval/run_eval.sh`](./eval/run_eval.sh) to regenerate the scorecard at
`eval/reports/scorecard.md` (generated output, not checked in); see
[`eval/README.md`](./eval/README.md) for the methodology.

Soniox also decides the caption *language*. The events are spoken in English, but Soniox
translates in-band — translated tokens arrive on the same websocket, chunk by chunk, at no
extra cost — so the captions are written in whatever language the device is set to: a
Russian phone reads Russian off the same English audio, with no second service in the path.
The locale is resolved per session and falls back to untranslated English when the device
already speaks English or is set to one of the few languages Soniox does not cover.
Deepgram's streaming API has no translation, so it could only ever caption in English —
the reason it was switched off rather than kept as a runtime alternative. Its client still
compiles and the provider switcher still exists in the source (`CaptionProviderButton`), but
nothing renders the switcher, so no audio can be routed to it. Restoring the choice means
rendering that button again — and updating the privacy policy, which now names Soniox alone.

## Architecture

```
:androidApp        — Android application module: MainActivity, manifest
                     (services, permissions, Discord redirect filter), signing
:composeApp        — app UI (feed, login, player), navigation, DI wiring, and
                     every platform playback integration
  commonMain       — shared UI, app state, composeResources
  androidMain      — ExoPlayer (in PlaybackService), DownloadsService/DownloadCenter,
                     PreviewFrameEngine, MemoryGovernor, LadderMediaSource
  iosMain          — AVPlayer playback (UIKitView + AVPlayerLayer cinterop),
                     PiP, background audio, native offline downloads
  wasmJsMain       — thin web target (poster tiles + in-app player screen)
:mediakit          — KMP playback SDK: pure-Kotlin core, commonMain only, no
                     platform sources
  commonMain       — PlaylistInspector (pure-Kotlin HLS parser), LadderSynthesizer
                     (multivariant playlist builder), LadderResolver (JIT rendition
                     probing), EventCatalog (parallel event probing), MediaKitConfig,
                     EventInfo/ProbedRendition/RenditionTier models
```

`:mediakit` holds the platform-independent HLS/ABR brain — parsing, ladder
synthesis, rendition probing — and nothing else; it compiles for Android, JVM,
iOS and wasm purely from `commonMain`. The Android playback engine that consumes
it is **owned by a `MediaSessionService`** in `composeApp/src/androidMain`, not by
a composable, so it survives config changes and enables background audio/PiP.

## Possible future work

- Rename `:composeApp` to `:shared` and extract a separate `:webApp` — the full
  platform restructure JetBrains recommends. The current split keeps iOS and
  wasm in `:composeApp`.
- Extract repeated build configuration into convention plugins.
- Audit R8 keep rules, shrinking policy, and packaging against the AGP 9
  defaults, with release smoke testing.

## Building

### Android
```bash
./gradlew assembleDebug            # debug APK → androidApp/build/outputs/apk/debug/
./gradlew installDebug             # install on a connected device
```

#### Release signing

Release builds are signed with the Play upload key, which lives outside the repo.
Create `~/key.properties` pointing at your keystore:

```properties
storeFile=/absolute/path/to/upload-keystore.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

`androidApp/build.gradle.kts` reads that file and wires it into the `release`
build type. When it is absent — a fresh clone, or CI — release falls back to the
debug key, which installs locally but is rejected by Play on upload.

```bash
./gradlew :androidApp:bundleRelease   # AAB → androidApp/build/outputs/bundle/release/
```

#### Foreground services and runtime permissions

The Android app runs two foreground services, both declared in
`androidApp/src/main/AndroidManifest.xml` and both declared to Google Play:

| Service | Type | Purpose |
| --- | --- | --- |
| `PlaybackService` | `mediaPlayback` | Keeps ExoPlayer and the `MediaSession` alive for background audio, lock-screen controls, and PiP |
| `DownloadsService` | `dataSync` | Runs user-initiated offline downloads to completion with a progress notification |

`POST_NOTIFICATIONS` is a runtime permission from API 33 and is requested on
launch by `MainActivity`. Denying it does not break playback or downloads, but
the system silently drops both services' notifications — no lock-screen transport
controls and no download progress.

> **Note.** On Android 15+ a `dataSync` foreground service is capped at 6 hours
> per 24, and Google steers user-initiated downloads toward user-initiated data
> transfer jobs (`JobInfo.Builder.setUserInitiated(true)`). media3's
> `DownloadService` is still on the older path.

> **Compose resources note.** The `com.android.kotlin.multiplatform.library`
> plugin assembles `composeResources/` for iOS and wasm but produces nothing for
> Android, so any `Res.drawable.*` lookup compiles and then throws
> `MissingResourceException` at runtime. The landing background works around this
> by reading the host module's `res/drawable` copy through
> `HostBridge.backgroundDrawableResId`. Any future shared resource needs the same
> treatment until the plugin gap is closed.

### iOS
```bash
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64   # Compose framework incl. the AVPlayer cinterop
```
A runnable SwiftUI host app lives in `iosApp/` (generated from
`project.yml` via `xcodegen`):
```bash
cd iosApp && xcodegen generate
xcodebuild -project ICSApp.xcodeproj -scheme ICSApp \
    -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 17' build
```

#### Device / Release build

For a physical arm64 device or an App Store archive, build the **release**
framework and target `iphoneos`:

```bash
./gradlew :composeApp:linkReleaseFrameworkIosArm64
cd iosApp && xcodegen generate
xcodebuild archive -project ICSApp.xcodeproj -scheme ICSApp \
    -configuration Release -sdk iphoneos \
    -archivePath ../build/ios/ICSApp.xcarchive
xcodebuild -exportArchive \
    -archivePath ../build/ios/ICSApp.xcarchive \
    -exportPath ../build/ios/ipa \
    -exportOptionsPlist ExportOptions.plist
```

The Xcode project dynamically selects the right framework via build settings in
`project.yml`: `KOTLIN_TARGET[sdk=iphoneos*] = iosArm64` and
`KOTLIN_FRAMEWORK_BUILD_TYPE[config=Release] = release`, so no manual path
switching is needed.

#### Codemagic CI/CD

`codemagic.yaml` in the project root defines an *iOS Release → TestFlight*
workflow that reconstructs `secrets.properties` from Codemagic environment
variables, builds the release framework, generates the Xcode project, archives,
exports, and uploads to TestFlight. Configure these in the Codemagic dashboard:

- **App Store Connect API key** — linked as an integration named "Apollo Videos"
- **Environment group `app_secrets`** — `SONIOX_TOKEN_URL`, `DISCORD_CLIENT_ID`,
  `APOLLO_GUILD_ID`, `STREAM_HOST`, `EXTRA_VIDEOS_URL`. Not `SONIOX_API_KEY`: these
  values reach `Info.plist`, which ships in cleartext inside the IPA.

Code signing is automatic: the workflow calls `app-store-connect
fetch-signing-files` to provision the certificate and profile on every build.

> **Compose resources note (iOS).** Because the Xcode target links a *prebuilt*
> framework instead of letting Xcode drive Gradle, CMP's own
> `syncComposeResourcesForIos` never runs and nothing would copy
> `composeResources/` into the app bundle — every `Res.drawable.*` lookup would
> throw `MissingResourceException` and kill the app on the landing screen. Two
> pieces close that gap, and neither adds a step to the commands above: the
> framework link task depends on `assemble<Target>MainResources`
> (`composeApp/build.gradle.kts`), and a `Copy Compose resources` build phase
> (`iosApp/project.yml`) copies the result to `<bundle>/compose-resources`.

> **cinterop note.** Kotlin 2.3.x's cinterop parses the def's `sources =
> AVPlayerBridge.m` but does not compile the Obj-C implementation into the
> framework, so `_OBJC_CLASS_$_AVPlayerBridge` stays undefined at link time.
> The Gradle build works around this by compiling `AVPlayerBridge.m` to LLVM
> bitcode (`.bc`) and injecting it into the cinterop klib's `natives/`
> directory, where the Kotlin/Native linker picks it up. No manual steps
> needed — `linkDebugFrameworkIosSimulatorArm64` handles it end-to-end.

### Web (Wasm)
```bash
./gradlew wasmJsBrowserDevelopmentRun
```

To serve the production build from a static server:
```bash
./gradlew wasmJsBrowserDistribution
# serve composeApp/build/dist/wasmJs/productionExecutable/
```

## Tests & SDK discipline
```bash
./gradlew :mediakit:jvmTest              # the pure-Kotlin SDK core (JVM)
./gradlew :mediakit:allTests             # the same core on every target
./gradlew :composeApp:testAndroidHostTest # Robolectric unit tests (resize matrix,
                                         # MemoryGovernor tiers, MainViewModel,
                                         # download-state mapping, ASR reconnect)
./gradlew :composeApp:allTests           # adds the commonTest suites
./gradlew :mediakit:apiCheck             # binary-compatibility validation
./gradlew :mediakit:dokkaHtml            # API docs → mediakit/build/dokka/html
./gradlew koverXmlReport                 # coverage report
```

The `:mediakit` public API surface is tracked under `mediakit/api/` — accidental
binary-breaking changes fail CI. Dokka API docs are published to GitHub Pages on
`main`.

## Privacy

[Privacy policy](https://ber4444.github.io/kmp-videos/privacy/) — the page Google Play links to,
published from [`docs/privacy/`](./docs/privacy) by the `Dokka API docs` workflow job.

The app collects nothing: no analytics, no crash reporting, and no advertising. The one
server the developer operates is [`:server`](./server), which mints a short-lived Soniox
key when captions are switched on. It receives no audio and stores nothing; it does check
the Discord token you already signed in with, to confirm you are on the Apollo server
before spending the transcription account. The Discord refresh token and display name are stored on-device only; the guild list
is checked in memory and discarded. Live captions send the *media's* audio — never the microphone,
for which the app holds no permission — to Soniox, and only while captions are on.

## Configuration

Build configuration is read from `secrets.properties` in the project root. See
`secrets.properties.example` for details.

> **No provider API key goes in that file.** Everything in it is compiled into the
> shipped app — dex constants on Android, `Info.plist` on iOS, the JS bundle on web —
> and all three are readable from a published binary. The Soniox key used to live
> there and therefore shipped to every user; it now lives only in the
> [`:server`](./server) deployment's environment, and the app carries just
> `SONIOX_TOKEN_URL`, the address of the service that mints per-session keys. See
> [server/README.md](./server/README.md). Deepgram is unreachable in the app and its
> key is no longer shipped either; the [`eval/`](./eval) harness takes its keys from
> its own gitignored `.env`.

`SONIOX_TOKEN_URL` is the one value that may be left empty and still work: it falls
back to `TranscriptionSecrets.DEFAULT_SONIOX_TOKEN_URL`, this project's own
deployment, so a fresh clone gets captions with nothing configured. That address is
hardcoded where `STREAM_HOST` deliberately is not, because it mints nothing on its
own — `:server` re-verifies the caller's Discord token against the Apollo guild and
rate limits per caller, so knowing the hostname only tells you where to be refused.
A fork sets the key to point at its own service.

Gradle is the single reader of that file on every platform: Android gets `BuildConfig`
fields, wasm a generated constants object, and iOS the gitignored
`iosApp/Secrets.xcconfig` (written by `:composeApp:generateIosSecretsXcconfig`, which the
framework link depends on) whose values `iosApp/project.yml` substitutes into
`Info.plist`. Add an iOS key in *both* those places — `Info.plist` itself is generated by
`xcodegen`, so hand-edits to it are discarded on the next `xcodegen generate`.

### Stream host

Every playlist URL is built from one value, `STREAM_HOST` in `secrets.properties`
— scheme and authority, no trailing slash:

```
STREAM_HOST=https://your-host.example:443
```

It is not compiled into the SDK. Each host injects it at startup
(`FeedConfig.streamHost` → `MediaKitConfig.defaultHost`) from its own gitignored
source: Android
`BuildConfig` via `IcsApplication`, the wasm bundle's generated constants, the
iOS `Info.plist`. Leaving it empty makes every probe resolve nowhere and the feed
come back empty — a louder failure than reaching a stale hardcoded server.

This is **not** a secret and cannot be: a host the client streams from is on the
wire and inside the binary — a web build ships it in the bundle, and any deployed
site (including `gh-pages`) serves it. Keeping it in `secrets.properties` keeps it
out of a public repository and its history, which is a different and achievable
goal. `eval/scripts/fetch_clips.py` reads the same host from `WOWZA_HOST`.

### Extra videos

The feed is the numbered events (`event1`…`event20`) probed on the Wowza server,
plus anything listed in a manifest whose raw URL is `EXTRA_VIDEOS_URL` in
`secrets.properties`. One video per line, URL first and an optional title after
it; `#` comments and blank lines are ignored. `docs/extra-videos.example.txt` is
a copyable starting point:

```
https://your-stream-host.example/vod/a-recording-8-20-26/playlist.m3u8?DVR   A Recording, Aug 20
https://your-stream-host.example/vod/another-recording/playlist.m3u8?DVR
```

Each URL is probed exactly like an event, so extras get the same LIVE badge,
duration label and download affordance; a 4xx drops the entry, anything else
keeps it. Editing the manifest is enough to change the feed — no release needed.

The body is cached in `SharedPreferences` / `NSUserDefaults` / `localStorage` and
reused for 24 hours, so the manifest is fetched about once a day per device; a
failed refresh falls back to the stale copy rather than emptying the feed. Pull
to refresh bypasses both that TTL and the event-probe cache.

A **secret gist** is the intended host: it keeps a private list out of this
repository and off GitHub's search. It is *unlisted, not access-controlled* —
anyone with the raw URL can read it, and the URL ships inside the app, where it
is extractable. Recordings that need real privacy want signed URLs or a backend
that authorizes each viewer. Leaving `EXTRA_VIDEOS_URL` empty disables the
feature outright: no request is made and the feed is exactly the events.

### Discord / Apollo gate

The landing screen's gate reads two more values from the same `secrets.properties`.
Neither is a secret — the client id is public by design and the guild id is a
snowflake — but they live there so a fork configures its own Discord application:

| Key | Where to get it |
| --- | --- |
| `DISCORD_CLIENT_ID` | Discord Developer Portal → your application → OAuth2 → Client ID |
| `APOLLO_GUILD_ID` | Enable Developer Mode in Discord, then right-click the Apollo server icon → Copy Server ID |

Register these redirect URIs on the application (OAuth2 → Redirects) — they must
match byte-for-byte or Discord rejects the request:

- Android & iOS: `discord-<DISCORD_CLIENT_ID>:/authorize/callback`
  — Discord mandates this exact shape for mobile deep links (note the **single**
  slash, and the `discord-` prefix). Arbitrary schemes are rejected by the portal.
- Web: the URL the wasm bundle is served from (origin + path, no query/fragment)

Also enable **Public Client** on the OAuth2 page. PKCE is mandatory for mobile
deep links, and the public-client flag is what lets the token exchange omit the
client secret — without it the exchange fails with `401 invalid_client`.

The Android manifest's redirect scheme is generated from `DISCORD_CLIENT_ID` (see
`androidApp/build.gradle.kts`), so the intent filter and the redirect URI cannot
drift apart. iOS gets the same guarantee: `:composeApp:generateIosSecretsXcconfig`
derives `DISCORD_REDIRECT_SCHEME` from the same value, and `iosApp/project.yml`
registers it under `CFBundleURLTypes`. Nothing to set by hand.

Leaving `DISCORD_CLIENT_ID` empty disables the gate — the button then reports that
sign-in is not configured rather than opening a broken authorization URL. Leaving
`APOLLO_GUILD_ID` empty falls back to matching the guild *name* `Apollo`, which is
convenient for a first run but not unique on Discord.