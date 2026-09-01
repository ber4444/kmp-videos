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
            return ServerConfig(
                sonioxApiKey = key,
                apolloGuildId = guildId,
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
