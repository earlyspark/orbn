package com.earlyspark.orbn.oura

/**
 * Derives a small, decaying "recent stress" lean on the energy target from Oura's
 * day-cumulative daytime-stress counters (amends F6; probed live 2026-06-10).
 *
 * The `daily_stress` document carries only two running totals for the day — seconds classified
 * high-stress and high-recovery, advancing in 900-s blocks whenever the ring syncs — with no
 * timestamps. The only usable "now" read is therefore a *delta between successive observations*,
 * attributed to the window since the counters last moved. Mirroring (D14) applies: stress accrued
 * recently leans the target intense, recovery leans it calm. The signal stays a supporting vote:
 *
 *  - the lean is the window fraction spent in stress minus recovery, capped at [MAX_LEAN]
 *    (≈ a quarter of the arousal span) — HR + movement remain the primary signal;
 *  - backfill smears abstain: a delta far exceeding its window is a sync delivering hours of
 *    history, which says nothing about the present (e.g. +90 min landing in an 11-min gap);
 *  - day rollovers and first observations abstain (no baseline to difference against);
 *  - a valid lean decays linearly to zero over [DECAY_MS], so it never outlives its relevance;
 *  - a fetch with unchanged counters keeps the previous lean (still decaying) — "nothing synced"
 *    is not evidence of calm, just absence of news.
 */
object StressSignal {

    /** Stress bookkeeping carried on the cached daily row between refreshes. */
    data class State(
        val stressHighSec: Long? = null,
        val recoveryHighSec: Long? = null,
        /** Epoch millis when the counters last *changed* — the start of the next delta's window. */
        val changedAt: Long? = null,
        /** Last valid delta as a signed window fraction, -1 (all recovery) .. +1 (all stress). */
        val nudge: Float? = null,
        /** Epoch millis the nudge was derived — drives the decay. */
        val nudgeAt: Long? = null,
    )

    /**
     * Result of folding one fetch: the new [state], plus an [observation] exactly when the
     * counters moved — persisted as the delta history behind the body-timeline graph.
     */
    data class Outcome(val state: State, val observation: Observation? = null)

    /**
     * One counter movement: the deltas, the window they accrued over, and whether the window
     * makes them [attributable]. Timeline bands are drawn only from attributable observations;
     * smeared backfills are stored (QA/tallies) but never plotted as positioned time.
     */
    data class Observation(
        val dStressSec: Long,
        val dRecoverySec: Long,
        val windowStartAt: Long,
        val observedAt: Long,
        val attributable: Boolean,
    )

    /**
     * Fold a freshly fetched counter pair into the previous [prev] state. [prevDay]/[day] guard
     * the rollover: counters reset each morning, so a cross-day delta is meaningless.
     */
    fun update(
        prev: State?,
        prevDay: String?,
        day: String?,
        stressHighSec: Long?,
        recoveryHighSec: Long?,
        now: Long,
    ): Outcome {
        // No stress document (endpoint empty/failed) → keep what we had; the old lean decays out.
        if (day == null || (stressHighSec == null && recoveryHighSec == null)) {
            return Outcome(prev ?: State())
        }
        val s = stressHighSec ?: 0L
        val r = recoveryHighSec ?: 0L
        // First observation ever, or a new day's document: baseline only, nothing to difference.
        if (prev?.stressHighSec == null || prev.changedAt == null || prevDay != day) {
            return Outcome(State(stressHighSec = s, recoveryHighSec = r, changedAt = now))
        }
        if (s == prev.stressHighSec && r == (prev.recoveryHighSec ?: 0L)) {
            return Outcome(prev) // nothing synced in — existing nudge keeps decaying
        }

        val windowSec = (now - prev.changedAt) / 1000L
        val dStress = s - prev.stressHighSec
        val dRecovery = r - (prev.recoveryHighSec ?: 0L)
        // Negative deltas (cloud reprocessing) and backfill smears yield no lean — and clear any
        // previous one, since whatever it described is older than this batch.
        val valid = windowSec > 0 && dStress >= 0 && dRecovery >= 0 &&
            (dStress + dRecovery) <= windowSec + BACKFILL_SLACK_SEC
        return Outcome(
            state = State(
                stressHighSec = s,
                recoveryHighSec = r,
                changedAt = now,
                nudge = if (valid) ((dStress - dRecovery).toFloat() / windowSec).coerceIn(-1f, 1f) else null,
                nudgeAt = if (valid) now else null,
            ),
            observation = Observation(
                dStressSec = dStress,
                dRecoverySec = dRecovery,
                windowStartAt = prev.changedAt,
                observedAt = now,
                attributable = valid,
            ),
        )
    }

    /**
     * The decayed lean (energy units) to add to the mirrored center: positive = stress accrued
     * recently, negative = recovery, 0 = abstain.
     */
    fun lean(nudge: Float?, nudgeAt: Long?, now: Long): Float {
        if (nudge == null || nudgeAt == null) return 0f
        val freshness = 1f - (now - nudgeAt).toFloat() / DECAY_MS
        if (freshness <= 0f) return 0f
        return nudge * MAX_LEAN * freshness.coerceAtMost(1f)
    }

    /** Cap on the lean — a secondary vote beside the HR/MET arousal (D18). */
    const val MAX_LEAN = 0.15f

    /** A valid delta's influence fades to zero over this long. */
    const val DECAY_MS = 45 * 60 * 1000L

    /** Counters move in 900-s blocks; allow two blocks of quantization overshoot before
     *  calling a delta a backfill. */
    const val BACKFILL_SLACK_SEC = 1800L
}
