package com.livingpresence.server

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the apps receive: a key they may use once, and when it stops working. */
@Serializable
data class TemporaryKeyResponse(
    @SerialName("api_key") val apiKey: String,
    @SerialName("expires_at") val expiresAt: String,
)

/** A refusal from Soniox, passed through as a status the client can act on. */
class SonioxException(val status: Int, message: String) : Exception(message)

/**
 * Trades the long-lived Soniox key for the short-lived ones the apps stream with.
 *
 * This is the whole security boundary of the change. Before it, every install
 * carried a permanent key that could be read out of the binary and spent without
 * limit; after it, an install carries nothing, and the worst a *minted* key buys
 * is one WebSocket session bounded by [ServerConfig.maxSessionSeconds].
 *
 * Three request options do that bounding, and all three matter:
 *  - `usage_type` scopes the key to streaming transcription, so a leaked one
 *    cannot be spent against text-to-speech.
 *  - `single_use` means one WebSocket connection per key. The client's reconnect
 *    loop asks for a fresh one per session, so this costs nothing and removes the
 *    "grab the key off the wire and reuse it" case entirely.
 *  - `max_session_duration_seconds` caps a session that *does* get established,
 *    which is the only thing `expires_in_seconds` cannot cover — expiry gates the
 *    connect, not the stream that follows it.
 */
class SonioxTokenService(
    private val httpClient: HttpClient,
    private val config: ServerConfig,
    private val endpoint: String = SONIOX_TEMPORARY_KEY_URL,
    // encodeDefaults, or the request silently loses its teeth: kotlinx omits
    // default values, and `usage_type` and `single_use` are *both* defaults here.
    // Dropping them does not fail — Soniox just applies its own defaults, and the
    // minted key comes back broader than the one this code appears to ask for.
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {

    suspend fun mint(): TemporaryKeyResponse {
        val response = httpClient.post(endpoint) {
            header(HttpHeaders.Authorization, "Bearer ${config.sonioxApiKey}")
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    TemporaryKeyRequest(
                        expiresInSeconds = config.keyTtlSeconds,
                        maxSessionDurationSeconds = config.maxSessionSeconds,
                    ),
                ),
            )
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            // The body can quote the request; never let it reach a client, and never
            // log it either — the failure is reported by status alone.
            throw SonioxException(
                status = response.status.value,
                message = "Soniox rejected the temporary-key request (${response.status.value})",
            )
        }
        return json.decodeFromString<TemporaryKeyResponse>(body)
    }

    @Serializable
    private data class TemporaryKeyRequest(
        @SerialName("usage_type") val usageType: String = "transcribe_websocket",
        @SerialName("expires_in_seconds") val expiresInSeconds: Int,
        @SerialName("single_use") val singleUse: Boolean = true,
        @SerialName("max_session_duration_seconds") val maxSessionDurationSeconds: Int,
    )

    companion object {
        const val SONIOX_TEMPORARY_KEY_URL = "https://api.soniox.com/v1/auth/temporary-api-key"
    }
}
