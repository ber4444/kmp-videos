package com.livingpresence.inner.circle.squared.transcription

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Fetches the short-lived Soniox key a caption session connects with.
 *
 * This is the client half of the fix for the shipped-credential problem: the app
 * no longer carries a Soniox key at all, and asks `:server` for a scoped one at
 * the moment it needs to open a socket. What comes back is good for a single
 * WebSocket connect within a minute, so there is nothing durable to extract from
 * the binary, from memory, or off the wire.
 *
 * **A key is fetched per session, not per app launch.** That is a requirement,
 * not an optimization: the keys are single-use, so [WebSocketTranscriber]'s
 * reconnect loop — which runs many times across a feature-length video — has to
 * ask again each time. Caching one would work exactly once and then fail every
 * reconnect for the rest of the stream.
 */
class SonioxKeyProvider(
    private val httpClient: HttpClient,
    private val baseUrl: () -> String = { TranscriptionSecrets.sonioxTokenEndpoint },
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    /**
     * Mints a key, or returns blank when captions are unconfigured for this build.
     *
     * @throws TranscriptionKeyException when the service is reachable but refuses,
     *   so the caller can tell "retry in a moment" (rate limited, provider blip)
     *   from "stop asking" (forbidden). Transport failures propagate as-is and are
     *   retried like any other dropped session.
     */
    suspend fun fetch(): String {
        val base = baseUrl().trim().trimEnd('/')
        if (base.isEmpty()) return ""

        val response = httpClient.post("$base$TEMPORARY_KEY_PATH")
        if (!response.status.isSuccess()) {
            throw TranscriptionKeyException(
                status = response.status.value,
                // The status carries the meaning; the body is the service's and is
                // not quoted into a user-visible transcript.
                message = "Caption key request failed (${response.status.value})",
            )
        }
        return json.decodeFromString<TemporaryKey>(response.bodyAsText()).apiKey
    }

    @Serializable
    private data class TemporaryKey(
        @SerialName("api_key") val apiKey: String,
        @SerialName("expires_at") val expiresAt: String? = null,
    )

    companion object {
        /** Must match `TEMPORARY_KEY_PATH` in the `:server` module. */
        const val TEMPORARY_KEY_PATH = "/v1/soniox/temporary-key"
    }
}

/**
 * A refusal from the temporary-key service.
 *
 * [status] is what decides whether the caption loop retries. 403 (a caller the
 * service will not mint for) is terminal; 429 and 5xx are not, and are left to the
 * ordinary reconnect backoff.
 */
class TranscriptionKeyException(
    val status: Int,
    message: String,
) : Exception(message)
