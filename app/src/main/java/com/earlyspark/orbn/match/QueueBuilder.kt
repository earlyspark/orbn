package com.earlyspark.orbn.match

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.earlyspark.orbn.data.FeedbackEntity
import com.earlyspark.orbn.data.OrbnDatabase
import com.earlyspark.orbn.model.HistoryEntry
import com.earlyspark.orbn.model.TrackFeatures
import com.earlyspark.orbn.model.WhyThisTrack
import com.earlyspark.orbn.model.energyWord
import com.earlyspark.orbn.model.valenceWord
import com.earlyspark.orbn.oura.Oura
import java.io.File

/**
 * The shared matching engine used by BOTH the home screen and the visualizer: it resolves the active
 * target (manual mood → Oura → neutral), builds the matched play queue, and owns the feedback
 * read/write + the "why this track" assembly. Extracted from MainActivity so the two surfaces never
 * drift and the viz can drive the same gestures (mood / why / rematch).
 *
 * Android-aware (it touches the DB, Oura cache, prefs, and `MediaItem`s) but stateless beyond the
 * persisted manual mood — safe to construct per use.
 */
class QueueBuilder(private val context: Context) {

    private val db = OrbnDatabase.get(context)
    private val uiPrefs = context.getSharedPreferences("orbn_ui", Context.MODE_PRIVATE)

    // --- Manual mood (persisted; the source of truth shared across screens) ----------------------

    fun manualMood(): Mood? = Mood.byName(uiPrefs.getString(KEY_MOOD, null))

    fun setManualMood(mood: Mood?) {
        uiPrefs.edit().putString(KEY_MOOD, mood?.name).apply()
    }

    /** Active target: a manual mood (D17) wins; else the cached Oura state; else neutral (D17). */
    suspend fun currentTarget(): MatchTarget =
        manualMood()?.toTarget()
            ?: Oura.repository(context).currentState()?.toTarget()
            ?: MatchTarget.neutral()

    // --- Queue building --------------------------------------------------------------------------

