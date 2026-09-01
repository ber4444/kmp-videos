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
