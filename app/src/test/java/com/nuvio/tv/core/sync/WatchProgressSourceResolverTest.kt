package com.nuvio.tv.core.sync

import com.nuvio.tv.data.local.WatchProgressSource
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchProgressSourceResolverTest {

    private val resolver = WatchProgressSourceResolver(
        traktSettingsDataStore = mockk(relaxed = true),
        traktAuthDataStore = mockk(relaxed = true)
    )

    @Test
    fun `trakt is honoured only when authenticated`() {
        assertEquals(
            WatchProgressSource.TRAKT,
            resolver.resolve(WatchProgressSource.TRAKT, traktAuthenticated = true)
        )
        assertEquals(
            WatchProgressSource.NUVIO_SYNC,
            resolver.resolve(WatchProgressSource.TRAKT, traktAuthenticated = false)
        )
    }

    @Test
    fun `nuvio sync resolves to itself regardless of trakt auth`() {
        assertEquals(
            WatchProgressSource.NUVIO_SYNC,
            resolver.resolve(WatchProgressSource.NUVIO_SYNC, traktAuthenticated = true)
        )
        assertEquals(
            WatchProgressSource.NUVIO_SYNC,
            resolver.resolve(WatchProgressSource.NUVIO_SYNC, traktAuthenticated = false)
        )
    }

    @Test
    fun `mdblist falls back until a progress read client exists`() {
        assertEquals(
            WatchProgressSource.NUVIO_SYNC,
            resolver.resolve(WatchProgressSource.MDBLIST, traktAuthenticated = true)
        )
        assertEquals(
            WatchProgressSource.NUVIO_SYNC,
            resolver.resolve(WatchProgressSource.MDBLIST, traktAuthenticated = false)
        )
    }

    /**
     * The refactor's no-op guarantee: for every input pair, resolving to TRAKT must
     * agree exactly with the predicate the three call sites used before the resolver
     * existed. If a future source changes this, it is a behaviour change and this
     * test is the place it should surface.
     */
    @Test
    fun `resolving to trakt matches the legacy predicate for every input`() {
        for (stored in WatchProgressSource.entries) {
            for (authenticated in listOf(true, false)) {
                val legacy = stored == WatchProgressSource.TRAKT && authenticated
                val resolved = resolver.resolve(stored, authenticated) == WatchProgressSource.TRAKT
                assertEquals("stored=$stored authenticated=$authenticated", legacy, resolved)
            }
        }
    }

    @Test
    fun `stored values round trip by name and unknown values fall back to trakt`() {
        for (source in WatchProgressSource.entries) {
            assertEquals(source, WatchProgressSource.fromStorage(source.name))
        }
        assertEquals(WatchProgressSource.TRAKT, WatchProgressSource.fromStorage(null))
        assertEquals(WatchProgressSource.TRAKT, WatchProgressSource.fromStorage("NOT_A_SOURCE"))
    }
}
