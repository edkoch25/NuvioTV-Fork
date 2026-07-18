package com.nuvio.tv.core.assessment

import com.nuvio.tv.data.local.Dv7HandlingMode
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.VodCacheSizeMode
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/**
 * Stage 2 apply path. Deliberate design choices:
 *
 *  - Writes go through the EXISTING public setters, never raw preference
 *    edits: each setter already carries its own coercions and side effects,
 *    so a sequential apply is behaviourally identical to a user flipping
 *    the same switches by hand, in a known-good order.
 *  - Order is load-bearing: setNuvioPerformanceModeEnabled(true) rewrites
 *    the whole buffer/parallel set as a preset, so it goes FIRST and all
 *    buffer/parallel values are written after it. Min buffer is written
 *    before max because setBufferMaxBufferMs clamps to >= the stored min;
 *    when apply lowers max below a min it isn't otherwise touching, the
 *    min is lowered with it (counted as a write).
 *  - Before the first write, the full touchable field set is captured from
 *    the live PlayerSettings and persisted per-profile as a JSON snapshot;
 *    revert restores every captured field and clears the snapshot. A second
 *    apply overwrites the snapshot: revert always undoes the MOST RECENT
 *    apply.
 *  - Only plan fields (null = untouched) and the selected profile are ever
 *    written. VERIFY-tier rows never reach the plan by construction.
 */
object DeviceAssessmentApplier {

    data class ApplyOutcome(val writtenCount: Int)

    suspend fun apply(
        dataStore: PlayerSettingsDataStore,
        plan: AssessmentApplyPlan,
        profile: IntentProfileOption?
    ): ApplyOutcome {
        val before = dataStore.playerSettings.first()
        dataStore.setAssessmentRevertSnapshot(snapshotJson(before))

        var written = 0
        suspend fun step(block: suspend () -> Unit) {
            block()
            written += 1
        }

        // 1. Performance mode first: enabling it rewrites the buffer set.
        plan.nuvioPerformanceModeEnabled?.let { step { dataStore.setNuvioPerformanceModeEnabled(it) } }
        // 2. Masters and budget mode.
        plan.bufferEngineEnabled?.let { step { dataStore.setBufferEngineEnabled(it) } }
        plan.parallelNetworkEnabled?.let { step { dataStore.setParallelNetworkEnabled(it) } }
        plan.bufferBudgetManaged?.let { step { dataStore.setBufferBudgetManaged(it) } }
        // 3. Slider range before the value that needs it.
        plan.allowLargeTargetBuffer?.let { step { dataStore.setAllowLargeTargetBuffer(it) } }
        plan.targetBufferSizeMb?.let { step { dataStore.setBufferTargetSizeMb(it) } }
        // 4. Profile trio (min BEFORE max), then the max cap.
        profile?.let {
            step { dataStore.setBufferMinBufferMs(it.minBufferMs) }
            step { dataStore.setBufferForPlaybackMs(it.initialBufferMs) }
            step { dataStore.setBufferForPlaybackAfterRebufferMs(it.rebufferMs) }
        }
        plan.maxBufferMs?.let { newMax ->
            if (profile == null && before.bufferSettings.minBufferMs > newMax) {
                // Max setter clamps to >= stored min; honour the intent by
                // lowering the min with it.
                step { dataStore.setBufferMinBufferMs(newMax) }
            }
            step { dataStore.setBufferMaxBufferMs(newMax) }
        }
        // 5. Parallel pipe.
        plan.useParallelConnections?.let { step { dataStore.setUseParallelConnections(it) } }
        plan.parallelConnectionCount?.let { step { dataStore.setParallelConnectionCount(it) } }
        plan.parallelChunkSizeKb?.let { step { dataStore.setParallelChunkSizeKb(it) } }
        plan.enableHttp2?.let { step { dataStore.setEnableHttp2(it) } }
        // 6. VOD, display, DV, audio route.
        plan.vodCacheEnabled?.let { step { dataStore.setVodCacheEnabled(it) } }
        plan.vodCacheSizeMode?.let { step { dataStore.setVodCacheSizeMode(it) } }
        plan.frameRateMatchingMode?.let { step { dataStore.setFrameRateMatchingMode(it) } }
        plan.resolutionMatchingEnabled?.let { step { dataStore.setResolutionMatchingEnabled(it) } }
        plan.dv7HandlingMode?.let { step { dataStore.setDv7HandlingMode(it) } }
        plan.dv5ToDv81Enabled?.let { step { dataStore.setDv5ToDv81Enabled(it) } }
        plan.stripHdr10PlusSei?.let { step { dataStore.setStripHdr10PlusSei(it) } }
        plan.forceOpticalPassthrough?.let { step { dataStore.setForceOpticalPassthrough(it) } }

        return ApplyOutcome(writtenCount = written)
    }

