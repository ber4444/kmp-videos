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
 * The account's feed, in full, as `:server` issued it.
 *
 * Both fields are addresses rather than permissions, and that is the point: the
 * app has no stream host and no manifest URL of its own, so an account that is
 * handed neither cannot reach the catalogue at all — there is nothing compiled
 * into the binary for a patched client to fall back on.
 *
 * @property streamHost Scheme and authority every numbered-event URL is built
 *   from. Empty means no numbered events.
 * @property manifestUrl The account's manifest: the members' extras list, or a
 *   review account's demo list. Empty means none is fetched.
 */
@Serializable
data class FeedPolicy(
    @SerialName("stream_host") val streamHost: String = "",
    @SerialName("manifest_url") val manifestUrl: String = "",
)

/** Why [FeedPolicyClient] had no policy to hand back. */
enum class FeedPolicyAbsence {
    /** No endpoint, or nobody signed in — nothing has been asked for yet. */
    NOT_APPLICABLE,

    /** The service was asked and could not answer. Retryable. */
    UNAVAILABLE,

    /** The service answered, and the answer is no. Terminal. */
    REFUSED,
}

/** Either the server's answer, or why there isn't one. */
sealed interface FeedPolicyResult {
    data class Resolved(val policy: FeedPolicy) : FeedPolicyResult
    data class Absent(val reason: FeedPolicyAbsence) : FeedPolicyResult
}

/**
 * Asks `:server` where this account's videos are.
 *
 * This is the Apollo membership check, and it is a check the app cannot make. It
 * used to be one: the client read the account's guild list, compared it to a
 * snowflake, and unlocked a UI built on a stream host and a manifest URL that
 * were compiled into every build. Membership decided what was *shown* while the
 * addresses shipped to anyone who could unzip an APK. Now the addresses are the
 * answer — a non-member is not told where the streams are — so the check holds
 * against a client that has been patched to ignore it.
 *
 * **Absence is not refusal.** A build with no endpoint and a user who is not yet
 * connected are [FeedPolicyAbsence.NOT_APPLICABLE]; a service that cannot answer
 * is [FeedPolicyAbsence.UNAVAILABLE] and is worth retrying. Only an explicit 403
 * is [FeedPolicyAbsence.REFUSED], and only that may cost a stored session — an
 * outage must not log a member out of an app they are still entitled to use.
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
     * Never throws: every transport failure is [FeedPolicyAbsence.UNAVAILABLE], so
     * the callers get to decide what an outage means for them — a retryable error
     * in the gallery, and a preserved session at the gate.
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
