package com.nuvio.tv.core.player

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator

/**
 * DefaultLoadControl with a byte target tied to the device memory budget, adjustable
 * at runtime once we know whether the stream is really DV7.
 *
 * On a DV-capable display AUTO arms conversion for every file, but most never actually
 * convert. So we build with the full budget and the user's back buffer, then tighten
 * the BUDGET only for confirmed DV7 via the override setter below.
 *
 * The back buffer deliberately has no runtime override. media3 1.8.0
 * ExoPlayerImplInternal reads getBackBufferDurationUs() and
 * retainBackBufferFromKeyframe() exactly once, in its constructor, into private final
 * fields (:211-212, :306-307). It never polls them again, so a setter here would be
 * silently inert. The back buffer is fixed for the life of the player instance -- and
 * for a REUSED player it stays at the value captured when that instance was first
 * built. Changing it requires constructing a new player.
 */
@UnstableApi
class BitrateAwareLoadControl(
    minBufferMs: Int,
    maxBufferMs: Int,
    bufferForPlaybackMs: Int,
    bufferForPlaybackAfterRebufferMs: Int,
    prioritizeTimeOverSizeThresholds: Boolean,
    backBufferDurationMs: Int,
    retainBackBufferFromKeyframe: Boolean,
    /** Memory ceiling in bytes. */
    private val budgetBytes: Long,
    allocator: DefaultAllocator = DefaultAllocator(/* trimOnReset= */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE, 64)
) : DefaultLoadControl(
    allocator,
    minBufferMs,
    maxBufferMs,
    bufferForPlaybackMs,
    bufferForPlaybackAfterRebufferMs,
    /* targetBufferBytes= */ C.LENGTH_UNSET,
    prioritizeTimeOverSizeThresholds,
    backBufferDurationMs,
    retainBackBufferFromKeyframe
) {

    // Effective byte budget when >= 0, else the constructed budget. Re-read on track
    // (re)selection, so this can change mid-playback.
    @Volatile
    private var budgetBytesOverride: Long = -1L

    /** Set the byte budget at runtime; negative restores the constructed budget. */
    fun setBudgetBytesOverride(bytes: Long) {
        budgetBytesOverride = if (bytes < 0L) -1L else bytes
    }

    override fun calculateTargetBufferBytes(
        trackSelectionArray: Array<out ExoTrackSelection?>
    ): Int {
        // Target = the memory budget. Time (Max Buffer Duration) is the real limit; this is
        // just the memory cap. Sizing from advertised bitrate starved variable-bitrate peaks,
        // so let high-bitrate fill up to the budget and low-bitrate stop at the time limit.
        val effectiveBudget = if (budgetBytesOverride >= 0L) budgetBytesOverride else budgetBytes
        return effectiveBudget.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
