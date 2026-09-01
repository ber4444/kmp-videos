package com.livingpresence.server

import io.ktor.server.application.ApplicationCall

/** Whether a caller may be minted a key. */
sealed interface AuthorizationDecision {
    data object Allowed : AuthorizationDecision
    data class Denied(val reason: String) : AuthorizationDecision
}

/**
 * Decides who gets a Soniox key.
 *
 * **The shipped implementation is [Open] — the endpoint checks no identity.** That
 * is a deliberate, temporary position, and it is worth being precise about what it
 * does and does not buy, because "we moved the key server-side" is easy to hear as
 * "the endpoint is protected".
 *
 * What holds without any authorizer: the long-lived key is no longer extractable
 * from any binary, every minted key dies in [ServerConfig.keyTtlSeconds], is good
 * for exactly one WebSocket connection, is scoped to transcription, and is capped
 * at [ServerConfig.maxSessionSeconds] of audio. Abuse means running a client
 * against a rate-limited endpoint for one bounded session at a time, not walking
 * off with an unlimited credential.
 *
 * What does not hold: anyone who finds the URL can obtain those keys. The rate
 * limit and a spend limit on the Soniox account are what bound the bill.
 *
 * **Closing that gap is a small change, and the app already has the identity for
 * it.** Every user who can reach captions has passed the Discord gate on the
 * landing screen (`DiscordConnectionViewModel` only unlocks the feed for a member
 * of the Apollo guild). A `DiscordGuildAuthorizer` would:
 *
 *  1. read the caller's Discord access token from the `Authorization` header,
 *  2. `GET https://discord.com/api/v10/users/@me/guilds` with it,
 *  3. return [AuthorizationDecision.Allowed] when the configured `APOLLO_GUILD_ID`
 *     is in the list — the same predicate `isApolloMember` already applies client
 *     side — and [AuthorizationDecision.Denied] otherwise,
 *  4. cache the answer per token for a few minutes, so a reconnect storm does not
 *     become a Discord rate-limit problem.
 *
 * The client half is the only real work: `DiscordConnectionViewModel.persist`
 * currently drops the access token once membership is verified, so it would need
 * to be held in memory and reach `SonioxKeyProvider`.
 *
 * That implementation is deliberately **not** written yet rather than written and
 * left unwired — an authorizer that exists but is never installed reads as a
 * control that is in force when it is not.
 */
fun interface Authorizer {

    suspend fun authorize(call: ApplicationCall): AuthorizationDecision

    companion object {
        /** Allows every caller. See the class KDoc for exactly what still bounds them. */
        val Open = Authorizer { AuthorizationDecision.Allowed }
    }
}
