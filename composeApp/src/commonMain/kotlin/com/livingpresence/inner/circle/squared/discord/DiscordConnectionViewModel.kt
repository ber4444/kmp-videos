package com.livingpresence.inner.circle.squared.discord

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Message shown when the account is not a member of the Apollo guild. */
const val NOT_ON_APOLLO_MESSAGE: String = "User must be on the Apollo server"

private const val NOT_CONFIGURED_MESSAGE =
    "Discord sign-in is not configured for this build."
private const val UNVERIFIED_MESSAGE =
    "Discord sign-in could not be verified. Please try again."
private const val UNREACHABLE_MESSAGE =
    "Could not reach Discord. Check your connection and try again."

/** Where the landing screen's Discord gate currently stands. */
sealed interface DiscordConnectionState {

    /** Nothing started yet — the connect button is showing. */
    data object Disconnected : DiscordConnectionState

    /** A stored session exists and is being refreshed and re-checked. */
    data class RestoringSession(val displayName: String) : DiscordConnectionState

    /** The browser is open on Discord's consent screen. */
    data object AwaitingAuthorization : DiscordConnectionState

    /** A token came back; the guild list is being checked. */
    data object Verifying : DiscordConnectionState

    /** Authorized *and* on Apollo — the feed is unlocked. */
    data class Connected(val displayName: String) : DiscordConnectionState

    /** Authorized, but the account is not a member of Apollo. */
    data object NotOnApolloServer : DiscordConnectionState

    /** Anything else: denial, config gap, or a network/API failure. */
    data class Failed(val message: String) : DiscordConnectionState
}

/**
 * Drives the connect-to-Discord gate: hands out an authorization URL, matches the
 * redirect that comes back, then asks Discord whether the account is on Apollo.
 *
 * The ViewModel never opens a browser or touches a deep link itself — it returns
 * the URL to open from [beginConnect] and is fed redirects through [onRedirect],
 * so all platform plumbing stays at the edges.
 */
