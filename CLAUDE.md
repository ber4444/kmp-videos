# CLAUDE.md

Apollo Videos: a Compose Multiplatform app (Android, iOS, wasmJs) that plays
live/recorded HLS from a Wowza nDVR server, synthesizes a client-side ABR ladder
from unadvertised sibling renditions, and captions it live through Soniox. The
README explains *why* things are the way they are; this file is the working
summary. When the two disagree, the code wins — fix whichever doc is stale.

## Modules

| Module | What it is |
| --- | --- |
| `:androidApp` | Android application shell: `MainActivity`, manifest (two foreground services, Discord redirect filter), signing, R8 config |
| `:composeApp` | All app UI and every platform integration. Source sets: `commonMain`, `androidMain`, `iosMain`, `wasmJsMain`, plus `nonAndroidMain` (shared parent of iOS + wasm, added by extending the default hierarchy template — not with manual `dependsOn`) |
| `:mediakit` | Pure-Kotlin playback SDK (HLS parsing, ladder synthesis, rendition probing). `commonMain` only; `androidMain` holds just a manifest. Has a public, ABI-checked API |
| `:server` | Ktor token and feed-policy service on Fly.io (`apollo-videos-tokens`). Holds the Soniox key, `STREAM_HOST`, manifest URLs, and the Apollo guild check |
| `iosApp/` | SwiftUI host, generated from `iosApp/project.yml` by `xcodegen`; it links a *prebuilt* `ComposeApp.framework` |
| `eval/` | Python caption-provider eval harness (own venv, own gitignored `.env`) |
| `e2e/` | Playwright smoke test that the wasm production build renders something |

Package root: `com.livingpresence.inner.circle.squared`. There's no DI framework:
wiring is by constructor (`rememberMainViewModel()` in `App.kt`). Metro sits in
the version catalog, but no build script applies it.

## Commands

```bash
# Android
./gradlew assembleDebug
./gradlew :androidApp:bundleRelease          # R8 + resource shrinking; debug-signed without ~/key.properties

# Tests (what CI runs)
./gradlew :mediakit:testAndroidHostTest :composeApp:testAndroidHostTest
./gradlew :mediakit:apiCheck
./gradlew -PserverOnly=true :server:test     # no Android SDK needed

# More tests
./gradlew :mediakit:jvmTest                  # fastest loop for SDK logic
./gradlew :mediakit:allTests                 # SDK on every target
./gradlew :composeApp:allTests               # adds the commonTest suites
./gradlew koverXmlReport

# iOS (macOS only)
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
cd iosApp && xcodegen generate && xcodebuild -project ICSApp.xcodeproj -scheme ICSApp \
    -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 17' build

# Web
./gradlew wasmJsBrowserDevelopmentRun
./gradlew wasmJsBrowserDistribution && python3 e2e/test_wasm_not_empty.py   # needs playwright

# SDK docs (Dokka 2: the old `dokkaHtml` task is a disabled V1 stub)
./gradlew :mediakit:dokkaGenerateHtml
```

The Robolectric suites live in `composeApp/src/androidHostTest`. CI runs on Linux,
so iOS tasks are disabled there and nothing in CI compiles Kotlin/Native. If you
touch `iosMain`, Swift, or the cinterop, build the iOS framework locally.

Toolchain: the Gradle daemon runs on JDK 25 (`gradle/gradle-daemon-jvm.properties`),
and compilation targets JDK 17 through `jvmToolchain(17)` (foojay auto-provisions
it). Versions live in `gradle/libs.versions.toml`, but the Kotlin, AGP and
Compose plugin versions are *also* pinned in `settings.gradle.kts`
`pluginManagement`. Bump both together.

## Explicit API mode

`:composeApp` and `:mediakit` both compile with `explicitApi()`. Every
declaration needs a visibility modifier, or the build fails.

- **`:composeApp`: default to `internal`.** Anything public is exported into the
  `ComposeApp.framework` Objective-C header and becomes API `:androidApp` can
  reach. The only public declarations are host entry points:
  - for `:androidApp`: `HostBridge`, `PipController`, `DiscordConfig.clientId`,
    `DiscordAuthBroker.deliver`/`isAuthRedirect`, `TranscriptionSecrets.sonioxTokenEndpoint`
  - for Swift: `mainViewController()` and `handleDeepLink()`
  - for wasm: `main()`

  Make something public only when one of those hosts calls it, and give the
  public declaration an explicit return type.
- Keep an `expect` and all its `actual`s at the same visibility.
- Test doubles that implement internal types must be `internal` too, or the
  compile fails with `EXPOSED_PARAMETER_TYPE`.
