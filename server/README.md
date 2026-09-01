# Soniox temporary-key service

A single-route Ktor service whose only job is to hold the Soniox API key so the
apps don't have to.

## Why it exists

Live captions stream audio straight from the device to Soniox over a WebSocket,
and Soniox authenticates that socket with an API key. Until this service existed,
that key was compiled into every build:

| Target  | Where it landed         | How to read it out            |
|---------|-------------------------|-------------------------------|
| Android | `BuildConfig` → `classes.dex` | `unzip` the APK, `strings`  |
| iOS     | `Info.plist` in the `.app`    | `unzip` the IPA, read plist |
| Web     | the wasmJs bundle             | view source                 |

Gitignoring `secrets.properties` protected the *repository*; it never protected
the *binary*. Soniox is billed per hour of audio, so an extracted key is a bill,
not just a hygiene problem.

The app now ships a URL instead. It asks this service for a key at the moment it
opens a socket, and what it gets back is bounded four ways — the key is
**single-use**, expires in **60 seconds**, is scoped to **`transcribe_websocket`**
(so it cannot be spent on text-to-speech), and caps any session it does open at
**one hour**. See `SonioxTokenService` for why each of those matters.

## API

```
POST /v1/soniox/temporary-key   ->  201 {"api_key": "...", "expires_at": "..."}
GET  /health                    ->  200 {"status": "ok"}
```

Failure modes the client distinguishes: `429` (rate limited — retry), `502`
(Soniox refused *our* key — retry, and check the logs), `403` (this caller is not
allowed one — stop).

## Deploy

Run these from the repository root — the Dockerfile's build context needs the
Gradle wrapper and `settings.gradle.kts`, which live there.

The app already exists (`apollo-videos-tokens`). For a fresh environment, create it
with `fly apps create <name>` rather than `fly launch`: launch rewrites `fly.toml`
and strips every comment out of it, and the app name is already declared there.

Set the secret **before** the first deploy. `ServerConfig.fromEnvironment` refuses
to boot without it, so deploying first only fails the health check and rolls back:

```bash
fly secrets set SONIOX_API_KEY=your-key --app apollo-videos-tokens
```

```bash
fly deploy --config server/fly.toml
```

`--app` on the secrets command is deliberate: the sibling chess project also has a
`server/fly.toml`, so a relative `--config` resolves against whichever repository
you happen to be standing in.

No local Docker daemon is needed — `fly deploy` falls back to a remote builder.

Then point the apps at it by setting `SONIOX_TOKEN_URL` in the gitignored
`secrets.properties` (and in the Codemagic `app_secrets` group for iOS builds):

```
SONIOX_TOKEN_URL=https://your-app.fly.dev
```

`SONIOX_API_KEY` must **not** go in `secrets.properties` — everything in that file
is compiled into the shipped app. The service refuses to boot without the key in
its environment, so a missing secret fails the deploy rather than serving errors.

## Configuration

| Variable | Default | Notes |
|---|---|---|
| `SONIOX_API_KEY` | — | Required. Fails startup if unset. |
| `PORT` | `8080` | |
| `ALLOWED_ORIGINS` | *(empty)* | Comma-separated browser origins for CORS. Empty blocks every web origin; the native apps are unaffected. Set this only if you serve the wasmJs build. |
| `KEY_TTL_SECONDS` | `60` | Only has to cover the WebSocket connect. |
| `MAX_SESSION_SECONDS` | `3600` | Caps a session that *did* connect. |
| `RATE_LIMIT` | `30` | Minted keys per client per window. |
| `RATE_LIMIT_REFILL_SECONDS` | `300` | |

## Authorization

**The endpoint currently checks no identity** — see the `Authorizer` KDoc, which
spells out exactly what that does and does not leave exposed, and sketches the
Discord/Apollo check that would close it. In short: no long-lived credential is
extractable from any binary any more, and abuse is bounded by the rate limit plus
whatever spend limit is set on the Soniox account, but anyone who finds the URL
can obtain short-lived keys.

## Tests

```bash
./gradlew :server:test
```

Covers the request options that do the bounding, the rule that neither the
long-lived key nor a Soniox error body may reach a response, per-client rate
limiting, and that a denied caller costs no Soniox call.
