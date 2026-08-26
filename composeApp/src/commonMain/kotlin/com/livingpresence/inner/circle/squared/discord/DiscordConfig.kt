package com.livingpresence.inner.circle.squared.discord

/**
 * Discord OAuth2 wiring for the landing screen's "Connect to Discord" gate.
 *
 * Mirrors `TranscriptionSecrets`: each platform host injects the values at
 * startup (Android `BuildConfig`, the wasm bundle's generated keys, the iOS
 * `Info.plist`) so `commonMain` stays free of build plumbing. Neither value is
 * actually a secret — the client id is public by design and the guild id is a
 * snowflake — but they ride along in the gitignored `secrets.properties` so a
 * fork does not inherit this app's Discord application.
 */
object DiscordConfig {

    /** OAuth2 client id of the Discord application. Empty disables the gate. */
    var clientId: String = ""

    /**
     * Snowflake of the Apollo guild. Empty falls back to matching on
     * [APOLLO_GUILD_NAME], which is convenient but not unique on Discord.
     */
    var apolloGuildId: String = ""

    /** Overrides [defaultDiscordRedirectUri] when a host needs a different URI. */
    var redirectUriOverride: String? = null

    /**
     * Where Discord sends the browser back to.
     *
     * Computed on each read rather than cached: on mobile it is derived from
     * [clientId], which the platform host does not set until startup.
     */
    val redirectUri: String get() = redirectUriOverride ?: defaultDiscordRedirectUri()

    /** Name match used when [apolloGuildId] is unset. */
    const val APOLLO_GUILD_NAME: String = "Apollo"

    /**
     * `identify` names the connected account, `guilds` lists the servers it
     * belongs to — the minimum needed to answer "is this user on Apollo?".
     */
    const val SCOPES: String = "identify guilds"

    const val API_BASE: String = "https://discord.com/api/v10"

    /** Whether a Discord application has been configured for this build. */
    val isConfigured: Boolean get() = clientId.isNotBlank()
}

/**
 * The redirect Discord returns to after authorization.
 *
 * On Android and iOS this is Discord's *mandated* mobile deep-link shape,
 * `discord-<APP_ID>:/authorize/callback` — note the single slash, and that the
 * scheme is derived from the application id. Discord's portal rejects arbitrary
 * schemes, so this format is not a preference. The web build round-trips through
 * its own page URL instead, since no browser tab can land on a custom scheme.
 *
 * Whatever this returns must be registered verbatim as a redirect URI on the
 * Discord application, or authorization fails before the consent screen.
 */
expect fun defaultDiscordRedirectUri(): String

/** The custom URL scheme Discord requires for [clientId]'s mobile redirect. */
fun discordRedirectScheme(clientId: String): String = "discord-$clientId"
