package com.livingpresence.inner.circle.squared.transcription

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The endpoint fallback. [TranscriptionSecrets] is a process-wide singleton, so
 * each test restores it rather than leaving a value behind for the next one.
 */
class TranscriptionSecretsTest {

    @AfterTest
    fun reset() {
        TranscriptionSecrets.sonioxTokenEndpoint = ""
    }

    @Test
    fun unset_readsBackTheDefaultDeployment() {
        assertEquals(
            TranscriptionSecrets.DEFAULT_SONIOX_TOKEN_URL,
            TranscriptionSecrets.sonioxTokenEndpoint,
        )
    }

    @Test
    fun hostAssigningEmpty_stillReadsBackTheDefault() {
        // The case that makes the fallback a getter rather than an initializer:
        // every platform host assigns unconditionally, so an unset build value
        // arrives as "" and must not read back as "captions are off".
        TranscriptionSecrets.sonioxTokenEndpoint = ""
        assertEquals(
            TranscriptionSecrets.DEFAULT_SONIOX_TOKEN_URL,
            TranscriptionSecrets.sonioxTokenEndpoint,
        )
    }

    @Test
    fun blankIsTreatedAsUnset() {
        TranscriptionSecrets.sonioxTokenEndpoint = "   "
        assertEquals(
            TranscriptionSecrets.DEFAULT_SONIOX_TOKEN_URL,
            TranscriptionSecrets.sonioxTokenEndpoint,
        )
    }

    @Test
    fun aForksOwnServiceOverridesTheDefault() {
        TranscriptionSecrets.sonioxTokenEndpoint = "https://tokens.fork.example"
        assertEquals("https://tokens.fork.example", TranscriptionSecrets.sonioxTokenEndpoint)
    }

    @Test
    fun defaultIsAnAbsoluteHttpsOriginWithNoTrailingSlash() {
        val default = TranscriptionSecrets.DEFAULT_SONIOX_TOKEN_URL
        assertTrue(default.startsWith("https://"), "the key it carries back must not cross the wire in the clear")
        assertTrue(!default.endsWith("/"), "SonioxKeyProvider appends a rooted path")
    }
}