- Compose resources are internal (`publicResClass = false`).
- **`:mediakit`: public is the SDK surface.** Any public API change shows up in
  `mediakit/api/mediakit.api`. Run `./gradlew :mediakit:apiDump` and commit the
  dump as part of the change. An unintended diff there means a binary break.

## Secrets and configuration

- `secrets.properties` (gitignored; copy `secrets.properties.example`) holds only
  `SONIOX_TOKEN_URL` and `DISCORD_CLIENT_ID`. Both are optional: an empty token
  URL falls back to `TranscriptionSecrets.DEFAULT_SONIOX_TOKEN_URL`, and an empty
  client id disables the Discord gate.
- **Never put a credential in `secrets.properties`.** Every value in it ships in
  the binary: a `BuildConfig` constant in the dex, an `Info.plist` entry in the
  IPA, a string in the JS bundle. Provider keys and stream addresses belong in
  `:server`'s environment (`fly secrets set …`): `SONIOX_API_KEY`, `STREAM_HOST`,
  `EXTRA_VIDEOS_URL`, `APOLLO_GUILD_ID`, `TEST_USER_IDS`, `DEMO_VIDEOS_URL`.
- Gradle is the only reader of the file. Android gets `BuildConfig` fields
  (`androidApp/build.gradle.kts`), wasm gets a generated `TranscriptionKeys` object,
  and iOS gets the gitignored `iosApp/Secrets.xcconfig` from
  `:composeApp:generateIosSecretsXcconfig`. To add an iOS key, add it to
  `iosSecretKeys` in `composeApp/build.gradle.kts` **and** to `iosApp/project.yml`.
  Never hand-edit `Info.plist`: `xcodegen` regenerates it.
- Release signing reads `~/key.properties`, outside the repo. Without it, release
  falls back to the debug key.
- `eval/` keeps its own keys in `eval/.env`, separate from the app's.

## Conventions and traps

- **`minSdk = 24` is set in three places**: `:androidApp`, `:composeApp` and
  `:mediakit`. Change all three together. iOS deploys to 16.0 (`iosApp/project.yml`).
- **`Res.drawable.*` throws `MissingResourceException` on Android.** The KMP
  Android library plugin packages no `composeResources/` for Android. Shared
  images go through the host's `res/drawable` via `HostBridge`; see
  `backgroundDrawableResId`. On iOS, resource assembly is tied to the framework
  link, and an Xcode build phase copies the result. Don't unwire either.
- **The AVPlayer cinterop needs a workaround.** `AVPlayerBridge.m` is compiled to
  bitcode and injected into the cinterop klib's `natives/`, because cinterop
  ignores the def's `sources =`. If you call a new AVPlayer method from Kotlin,
  route it through `composeApp/native/avplayer/cinterop/AVPlayerBridge.{h,m}`:
  cinterop doesn't merge AVPlayer's category methods.
- **R8**: `androidApp/proguard-rules.pro` deliberately has no blanket
  `-keep class pkg.** { *; }` rules. Libraries ship their consumer rules, and AGP
  keeps manifest components. Add a targeted rule only for a demonstrated
  reflective need, and smoke-test the minified release build.
- **Security dependency floors are listed three times**: the `buildscript` block in
  `settings.gradle.kts`, the `buildscript` block in `build.gradle.kts`, and
  `forceSecurityPatchedVersions()` in `build.gradle.kts`. To raise a floor, change
  every copy that lists it. The `settings.gradle.kts` copy has no httpcomponents
  5.x entries. `io.netty` is forced only on the 4.1 line, because forcing 4.2
  requests down breaks resolution.
- Android playback is owned by `PlaybackService` (a `MediaSessionService`), not
  by a composable, so it survives config changes and powers background
  audio and PiP. Don't move the player into composition.
- Captions run on Soniox only. Deepgram code still compiles, but nothing renders
  `CaptionProviderButton`. Re-enabling it would also mean updating
  `docs/privacy/`, which names Soniox alone.
- Changing what the app sends where (audio, tokens, identifiers) means updating
  the privacy policy in `docs/privacy/index.html`, the page Google Play links to.
- Gradle comments in this repo explain *why* a setting exists, usually with the
  failure it prevents. Read them before changing a setting, and write new ones
  the same way.

## Commits and PRs

- Commit titles use a short conventional prefix where one fits, such as
  `build(deps):`, `fix(android):`, `feat(ios):`, `docs:`, or a plain sentence
  saying what changed.
- PR descriptions are prose. They cover what was wrong, why the fix is right,
  and what was actually verified (device, build type, tests). Say what wasn't
  verified, too.
