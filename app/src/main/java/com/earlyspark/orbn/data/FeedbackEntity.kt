package com.earlyspark.orbn.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The user's current 👍/👎 for a track (D12 — feedback is rich personalization data we can't backfill).
 * **One row per track** (`trackPath` is the key): a new rating *replaces* the old, and the History
 * drawer can clear it (delete the row) — so the user can change their mind or null a decision out.
 *
 * The **context** still matters: a thumbs-down means "wrong pick *for this state*", not "bad song".
 * The row stores the matching target (energy/valence/source) it was rated in + the track's own affect,
 * so [com.earlyspark.orbn.match.FeedbackBias] down/up-weights the track only in *similar* states.
 *
 * @property rating        +1 (thumbs up) or −1 (thumbs down). Clearing deletes the row entirely.
 * @property targetEnergy  Active/at-play target energy when rated.
 * @property targetValence Active target valence, or null if it was free (Oura case).
 * @property source        "oura" / "mood:<NAME>" / "history" — where the rating's context came from.
 * @property trackEnergy/trackValence  The track's own computed affect point.
 */
@Entity(tableName = "feedback")
data class FeedbackEntity(
    @PrimaryKey val trackPath: String,
    val ratedAt: Long,
    val rating: Int,
    val targetEnergy: Float,
    val targetValence: Float? = null,
    val source: String,
    val trackEnergy: Float,
    val trackValence: Float,
)
