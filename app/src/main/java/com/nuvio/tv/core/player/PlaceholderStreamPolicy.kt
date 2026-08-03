package com.nuvio.tv.core.player

/**
 * 5a: reject a "stream" that is really a provider error card.
 *
 * Debrid services and scrapers sometimes answer a stream request with HTTP 200 and
 * a valid, playable MP4 whose entire content is an error message. Two observed in
 * the field on 3 Aug 2026:
 *
 *  - StremThru Torz -> "[StremThru] No Matching File !!!"
 *  - Premiumize (via Comet) -> "Internal provider issue. Please retry shortly."
 *    measured from the stats HUD: 406 KB, 1280x720, 17.876 fps, avc, 1.2 Mbit/s,
 *    against a label promising a 2160p NF WEB-DL.
 *
 * Nothing in the transport says anything is wrong, so the only tell is the shape of
 * the file itself.
 *
 * WHY AN ABSOLUTE FLOOR RATHER THAN A RATIO AGAINST THE ADVERTISED SIZE:
 * season packs. An addon that advertises a 60 GB pack and serves one 6 GB episode is
 * a legitimate 10% ratio, and a ratio gate would reject it on every pack. The floor
 * sits three orders of magnitude below any real file, needs no advertised size to
 * compare against (so it works for sources that declare none, including the Emby
 * bridge), and cannot be confused by pack accounting.
 *
 * WHY THE RUNTIME GUARD: short content is legitimately small. A personal library can
 * serve a two-minute extra or a clip. The floor is therefore only applied when the
 * title's own metadata says it should be long. With no runtime known, no verdict.
 *
 * This file is deliberately pure -- no Android, no coroutines, no player -- so the
 * decision is covered by executable assertions rather than by a commit message.
 *
 * Upstream: NuvioMedia/NuvioTV. Licensed under GPL-3.0.
 */
object PlaceholderStreamPolicy {

    /**
     * Files at or below this are not video anyone asked for. The largest placeholder
     * observed is 406 KB; the smallest plausible real episode at watchable quality is
     * tens of MB. 8 MB sits in the empty space between, ~20x above the observed
     * placeholder and well under any real content.
     */
    const val MIN_PLAUSIBLE_BYTES = 8L * 1024L * 1024L

    /**
     * The runtime guard. Only titles the metadata says run longer than this are
     * subject to the floor, so clips and extras are never judged.
     */
    const val MIN_GUARDED_RUNTIME_MS = 20L * 60L * 1000L

    /**
     * A file shorter than this, for a title whose metadata says it is feature length,
     * is an error card rather than the feature. Deliberately far below any real
     * content so a mis-scraped runtime cannot cause a rejection on its own.
     */
    const val MIN_PLAUSIBLE_DURATION_MS = 3L * 60L * 1000L

    /**
     * Fraction of the expected runtime below which a file is not the title, used only
     * alongside [MIN_PLAUSIBLE_DURATION_MS]; both must agree before a duration verdict
     * is returned.
     */
    const val MAX_IMPLAUSIBLE_DURATION_RATIO = 0.33

    sealed interface Verdict {
        /** Nothing suspicious, or not enough information to judge. Play it. */
        data object Accept : Verdict

        /** [reason] is logged and shown; a silent rejection would be worse than none. */
        data class Reject(val reason: Reason, val detail: String) : Verdict
    }

    enum class Reason {
        /** Content-Length is implausibly small for a feature-length title. */
        ImplausibleSize,

        /** Decoded duration is a small fraction of the expected runtime. */
        ImplausibleDuration
    }

    /**
     * @param contentLengthBytes actual bytes the server declared for the file being
     *   played, or null when unknown. NOT the advertised/label size.
     * @param durationMs decoded duration once known, or null before prepare.
     * @param expectedRuntimeMs runtime from the title's metadata, or null when unknown.
     */
    fun evaluate(
        contentLengthBytes: Long?,
        durationMs: Long?,
        expectedRuntimeMs: Long?
    ): Verdict {
        // No runtime, no verdict: without it we cannot tell a clip from an error card.
        if (expectedRuntimeMs == null || expectedRuntimeMs < MIN_GUARDED_RUNTIME_MS) {
            return Verdict.Accept
        }

        if (contentLengthBytes != null &&
            contentLengthBytes > 0L &&
            contentLengthBytes <= MIN_PLAUSIBLE_BYTES
        ) {
            return Verdict.Reject(
                reason = Reason.ImplausibleSize,
                detail = "${contentLengthBytes / 1024L} KB for a " +
                    "${expectedRuntimeMs / 60000L} min title"
            )
        }

        if (durationMs != null && durationMs > 0L) {
            val tooShortAbsolute = durationMs < MIN_PLAUSIBLE_DURATION_MS
            val tooShortRelative = durationMs < expectedRuntimeMs * MAX_IMPLAUSIBLE_DURATION_RATIO
            if (tooShortAbsolute && tooShortRelative) {
                return Verdict.Reject(
                    reason = Reason.ImplausibleDuration,
                    detail = "${durationMs / 1000L}s of a " +
                        "${expectedRuntimeMs / 60000L} min title"
                )
            }
        }

        return Verdict.Accept
    }
}
