package com.livingpresence.inner.circle.squared.discord

import androidx.compose.runtime.Composable

/**
 * What survives between app launches so the user only authorizes once.
 *
 * Only the refresh token is load-bearing. Access tokens are deliberately *not*
 * stored: they expire in a week, and a refresh gets a fresh one anyway, so
 * keeping one on disk adds exposure without buying anything.
 *
 * [displayName] is cached purely so the restoring UI can greet the user without
 * waiting on a round trip; it is re-read from Discord on every restore.
 */
data class DiscordSession(
    val refreshToken: String,
    val displayName: String,
)

/**
 * Where a [DiscordSession] persists between launches.
 *
 * A refresh token is a bearer credential — anyone holding it can mint access
 * tokens for the account until it is revoked — so implementations use the most
 * protected per-app store each platform offers.
 */
interface DiscordSessionStore {

    /** Persists [session], replacing any previous one. */
    fun save(session: DiscordSession)

    /** The stored session, or null if there is none (or it could not be read). */
    fun load(): DiscordSession?

    /** Forgets the stored session — used when Discord rejects the refresh token. */
    fun clear()
}

/** The platform's [DiscordSessionStore]. */
@Composable
expect fun rememberDiscordSessionStore(): DiscordSessionStore

/**
 * A store that forgets everything, for previews, tests, and any platform where
 * persistence is unavailable. Restoring simply finds nothing and the user sees
 * the connect button.
 */
object NoOpDiscordSessionStore : DiscordSessionStore {
    override fun save(session: DiscordSession) = Unit
    override fun load(): DiscordSession? = null
    override fun clear() = Unit
}
