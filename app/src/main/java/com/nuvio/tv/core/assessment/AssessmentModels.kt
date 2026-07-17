package com.nuvio.tv.core.assessment

import com.nuvio.tv.ui.screens.settings.MemoryUsageStatus

/**
 * Confidence tiers for Device Assessment rows. The tier states HOW a
 * recommendation was derived, so the UI can be honest about certainty:
 *
 *  MEASURED   - tested on this device against the real last-played stream
 *               (the sweep). Highest confidence.
 *  CALCULATED - derived from queryable device facts (RAM tier, display
 *               modes, HDR capability reads, decoder lists) plus the
 *               documented behaviour of the setting. No measurement ran.
 *  PRIORITY   - a genuine trade-off with no device-derivable answer; the
 *               user picks an intent profile. Never auto-applied.
 *  VERIFY     - a recommendation exists but the deciding fact is not
 *               queryable on this device (unknown HDR caps, uninspectable
 *               display, tunnelling quirks). Eyes-on verification advised.
 */
enum class AssessmentTier { MEASURED, CALCULATED, PRIORITY, VERIFY }

enum class ProfileKind { FAST_START, BALANCED, STALL_RESISTANT }

data class AssessmentItem(
    /** Stable identifier, e.g. "parallel", "target_buffer". */
    val key: String,
    val title: String,
    /** Localised display of the current setting; null when not meaningful. */
    val currentValue: String?,
    val recommendedValue: String,
    /** One-line, honest "why" - names the fact or measurement behind it. */
    val grounds: String,
    val tier: AssessmentTier,
    /** True when the current setting differs from the recommendation. */
    val changeNeeded: Boolean,
    /** Safe/warning/danger colouring for memory-bearing rows, else null. */
    val memoryStatus: MemoryUsageStatus? = null
)

data class IntentProfileOption(
    val kind: ProfileKind,
    val title: String,
    val subtitle: String,
    val initialBufferMs: Int,
    val rebufferMs: Int,
    val minBufferMs: Int
)

data class AssessmentHeaderFacts(
    val deviceRamLabel: String,
    val safeLimitMb: Int,
    val warningLimitMb: Int,
    /** "3840x2160 · 24/50/60 Hz" style summary, null when uninspectable. */
    val displaySummary: String?,
    /** Filename (preferred) or host of the last-played stream, null when none. */
    val streamLabel: String?,
    val streamBitrateMbps: Double?,
    val streamDvProfile: String?,
    val streamHdrType: String?
)

data class AssessmentResult(
    val timestampMs: Long,
    val header: AssessmentHeaderFacts,
    val sweepRan: Boolean,
    val sweepVerdictText: String?,
    val items: List<AssessmentItem>,
    val profiles: List<IntentProfileOption>,
    /** Stability-derived suggestion among the profiles; null = no signal. */
    val suggestedProfile: ProfileKind?,
    val stabilityCoV: Double?,
    val errorText: String?
)
