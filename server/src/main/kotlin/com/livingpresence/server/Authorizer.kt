package com.livingpresence.server

import io.ktor.server.application.ApplicationCall

/** Whether a caller may be minted a key. */
sealed interface AuthorizationDecision {
    data object Allowed : AuthorizationDecision

    /**
     * [reason] is shown to the user, so it is written for them rather than for a
     * log: "Connect to Discord to use captions", not "missing bearer token". It is
     * the one place this service's refusals become user-facing text.
     */
    data class Denied(val reason: String) : AuthorizationDecision
}

/**
 * Decides who gets a Soniox key.
 *
 * The shipped implementation is [DiscordGuildAuthorizer]: the caller presents the
 * Discord access token the app already holds, and only members of the Apollo guild
 * are minted for. It is the default argument of `Application.module`, so a
 * deployment cannot end up without an identity check by omission — and
 * [ServerConfig] refuses to boot without a guild id, so it cannot end up with one
 * that trivially passes either.
 *
 * This stays an interface rather than a function on the route because the two
 * things it separates are genuinely separate: *what the endpoint costs* (a Soniox
 * call, bounded by rate limiting) and *who may spend it*. Tests substitute
 * [AllowAll] to exercise the first without standing up the second.
 */
fun interface Authorizer {

    suspend fun authorize(call: ApplicationCall): AuthorizationDecision

    companion object {
        /**
         * Allows every caller. **Tests only** — it exists so route behaviour can be
         * exercised without a Discord round trip. Installing this in `main` would
         * turn the endpoint back into an open faucet whose address is public in
         * `fly.toml`, which is exactly the state this module was written to leave.
         */
        val AllowAll = Authorizer { AuthorizationDecision.Allowed }
    }
}