    /** Restores every captured field; returns false when no snapshot exists. */
    suspend fun revert(dataStore: PlayerSettingsDataStore): Boolean {
        val json = dataStore.assessmentRevertSnapshot.first() ?: return false
        val snap = runCatching { JSONObject(json) }.getOrNull() ?: run {
            dataStore.setAssessmentRevertSnapshot(null)
            return false
        }

        // Same locked order as apply. The snapshot is internally consistent
        // (min <= max), and min is written first, so the mutual clamps in the
        // buffer setters settle on the captured values.
        dataStore.setNuvioPerformanceModeEnabled(snap.getBoolean("nuvioPerformanceModeEnabled"))
        dataStore.setBufferEngineEnabled(snap.getBoolean("bufferEngineEnabled"))
        dataStore.setParallelNetworkEnabled(snap.getBoolean("parallelNetworkEnabled"))
        dataStore.setBufferBudgetManaged(snap.getBoolean("bufferBudgetManaged"))
        dataStore.setAllowLargeTargetBuffer(snap.getBoolean("allowLargeTargetBuffer"))
        dataStore.setBufferTargetSizeMb(snap.getInt("targetBufferSizeMb"))
        dataStore.setBufferMinBufferMs(snap.getInt("minBufferMs"))
        dataStore.setBufferForPlaybackMs(snap.getInt("bufferForPlaybackMs"))
        dataStore.setBufferForPlaybackAfterRebufferMs(snap.getInt("bufferForPlaybackAfterRebufferMs"))
        dataStore.setBufferMaxBufferMs(snap.getInt("maxBufferMs"))
        dataStore.setUseParallelConnections(snap.getBoolean("useParallelConnections"))
        dataStore.setParallelConnectionCount(snap.getInt("parallelConnectionCount"))
        dataStore.setParallelChunkSizeKb(snap.getInt("parallelChunkSizeKb"))
        dataStore.setEnableHttp2(snap.getBoolean("enableHttp2"))
        dataStore.setVodCacheEnabled(snap.getBoolean("vodCacheEnabled"))
        dataStore.setVodCacheSizeMode(VodCacheSizeMode.valueOf(snap.getString("vodCacheSizeMode")))
        dataStore.setFrameRateMatchingMode(FrameRateMatchingMode.valueOf(snap.getString("frameRateMatchingMode")))
        dataStore.setResolutionMatchingEnabled(snap.getBoolean("resolutionMatchingEnabled"))
        dataStore.setDv7HandlingMode(Dv7HandlingMode.valueOf(snap.getString("dv7HandlingMode")))
        dataStore.setDv5ToDv81Enabled(snap.getBoolean("dv5ToDv81Enabled"))
        dataStore.setStripHdr10PlusSei(snap.getBoolean("stripHdr10PlusSei"))
        dataStore.setForceOpticalPassthrough(snap.getBoolean("forceOpticalPassthrough"))

        dataStore.setAssessmentRevertSnapshot(null)
        return true
    }

    private fun snapshotJson(s: PlayerSettings): String = JSONObject().apply {
        put("timestampMs", System.currentTimeMillis())
        put("nuvioPerformanceModeEnabled", s.nuvioPerformanceModeEnabled)
        put("bufferEngineEnabled", s.bufferEngineEnabled)
        put("parallelNetworkEnabled", s.parallelNetworkEnabled)
        put("bufferBudgetManaged", s.bufferBudgetManaged)
        put("allowLargeTargetBuffer", s.allowLargeTargetBuffer)
        put("targetBufferSizeMb", s.bufferSettings.targetBufferSizeMb)
        put("minBufferMs", s.bufferSettings.minBufferMs)
        put("maxBufferMs", s.bufferSettings.maxBufferMs)
        put("bufferForPlaybackMs", s.bufferSettings.bufferForPlaybackMs)
        put("bufferForPlaybackAfterRebufferMs", s.bufferSettings.bufferForPlaybackAfterRebufferMs)
        put("useParallelConnections", s.useParallelConnections)
        put("parallelConnectionCount", s.parallelConnectionCount)
        put("parallelChunkSizeKb", s.parallelChunkSizeKb)
        put("enableHttp2", s.enableHttp2)
        put("vodCacheEnabled", s.vodCacheEnabled)
        put("vodCacheSizeMode", s.vodCacheSizeMode.name)
        put("frameRateMatchingMode", s.frameRateMatchingMode.name)
        put("resolutionMatchingEnabled", s.resolutionMatchingEnabled)
        put("dv7HandlingMode", s.dv7HandlingMode.name)
        put("dv5ToDv81Enabled", s.dv5ToDv81Enabled)
        put("stripHdr10PlusSei", s.stripHdr10PlusSei)
        put("forceOpticalPassthrough", s.forceOpticalPassthrough)
    }.toString()
}
