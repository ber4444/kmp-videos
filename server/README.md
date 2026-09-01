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
fly secrets set SONIOX_API_KEY=your-key APOLLO_GUILD_ID=your-guild-snowflake --app apollo-videos-tokens
```

The guild id is not a secret — any member can read it off the server — but it is
set alongside the key rather than committed to `fly.toml`, matching how the app
keeps it in the gitignored `secrets.properties` so a fork configures its own.

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
| `APOLLO_GUILD_ID` | — | Required. Snowflake of the guild whose members may mint. Fails startup if unset. |
| `PORT` | `8080` | |
| `ALLOWED_ORIGINS` | *(empty)* | Comma-separated browser origins for CORS. Empty blocks every web origin; the native apps are unaffected. Set this only if you serve the wasmJs build. |
| `KEY_TTL_SECONDS` | `60` | Only has to cover the WebSocket connect. |
| `MAX_SESSION_SECONDS` | `3600` | Caps a session that *did* connect. |
| `RATE_LIMIT` | `30` | Minted keys per client per window. |
| `RATE_LIMIT_REFILL_SECONDS` | `300` | |

## Authorization

Keys are minted **only for members of the Apollo Discord guild**. The caller sends
the Discord access token the app already holds from the landing-screen gate, and
`DiscordGuildAuthorizer` asks Discord which guilds that token can see. The client's
own membership check decides what the UI shows; it is not a constraint on anyone
calling this endpoint directly, which is why the check is repeated here.

It **fails closed** — a rejected token, a non-member, and a Discord outage all
deny — and matches on the guild snowflake only, never the name, since guild names
are not unique. Answers are cached for five minutes keyed on a hash of the token,
because a long video's reconnects would otherwise become a stream of Discord calls.

`APOLLO_GUILD_ID` is required and startup fails without it: an unset guild id could
only mean "mint for everyone", and a service that silently stops checking identity
looks healthy while standing open.

## Tests

```bash
./gradlew :server:test
```

Covers the request options that do the bounding, the rule that neither the
long-lived key nor a Soniox error body may reach a response, per-client rate
limiting, and that a denied caller costs no Soniox call. The authorizer suite adds
the gate itself: members minted, non-members and expired tokens refused, a Discord
outage failing closed, the cache not confusing two users, and a revoked membership
being re-checked once the cache expires.
