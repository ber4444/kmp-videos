package com.livingpresence.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ServerConfigTest {

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
            ServerConfig.fromEnvironment { if (it == "SONIOX_API_KEY") "   " else null }
        }
    }

    @Test
    fun defaultsKeepTheBlastRadiusSmall() {
        val config = ServerConfig.fromEnvironment { if (it == "SONIOX_API_KEY") "k" else null }

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
                "SONIOX_API_KEY" -> "k"
                "ALLOWED_ORIGINS" -> "https://apollo.example, https://staging.example "
                else -> null
            }
        }

        assertEquals(listOf("https://apollo.example", "https://staging.example"), config.allowedOrigins)
    }
}
