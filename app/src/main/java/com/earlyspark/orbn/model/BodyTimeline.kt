package com.earlyspark.orbn.model

/**
 * One day of body data for the timeline graph (why-this-track sheet, second stage): the cached
 * HR samples, the persisted 5-min MET movement series, and positioned high-stress/high-recovery
 * bands recovered from the daytime-stress delta history.
 *
 * Everything here is sync-gated cache — gaps are real and rendered as gaps (no interpolation).
 * Bands come from **attributable** counter movements only (delta plausibly within its accrual
 * window); smeared backfills carry no positional information and are simply absent.
 */
data class BodyTimeline(
    /** Window start (local midnight) and end ("now" at assembly), epoch millis. */
    val startMillis: Long,
    val endMillis: Long,
    val hr: List<HrPoint>,
    val met: List<MetPoint>,
    val bands: List<Band>,
) {
    data class HrPoint(val atMillis: Long, val bpm: Int)

    /** A 5-min movement bucket (mean MET) anchored at its start. */
    data class MetPoint(val atMillis: Long, val met: Float)

    /** A vertical overlay band: one or more contiguous 15-min blocks of one type. */
    data class Band(val startMillis: Long, val endMillis: Long, val recovery: Boolean)

    val isEmpty: Boolean get() = hr.isEmpty() && met.isEmpty() && bands.isEmpty()

    companion object {
        /** Oura classifies stress in 15-min blocks; bands are multiples of this. */
        const val BLOCK_MILLIS = 15 * 60 * 1000L

        /**
         * Position one attributable observation's blocks on the time axis. The counters say how
         * many blocks accrued in the window but not where; with frequent syncs the window is
         * ≈ one block so placement is essentially exact. For multi-block windows the blocks are
         * placed contiguously **ending at the observation** (the most recent plausible position),
         * recovery before stress — a documented convention, not data.
         */
        fun placeBands(
            dStressSec: Long,
            dRecoverySec: Long,
            observedAt: Long,
        ): List<Band> {
            val stressBlocks = (dStressSec / (BLOCK_MILLIS / 1000)).toInt()
            val recoveryBlocks = (dRecoverySec / (BLOCK_MILLIS / 1000)).toInt()
            val bands = ArrayList<Band>(2)
            var end = observedAt
            if (stressBlocks > 0) {
                val start = end - stressBlocks * BLOCK_MILLIS
                bands.add(Band(start, end, recovery = false))
                end = start
            }
            if (recoveryBlocks > 0) {
                bands.add(Band(end - recoveryBlocks * BLOCK_MILLIS, end, recovery = true))
            }
            return bands
        }
    }
}
