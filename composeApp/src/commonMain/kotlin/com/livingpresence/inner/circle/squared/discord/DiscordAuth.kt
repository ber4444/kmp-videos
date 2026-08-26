package com.livingpresence.inner.circle.squared.discord

import io.ktor.http.encodeURLParameter
import io.ktor.http.parseQueryString
import org.kotlincrypto.hash.sha2.SHA256
import kotlin.random.Random

/**
 * Builds the Discord authorization URL and parses the redirect it comes back on.
 *
 * Uses the authorization-code grant with PKCE. That is not a style choice:
 * Discord rejects arbitrary custom redirect schemes, and the only mobile shape it
 * accepts (`discord-<APP_ID>:/authorize/callback`) makes PKCE mandatory. The
 * earlier implicit grant could never have worked on a device.
 *
 * PKCE also removes the need for a client secret: the app proves it started the
 * authorization by presenting the `code_verifier` at token exchange, so nothing
 * confidential has to ship in the binary. The Discord application must have the
 * public-client flag set for the secret-less exchange to be accepted.
 */
object DiscordAuth {

    private const val AUTHORIZE_ENDPOINT = "https://discord.com/oauth2/authorize"
    private const val STATE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    private const val STATE_LENGTH = 32
    private const val VERIFIER_BYTES = 32

    /**
     * The URL to open in the browser to start authorization.
     *
     * `prompt=consent` is deliberate: without it Discord silently reuses a
     * previous grant, so a user who authorized the wrong account can never
     * switch. The cost is one extra tap on every connect.
     */
    fun authorizeUrl(
        clientId: String,
        redirectUri: String,
        state: String,
        codeChallenge: String,
        scopes: String = DiscordConfig.SCOPES,
    ): String = buildString {
        append(AUTHORIZE_ENDPOINT)
        append("?client_id=").append(clientId.encodeURLParameter())
        append("&redirect_uri=").append(redirectUri.encodeURLParameter())
        append("&response_type=code")
        append("&scope=").append(scopes.encodeURLParameter())
        append("&state=").append(state.encodeURLParameter())
        append("&code_challenge=").append(codeChallenge.encodeURLParameter())
        append("&code_challenge_method=S256")
        append("&prompt=consent")
    }

    /**
     * A fresh opaque nonce for the `state` parameter. Discord echoes it back on
     * the redirect; a mismatch means the redirect did not originate from the
     * authorization this app started, and the code is discarded.
     */
    fun newState(): String = buildString(STATE_LENGTH) {
        repeat(STATE_LENGTH) { append(STATE_ALPHABET[Random.nextInt(STATE_ALPHABET.length)]) }
    }

    /**
     * A fresh PKCE `code_verifier`: 32 random bytes, base64url-encoded without
     * padding, giving the 43 characters Discord recommends.
     *
     * [Random] is not a CSPRNG, which is a real limitation — a verifier that an
     * attacker could predict would weaken PKCE's binding of the authorization to
     * this app. Kotlin has no multiplatform secure RNG, and pulling one in means
     * a per-platform seam; on a device where an attacker can observe this
     * process's RNG state they can already read the token directly. Worth
     * revisiting if a platform-backed CSPRNG seam is ever added.
     */
    fun newCodeVerifier(): String =
        base64UrlNoPadding(ByteArray(VERIFIER_BYTES) { Random.nextInt(256).toByte() })

    /** The `code_challenge` for [codeVerifier]: base64url(SHA-256(verifier)). */
    fun codeChallenge(codeVerifier: String): String =
        base64UrlNoPadding(SHA256().digest(codeVerifier.encodeToByteArray()))

    /**
     * Interprets the URI Discord redirected to.
     *
     * The authorization code arrives in the query string (unlike the implicit
     * grant's fragment), but denials can land in either depending on where
     * authorization failed — so both are read.
     */
    fun parseRedirect(redirectUri: String): DiscordRedirect {
        val fragment = redirectUri.substringAfter('#', missingDelimiterValue = "")
        val query = redirectUri
            .substringBefore('#')
            .substringAfter('?', missingDelimiterValue = "")

        val fragmentParams = parseQueryString(fragment)
        val queryParams = parseQueryString(query)
        fun param(name: String): String? =
            (queryParams[name] ?: fragmentParams[name])?.takeIf { it.isNotBlank() }

        val state = param("state")
        val error = param("error")
        if (error != null) {
            return DiscordRedirect.Denied(
                error = error,
                description = param("error_description"),
                state = state,
            )
        }

        val code = param("code")
        if (code != null) {
            return DiscordRedirect.Code(code = code, state = state)
        }

        return DiscordRedirect.Unrecognized
    }

    /**
     * Base64url without padding (RFC 4648 §5), the encoding PKCE requires for
     * both the verifier and the challenge. Hand-rolled because Kotlin's common
     * stdlib Base64 is still experimental and its url-safe variant pads.
     */
    private fun base64UrlNoPadding(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val out = StringBuilder((bytes.size * 4 + 2) / 3)
        var index = 0
        while (index + 2 < bytes.size) {
            val chunk = ((bytes[index].toInt() and 0xFF) shl 16) or
                ((bytes[index + 1].toInt() and 0xFF) shl 8) or
                (bytes[index + 2].toInt() and 0xFF)
            out.append(alphabet[(chunk ushr 18) and 0x3F])
            out.append(alphabet[(chunk ushr 12) and 0x3F])
            out.append(alphabet[(chunk ushr 6) and 0x3F])
            out.append(alphabet[chunk and 0x3F])
            index += 3
        }
        when (bytes.size - index) {
            1 -> {
                val chunk = (bytes[index].toInt() and 0xFF) shl 16
                out.append(alphabet[(chunk ushr 18) and 0x3F])
                out.append(alphabet[(chunk ushr 12) and 0x3F])
            }
            2 -> {
                val chunk = ((bytes[index].toInt() and 0xFF) shl 16) or
                    ((bytes[index + 1].toInt() and 0xFF) shl 8)
                out.append(alphabet[(chunk ushr 18) and 0x3F])
                out.append(alphabet[(chunk ushr 12) and 0x3F])
                out.append(alphabet[(chunk ushr 6) and 0x3F])
            }
        }
        return out.toString()
    }
}

/** The three shapes a Discord redirect can take. */
sealed interface DiscordRedirect {

    /** Authorization succeeded; [code] is exchangeable for an access token. */
    data class Code(
        val code: String,
        val state: String?,
    ) : DiscordRedirect

    /** The user declined, or Discord rejected the request (e.g. bad redirect URI). */
    data class Denied(
        val error: String,
        val description: String?,
        val state: String?,
    ) : DiscordRedirect

    /** A deep link that is not an authorization result — ignored. */
    data object Unrecognized : DiscordRedirect
}
