package com.nuvio.tv.ui.screens.settings

import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.data.local.BufferSettings
import com.nuvio.tv.data.local.PlayerSettings

/**
 * Shared memory budget constants and helpers for buffer + parallel connection settings.
 * Used by both PlaybackSettingsViewModel and PlaybackBufferNetworkSettings UI.
 */
@UnstableApi
object MemoryBudget {
    const val TAG = "MemoryBudget"

    // Heap-tiered budget ratios. Low-RAM devices (Fire TV class) need more
    // headroom for codec/surface/UI; high-RAM devices can dedicate more to buffering.
    private const val LOW_HEAP_RATIO = 0.65
    private const val HIGH_HEAP_RATIO = 0.85
    private const val HIGH_HEAP_THRESHOLD_MB = 512L
    // The buffer allocator is on the Java heap; on low-RAM reserve a slice for the UI/decoder/caches
    // and give the rest to the buffer (a flat % of max heap overcommits and starves them).
    private const val LOW_HEAP_RESERVE_MB = 210L

    /** ParallelRangeDataSource schedules maxAhead = parallelConnections + 1 chunks concurrently */
    // nt7: matches ParallelRangeDataSource reality — the session holds up to
    // connections + 2 chunks on low-RAM devices (connections + 4 on high-RAM;
    // the extra headroom is deliberately not advertised so the enforcement
    // maths stays conservative). Was 1, which under-stated the ceiling.
    private const val BUFFER_OVERHEAD = 2

    // nt37: nt36 clamps ParallelRangeDataSource's live prefetch window to at most
    // 4*connections chunks (sized from a device-RAM budget). The DISPLAYED memory
    // estimate uses this upper bound so the "estimated usage" warning over-estimates
    // rather than under-estimates - the safe direction for a guard, and the fix for
    // the pre-nt37 undercount that let real usage exceed the "safe" figure shown.
    // nt-exact: these clamp multiples are the SINGLE SOURCE OF TRUTH for the deep-path
    // window, shared by the runtime depth (PlayerMediaSourceFactory delegates to
    // prefetchDepthChunks below) and the displayed estimate, so the two can never
    // drift. bufferCount and the enforcement maths (maxChunkMb / maxBufferMb / enforce)
    // are deliberately left on the conservative connections+2 accounting so the slider
    // maxima are unchanged.
    private const val PREFETCH_DEPTH_LOWER_MULTIPLE = 2
    private const val PREFETCH_DEPTH_UPPER_MULTIPLE = 4

    const val MIN_CONNECTIONS = 2
    const val MAX_CONNECTIONS = 4
    const val MIN_CHUNK_MB = 8
    const val MAX_CHUNK_MB = 128
    const val BUFFER_STEP_MB = 25
    const val MIN_BUFFER_MB = 25
    const val MAX_BUFFER_MB = 1024 * 4
    private const val DEFAULT_EFFECTIVE_BUFFER_MB = 50

    val defaultBufferSizeMb: Int = if (BufferSettings.DEFAULT_TARGET_BUFFER_SIZE_MB > 0) {
        BufferSettings.DEFAULT_TARGET_BUFFER_SIZE_MB
    } else {
        DEFAULT_EFFECTIVE_BUFFER_MB
    }

    private val maxHeapMb: Long = Runtime.getRuntime().maxMemory() / (1024L * 1024L)

    /** True when the app heap is below the high-RAM threshold (Fire TV / TV-stick class). */
    val isLowRamTier: Boolean = maxHeapMb < HIGH_HEAP_THRESHOLD_MB

    // Pre-cap ratio budget; conversionBudgetMb derives from this so DV7 headroom isn't cut by the cap.
    private val rawBudgetMb: Int =
        (maxHeapMb * (if (isLowRamTier) LOW_HEAP_RATIO else HIGH_HEAP_RATIO)).toInt()

    val budgetMb: Int =
        if (isLowRamTier)
            rawBudgetMb.coerceAtMost((maxHeapMb - LOW_HEAP_RESERVE_MB).toInt()).coerceAtLeast(MIN_BUFFER_MB)
        else rawBudgetMb

    // DV7 conversion headroom: a third of the raw budget on low-RAM, half on high-RAM; never above budget.
    val conversionBudgetMb: Int =
        (if (isLowRamTier) rawBudgetMb / 3 else rawBudgetMb / 2)
            .coerceAtMost(budgetMb).coerceAtLeast(MIN_BUFFER_MB)

    fun effectiveBufferMb(stored: Int): Int =
        if (stored > 0) stored else defaultBufferSizeMb

    /** Number of chunk-sized buffers alive concurrently */
    fun bufferCount(connectionCount: Int): Int =
        connectionCount + BUFFER_OVERHEAD

    fun parallelOverheadMb(connectionCount: Int, chunkSizeMb: Int): Int =
        bufferCount(connectionCount) * chunkSizeMb

    fun totalUsageMb(bufferMb: Int, connectionCount: Int, chunkSizeMb: Int, parallelEnabled: Boolean): Int =
        bufferMb + if (parallelEnabled) parallelOverheadMb(connectionCount, chunkSizeMb) else 0

