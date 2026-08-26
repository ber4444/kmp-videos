package com.livingpresence.inner.circle.squared.discord

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** In-memory [DiscordSessionStore] standing in for the platform stores. */
class FakeDiscordSessionStore(
    private var session: DiscordSession? = null,
) : DiscordSessionStore {
    var clearCount: Int = 0
        private set

    override fun save(session: DiscordSession) {
        this.session = session
    }

    override fun load(): DiscordSession? = session

    override fun clear() {
        clearCount++
        session = null
    }
}

class DiscordSessionStoreTest {

    @Test
    fun savedSessionIsReadBack() {
        val store = FakeDiscordSessionStore()
        store.save(DiscordSession(refreshToken = "r1", displayName = "Ada"))

        assertEquals(DiscordSession("r1", "Ada"), store.load())
    }

    @Test
    fun saveReplacesThePreviousSession() {
        val store = FakeDiscordSessionStore(DiscordSession("old", "Ada"))
        store.save(DiscordSession(refreshToken = "new", displayName = "Grace"))

        // Refresh tokens rotate — keeping the old one would break the next launch.
        assertEquals(DiscordSession("new", "Grace"), store.load())
    }

    @Test
    fun clearForgetsTheSession() {
        val store = FakeDiscordSessionStore(DiscordSession("r1", "Ada"))
        store.clear()

        assertNull(store.load())
    }

    @Test
    fun noOpStoreNeverPersists() {
        NoOpDiscordSessionStore.save(DiscordSession("r1", "Ada"))

        assertNull(NoOpDiscordSessionStore.load())
    }
}
