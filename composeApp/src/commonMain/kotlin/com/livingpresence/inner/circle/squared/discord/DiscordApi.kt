package com.livingpresence.inner.circle.squared.discord

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The connected Discord account. Only the naming fields are modelled — the rest
 * of the `/users/@me` payload is not needed to gate the feed.
 */
@Serializable
data class DiscordUser(
    val id: String,
    val username: String,
    @SerialName("global_name") val globalName: String? = null,
) {
    /** Discord's display name, falling back to the legacy username. */
    val displayName: String get() = globalName?.takeIf { it.isNotBlank() } ?: username
}

/** A partial guild object as returned by `/users/@me/guilds`. */
@Serializable
data class DiscordGuild(
    val id: String,
    val name: String,
)

/** The token endpoint's response. */
@Serializable
data class DiscordAccessToken(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("expires_in") val expiresIn: Long = 0,
    /**
     * Exchanged on the next launch for a fresh access token. Discord rotates
     * this on every refresh, so the newest value must always be persisted or the
     * session silently dies at the following launch.
     */
    @SerialName("refresh_token") val refreshToken: String? = null,
)

/** A non-2xx response from Discord, carrying the status for the error message. */
class DiscordApiException(
    val status: Int,
    message: String,
) : Exception(message)

/**
 * The two reads the Apollo gate needs, over the same [HttpClient] the rest of the
 * app uses. Deliberately tiny: an access token goes in, an answer comes out, and
 * nothing is cached or persisted.
 */
class DiscordApi(
    private val httpClient: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    /**
     * Exchanges an authorization [code] for an access token.
     *
     * No `client_secret` is sent — the PKCE [codeVerifier] is what proves this is
     * the app that started the authorization. Discord accepts the secret-less
     * exchange only when the application has the public-client flag set;
     * without it this comes back `401 invalid_client`.
     */
    suspend fun exchangeCode(
        code: String,
        codeVerifier: String,
        clientId: String = DiscordConfig.clientId,
        redirectUri: String = DiscordConfig.redirectUri,
    ): DiscordAccessToken = postToken(
        Parameters.build {
            append("client_id", clientId)
            append("grant_type", "authorization_code")
            append("code", code)
            append("redirect_uri", redirectUri)
            append("code_verifier", codeVerifier)
        },
    )

    /**
     * Trades a stored refresh token for a fresh access token, so a returning user
     * never sees the consent screen again.
     *
     * The response carries a *new* refresh token which the caller must persist —
     * Discord rotates them, and the old one stops working.
     */
    suspend fun refreshAccessToken(
        refreshToken: String,
        clientId: String = DiscordConfig.clientId,
    ): DiscordAccessToken = postToken(
        Parameters.build {
            append("client_id", clientId)
            append("grant_type", "refresh_token")
            append("refresh_token", refreshToken)
        },
    )

    private suspend fun postToken(form: Parameters): DiscordAccessToken {
        val response = httpClient.submitForm(
            url = "${DiscordConfig.API_BASE}/oauth2/token",
            formParameters = form,
        )
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw DiscordApiException(
                status = response.status.value,
                message = "Discord rejected the token request (${response.status.value}): $body",
            )
        }
        return json.decodeFromString(body)
    }

    /** The account that authorized, for the "Connected as …" line. */
    suspend fun currentUser(accessToken: String): DiscordUser =
        json.decodeFromString(authorizedGet("${DiscordConfig.API_BASE}/users/@me", accessToken))

    /** Every guild the account is a member of (requires the `guilds` scope). */
    suspend fun currentUserGuilds(accessToken: String): List<DiscordGuild> =
        json.decodeFromString(authorizedGet("${DiscordConfig.API_BASE}/users/@me/guilds", accessToken))

    private suspend fun authorizedGet(url: String, accessToken: String): String {
        val response = httpClient.get(url) {
            header("Authorization", "Bearer $accessToken")
        }
        if (!response.status.isSuccess()) {
            throw DiscordApiException(
                status = response.status.value,
                message = "Discord returned ${response.status.value} for ${url.substringAfterLast('/')}",
            )
        }
        return response.bodyAsText()
    }
}

/**
 * Whether [guilds] includes the Apollo server.
 *
 * Matches the configured snowflake when there is one — guild *names* are not
 * unique on Discord, so anyone could stand up their own "Apollo" and walk in.
 * The name match is only a fallback so the gate still functions before an id has
 * been configured.
 */
fun isApolloMember(guilds: List<DiscordGuild>): Boolean {
    val guildId = DiscordConfig.apolloGuildId
    return if (guildId.isNotBlank()) {
        guilds.any { it.id == guildId }
    } else {
        guilds.any { it.name.equals(DiscordConfig.APOLLO_GUILD_NAME, ignoreCase = true) }
    }
}
