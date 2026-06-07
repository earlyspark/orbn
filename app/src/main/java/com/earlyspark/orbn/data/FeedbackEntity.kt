package com.earlyspark.orbn.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One thumbs-up / thumbs-down the user gave a track from the "why this track" sheet (D12 — feedback
 * is rich personalization data we can't backfill, so capture it from day one).
 *
 * The **context** matters more than the rating alone: a thumbs-down means "wrong pick *for this
 * state*", not "bad song". So each row stores the active matching target (energy/valence/source) at
 * rating time plus the track's own affect — enough for [com.earlyspark.orbn.match.FeedbackBias] to
 * later down/up-weight the track only in *similar* states.
 *
 * @property rating        +1 (thumbs up) or −1 (thumbs down).
 * @property targetEnergy  Active target energy when rated.
 * @property targetValence Active target valence, or null if it was free (Oura case).
 * @property source        "oura" or "mood:<NAME>" — where the target came from.
 * @property trackEnergy/trackValence  The track's own computed affect point.
 */
@Entity(tableName = "feedback")
data class FeedbackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackPath: String,
    val ratedAt: Long,
    val rating: Int,
    val targetEnergy: Float,
    val targetValence: Float? = null,
    val source: String,
    val trackEnergy: Float,
    val trackValence: Float,
)
