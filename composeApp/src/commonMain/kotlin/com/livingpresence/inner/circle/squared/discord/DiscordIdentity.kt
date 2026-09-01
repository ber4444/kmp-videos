package com.livingpresence.inner.circle.squared.discord

import kotlin.concurrent.Volatile

/**
 * The Discord access token for the current session, held in memory so the caption
 * path can prove who is asking.
 *
 * `:server` mints Soniox keys only for members of the Apollo guild, and it decides
 * that by calling Discord with this token. The landing-screen gate already obtains
 * one; before this existed it was used to answer "is this account on Apollo?" and
 * then dropped on the floor.
 *
 * **In memory only, and deliberately not persisted.** [DiscordSessionStore] stores
 * the *refresh* token and explains why it stores nothing else: an access token
 * expires in about a week, a refresh mints a fresh one at launch, so writing one to
 * disk adds exposure and buys nothing. That reasoning is unchanged — this holder
 * simply keeps the token that is already in hand for as long as the process lives.
 *
 * Empty when the user has not connected, or in a build with no Discord application
 * configured (`DiscordConfig.isConfigured` false). [SonioxKeyProvider] sends no
 * credential in that case and the service refuses it, which is the correct outcome:
 * captions are for Apollo members, and a build that cannot identify anyone cannot
 * have them.
 */
object DiscordIdentity {

    @Volatile
    private var token: String = ""

    /** The current access token, or empty when nobody is connected. */
    val accessToken: String get() = token

    /** Whether a token is available to authorize a caption key request. */
    val isConnected: Boolean get() = token.isNotEmpty()

    /**
     * Records the token from a completed authorization or refresh. Called once
     * membership has been verified, so a non-member's token is never retained.
     */
    fun remember(accessToken: String) {
        token = accessToken
    }

    /** Forgets the token — a rejected refresh, or a user who disconnects. */
    fun clear() {
        token = ""
    }
}
