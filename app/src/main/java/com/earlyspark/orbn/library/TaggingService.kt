package com.earlyspark.orbn.library

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.earlyspark.orbn.analysis.AudioAnalyzer
import com.earlyspark.orbn.data.OrbnDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Foreground service that analyzes the music library in the background.
 *
 * Runs as a foreground service (with an ongoing notification) so the OS does not
 * defer it under Doze — analysis makes steady progress whenever the app is alive,
 * including screen-off battery playback. It loops through unanalyzed tracks one at
 * a time (memory stays bounded), committing each result as it finishes, so it is
 * fully resumable across restarts.
 *
 * It pauses — without giving up the foreground slot — while:
 *   - the visualizer is foreground ([AnalysisGate]); analysis competes with the GL
 *     renderer for CPU and memory, so the visualizer takes priority, and
 *   - the battery is critically low and not charging, to avoid draining to empty.
 *
 * Replaces the earlier WorkManager-based tagger, whose exponential retry backoff
 * and Doze deferral stalled progress when listening on battery.
 */
class TaggingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var worker: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Idempotent: a second start while already grinding is a no-op.
        if (worker?.isActive == true) return START_STICKY
        val notif = buildNotification(getString_progress(0, 0))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        worker = scope.launch { runQueue() }
        return START_STICKY
    }

    /** Drain the unanalyzed queue, pausing as needed, until nothing remains. */
    private suspend fun runQueue() {
        val dao = OrbnDatabase.get(applicationContext).trackDao()
        val analyzer = AudioAnalyzer(applicationContext.assets)
        try {
            val total = dao.totalCountOnce()
            while (scope.isActive) {
                val pending = dao.unanalyzed()
                if (pending.isEmpty()) {
                    Log.i(TAG, "Nothing left to tag; stopping.")
                    break
                }
                var done = total - pending.size
                for (track in pending) {
                    awaitRunnable()                       // block while paused (viz / low battery)
                    updateNotification(getString_progress(done, total))
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
                        // Decode/analysis failed (too short / unsupported): mark analyzed-with-no-data
                        // so it isn't retried forever; a file change re-queues it via the scanner.
                        Log.w(TAG, "Analysis returned null for ${track.path}; marking skipped.")
                        dao.update(track.copy(analyzedAt = System.currentTimeMillis()))
                    }
                    done++
                }
            }
        } catch (e: Exception) {
            // Let cancellation propagate; log anything else and exit cleanly.
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Tagging loop error", e)
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** Suspend while tagging should hold off; returns once it may run again. */
    private suspend fun awaitRunnable() {
        var announced = false
        while (shouldPause()) {
            if (!announced) {
                Log.i(TAG, "Pausing tagging (${pauseReason()}).")
                updateNotification(pauseReason())
                announced = true
            }
            delay(PAUSE_POLL_MS)
        }
    }

    private fun shouldPause(): Boolean =
        AnalysisGate.isVisualizerActive() || isBatteryCritical()

    private fun pauseReason(): String = when {
        AnalysisGate.isVisualizerActive() -> "Paused — visualizer active"
        else -> "Paused — battery low"
    }

    /** True when unplugged and at/below the critical battery floor. */
    private fun isBatteryCritical(): Boolean {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return !bm.isCharging && level in 0..BATTERY_MIN_PCT
    }

    // ── Foreground notification ────────────────────────────────────────────────

    private fun buildNotification(text: String): Notification {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            mgr.getNotificationChannel(CHANNEL_ID) == null
        ) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Library tagging", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Background music analysis progress" }
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Analyzing your library")
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIF_ID, buildNotification(text))
    }

    private fun getString_progress(done: Int, total: Int): String =
        if (total > 0) "$done / $total tracks" else "Starting…"

    private fun encodeTags(names: List<String>, scores: List<Float>): String {
        val arr = JSONArray()
        names.forEachIndexed { i, name ->
            arr.put(JSONObject().put("name", name).put("score", scores.getOrElse(i) { 0f }.toDouble()))
        }
        return arr.toString()
    }

    /**
     * Android 15 enforces a cumulative daily runtime cap on dataSync services. The
     * full library finishes far under it, but honor the signal: stop cleanly.
     */
    override fun onTimeout(startId: Int) {
        Log.w(TAG, "Foreground service timed out; stopping.")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TaggingService"
        private const val CHANNEL_ID = "orbn-tagging"
        private const val NOTIF_ID = 0x0B
        private const val PAUSE_POLL_MS = 2_000L
        private const val BATTERY_MIN_PCT = 15

        /** Unique name of the retired WorkManager tagger, cancelled on migration. */
        const val LEGACY_WORK_NAME = "orbn-tagging"

        /** Start (or no-op if already running) the foreground tagging service. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, TaggingService::class.java)
            )
        }
    }
}
