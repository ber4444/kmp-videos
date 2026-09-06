# Token and feed-policy service

A small Ktor service holding what the apps must not carry themselves: the Soniox
API key, the addresses the video feed is built from, and the list of accounts
whose feed differs from everyone else's.

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
GET  /v1/feed/policy            ->  200 {"stream_host": "...", "manifest_url": "..."}
GET  /health                    ->  200 {"status": "ok"}
```

Failure modes the client distinguishes: `429` (rate limited — retry), `502`
(Soniox refused *our* key — retry, and check the logs), `403` (this caller is not
allowed one — stop).

`/v1/feed/policy` answers "where are this account's videos". Both fields are
addresses, not permissions: the apps hold no stream host and no manifest URL of
their own, so an account handed neither cannot reach the catalogue at all. This
route **is** the Apollo membership check — see [Feed policy](#feed-policy).

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
| `STREAM_HOST` | *(empty)* | Scheme + authority of the stream server, no trailing slash. Handed to members only. Empty means members see no numbered events. |
| `EXTRA_VIDEOS_URL` | *(empty)* | Raw URL of the members' extras manifest. Handed to members only. |
| `TEST_USER_IDS` | *(empty)* | Comma-separated Discord **user** snowflakes given `DEMO_VIDEOS_URL` and no stream host, and minted caption keys without Apollo membership. Empty means no account is exempt. |
| `DEMO_VIDEOS_URL` | *(empty)* | Raw URL of the manifest those accounts see. Required if `TEST_USER_IDS` is set; without it those accounts are refused. |
| `PORT` | `8080` | |
| `ALLOWED_ORIGINS` | *(empty)* | Comma-separated browser origins for CORS. Empty blocks every web origin; the native apps are unaffected. Set this only if you serve the wasmJs build. |
| `KEY_TTL_SECONDS` | `60` | Only has to cover the WebSocket connect. |
| `MAX_SESSION_SECONDS` | `3600` | Caps a session that *did* connect. |
| `RATE_LIMIT` | `30` | Minted keys per client per window. |
| `RATE_LIMIT_REFILL_SECONDS` | `300` | |

## Authorization

Keys are minted **only for members of the Apollo Discord guild**. The caller sends
the Discord access token the app already holds from the landing-screen gate, and
`DiscordGuildAuthorizer` asks Discord which guilds that token can see.

This is a second, independent membership check, not a duplicate of the feed
route's. The two answer different questions — who may spend the Soniox account,
and where an account's videos are — and a deployment could reasonably answer them
differently, so neither is allowed to depend on the other's configuration.

**A review account *is* minted for**, by way of `TEST_USER_IDS`. An account handed
to app-store review has to be able to use the feature, not just see the button;
the alternative was a reviewer whose only experience of captions is an error.

The identity read that decides this happens **only after the guild check has
already failed**, so a member's mint still costs one Discord call — which matters,
because that is the path taken on every caption reconnect through a long video.
An empty `TEST_USER_IDS` never reaches for an identity at all.

This does put the Soniox bill behind an account given to strangers. What bounds it
is what bounds every other caller — the per-client rate limit and the one-hour
session cap — rather than the list being short.

It **fails closed** — a rejected token, a non-member, and a Discord outage all
deny — and matches on the guild snowflake only, never the name, since guild names
are not unique. Answers are cached for five minutes keyed on a hash of the token,
because a long video's reconnects would otherwise become a stream of Discord calls.

`APOLLO_GUILD_ID` is required and startup fails without it: an unset guild id could
only mean "mint for everyone", and a service that silently stops checking identity
looks healthy while standing open.

## Feed policy

`/v1/feed/policy` decides where an account's videos are, and answers with the
addresses themselves:

| Caller | Response |
|---|---|
| Listed in `TEST_USER_IDS` | `200 {"stream_host": "", "manifest_url": "<DEMO_VIDEOS_URL>"}` |
| An Apollo member | `200 {"stream_host": "<STREAM_HOST>", "manifest_url": "<EXTRA_VIDEOS_URL>"}` |
| Neither | `403` |

**This is the Apollo membership check.** It used to run in the client, comparing
the account's guild list to a snowflake — while `STREAM_HOST` and
`EXTRA_VIDEOS_URL` were compiled into every build. Membership therefore decided
what the UI *showed*, and the addresses it guarded shipped to anyone who could
unzip an APK, read an `Info.plist` out of an IPA, or view-source the web bundle.
Here the addresses *are* the answer: a non-member is not told where the streams
are, which is a decision a patched client cannot reverse.

A review account is given a manifest and **no stream host**, so the numbered
events are not filtered out of its gallery — there is no URL for it to build.

Properties covered by `FeedPolicyRouteTest`:

- **Snowflakes only, never usernames.** Discord usernames can be changed and a
  released one can be re-registered, so a username in the allowlist would be an
  exemption inherited by whoever claims it next.
- **Identity is checked before membership.** A listed account gets no stream host
  even if it is on Apollo — the guild call is skipped for it entirely — so one
  added to the server later keeps the demo feed rather than gaining the real
  catalogue.
- **A half-applied config cannot widen the feed.** A listed account with no
  `DEMO_VIDEOS_URL` is refused rather than falling through to the membership
  check.
- **A refusal leaks nothing**, including the host it is refusing access to.

Like the caption route it fails closed and caches per token for five minutes.

```bash
fly secrets set STREAM_HOST=https://your-host.example:443 \
  EXTRA_VIDEOS_URL=https://gist.githubusercontent.com/…/raw/extras.txt \
  TEST_USER_IDS=1545912350056390857 \
  DEMO_VIDEOS_URL=https://gist.githubusercontent.com/…/raw/videos.txt \
  --app apollo-videos-tokens
```

Use gist raw URLs **without** the revision hash (`…/raw/videos.txt`, not
`…/raw/<sha>/videos.txt`). The pinned form freezes the manifest at one revision,
which costs the whole point of hosting the list outside the app.

### Availability

Moving the check here makes this service a dependency of playback, not just of
captions. The apps distinguish the two failure modes so an outage is not an
eviction: a `403` is terminal and clears the stored session, while an
unreachable service leaves the session intact and surfaces a retry. Nobody is
locked out permanently by a bad deploy, but nobody watches anything during one.