class DiscordConnectionViewModel(
    private val api: DiscordApi,
    private val sessionStore: DiscordSessionStore = NoOpDiscordSessionStore,
    private val broker: DiscordAuthBroker = DiscordAuthBroker,
) : ViewModel() {

    /** The session found at construction, if any. Consumed once by [restoreSession]. */
    private val storedSession: DiscordSession? =
        if (DiscordConfig.isConfigured) sessionStore.load() else null

    // Starting in RestoringSession rather than flipping to it from an effect keeps
    // a returning user from seeing the connect button flash on every cold start.
    private val _state = MutableStateFlow<DiscordConnectionState>(
        storedSession
            ?.let { DiscordConnectionState.RestoringSession(it.displayName) }
            ?: DiscordConnectionState.Disconnected,
    )
    val state: StateFlow<DiscordConnectionState> = _state.asStateFlow()

    /** Guards against a recomposition kicking off a second restore. */
    private var restoreStarted = false

    /**
     * Starts an authorization and returns the URL the caller should open, or null
     * when this build has no Discord application configured.
     */
    fun beginConnect(): String? {
        if (!DiscordConfig.isConfigured) {
            _state.value = DiscordConnectionState.Failed(NOT_CONFIGURED_MESSAGE)
            return null
        }
        val authState = DiscordAuth.newState()
        val codeVerifier = DiscordAuth.newCodeVerifier()
        broker.startAuthorization(state = authState, codeVerifier = codeVerifier)
        _state.value = DiscordConnectionState.AwaitingAuthorization
        return DiscordAuth.authorizeUrl(
            clientId = DiscordConfig.clientId,
            redirectUri = DiscordConfig.redirectUri,
            state = authState,
            codeChallenge = DiscordAuth.codeChallenge(codeVerifier),
        )
    }

    /**
     * Refreshes a stored session and re-checks Apollo membership, so a returning
     * user goes straight to the feed. No-op when there is nothing stored.
     *
     * Membership is re-verified on every launch rather than trusting a "was a
     * member once" flag: someone who leaves Apollo should lose access at the next
     * launch, not keep it forever.
     */
    fun restoreSession() {
        val session = storedSession
        if (session == null || restoreStarted) {
            return
        }
        restoreStarted = true
        viewModelScope.launch {
            _state.value = try {
                val token = api.refreshAccessToken(session.refreshToken)
                persist(token, fallbackRefreshToken = session.refreshToken)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: DiscordApiException) {
                // 4xx means the refresh token is revoked or expired — the session
                // is genuinely dead, so drop it and show the connect button rather
                // than an error the user can do nothing about.
                if (error.status in 400..499) {
                    sessionStore.clear()
                    DiscordConnectionState.Disconnected
                } else {
                    DiscordConnectionState.Failed(error.message ?: UNREACHABLE_MESSAGE)
                }
            } catch (error: Throwable) {
                // Offline or DNS failure: the session may still be good, so keep it
                // and let the user retry instead of forcing a re-authorization.
                DiscordConnectionState.Failed(error.message ?: UNREACHABLE_MESSAGE)
            }
        }
    }

    /** Returns to the initial state so the user can try again. */
    fun reset() {
        broker.finishAuthorization()
        broker.consumeRedirect()
        _state.value = DiscordConnectionState.Disconnected
    }

    /**
     * Handles a redirect URI delivered by a platform entry point. Consumes it from
     * the broker first so a recomposition cannot process the same token twice.
     */
    fun onRedirect(redirectUri: String) {
        broker.consumeRedirect()
        when (val redirect = DiscordAuth.parseRedirect(redirectUri)) {
            is DiscordRedirect.Code -> onAuthorizationCode(redirect)
            is DiscordRedirect.Denied -> {
                broker.finishAuthorization()
                _state.value = DiscordConnectionState.Failed(denialMessage(redirect))
            }
            // Some unrelated deep link reached us — leave the screen as it was.
            DiscordRedirect.Unrecognized -> Unit
        }
    }

    private fun onAuthorizationCode(redirect: DiscordRedirect.Code) {
        val expectedState = broker.pendingAuthState
        val codeVerifier = broker.pendingCodeVerifier
        // A redirect whose `state` does not match the one we sent did not come
        // from an authorization this app started, so the code is not ours to use.
        if (expectedState == null || redirect.state != expectedState || codeVerifier == null) {
            broker.finishAuthorization()
            _state.value = DiscordConnectionState.Failed(UNVERIFIED_MESSAGE)
            return
        }
        broker.finishAuthorization()
        verifyApolloMembership(code = redirect.code, codeVerifier = codeVerifier)
    }

    private fun verifyApolloMembership(code: String, codeVerifier: String) {
        _state.value = DiscordConnectionState.Verifying
        viewModelScope.launch {
            _state.value = try {
                persist(api.exchangeCode(code = code, codeVerifier = codeVerifier))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                DiscordConnectionState.Failed(error.message ?: UNREACHABLE_MESSAGE)
            }
        }
    }

    /**
     * Checks Apollo membership with [token] and, when the user is a member,
     * stores the session so the next launch skips the consent screen.
     *
     * Nothing is stored for a non-member: there is no access to resume, and a
     * saved token would only be a credential sitting on disk for no reason.
     *
     * @param fallbackRefreshToken kept when Discord omits a rotated token from
     *   the response, so a refresh never leaves the session without one.
     */
    private suspend fun persist(
        token: DiscordAccessToken,
        fallbackRefreshToken: String? = null,
    ): DiscordConnectionState {
        val user = api.currentUser(token.accessToken)
        val guilds = api.currentUserGuilds(token.accessToken)
        if (!isApolloMember(guilds)) {
            sessionStore.clear()
            return DiscordConnectionState.NotOnApolloServer
        }
        val refreshToken = token.refreshToken ?: fallbackRefreshToken
        if (refreshToken != null) {
            sessionStore.save(
                DiscordSession(refreshToken = refreshToken, displayName = user.displayName),
            )
        }
        return DiscordConnectionState.Connected(user.displayName)
    }

    private fun denialMessage(redirect: DiscordRedirect.Denied): String = when (redirect.error) {
        "access_denied" -> "Discord authorization was cancelled."
        else -> redirect.description ?: "Discord rejected the sign-in (${redirect.error})."
    }
}
