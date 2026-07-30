package com.nuvio.tv.core.sync

import com.nuvio.tv.data.local.WatchProgressSource
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchProgressSourceResolverTest {

    private val resolver = WatchProgressSourceResolver(
        traktSettingsDataStore = mockk(relaxed = true),
        traktAuthDataStore = mockk(relaxed = true),
        mdbListSettingsDataStore = mockk(relaxed = true)
    )

    @Test
    fun `trakt is honoured only when authenticated`() {
        for (mdbReady in listOf(true, false)) {
            assertEquals(
                WatchProgressSource.TRAKT,
                resolver.resolve(WatchProgressSource.TRAKT, traktAuthenticated = true, mdbListTrackingReady = mdbReady)
            )
            assertEquals(
                WatchProgressSource.NUVIO_SYNC,
                resolver.resolve(WatchProgressSource.TRAKT, traktAuthenticated = false, mdbListTrackingReady = mdbReady)
            )
        }
    }

    @Test
    fun `nuvio sync resolves to itself regardless of other backends`() {
        for (auth in listOf(true, false)) for (mdbReady in listOf(true, false)) {
            assertEquals(
                WatchProgressSource.NUVIO_SYNC,
                resolver.resolve(WatchProgressSource.NUVIO_SYNC, auth, mdbReady)
            )
        }
    }

    @Test
    fun `mdblist is honoured when tracking is configured`() {
        for (auth in listOf(true, false)) {
            assertEquals(
                WatchProgressSource.MDBLIST,
                resolver.resolve(WatchProgressSource.MDBLIST, traktAuthenticated = auth, mdbListTrackingReady = true)
            )
        }
    }

    @Test
    fun `mdblist falls back when tracking is not configured`() {
        for (auth in listOf(true, false)) {
            assertEquals(
                WatchProgressSource.NUVIO_SYNC,
                resolver.resolve(WatchProgressSource.MDBLIST, traktAuthenticated = auth, mdbListTrackingReady = false)
            )
        }
    }

    /**
     * The original refactor's no-op guarantee, preserved through the MDBLIST
     * extension: for every input triple, resolving to TRAKT must agree exactly
     * with the predicate the resolver replaced.
     */
    @Test
    fun `resolving to trakt matches the legacy predicate for every input`() {
        for (stored in WatchProgressSource.entries) {
            for (authenticated in listOf(true, false)) {
                for (mdbReady in listOf(true, false)) {
                    val legacy = stored == WatchProgressSource.TRAKT && authenticated
                    val resolved =
                        resolver.resolve(stored, authenticated, mdbReady) == WatchProgressSource.TRAKT
                    assertEquals(
                        "stored=$stored authenticated=$authenticated mdbReady=$mdbReady",
                        legacy,
                        resolved
                    )
                }
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
