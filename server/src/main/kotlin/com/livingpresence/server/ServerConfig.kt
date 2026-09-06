package com.livingpresence.server

/**
 * Everything this service reads from the environment.
 *
 * [sonioxApiKey] is the *only* secret here, and the whole point of the service is
 * that it is the only place that value ever lives. It is read from the environment
 * (`fly secrets set SONIOX_API_KEY=…`), never from a file in the repo, and never
 * echoed into a response or a log line.
 */
data class ServerConfig(
    val sonioxApiKey: String,
    /**
     * Snowflake of the Apollo guild. Callers must present a Discord token that can
     * see it. Not a secret — any member can read it off the server — but required,
     * because it is the whole of the identity check.
     */
    val apolloGuildId: String,
    /**
     * Scheme and authority of the stream server, e.g. `https://host:443`, handed
     * to members and to nobody else.
     *
     * Every numbered-event playlist URL is built from this, so an account that is
     * not given it has no way to reach the catalogue at all — which is why it is
     * served per-caller rather than compiled into the apps, where `unzip` would
     * read it out of any build regardless of who was signed in.
     *
     * Empty is not fatal: members then see only [extraVideosUrl], which is an
     * obviously wrong feed rather than a silently open door.
     */
    val streamHost: String,
    /**
     * Raw URL of the members' extras manifest — the plain-text list of recordings
     * appended to the numbered events. Served alongside [streamHost], to the same
     * callers, for the same reason.
     */
    val extraVideosUrl: String,
    /**
     * Snowflakes of the review/demo accounts, which are admitted without being
     * Apollo members and shown [demoVideosUrl] alone.
     *
     * Lives here rather than in the apps because it is a *policy*, and a policy
     * compiled into a shipped binary is neither confidential nor revocable: it
     * would be readable with `unzip`, and changing it would mean a release. Empty
     * is the ordinary state — no account is exempt.
     */
    val testUserIds: Set<String>,
    /**
     * Manifest served to a [testUserIds] account, in the plain-text format
     * `ExtraVideoCatalog` parses. It is that account's *whole* feed: those
     * accounts are given no [streamHost], so the numbered events are not merely
     * hidden from them — they are unreachable.
     *
     * Empty means the exemption cannot be honoured, and [DiscordFeedPolicyResolver]
     * refuses those accounts rather than falling back to the members' feed.
     */
    val demoVideosUrl: String,
    val port: Int,
    /**
     * Origins allowed to call the endpoint from a browser. The wasmJs build is a
     * cross-origin caller, so without a match here the browser refuses the request
     * before it is ever sent — the native apps are unaffected either way.
     *
     * Empty means "no browser origin", which is the safe default: a native-only
     * deployment should not be reachable from any web page.
     */
    val allowedOrigins: List<String>,
    /** Lifetime of a minted key. Only has to cover the WebSocket *connect*. */
    val keyTtlSeconds: Int,
    /** Hard cap on how long one Soniox session opened with a minted key may run. */
    val maxSessionSeconds: Int,
    /** Minted keys allowed per client per [rateLimitRefillSeconds]. */
    val rateLimit: Int,
    val rateLimitRefillSeconds: Int,
) {
    companion object {

        /**
         * Reads the config, failing loudly when the key is absent.
         *
         * Deliberately fatal rather than degrading to a 503 route: a service whose
         * only job is to hold one secret, booting happily without it, is a silent
         * misconfiguration that looks healthy in the Fly dashboard and only shows
         * up as broken captions. A failed deploy rolls back, which is the signal
         * that should reach whoever ran it.
         */
        fun fromEnvironment(env: (String) -> String? = System::getenv): ServerConfig {
            val key = env("SONIOX_API_KEY")?.trim().orEmpty()
            require(key.isNotEmpty()) {
                "SONIOX_API_KEY is not set. Run: fly secrets set SONIOX_API_KEY=…"
            }
            // Fatal for the same reason the key is. An unset guild id could only
            // mean "let everyone through", and a service that silently stops
            // checking identity is worse than one that never claimed to: the
            // dashboard stays green while the gate is open.
            val guildId = env("APOLLO_GUILD_ID")?.trim().orEmpty()
            require(guildId.isNotEmpty()) {
                "APOLLO_GUILD_ID is not set. Run: fly secrets set APOLLO_GUILD_ID=…"
            }
            // The feed values are all optional. Unset means "hand out nothing",
            // which shows up immediately as an empty gallery — unlike an unset
            // guild id, there is no reading of them under which the service keeps
            // looking healthy while standing open.
            return ServerConfig(
                sonioxApiKey = key,
                apolloGuildId = guildId,
                streamHost = env("STREAM_HOST")?.trim()?.trimEnd('/').orEmpty(),
                extraVideosUrl = env("EXTRA_VIDEOS_URL")?.trim().orEmpty(),
                testUserIds = env("TEST_USER_IDS").orEmpty()
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet(),
                demoVideosUrl = env("DEMO_VIDEOS_URL")?.trim().orEmpty(),
                port = env("PORT")?.toIntOrNull() ?: 8080,
                allowedOrigins = env("ALLOWED_ORIGINS").orEmpty()
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() },
                keyTtlSeconds = env("KEY_TTL_SECONDS")?.toIntOrNull() ?: DEFAULT_KEY_TTL_SECONDS,
                maxSessionSeconds = env("MAX_SESSION_SECONDS")?.toIntOrNull()
                    ?: DEFAULT_MAX_SESSION_SECONDS,
                rateLimit = env("RATE_LIMIT")?.toIntOrNull() ?: DEFAULT_RATE_LIMIT,
                rateLimitRefillSeconds = env("RATE_LIMIT_REFILL_SECONDS")?.toIntOrNull()
                    ?: DEFAULT_RATE_LIMIT_REFILL_SECONDS,
            )
        }

        /**
         * 60 s. The key is spent the moment the WebSocket handshake completes, so
         * this only has to cover "app got the response, app dialled Soniox" — not
         * the length of the video. Soniox's own maximum is 3600.
         */
        const val DEFAULT_KEY_TTL_SECONDS = 60

        /**
         * 1 hour. A caption session that outlives this is dropped by Soniox and the
         * client's reconnect loop opens a fresh one with a fresh key, so the ceiling
         * costs a reconnect rather than the captions.
         */
        const val DEFAULT_MAX_SESSION_SECONDS = 3_600

        /**
         * Generous for a viewer, cheap for an abuser. One caption session needs one
         * key; the rest of the budget absorbs reconnects across a long event.
         */
        const val DEFAULT_RATE_LIMIT = 30
        const val DEFAULT_RATE_LIMIT_REFILL_SECONDS = 300
    }
}
