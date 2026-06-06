package com.earlyspark.orbn.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per audio file known to orbn. Acts as both the library catalog and the
 * analysis store: a row exists as soon as the file is discovered; the analysis
 * fields are filled in once the background tagging job processes it.
 *
 * [analyzedAt] == null means "not yet analyzed". File [sizeBytes] + [lastModified]
 * form a cheap change-signature: if either differs from disk, the file is re-queued.
 */
@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val path: String,
    val sizeBytes: Long,
    val lastModified: Long,
    // Analysis results (null until analyzed):
    val bpm: Float? = null,
    val musicKey: String? = null,
    val keyStrength: Float? = null,
    val loudness: Float? = null,
    val valence: Float? = null,
    val energy: Float? = null,
    val genre: String? = null,
    val genreConfidence: Float? = null,
    val moodTagsJson: String? = null,
    val analyzedAt: Long? = null,
)
