package com.earlyspark.orbn.library

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.earlyspark.orbn.analysis.AudioAnalyzer
import com.earlyspark.orbn.data.OrbnDatabase
import org.json.JSONArray
import org.json.JSONObject

/**
 * Background batch tagger. Analyzes every not-yet-analyzed track and writes the
 * result to Room. Designed to be resumable: each track is committed as it
 * finishes, so a re-run (or recovery after the system stops the worker) simply
 * continues with whatever is still unanalyzed.
 *
 * To respect WorkManager's per-run time limit (~10 min), it stops after a budget
 * and returns retry() if work remains; per-track commits make that safe.
 */
class TaggingWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val dao = OrbnDatabase.get(appContext).trackDao()
    private val analyzer = AudioAnalyzer(appContext.assets)

    override suspend fun doWork(): Result {
        val pending = dao.unanalyzed()
        if (pending.isEmpty()) {
            Log.i(TAG, "Nothing to tag.")
            return Result.success()
        }
        Log.i(TAG, "Tagging ${pending.size} track(s)…")

        val start = System.currentTimeMillis()
        var done = 0
        for (track in pending) {
            // Hold off while the visualizer is foreground: analysis is memory-heavy
            // and competing with the GL visualizer + playback can trip the OOM killer.
            // Re-checked each track (the native call can't be interrupted mid-track).
            if (AnalysisGate.isVisualizerActive()) {
                Log.i(TAG, "Visualizer foreground after $done; backing off tagging.")
                return Result.retry()
            }
            if (System.currentTimeMillis() - start > BUDGET_MS) {
                Log.i(TAG, "Time budget hit after $done; rescheduling for the rest.")
                return Result.retry()
            }

            val result = analyzer.analyze(track.path)
            if (result != null) {
                dao.update(
                    track.copy(
                        bpm = result.bpm,
                        musicKey = result.key,
                        keyStrength = result.keyStrength,
                        loudness = result.loudness,
                        valence = result.valence,
                        energy = result.energy,
                        genre = result.genre,
                        genreConfidence = result.genreConfidence,
                        moodTagsJson = encodeTags(result.moodTagNames, result.moodTagScores),
                        danceability = result.danceability,
                        voiceInstrumental = result.voiceInstrumental,
                        analyzedAt = System.currentTimeMillis(),
                    )
                )
            } else {
                // Decode/analysis failed (e.g. too short or unsupported). Mark it
                // analyzed-with-no-data so we don't retry it forever; a future file
                // change (size/mtime) will re-queue it via the scanner.
                Log.w(TAG, "Analysis returned null for ${track.path}; marking skipped.")
                dao.update(track.copy(analyzedAt = System.currentTimeMillis()))
            }
            done++
        }

        // New files may have been added mid-run; only declare success when clear.
        return if (dao.unanalyzed().isEmpty()) Result.success() else Result.retry()
    }

    private fun encodeTags(names: List<String>, scores: List<Float>): String {
        val arr = JSONArray()
        names.forEachIndexed { i, name ->
            arr.put(JSONObject().put("name", name).put("score", scores.getOrElse(i) { 0f }.toDouble()))
        }
        return arr.toString()
    }

    companion object {
        private const val TAG = "TaggingWorker"
        const val UNIQUE_NAME = "orbn-tagging"
        private const val BUDGET_MS = 9 * 60 * 1000L
    }
}
