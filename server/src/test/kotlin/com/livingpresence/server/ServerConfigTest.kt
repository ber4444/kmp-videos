package com.livingpresence.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ServerConfigTest {

    /** A fully-configured environment, so each test can knock out one value. */
    private fun configured(name: String): String? = when (name) {
        "SONIOX_API_KEY" -> "k"
        "APOLLO_GUILD_ID" -> "952353661969920051"
        else -> null
    }

    /**
     * The identity check has no safe default. An unset guild id could only mean
     * "mint for everyone", so it is fatal exactly like the missing key: a service
     * that quietly stops checking who is calling looks healthy while being open.
     */
    @Test
    fun refusesToBootWithoutTheApolloGuildId() {
        val failure = assertFailsWith<IllegalArgumentException> {
            ServerConfig.fromEnvironment { if (it == "SONIOX_API_KEY") "k" else null }
        }
        assertTrue(failure.message.orEmpty().contains("APOLLO_GUILD_ID"))
    }

    @Test
    fun refusesToBootWithoutTheKey() {
        val failure = assertFailsWith<IllegalArgumentException> {
            ServerConfig.fromEnvironment { null }
        }
        assertTrue(failure.message.orEmpty().contains("SONIOX_API_KEY"))
    }

    @Test
    fun refusesToBootOnABlankKey() {
        // An unset Fly secret and an empty one look identical from inside the
        // container; both have to fail the deploy rather than serve 502s.
        assertFailsWith<IllegalArgumentException> {
            ServerConfig.fromEnvironment { if (it == "SONIOX_API_KEY") "   " else "g" }
        }
    }

    @Test
    fun defaultsKeepTheBlastRadiusSmall() {
        val config = ServerConfig.fromEnvironment { configured(it) }

        assertEquals(60, config.keyTtlSeconds)
        assertEquals(
            emptyList(),
            config.allowedOrigins,
            "an unconfigured deployment must not be callable from an arbitrary web page",
        )
    }

    /**
     * Unlike the guild id, an unset allowlist has an unambiguous safe meaning —
     * "no account is exempt" — so it is a default rather than a boot failure.
     */
    @Test
    fun noReviewAccountsIsTheDefault() {
        val config = ServerConfig.fromEnvironment { configured(it) }

        assertEquals(emptySet(), config.testUserIds)
        assertEquals("", config.demoVideosUrl)
    }

    /**
     * Unset means "hand out nothing", which surfaces as an empty gallery. Unlike
     * the guild id there is no reading of these under which the gate quietly opens,
     * so they are defaults rather than boot failures.
     */
    @Test
    fun anUnconfiguredFeedHandsOutNothing() {
        val config = ServerConfig.fromEnvironment { configured(it) }

        assertEquals("", config.streamHost)
        assertEquals("", config.extraVideosUrl)
    }

    /** A trailing slash would double up when the app appends `/live/event…`. */
    @Test
    fun theStreamHostIsTrimmedOfItsTrailingSlash() {
        val config = ServerConfig.fromEnvironment {
            if (it == "STREAM_HOST") " https://stream.example:443/ " else configured(it)
        }

        assertEquals("https://stream.example:443", config.streamHost)
    }

    @Test
    fun parsesACommaSeparatedReviewAccountList() {
        val config = ServerConfig.fromEnvironment {
            when (it) {
                "TEST_USER_IDS" -> " 100000000000000001, 200000000000000002 ,, "
                else -> configured(it)
            }
        }

        assertEquals(setOf("100000000000000001", "200000000000000002"), config.testUserIds)
    }

    @Test
    fun parsesACommaSeparatedOriginList() {
        val config = ServerConfig.fromEnvironment {
            when (it) {
                "ALLOWED_ORIGINS" -> "https://apollo.example, https://staging.example "
                else -> configured(it)
            }
        }

        assertEquals(listOf("https://apollo.example", "https://staging.example"), config.allowedOrigins)
    }
}
