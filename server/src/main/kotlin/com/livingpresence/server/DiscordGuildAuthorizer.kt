package com.livingpresence.server

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Mints only for members of the Apollo Discord guild.
 *
 * The caller presents the same Discord access token the app already obtained to
 * get through the landing-screen gate; this asks Discord which guilds that token
 * can see and looks for [guildId]. It is the server-side restatement of the
 * client's `isApolloMember`, and it has to be server-side: the client's own check
 * decides what the *UI* shows, which is not a constraint on anyone calling this
 * endpoint directly.
 *
 * **Fails closed.** A token Discord rejects, a token that sees no Apollo, *and* a
 * Discord outage all deny. Failing open on the outage would be more forgiving to
 * users and would also mean anyone could mint keys during it, which defeats the
 * point of having the check at all.
 *
 * **Matches on the snowflake only.** Guild *names* are not unique on Discord, so
 * the client's name-based fallback (convenient before an id is configured) is
 * deliberately not reproduced here — anyone could stand up their own "Apollo".
 * [ServerConfig] refuses to boot without an id for the same reason.
 *
 * **Answers are cached per token** for [CACHE_TTL_MS]. Captions reconnect through
 * a long video and each reconnect mints a fresh key, so an uncached check would
 * turn one viewer into a stream of Discord calls and reach Discord's rate limit.
 * The cache is keyed on a hash of the token, never the token itself: this process
 * has no reason to hold borrowed credentials in memory any longer than the call
 * that uses them.
 */
class DiscordGuildAuthorizer(
    private val httpClient: HttpClient,
    private val guildId: String,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val apiBase: String = DISCORD_API_BASE,
) : Authorizer {

    private val cache = mutableMapOf<Int, CachedDecision>()
    private val cacheLock = Mutex()

    override suspend fun authorize(call: ApplicationCall): AuthorizationDecision {
        val token = call.request.header(HttpHeaders.Authorization)
            ?.removePrefix("Bearer ")
            ?.trim()
            .orEmpty()
        if (token.isEmpty()) return AuthorizationDecision.Denied(NOT_SIGNED_IN)

        val key = token.hashCode()
        cached(key)?.let { return it }

        val decision = checkWithDiscord(token)
        remember(key, decision)
        return decision
    }

    private suspend fun checkWithDiscord(token: String): AuthorizationDecision {
        val response = try {
            httpClient.get("$apiBase/users/@me/guilds") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
        } catch (e: Throwable) {
            // Deny rather than propagate: an unreachable Discord is not a 500 from
            // this service, and must not become a window in which anyone can mint.
            return AuthorizationDecision.Denied(DISCORD_UNREACHABLE)
        }
        if (response.status == HttpStatusCode.Unauthorized) {
            return AuthorizationDecision.Denied(TOKEN_REJECTED)
        }
        if (!response.status.isSuccess()) {
            return AuthorizationDecision.Denied(DISCORD_UNREACHABLE)
        }
        val guilds = runCatching {
            json.decodeFromString<List<Guild>>(response.bodyAsText())
        }.getOrNull() ?: return AuthorizationDecision.Denied(DISCORD_UNREACHABLE)

        return if (guilds.any { it.id == guildId }) {
            AuthorizationDecision.Allowed
        } else {
            AuthorizationDecision.Denied(NOT_A_MEMBER)
        }
    }

    private suspend fun cached(key: Int): AuthorizationDecision? = cacheLock.withLock {
        val hit = cache[key] ?: return@withLock null
        if (hit.expiresAtMs <= nowMs()) {
            cache.remove(key)
            null
        } else {
            hit.decision
        }
    }

    private suspend fun remember(key: Int, decision: AuthorizationDecision) = cacheLock.withLock {
        // Denials are cached too, and for the same duration: a client that keeps
        // retrying a token Discord already refused must not become a way to spend
        // this service's Discord rate limit.
        if (cache.size >= MAX_CACHE_ENTRIES) cache.clear()
        cache[key] = CachedDecision(decision, nowMs() + CACHE_TTL_MS)
    }

    private data class CachedDecision(val decision: AuthorizationDecision, val expiresAtMs: Long)

    @Serializable
    private data class Guild(val id: String)

    companion object {
        const val DISCORD_API_BASE = "https://discord.com/api/v10"

        /**
         * Five minutes. Long enough that a video's reconnects cost one Discord call,
         * short enough that someone removed from Apollo loses captions promptly —
         * the app re-checks membership at every launch, so this is the only window
         * in which the two can disagree.
         */
        const val CACHE_TTL_MS = 5 * 60 * 1000L

        /** Bounded so a flood of distinct tokens cannot grow this without limit. */
        const val MAX_CACHE_ENTRIES = 10_000

        const val NOT_SIGNED_IN = "Connect to Discord to use captions."
        const val NOT_A_MEMBER = "Captions are for members of the Apollo server."
        const val TOKEN_REJECTED = "Your Discord sign-in has expired. Reconnect to use captions."
        const val DISCORD_UNREACHABLE = "Could not verify your Discord membership right now."
    }
}
