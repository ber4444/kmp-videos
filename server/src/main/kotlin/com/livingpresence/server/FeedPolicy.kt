package com.livingpresence.server

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the caller's feed is allowed to contain.
 *
 * One nullable field rather than a flag plus a URL, because the two would only
 * ever be set together: a restricted account is restricted *to* something.
 */
@Serializable
data class FeedPolicy(
    /**
     * When present, this manifest is the caller's **entire** feed — the app skips
     * the numbered events and its own build-time extras. Absent is the ordinary
     * feed, and carries no URL: a member's extras manifest stays a build-time
     * value in the app, so an outage here cannot empty a member's gallery.
     */
    @SerialName("restricted_manifest_url") val restrictedManifestUrl: String? = null,
)

/** Whether a caller gets a feed, and which one. */
sealed interface FeedDecision {
    data class Granted(val policy: FeedPolicy) : FeedDecision

    /** [reason] is user-facing text, as in [AuthorizationDecision.Denied]. */
    data class Denied(val reason: String) : FeedDecision
}

/**
 * Decides what a caller may watch.
 *
 * Separate from [Authorizer] because the questions differ: that one asks whether
 * a caller may spend the Soniox account, this one asks which videos they see. A
 * deployment could reasonably answer them differently, and the caption route must
 * not start depending on feed configuration to keep working.
 */
fun interface FeedPolicyResolver {

    suspend fun resolve(call: ApplicationCall): FeedDecision
}

/**
 * The shipped [FeedPolicyResolver]: Apollo members get the ordinary feed, the
 * configured review accounts get the demo manifest and nothing else.
 *
 * **Why this is server-side.** The apps used to carry the account list and the
 * demo URL in their build config and branch on them locally. That put a policy
 * in a shipped binary — readable with `unzip`, and changeable only by cutting a
 * release — and made "which videos does this account see" a claim the client made
 * about itself. Here the list is a `fly secrets` value, the answer is derived from
 * the identity Discord reports for the presented token, and changing either is a
 * deploy rather than a release.
 *
 * **Matches on the snowflake only.** Discord usernames can be changed and a
 * released one can be re-registered, so a username in the allowlist would be an
 * exemption inherited by whoever claims it next. Ids are permanent, which is the
 * only property that makes an allowlist meaningful.
 *
 * **Identity is checked before membership.** A review account that later joins
 * Apollo must keep seeing the demo feed rather than silently gaining the real one,
 * so the allowlist wins and the guild call is skipped entirely for those accounts.
 *
 * **Fails closed**, and caches per token for [CACHE_TTL_MS], for the reasons given
 * on [DiscordGuildAuthorizer] — the gallery reloads on every launch and pull to
 * refresh, so an uncached check would be a Discord call per refresh.
 */
class DiscordFeedPolicyResolver(
    private val httpClient: HttpClient,
    private val guildId: String,
    private val testUserIds: Set<String>,
    private val demoVideosUrl: String,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val apiBase: String = DiscordGuildAuthorizer.DISCORD_API_BASE,
) : FeedPolicyResolver {

    private val cache = mutableMapOf<Int, CachedDecision>()
    private val cacheLock = Mutex()

    override suspend fun resolve(call: ApplicationCall): FeedDecision {
        val token = call.request.header(HttpHeaders.Authorization)
            ?.removePrefix("Bearer ")
            ?.trim()
            .orEmpty()
        if (token.isEmpty()) return FeedDecision.Denied(NOT_SIGNED_IN)

        val key = token.hashCode()
        cached(key)?.let { return it }

        val decision = decide(token)
        remember(key, decision)
        return decision
    }

    private suspend fun decide(token: String): FeedDecision {
        val userId = fetchUserId(token) ?: return FeedDecision.Denied(DISCORD_UNREACHABLE)
        if (userId in testUserIds) {
            // A named review account with nothing to show it is a misconfiguration,
            // and the safe reading of it is "no feed" — falling through to the
            // membership check would hand the real catalogue to an account that was
            // singled out precisely to be kept away from it.
            return if (demoVideosUrl.isNotEmpty()) {
                FeedDecision.Granted(FeedPolicy(restrictedManifestUrl = demoVideosUrl))
            } else {
                FeedDecision.Denied(NO_DEMO_FEED)
            }
        }
        return when (isApolloMember(token)) {
            null -> FeedDecision.Denied(DISCORD_UNREACHABLE)
            true -> FeedDecision.Granted(FeedPolicy())
            false -> FeedDecision.Denied(NOT_A_MEMBER)
        }
    }

    /** The caller's snowflake, or null when Discord would not tell us. */
    private suspend fun fetchUserId(token: String): String? {
        val body = discordGet("/users/@me", token) ?: return null
        return runCatching { json.decodeFromString<User>(body).id }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    /** Whether the token can see Apollo, or null when Discord would not tell us. */
    private suspend fun isApolloMember(token: String): Boolean? {
        val body = discordGet("/users/@me/guilds", token) ?: return null
        val guilds = runCatching {
            json.decodeFromString<List<Guild>>(body)
        }.getOrNull() ?: return null
        return guilds.any { it.id == guildId }
    }

    /** The response body, or null for any outcome that is not a usable answer. */
    private suspend fun discordGet(path: String, token: String): String? {
        val response = try {
            httpClient.get("$apiBase$path") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
        } catch (e: Throwable) {
            return null
        }
        // A rejected token and an outage are collapsed on purpose: both mean this
        // service has no basis on which to grant a feed, and neither may become a
        // window in which one is granted anyway.
        if (!response.status.isSuccess()) return null
        return runCatching { response.bodyAsText() }.getOrNull()
    }

    private suspend fun cached(key: Int): FeedDecision? = cacheLock.withLock {
        val hit = cache[key] ?: return@withLock null
        if (hit.expiresAtMs <= nowMs()) {
            cache.remove(key)
            null
        } else {
            hit.decision
        }
    }

    private suspend fun remember(key: Int, decision: FeedDecision) = cacheLock.withLock {
        if (cache.size >= DiscordGuildAuthorizer.MAX_CACHE_ENTRIES) cache.clear()
        cache[key] = CachedDecision(decision, nowMs() + CACHE_TTL_MS)
    }

    private data class CachedDecision(val decision: FeedDecision, val expiresAtMs: Long)

    @Serializable
    private data class User(val id: String)

    @Serializable
    private data class Guild(val id: String)

    companion object {
        /** Five minutes, matching [DiscordGuildAuthorizer.CACHE_TTL_MS]. */
        const val CACHE_TTL_MS = DiscordGuildAuthorizer.CACHE_TTL_MS

        const val NOT_SIGNED_IN = "Connect to Discord to load the feed."
        const val NOT_A_MEMBER = "The feed is for members of the Apollo server."
        const val NO_DEMO_FEED = "No demo feed is configured for this account."
        const val DISCORD_UNREACHABLE = "Could not verify your Discord account right now."
    }
}
