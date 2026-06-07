package com.earlyspark.orbn.match

/**
 * A manual emotional mood (D17) — a labeled point in orbn's valence×energy space (the "circumplex of
 * affect"). Picking a mood overrides the Oura-derived target: it pins energy (arousal) and, for most
 * moods, valence (sad↔bright). The absence of a mood (null) is the "Default" chip = follow Oura.
 *
 * Design notes:
 *  - **Excited** leaves valence free, so it ranges across *any* feeling — purely high-energy/danceable.
 *  - **Chill** carries a strong [instrumentalBias], doubling as a focus/background mood (favors
 *    lyric-less tracks without excluding vocals — distinct from the hard functional gate `instrumentalMin`).
 *  - **Sad** pins valence low but uses a wide energy band, so it spans slow-sad through mid-energy
 *    tense/stressed, while **Angry** holds the high-energy dark extreme.
 *
 * @property label            Chip label shown to the user.
 * @property valenceCenter    Target valence 0..1, or null = free (don't constrain mood).
 * @property energyCenter     Target energy 0..1 (arousal).
 * @property energyBand       Half-width (Gaussian σ) of the energy zone — wider roams more.
 * @property instrumentalBias 0 = no lean; >0 softly favors lyric-less tracks (vocal tracks keep
 *                            `1 - bias` of their weight).
 */
enum class Mood(
    val label: String,
    val valenceCenter: Float?,
    val energyCenter: Float,
    val energyBand: Float,
    val instrumentalBias: Float,
) {
    HAPPY("Happy", valenceCenter = 0.85f, energyCenter = 0.60f, energyBand = 0.18f, instrumentalBias = 0f),
    EXCITED("Excited", valenceCenter = null, energyCenter = 0.88f, energyBand = 0.18f, instrumentalBias = 0f),
    CHILL("Chill", valenceCenter = 0.72f, energyCenter = 0.25f, energyBand = 0.18f, instrumentalBias = 0.7f),
    SAD("Sad", valenceCenter = 0.20f, energyCenter = 0.45f, energyBand = 0.30f, instrumentalBias = 0f),
    ANGRY("Angry", valenceCenter = 0.20f, energyCenter = 0.88f, energyBand = 0.18f, instrumentalBias = 0f);

    fun toTarget(): MatchTarget = MatchTarget(
        energyCenter = energyCenter,
        energyBand = energyBand,
        valenceCenter = valenceCenter,
        instrumentalMin = null,
        instrumentalBias = instrumentalBias,
    )

    companion object {
        /** Resolve a saved enum name back to a Mood, or null (Default) if unknown/absent. */
        fun byName(name: String?): Mood? = entries.firstOrNull { it.name == name }
    }
}
