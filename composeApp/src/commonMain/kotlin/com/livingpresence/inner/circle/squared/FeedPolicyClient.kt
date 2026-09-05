package com.livingpresence.inner.circle.squared

import com.livingpresence.inner.circle.squared.discord.DiscordIdentity
import com.livingpresence.inner.circle.squared.transcription.TranscriptionSecrets
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What `:server` says this account may watch.
 *
 * @property restrictedManifestUrl When non-null, the account's **entire** feed:
 *   no numbered events and none of the build-time extras. Null is the ordinary
 *   feed, and deliberately carries no URL — a member's extras manifest stays a
 *   build-time value, so an outage here cannot empty a member's gallery.
 */
@Serializable
data class FeedPolicy(
    @SerialName("restricted_manifest_url") val restrictedManifestUrl: String? = null,
)

/** Why [FeedPolicyClient] had no policy to hand back. */
enum class FeedPolicyAbsence {
    /** No endpoint, or nobody signed in. The app falls back to its own config. */
    NOT_APPLICABLE,

    /** The service was asked and could not answer. Also a fallback. */
    UNAVAILABLE,

    /** The service answered, and the answer is no. Not a fallback. */
    REFUSED,
}

/** Either the server's answer, or why there isn't one. */
sealed interface FeedPolicyResult {
    data class Resolved(val policy: FeedPolicy) : FeedPolicyResult
    data class Absent(val reason: FeedPolicyAbsence) : FeedPolicyResult
}

/**
 * Asks `:server` which videos the connected account is allowed to see.
 *
 * The demo/review accounts used for app-store review are not on the Apollo
 * server and must see a curated manifest instead of the real catalogue. That list
 * and that URL used to live in each app's build config, which made a *policy*
 * into a shipped constant: readable with `unzip`, unchangeable without a release,
 * and enforced by the client on its own say-so. Both now live in `:server`, which
 * derives the answer from the identity Discord reports for the presented token.
 *
 * **Absence is not refusal.** A build with no endpoint, a user who is not
 * connected, and a service that is down all yield [FeedPolicyAbsence] values the
 * caller treats as "carry on with the build-time feed" — the ordinary feed must
 * not depend on this service being reachable. Only an explicit 403 is a refusal.
 */
class FeedPolicyClient(
    private val httpClient: HttpClient,
    /**
     * The same `:server` deployment the caption path mints against — one service,
     * two routes, so it reads back the endpoint `SONIOX_TOKEN_URL` configures
     * rather than introducing a second URL every host would have to inject.
     */
    private val baseUrl: () -> String = { TranscriptionSecrets.sonioxTokenEndpoint },
    private val discordToken: () -> String = { DiscordIdentity.accessToken },
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    /**
     * The policy for [token], or why there is none.
     *
     * Never throws: every transport failure is [FeedPolicyAbsence.UNAVAILABLE],
     * because a gallery that goes blank when this service hiccups would be a worse
     * outcome than one that shows what the build was configured with.
     */
    suspend fun fetch(token: String = discordToken()): FeedPolicyResult {
        val base = baseUrl().trim().trimEnd('/')
        if (base.isEmpty() || token.isEmpty()) {
            return FeedPolicyResult.Absent(FeedPolicyAbsence.NOT_APPLICABLE)
        }

        val response = runCatching {
            httpClient.get("$base$FEED_POLICY_PATH") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
        }.getOrNull() ?: return FeedPolicyResult.Absent(FeedPolicyAbsence.UNAVAILABLE)

        if (response.status == HttpStatusCode.Forbidden) {
            return FeedPolicyResult.Absent(FeedPolicyAbsence.REFUSED)
        }
        if (!response.status.isSuccess()) {
            return FeedPolicyResult.Absent(FeedPolicyAbsence.UNAVAILABLE)
        }
        return runCatching {
            FeedPolicyResult.Resolved(json.decodeFromString<FeedPolicy>(response.bodyAsText()))
        }.getOrElse { FeedPolicyResult.Absent(FeedPolicyAbsence.UNAVAILABLE) }
    }

    companion object {
        /** Must match `FEED_POLICY_PATH` in the `:server` module. */
        const val FEED_POLICY_PATH = "/v1/feed/policy"
    }
}
