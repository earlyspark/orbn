package com.earlyspark.orbn.match

import com.earlyspark.orbn.data.TrackEntity
import com.earlyspark.orbn.model.TrackFeatures
import org.json.JSONArray

/**
 * Decodes a stored [TrackEntity] into pure [TrackFeatures] for the fold. Lives at the Android
 * boundary (parses `moodTagsJson` and the key string); [AffectFold] itself stays Android-free.
 *
 * Returns null if the track isn't fully analyzed for the fold's needs (missing danceability /
 * loudness / bpm) — callers should skip such tracks until tagging fills them in.
 */
fun TrackEntity.toFeaturesOrNull(): TrackFeatures? {
    val dance = danceability ?: return null
    val loud = loudness ?: return null
    val tempo = bpm ?: return null

    val moods = parseMoodScores(moodTagsJson)
    return TrackFeatures(
        bpm = tempo,
        loudness = loud,
        danceability = dance,
        happy = moods["happy"] ?: 0f,
        sad = moods["sad"] ?: 0f,
        aggressive = moods["aggressive"] ?: 0f,
        relaxed = moods["relaxed"] ?: 0f,
        isMajorKey = parseMajorKey(musicKey),
        instrumental = voiceInstrumental,
    )
}

/** Parse the `[{"name","score"}]` mood JSON into a name→score map. */
private fun parseMoodScores(json: String?): Map<String, Float> {
    if (json.isNullOrBlank()) return emptyMap()
    return runCatching {
        val arr = JSONArray(json)
        buildMap {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                put(o.getString("name"), o.getDouble("score").toFloat())
            }
        }
    }.getOrDefault(emptyMap())
}

/** "E major" → true, "A minor" → false, anything else → null. */
private fun parseMajorKey(key: String?): Boolean? = when {
    key == null -> null
    key.contains("major", ignoreCase = true) -> true
    key.contains("minor", ignoreCase = true) -> false
    else -> null
}
