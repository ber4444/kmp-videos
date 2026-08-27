# Inner Circle Squared

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
| **Live STT Captions** (Deepgram/Soniox WebSockets) | ✅ | ✅ | ✅ |
| **ABR Ladder Synthesis** (from sibling renditions) | ✅ | ✅ | ✅ |
| **Viewport-Aware ABR** (auto-caps to screen size) | ✅ | ✅ | ✅ |
| **Manual Quality Override** (Auto, 720p, audio, etc) | ✅ | ✅ | ✅ |
| **Scrub Frame Previews** (floating thumbnail) | ✅ | ✅ | ✅ |
| **Live Event DVR** (drifting seekbar & jump-to-live) | ✅ | ✅ | ✅ |
| **Pillarboxing & Orientation Handling** | ✅ | ✅ | ✅ |
| **Background Audio** (auto-shifts to audio-only tier) | ✅ | ✅ | ✅ |
| **Picture-in-Picture (PiP)** | ✅ | ✅ | ✅ |
| **Offline HLS Downloads** | ✅ | ✅ | ❌ |
| **Preview Disk Caching** (persisted between sessions) | ✅ | ✅ | ❌ |
| **Memory Governor** (OOM prevention during PiP/bg) | ✅ | ❌ | ❌ |

## Caption provider evaluation

The live-caption provider choice is backed by a reproducible, record/replay eval
harness in [`eval/`](./eval) that scores Deepgram and Soniox against a domain-specific
golden set of event audio — batch WER/CER, domain-term recall (Entity F1),
keyterm-boosting impact, and real-time streaming realism (flicker + finalization
latency). On this material **Soniox clearly outperforms Deepgram** (0.242 vs 0.350
normalized WER; 0.77 vs 0.49 Entity F1), which is why the app ships Soniox as its
preferred provider. Run [`eval/run_eval.sh`](./eval/run_eval.sh) to regenerate the scorecard at
`eval/reports/scorecard.md` (generated output, not checked in); see
[`eval/README.md`](./eval/README.md) for the methodology.

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

## Configuration

Transcription API keys (for Deepgram and Soniox) are read from `secrets.properties` in the project root. See `secrets.properties.example` for details.

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
drift apart. On iOS, set `DISCORD_REDIRECT_SCHEME` to `discord-$(DISCORD_CLIENT_ID)`
in the Xcode build settings.

Leaving `DISCORD_CLIENT_ID` empty disables the gate — the button then reports that
sign-in is not configured rather than opening a broken authorization URL. Leaving
`APOLLO_GUILD_ID` empty falls back to matching the guild *name* `Apollo`, which is
convenient for a first run but not unique on Discord.