    /**
     * Build the matched play queue (M5.2): score the analyzed library through [AffectFold], sample it
     * against the current target with recency + learned-feedback shaping, and return ready
     * [MediaItem]s. Falls back to a plain folder listing if nothing is analyzed yet; empty if there's
     * no music at all. Setting these on a player + autoplay is the caller's job.
     */
    suspend fun buildItems(): List<MediaItem> {
        val tracks = db.trackDao().analyzed()
        val byPath = tracks.associateBy { it.path }
        val candidates = tracks.mapNotNull { t ->
            val f = t.toFeaturesOrNull() ?: return@mapNotNull null
            Matcher.Candidate(t.path, AffectFold.fold(f), f.instrumental, artist = t.artist ?: artistOf(t.path))
        }
        if (candidates.isEmpty()) return folderItems() // nothing analyzed yet → keep playback working

        val target = currentTarget()
        val now = System.currentTimeMillis()
        val lastPlayed = db.playEventDao().lastPlayedSince(now - RECENCY_WINDOW_MS)
            .associate { it.trackPath to it.lastPlayed }
        val recency = RecencyPenalty.multipliers(lastPlayed, now, RECENCY_WINDOW_MS, RECENCY_FLOOR)

        val ratingsByTrack = db.feedbackDao().all().groupBy(
            { it.trackPath },
            { FeedbackBias.Rating(it.rating, it.ratedAt, it.targetEnergy) },
        )
        val feedback = FeedbackBias.multipliers(ratingsByTrack, target, now)

        val queue = Matcher.buildQueue(
            candidates, target, count = QUEUE_SIZE, recency = recency, feedback = feedback,
        )
        val sequenced = QueueSequencer.sequence(queue)
        android.util.Log.i(
            "OrbnMatch",
            "target e=%.2f±%.2f val=%s → %d/%d queued (%d recent); seq energies=%s".format(
                target.energyCenter, target.energyBand,
                target.valenceCenter?.let { "%.2f".format(it) } ?: "free",
                sequenced.size, candidates.size, lastPlayed.size,
                sequenced.take(10).joinToString(",") { "%.2f".format(it.point.energy) },
            ),
        )
        return sequenced.map { cand ->
            val f = File(cand.id)
            val entity = byPath[cand.id]
            MediaItem.Builder()
                .setUri(Uri.fromFile(f))
                .setMediaId(cand.id)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        // Prefer embedded tags; else parse the "Artist - Title" filename (cand.artist
                        // already carries that fallback). No " - " → title is the whole filename, no artist.
                        .setTitle(entity?.title ?: titleOf(cand.id))
                        .setArtist(cand.artist)
                        .build()
                )
                .build()
        }
    }

    /** Build the matched queue and set it on [player] (no-op if there's no music). Caller-agnostic. */
    suspend fun applyTo(player: Player, autoPlay: Boolean) {
        val items = buildItems()
        if (items.isEmpty()) return
        player.setMediaItems(items)
        player.prepare()
        if (autoPlay) player.play()
    }

    /** Plain folder listing (audio files only) — the fallback before anything is analyzed. */
    private fun folderItems(): List<MediaItem> {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: return emptyList()
        val files = dir.listFiles { f ->
            f.isFile && f.extension.lowercase() in setOf("mp3", "flac", "m4a", "ogg", "wav", "aac")
        }?.sortedBy { it.name } ?: return emptyList()
        return files.map { f ->
            MediaItem.Builder()
                .setUri(Uri.fromFile(f))
                .setMediaId(f.absolutePath)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(titleOf(f.absolutePath))
                        .setArtist(artistOf(f.absolutePath))
                        .build()
                )
                .build()
        }
    }

    // --- Feedback + "why this track" -------------------------------------------------------------

    /**
     * Persist a 👍/👎 with the context at this moment (D12): the active target (energy/valence/source)
     * and the track's own affect — so [FeedbackBias] can later learn state-aware, not just "good/bad".
     */
    suspend fun recordFeedback(path: String, rating: Int) {
        val target = currentTarget()
        val source = manualMood()?.let { "mood:${it.name}" } ?: "oura"
        upsertRating(path, rating, target.energyCenter, target.valenceCenter, source)
    }

    /** Set/clear a track's rating from the History drawer, stamped with the energy it played in. */
    suspend fun setHistoryRating(path: String, rating: Int, contextEnergy: Float) {
        upsertRating(path, rating, contextEnergy, targetValence = null, source = "history")
    }

    /** Upsert (or clear, when rating == 0) the track's single rating with the given context. */
    private suspend fun upsertRating(path: String, rating: Int, targetEnergy: Float, targetValence: Float?, source: String) {
        if (rating == 0) { db.feedbackDao().clear(path); return }
        val point = db.trackDao().byPath(path)?.toFeaturesOrNull()?.let { AffectFold.fold(it) }
        db.feedbackDao().upsert(
            FeedbackEntity(
                trackPath = path,
                ratedAt = System.currentTimeMillis(),
                rating = rating,
                targetEnergy = targetEnergy,
                targetValence = targetValence,
                source = source,
                trackEnergy = point?.energy ?: 0.5f,
                trackValence = point?.valence ?: 0.5f,
            )
        )
    }

    /**
     * The recent play history (D12 log) for the History drawer — every PLAYED event (with repeats),
     * newest first, each tagged with the energy you were in then + your current rating for the track.
     */
    suspend fun recentHistory(limit: Int = 30): List<HistoryEntry> {
        val plays = db.playEventDao().recentPlays(limit)
        val byPath = db.trackDao().analyzed().associateBy { it.path }
        val ratings = db.feedbackDao().all().associate { it.trackPath to it.rating }
        return plays.map { p ->
            val entity = byPath[p.trackPath]
            val energy = p.energyTarget ?: 0.5f
            HistoryEntry(
                id = p.id,
                trackPath = p.trackPath,
                title = entity?.title ?: titleOf(p.trackPath),
                artist = entity?.artist ?: artistOf(p.trackPath),
                energyLabel = energyWord(energy),
                energyValue = energy,
                rating = ratings[p.trackPath] ?: 0,
            )
        }
    }

    /** Assemble the "why this track" detail for [path], or a placeholder while it's still analyzing. */
    suspend fun whyThisTrack(path: String, title: String, artist: String?): WhyThisTrack {
        val target = currentTarget()
        val targetLabel = energyWord(target.energyCenter) // YOUR matched energy word (= home readout)
        val features = db.trackDao().byPath(path)?.toFeaturesOrNull()
            ?: return WhyThisTrack(
                trackPath = path, title = title, artist = artist, targetEnergyLabel = targetLabel,
                targetEnergyValue = target.energyCenter, energyLabel = "—", energyValue = 0f,
                valenceLabel = "—", topMood = null,
                reason = "Still analyzing this track — check back once tagging finishes.",
            )
        val point = AffectFold.fold(features)
        return WhyThisTrack(
            trackPath = path,
            title = title,
            artist = artist,
            targetEnergyLabel = targetLabel,
            targetEnergyValue = target.energyCenter,
            energyLabel = energyWord(point.energy),
            energyValue = point.energy,
            valenceLabel = valenceWord(point.valence),
            topMood = features.moodLabel(),
            reason = reasonFor(point.energy, target.energyCenter, manualMood()),
        )
    }

    /**
     * The song's mood label, always present: the strongest of the four mood heads when it's confident
     * (≥ 0.40), else "mixed · <leaning mood>" — the song still has a mood, it's just not clear-cut.
     */
    private fun TrackFeatures.moodLabel(): String {
        val top = listOf(
            "happy" to happy, "sad" to sad, "aggressive" to aggressive, "relaxed" to relaxed,
        ).maxByOrNull { it.second } ?: return "mixed"
        return if (top.second >= 0.4f) top.first else "mixed · ${top.first}"
    }

    /** One plain-language reason a track fits (or pleasantly deviates from) the target. */
    private fun reasonFor(trackEnergy: Float, targetEnergy: Float, mood: Mood?): String {
        val src = if (mood != null) "your ${mood.label} mood" else "your current state"
        val d = trackEnergy - targetEnergy
        return when {
            kotlin.math.abs(d) < 0.12f -> "A close match for $src (${energyWord(targetEnergy)})."
            d > 0f -> "A little livelier than $src, mixed in for variety."
            else -> "A little calmer than $src, mixed in for variety."
        }
    }

    private companion object {
        const val KEY_MOOD = "manual_mood"

        /** How many tracks the matcher samples into a queue per build. */
        const val QUEUE_SIZE = 30

        /** Recently-played tracks are down-weighted, recovering over this window (≈ a small-library cycle). */
        const val RECENCY_WINDOW_MS = 2 * 60 * 60 * 1000L
        const val RECENCY_FLOOR = 0.1f
    }
}

/** Best-effort artist from an "Artist - Title.ext" filename; null if the pattern doesn't match. */
private fun artistOf(path: String): String? =
    path.substringAfterLast('/').substringBeforeLast('.')
        .substringBefore(" - ", missingDelimiterValue = "").trim().ifBlank { null }

/** Best-effort title from an "Artist - Title.ext" filename; the whole filename if there's no " - ". */
private fun titleOf(path: String): String {
    val name = path.substringAfterLast('/').substringBeforeLast('.')
    return name.substringAfter(" - ", missingDelimiterValue = name).trim().ifBlank { name }
}
