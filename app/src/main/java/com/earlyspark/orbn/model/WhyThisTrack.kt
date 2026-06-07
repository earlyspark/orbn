package com.earlyspark.orbn.model

/**
 * The data behind the "why this track" sheet, assembled from the current track's stored analysis +
 * the active matching target. The raw numbers live here (the one place the user sees them);
 * everywhere else speaks in words. A plain model so both the home screen and the visualizer can
 * render the same sheet, and the match layer can build it without depending on the UI.
 */
data class WhyThisTrack(
    val trackPath: String,        // identifies the rated track for feedback
    val title: String,
    val artist: String?,
    val targetEnergyLabel: String, // YOUR matched energy word (same scale as the home readout)
    val targetEnergyValue: Float,  // YOUR energy on the 0..1 scale
    val energyLabel: String,      // the SONG's energy word, e.g. "intense"
    val energyValue: Float,       // 0..1 raw (the song's)
    val valenceLabel: String,     // the SONG's feel, e.g. "warm"
    val topMood: String?,         // the SONG's dominant mood head, or null
    val reason: String,           // one plain-language match sentence
)
