package com.earlyspark.orbn.model

/**
 * The user's current affective target in the shared valence–energy space — the output of M4
 * and the input to the M5 matching engine.
 *
 * Per the locked matching decisions:
 *  - **D14 (MIRROR):** the body state is reflected, not regulated. Calm body → calm music.
 *  - **D15 (energy-driven, valence free):** Oura senses physiology, so it sets the *energy*
 *    center/band only. Valence is left free ([valenceFree]) so the selector can range across
 *    the happy↔sad axis for variety — orbn never claims to know how the user *feels*.
 *  - **D18 (per-person normalization):** [energyCenter] is derived from Oura's already-personalized
 *    daily scores plus a heart-rate-reserve style intra-day arousal, never absolute BPM thresholds.
 *
 * This type is source-agnostic (D17): it can come from Oura or from a manual mood pick.
 *
 * @property energyCenter Target energy on the affect plane, 0f (calm) .. 1f (energetic).
 * @property energyBand   Half-width of the allowable energy band around [energyCenter]. Low
 *                        recovery → narrower band (don't push the body); high recovery → wider.
 * @property valenceFree  Whether valence is unconstrained (true for Oura; a manual *emotional*
 *                        mood may pin it — see [valenceCenter]).
 * @property valenceCenter Optional valence target (only set by manual emotional moods); null = free.
 * @property source       Where this target came from.
 * @property syncedAt     Epoch millis of the freshest underlying datum (for "synced N min ago"),
 *                        or null when unknown / manual.
 * @property diagnostics  Human-readable factors behind the target, for the "why this" readout and
 *                        debugging. Not used by the matcher.
 */
data class BiometricState(
    val energyCenter: Float,
    val energyBand: Float,
    val valenceFree: Boolean = true,
    val valenceCenter: Float? = null,
    val source: Source,
    val syncedAt: Long? = null,
    val diagnostics: Diagnostics = Diagnostics(),
) {
    enum class Source { OURA, MANUAL }

    /** Inputs that shaped the target — surfaced in the UI's "why this track", never in matching. */
    data class Diagnostics(
        val readinessScore: Int? = null,
        /**
         * Whether [readinessScore] is from today. Readiness is an overnight metric; if the latest
         * daily is from a prior day (e.g. the ring wasn't worn last night), the score is stale and
         * the readout should drop the recovery word rather than present yesterday's as current.
         */
        val readinessFresh: Boolean = true,
        val restingHr: Int? = null,
        val latestHr: Int? = null,
        val hrvMs: Int? = null,
        /** Heart-rate-reserve fraction: (latestHr − restingHr) / hrSpan, clamped 0..1. */
        val arousal: Float? = null,
        val note: String? = null,
    )

    /** Lower energy band bound, clamped to the valid plane. */
    val energyLow: Float get() = (energyCenter - energyBand).coerceIn(0f, 1f)

    /** Upper energy band bound, clamped to the valid plane. */
    val energyHigh: Float get() = (energyCenter + energyBand).coerceIn(0f, 1f)

    companion object {
        /**
         * Fallback used when Oura data is unavailable and the user hasn't picked a mood yet:
         * a neutral, fairly wide target so playback still works (D17 fallback).
         */
        fun neutral(): BiometricState = BiometricState(
            energyCenter = 0.5f,
            energyBand = 0.25f,
            valenceFree = true,
            source = Source.MANUAL,
            syncedAt = null,
            diagnostics = Diagnostics(note = "neutral fallback (no Oura data)"),
        )
    }
}
