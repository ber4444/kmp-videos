package com.livingpresence.inner.circle.squared.discord

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hands the OAuth redirect from whatever platform entry point receives it back
 * to the landing screen.
 *
 * This is a process-level singleton rather than screen state on purpose. The
 * redirect arrives from *outside* composition — an Android deep-link `Intent`,
 * the wasm page's own URL on load — and on Android that Intent can recreate the
 * Activity, which discards composition entirely. Anything scoped to the screen
 * would be gone by the time the token showed up.
 *
 * The pending [pendingAuthState] nonce lives here for the same reason: it has to
 * outlive the screen that started the authorization so the CSRF check still has
 * something to compare against. Full process death does lose it, and the user
 * then has to tap connect again — an acceptable trade for not persisting
 * anything auth-related to disk.
 */
object DiscordAuthBroker {

    private val _pendingRedirect = MutableStateFlow<String?>(null)

    /** The redirect URI waiting to be handled, or null when there is none. */
    val pendingRedirect: StateFlow<String?> = _pendingRedirect.asStateFlow()

    /** The `state` nonce of the authorization currently in flight, if any. */
    val pendingAuthState: String? get() = loadDiscordAuthState()

    /** The PKCE `code_verifier` for the authorization currently in flight. */
    val pendingCodeVerifier: String? get() = loadDiscordCodeVerifier()

    /**
     * Records the nonce and PKCE verifier for an authorization about to launch.
     * Both must survive the round trip: the nonce proves the redirect is ours,
     * and without the verifier the token exchange fails with `invalid_grant`.
     */
    fun startAuthorization(state: String, codeVerifier: String) {
        saveDiscordAuthState(state)
        saveDiscordCodeVerifier(codeVerifier)
        _pendingRedirect.value = null
    }

    /** Clears the in-flight nonce and verifier once a redirect has been matched. */
    fun finishAuthorization() {
        saveDiscordAuthState(null)
        saveDiscordCodeVerifier(null)
    }

    /** Publishes a redirect URI received by a platform entry point. */
    fun deliver(redirectUri: String) {
        _pendingRedirect.value = redirectUri
    }

    /** Drops the pending redirect once the screen has handled it. */
    fun consumeRedirect() {
        _pendingRedirect.value = null
    }

    /**
     * Whether [uri] looks like the redirect this app registered with Discord.
     * Platform entry points use this to ignore unrelated deep links.
     */
    fun isAuthRedirect(uri: String): Boolean =
        uri.startsWith(DiscordConfig.redirectUri, ignoreCase = true)
}

/**
 * Persists the in-flight `state` nonce for the duration of the authorization
 * round trip.
 *
 * In-memory is enough on Android and iOS, where the app stays resident while the
 * browser is in front. The web build genuinely leaves the page — Discord
 * navigates the tab away and back — so there the nonce has to survive a full
 * reload, which is what `sessionStorage` is for.
 */
internal expect fun saveDiscordAuthState(state: String?)

/** Reads back whatever [saveDiscordAuthState] last stored. */
internal expect fun loadDiscordAuthState(): String?

/** Persists the in-flight PKCE `code_verifier`. See [saveDiscordAuthState]. */
internal expect fun saveDiscordCodeVerifier(codeVerifier: String?)

/** Reads back whatever [saveDiscordCodeVerifier] last stored. */
internal expect fun loadDiscordCodeVerifier(): String?
