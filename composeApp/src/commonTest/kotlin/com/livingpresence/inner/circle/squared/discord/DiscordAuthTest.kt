package com.livingpresence.inner.circle.squared.discord

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DiscordAuthTest {

    @AfterTest
    fun resetConfig() {
        DiscordConfig.apolloGuildId = ""
    }

    @Test
    fun authorizeUrlRequestsAnAuthorizationCodeWithPkceAndTheGuildScope() {
        val url = DiscordAuth.authorizeUrl(
            clientId = "123456789",
            redirectUri = "discord-123456789:/authorize/callback",
            state = "nonce",
            codeChallenge = "challenge",
        )

        assertTrue(url.startsWith("https://discord.com/oauth2/authorize?"), url)
        assertTrue("client_id=123456789" in url, url)
        assertTrue("response_type=code" in url, url)
        assertTrue("state=nonce" in url, url)
        // Discord accepts S256 only.
        assertTrue("code_challenge=challenge" in url, url)
        assertTrue("code_challenge_method=S256" in url, url)
        assertTrue("scope=identify+guilds" in url || "scope=identify%20guilds" in url, url)
        assertTrue("redirect_uri=discord-123456789%3A%2Fauthorize%2Fcallback" in url, url)
    }

    @Test
    fun redirectSchemeUsesDiscordsMandatedPrefix() {
        assertEquals("discord-123456789", discordRedirectScheme("123456789"))
    }

    @Test
    fun parseRedirectReadsTheAuthorizationCodeFromTheQuery() {
        val redirect = DiscordAuth.parseRedirect(
            "discord-123456789:/authorize/callback?code=abc123&state=nonce",
        )

        val code = assertIs<DiscordRedirect.Code>(redirect)
        assertEquals("abc123", code.code)
        assertEquals("nonce", code.state)
    }

    @Test
    fun parseRedirectReadsADenialFromTheQuery() {
        val redirect = DiscordAuth.parseRedirect(
            "discord-123456789:/authorize/callback?error=access_denied&error_description=The+user+said+no&state=nonce",
        )

        val denied = assertIs<DiscordRedirect.Denied>(redirect)
        assertEquals("access_denied", denied.error)
        assertEquals("The user said no", denied.description)
        assertEquals("nonce", denied.state)
    }

    @Test
    fun parseRedirectIgnoresAnUnrelatedDeepLink() {
        assertEquals(
            DiscordRedirect.Unrecognized,
            DiscordAuth.parseRedirect("discord-123456789:/authorize/callback"),
        )
    }

    @Test
    fun newStateProducesDistinctNonces() {
        assertNotEquals(DiscordAuth.newState(), DiscordAuth.newState())
    }

    @Test
    fun codeVerifierMatchesPkceCharsetAndLength() {
        val verifier = DiscordAuth.newCodeVerifier()

        // RFC 7636: 43-128 chars from [A-Za-z0-9-._~]. 32 random bytes → 43.
        assertEquals(43, verifier.length, verifier)
        assertTrue(verifier.all { it.isLetterOrDigit() || it in "-._~" }, verifier)
        assertNotEquals(verifier, DiscordAuth.newCodeVerifier())
    }

    @Test
    fun codeChallengeIsUnpaddedBase64UrlOfTheSha256Verifier() {
        // RFC 7636 Appendix B's worked example — the canonical PKCE test vector.
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            DiscordAuth.codeChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun apolloMembershipMatchesTheConfiguredGuildId() {
        DiscordConfig.apolloGuildId = "999"

        assertTrue(isApolloMember(listOf(DiscordGuild(id = "999", name = "Something Else"))))
        // A guild merely *named* Apollo is not the configured one.
        assertFalse(isApolloMember(listOf(DiscordGuild(id = "111", name = "Apollo"))))
    }

    @Test
    fun apolloMembershipFallsBackToTheGuildNameWhenNoIdIsConfigured() {
        DiscordConfig.apolloGuildId = ""

        assertTrue(isApolloMember(listOf(DiscordGuild(id = "111", name = "apollo"))))
        assertFalse(isApolloMember(listOf(DiscordGuild(id = "111", name = "Artemis"))))
        assertFalse(isApolloMember(emptyList()))
    }
}
