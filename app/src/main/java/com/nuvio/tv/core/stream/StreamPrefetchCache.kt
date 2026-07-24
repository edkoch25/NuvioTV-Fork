package com.nuvio.tv.core.stream

import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.repository.StreamRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * S4a: run the addon scrape while the details page is open, so pressing Play
 * does not start it from cold.
 *
 * Measured 24 Jul 2026 (Xiaomi S905X5M, auto-play "Best quality", 4K TB
 * Instant): `streams_load_start -> sources_ready` is 1,949-2,896 ms and is paid
 * on every play. It cannot begin any earlier today because
 * StreamRepositoryImpl.getStreamsFromAllAddons() is a cold flow with no cache
 * of any kind, and it is first collected from StreamScreenViewModel.init{} —
 * i.e. only once the stream screen exists, which is after the Play press.
 *
 * The details page already knows the exact target before the press: the hero
 * button resolves NextToWatch ("Next S1 E2" / "Resume ..."), and the episode
 * list reports focus through an existing callback. Both are handed here.
 *
 * Design notes:
 * - Single-flight, and at most ONE prefetch in flight. A new request cancels
 *   the previous one, so scanning an episode list cannot fan out N concurrent
 *   scrapes across every addon plus the debrid availability checks.
 * - Completed results are kept (2 entries, LRU, 5 min TTL), so moving back to
 *   an episode already prefetched is free.
 * - [streamsFor] substitutes the flow rather than bypassing the consumer. A hit
 *   emits Loading then one Success then completes, which is what a very fast
 *   scrape looks like: StreamScreenViewModel's existing collect applies it with
 *   isAllLoaded=false, and its post-collect line then applies isAllLoaded=true.
 *   Auto-select timing, binge-group matching and debrid cache handling are
 *   untouched.
 * - A join that times out or yields nothing falls through to the live flow, so
 *   the worst case is today's behaviour.
 *
 * TTL rationale: entries carry debrid cached-availability annotations, which
 * are time-sensitive. Five minutes is short enough that a stale annotation is
 * unlikely and long enough to cover reading a details page.
 */
object StreamPrefetchCache {

    private const val TAG = "StreamPrefetch"
    private const val TTL_MS = 5L * 60L * 1000L
    private const val MAX_ENTRIES = 2
    private const val JOIN_TIMEOUT_MS = 20_000L

    /** Outlives any ViewModel: the details screen may be gone before this finishes. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class Entry(val streams: List<AddonStreams>, val atMs: Long)

    private val lock = Any()
    private val completed = LinkedHashMap<String, Entry>(4, 0.75f, true)
    private var inFlightKey: String? = null
    private var inFlightJob: Deferred<List<AddonStreams>>? = null

    fun keyOf(type: String, videoId: String, season: Int?, episode: Int?): String {
        return type + "|" + videoId + "|" + (season ?: -1) + "|" + (episode ?: -1)
    }

    /** Caller must hold [lock]. */
    private fun freshLocked(key: String): List<AddonStreams>? {
        val entry = completed[key] ?: return null
        if (SystemClock.elapsedRealtime() - entry.atMs > TTL_MS) {
            completed.remove(key)
            return null
        }
        return entry.streams
    }

    /** Caller must hold [lock]. */
    private fun putLocked(key: String, streams: List<AddonStreams>) {
        completed[key] = Entry(streams, SystemClock.elapsedRealtime())
        while (completed.size > MAX_ENTRIES) {
            val eldest = completed.keys.firstOrNull() ?: break
            completed.remove(eldest)
        }
    }

    /**
     * Start (or keep) a prefetch for this target. Cheap and idempotent: a fresh
     * completed entry or an identical in-flight job is a no-op.
     */
    fun prefetch(
        repository: StreamRepository,
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?
    ) {
        if (type.isBlank() || videoId.isBlank()) return
        val key = keyOf(type, videoId, season, episode)
        synchronized(lock) {
            if (freshLocked(key) != null) return
            val existing = inFlightJob
            if (inFlightKey == key && existing != null && existing.isActive) return
            existing?.cancel()
            inFlightKey = key
            inFlightJob = scope.async {
                val result = collectFinal(repository, type, videoId, season, episode)
                synchronized(lock) {
                    if (result.isNotEmpty()) putLocked(key, result)
                    if (inFlightKey == key) {
                        inFlightKey = null
                        inFlightJob = null
                    }
                }
                result
            }
        }
        Log.i(TAG, "PREFETCH start key=$key")
    }

    private suspend fun collectFinal(
        repository: StreamRepository,
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?
    ): List<AddonStreams> {
        var last: List<AddonStreams> = emptyList()
        try {
            repository.getStreamsFromAllAddons(type, videoId, season, episode).collect { result ->
                if (result is NetworkResult.Success) last = result.data
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "PREFETCH failed: ${e.message}")
            return emptyList()
        }
        Log.i(TAG, "PREFETCH done groups=${last.size}")
        return last
    }

    /**
     * The stream list for this target: a completed prefetch, a join onto one in
     * flight, or the live repository flow. Drop-in for the repository call.
     */
    fun streamsFor(
        repository: StreamRepository,
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?
    ): Flow<NetworkResult<List<AddonStreams>>> {
        val key = keyOf(type, videoId, season, episode)
        var hit: List<AddonStreams>? = null
        var join: Deferred<List<AddonStreams>>? = null
        synchronized(lock) {
            hit = freshLocked(key)
            if (hit == null && inFlightKey == key) {
                val running = inFlightJob
                if (running != null && running.isActive) join = running
            }
        }

        val hitData = hit
        if (hitData != null) {
            Log.i(TAG, "PREFETCH hit key=$key groups=${hitData.size}")
            return flow {
                emit(NetworkResult.Loading)
                emit(NetworkResult.Success(hitData))
            }
        }

        val joinJob = join
        if (joinJob != null) {
            Log.i(TAG, "PREFETCH join key=$key")
            return flow {
                emit(NetworkResult.Loading)
                val joined = try {
                    withTimeoutOrNull(JOIN_TIMEOUT_MS) { joinJob.await() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (joined != null && joined.isNotEmpty()) {
                    emit(NetworkResult.Success(joined))
                } else {
                    Log.i(TAG, "PREFETCH join empty; falling back to live scrape")
                    emitAll(repository.getStreamsFromAllAddons(type, videoId, season, episode))
                }
            }
        }

        Log.i(TAG, "PREFETCH miss key=$key")
        return repository.getStreamsFromAllAddons(type, videoId, season, episode)
    }
}
