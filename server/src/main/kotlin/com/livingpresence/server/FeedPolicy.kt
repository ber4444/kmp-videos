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
 * The caller's feed, in full.
 *
 * Not a description of what to filter — the app has nothing to filter *from*.
 * These two values are the only sources it has, so an account is confined to a
 * feed by being handed less of them, not by being told to hide something it
 * already holds.
 */
@Serializable
data class FeedPolicy(
    /**
     * Scheme and authority every numbered-event URL is built from. Empty means the
     * caller gets no numbered events at all — not hidden, unreachable.
     */
    @SerialName("stream_host") val streamHost: String = "",
    /**
     * Raw URL of the caller's manifest: the members' extras list, or a review
     * account's demo list. Empty means no manifest is fetched.
     */
    @SerialName("manifest_url") val manifestUrl: String = "",
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
 * The shipped [FeedPolicyResolver]: Apollo members get the stream host and the
 * extras manifest, the configured review accounts get the demo manifest alone,
 * and everyone else is refused.
 *
 * **This is the Apollo membership check.** It used to be a client-side one, with
 * the stream host and the extras URL compiled into every build; membership then
 * decided what the UI *showed* while the addresses themselves shipped to anyone
 * who could unzip an APK. Here the addresses are the answer: an account that is
 * not a member is not told where the streams are, so there is nothing for a
 * patched client to reveal. The apps hold no host, no manifest URL and no account
 * list of their own.
 *
 * **Matches on the snowflake only.** Discord usernames can be changed and a
 * released one can be re-registered, so a username in the allowlist would be an
 * exemption inherited by whoever claims it next. Ids are permanent, which is the
 * only property that makes an allowlist meaningful.
 *
 * **Identity is checked before membership.** A review account gets no stream host
 * even if it is on Apollo — the guild call is skipped for it entirely — so one
 * that is later added to the server keeps the demo feed rather than silently
 * gaining the real catalogue.
 *
 * **Fails closed**, and caches per token for [CACHE_TTL_MS], for the reasons given
 * on [DiscordGuildAuthorizer] — the gallery reloads on every launch and pull to
 * refresh, so an uncached check would be a Discord call per refresh.
 */
class DiscordFeedPolicyResolver(
    private val httpClient: HttpClient,
    private val guildId: String,
    private val streamHost: String,
    private val extraVideosUrl: String,
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
            // No stream host, deliberately: a review account is meant to see the
            // demo list and nothing else, and withholding the host is what makes
            // that true of the streams rather than only of the gallery.
            //
            // A named review account with no manifest behind it is a
            // misconfiguration whose safe reading is "no feed" — falling through
            // to the membership check would hand the real catalogue to an account
            // singled out precisely to be kept away from it.
            return if (demoVideosUrl.isNotEmpty()) {
                FeedDecision.Granted(FeedPolicy(streamHost = "", manifestUrl = demoVideosUrl))
            } else {
                FeedDecision.Denied(NO_DEMO_FEED)
            }
        }
        return when (isApolloMember(token)) {
            null -> FeedDecision.Denied(DISCORD_UNREACHABLE)
            true -> FeedDecision.Granted(
                FeedPolicy(streamHost = streamHost, manifestUrl = extraVideosUrl),
            )
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
        const val NOT_A_MEMBER = "User must be on the Apollo server."
        const val NO_DEMO_FEED = "No demo feed is configured for this account."
        const val DISCORD_UNREACHABLE = "Could not verify your Discord account right now."
    }
}