    /**
     * Deep-path prefetch depth — the SINGLE SOURCE OF TRUTH shared by the runtime
     * (PlayerMediaSourceFactory.computePrefetchDepthChunks delegates here) and the
     * displayed memory estimate. Mirrors the nt36 runtime maths exactly: spend the safe
     * native budget minus the SampleQueue reserve on chunks, divide by chunk size, clamp
     * to [2*connections, 4*connections].
     */
    fun prefetchDepthChunks(
        connections: Int,
        chunkSizeMb: Int,
        safeNativeLimitMb: Int,
        reserveBufferMb: Int,
    ): Int {
        val chunkMb = chunkSizeMb.coerceAtLeast(1)
        val chunkBudgetMb = (safeNativeLimitMb - reserveBufferMb.coerceAtLeast(0))
            .coerceAtLeast(chunkMb * PREFETCH_DEPTH_LOWER_MULTIPLE)
        val byBudget = chunkBudgetMb / chunkMb
        return byBudget.coerceIn(
            connections * PREFETCH_DEPTH_LOWER_MULTIPLE,
            connections * PREFETCH_DEPTH_UPPER_MULTIPLE
        )
    }

    /**
     * Parallel overhead for the DISPLAYED memory-usage estimate only. nt-exact revision:
     * instead of always assuming the 4*connections upper clamp (which over-stated usage
     * whenever the device budget clamps the window lower, and made the per-step deltas
     * look inflated — field report: +1 connection at 16 MB chunks jumped the estimate by
     * 64 MB, and +8 MB of chunk at 2 connections jumped it by 80 MB), this computes the
     * ACTUAL depth the runtime will use via [prefetchDepthChunks], plus the session's
     * live-chunk headroom (depth + 2 on low-RAM, depth + 4 on high-RAM — mirrors
     * ParallelRangeDataSource.sessionChunkCap; the old flat +2 under-counted the
     * high-RAM ceiling by two chunks at the clamp). When the deep path is inactive
     * (performance mode off, or MP4 session mode) the runtime window is connections + 1
     * and the estimate says so. Unlike [parallelOverheadMb] (which underpins the slider
     * maxima and must stay conservative), this is display-only.
     */
    fun displayParallelOverheadMb(
        connectionCount: Int,
        chunkSizeMb: Int,
        safeNativeLimitMb: Int,
        reserveBufferMb: Int,
        deepPathActive: Boolean,
    ): Int {
        val depth = if (deepPathActive) {
            prefetchDepthChunks(connectionCount, chunkSizeMb, safeNativeLimitMb, reserveBufferMb)
        } else {
            connectionCount + 1
        }
        val sessionHeadroom = if (isLowRamTier) 2 else 4
        return (depth + sessionHeadroom) * chunkSizeMb
    }

    /** Total usage for the DISPLAYED estimate: buffer plus the exact parallel overhead. */
    fun displayTotalUsageMb(
        bufferMb: Int,
        connectionCount: Int,
        chunkSizeMb: Int,
        parallelEnabled: Boolean,
        safeNativeLimitMb: Int,
        deepPathActive: Boolean,
    ): Int =
        bufferMb + if (parallelEnabled) {
            displayParallelOverheadMb(connectionCount, chunkSizeMb, safeNativeLimitMb, bufferMb, deepPathActive)
        } else {
            0
        }

    /** Max chunk size that fits budget given current buffer size */
    fun maxChunkMb(bufferMb: Int, connectionCount: Int): Int =
        ((budgetMb - bufferMb) / bufferCount(connectionCount)).coerceIn(MIN_CHUNK_MB, MAX_CHUNK_MB)

    /** Max buffer size that fits budget given current parallel overhead */
    fun maxBufferMb(parallelOverheadMb: Int): Int =
        ((budgetMb - parallelOverheadMb) / BUFFER_STEP_MB * BUFFER_STEP_MB)
            .coerceIn(MIN_BUFFER_MB, MAX_BUFFER_MB)

    /** Slider max for target buffer, optionally raised to 2GB when override is on. */
    fun maxBufferMbWithOverride(parallelOverheadMb: Int, allowLargeTargetBuffer: Boolean): Int {
        val safeMax = maxBufferMb(parallelOverheadMb)
        return if (allowLargeTargetBuffer) {
            PlayerSettings.LARGE_TARGET_BUFFER_MAX_MB
                .coerceAtMost(MAX_BUFFER_MB)
                .coerceAtLeast(safeMax)
        } else {
            safeMax
        }
    }

    /**
     * Enforce budget: reduce chunk first, then buffer as last resort.
     * Returns (adjustedBufferMb, adjustedChunkMb).
     */
    fun enforce(bufferMb: Int, chunkMb: Int, connectionCount: Int): Pair<Int, Int> {
        val buffers = bufferCount(connectionCount)
        if (bufferMb + buffers * chunkMb <= budgetMb) return bufferMb to chunkMb

        val newChunkMb = maxChunkMb(bufferMb, connectionCount)
        if (bufferMb + buffers * newChunkMb <= budgetMb) return bufferMb to newChunkMb

        // Even min chunk doesn't fit, also reduce buffer
        val newBufferMb = ((budgetMb - buffers * MIN_CHUNK_MB) / BUFFER_STEP_MB * BUFFER_STEP_MB)
            .coerceAtLeast(MIN_BUFFER_MB)
        return newBufferMb to MIN_CHUNK_MB
    }

    /**
     * Determines the memory usage status based on total usage, safe limit, and warning limit.
     */
    fun getUsageStatus(
        totalUsageMb: Int,
        safeLimitMb: Int,
        warningLimitMb: Int
    ): MemoryUsageStatus {
        return when {
            totalUsageMb > warningLimitMb -> MemoryUsageStatus.DANGER
            totalUsageMb > safeLimitMb -> MemoryUsageStatus.WARNING
            else -> MemoryUsageStatus.SAFE
        }
    }
}

@UnstableApi
enum class MemoryUsageStatus {
    SAFE,
    WARNING,
    DANGER
}

