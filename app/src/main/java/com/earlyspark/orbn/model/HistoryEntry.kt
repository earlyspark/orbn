package com.earlyspark.orbn.model

/**
 * One row in the History drawer: a single past play (repeats included, so [id] = the play-event id
 * gives each row a stable key). Shows the track, the energy you were in *then*, and your *current*
 * rating for the track (shared across all of its plays — editable/clearable from the drawer).
 */
data class HistoryEntry(
    val id: String,            // play-event id — stable LazyColumn key (a track can appear twice)
    val trackPath: String,
    val title: String,
    val artist: String?,
    val energyLabel: String,   // the energy word you were in at play time
    val energyValue: Float,    // 0..1 raw
    val rating: Int,           // +1 / −1 / 0 (none) — the track's current rating
)
