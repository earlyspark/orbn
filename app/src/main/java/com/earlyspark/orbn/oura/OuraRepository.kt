package com.earlyspark.orbn.oura

import android.util.Log
import com.earlyspark.orbn.BuildConfig
import com.earlyspark.orbn.data.OuraDailyEntity
import com.earlyspark.orbn.data.OuraDao
import com.earlyspark.orbn.data.OuraHeartRateEntity
import com.earlyspark.orbn.data.OuraSessionEntity
import com.earlyspark.orbn.model.BiometricState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Fetches the user's Oura data, caches it locally (D10), and folds it into a [BiometricState]
 * target for the matching engine. Network calls run on [Dispatchers.IO].
 *
 * Normalization is per-person (D18), never absolute BPM:
 *  - **Energy center mirrors live arousal** (D14): calm body → calm music. Readiness (recovery) is
 *    *capacity*, a different axis, so it sets only the *band* (how wide a range to roam) — being
 *    well-recovered no longer reads as "high energy" while you're at rest (SPEC §13).
 *  - **Intra-day activation** is the stronger of two live signals: heart-rate-reserve (how far the
 *    current HR sits above resting, scaled by a personal HR span) and current movement (MET).
 *    HRR is used (rather than raw bpm) so "elevated" means elevated *for this user* — relative to
 *    their own resting HR — not against an arbitrary fixed threshold.
 *  - **Current HR** is the latest 5-min daytime sample, or — when more recent — the heart rate
 *    from a logged session (a higher-fidelity on-demand read).
 *  - Oura's daily stress is deliberately NOT used: the API exposes only a whole-day `day_summary`
 *    (no intra-day stress), which can't track "now", so it isn't fetched or folded in at all.
 *
 * v1 (M4) uses a fixed HR-span estimate for the reserve denominator. It self-calibrates from the
 * user's own logged HR distribution (D12 play-history log) once the M5 matching work lands —
 * age is deliberately not used as an input.
 */
class OuraRepository(
    private val api: OuraApiClient,
    private val dao: OuraDao,
    private val tokenStore: OuraTokenStore,
) {
    sealed interface RefreshResult {
        data class Success(val state: BiometricState) : RefreshResult
        data object NotConfigured : RefreshResult
        data object NotConnected : RefreshResult
        data class Error(val message: String) : RefreshResult
    }

    val isConnected: Boolean get() = tokenStore.isAuthorized

    /**
     * True when the stored grant covers every scope orbn now requests. False after the requested
     * scope list changes (or for tokens saved before grants were recorded) — the caller should
     * send the user back through consent rather than risk 403s on newly scoped endpoints.
     */
    val hasCurrentScopes: Boolean get() = tokenStore.coversScopes(OuraConfig.scopes)

    fun disconnect() = tokenStore.clear()

    /** Guards against overlapping auto-refreshes (app-open + song-change firing together). */
    private val refreshing = AtomicBoolean(false)

    /** Pull the latest Oura data, cache it, and compute the current target. */
    suspend fun refresh(): RefreshResult = withContext(Dispatchers.IO) {
        if (!OuraConfig.isConfigured) return@withContext RefreshResult.NotConfigured
        if (!tokenStore.isAuthorized) return@withContext RefreshResult.NotConnected

        try {
            val today = LocalDate.now()
            val startDate = today.minusDays(2).toString()
            val endDate = today.plusDays(1).toString() // end_date is treated exclusively by some endpoints
            val now = OffsetDateTime.now()
            val startDateTime = now.minusHours(HR_WINDOW_HOURS).format(ISO)
            val endDateTime = now.format(ISO)

            // Debug-only probe (F6 amendment experiment): `daily_stress` counters are documented
            // as day-cumulative, but whether they advance on each ring sync (differencable into a
            // "recent stress" signal) or only land in the evening is undocumented. Log them on
            // every refresh; `adb logcat -s StressProbe` over a day answers it empirically.
            // Failures stay isolated from the real refresh (e.g. 403 = token lacks the scope).
            if (BuildConfig.DEBUG) {
                runCatching { api.getDailyStress(startDate, endDate) }
                    .onSuccess { stress ->
                        val s = stress.maxByOrNull { it.day ?: "" }
                        Log.i(
                            "StressProbe",
                            "day=${s?.day} stress_high=${s?.stressHigh} " +
                                "recovery_high=${s?.recoveryHigh} summary=${s?.daySummary}",
                        )
                    }
                    .onFailure { Log.i("StressProbe", "fetch failed: $it") }
            }

            val readiness = api.getDailyReadiness(startDate, endDate).maxByOrNull { it.day ?: "" }
            val dailySleep = api.getDailySleep(startDate, endDate).maxByOrNull { it.day ?: "" }
            val sleep = api.getSleepPeriods(startDate, endDate).maxByOrNull { it.day ?: "" }
            val heartRates = api.getHeartRate(startDateTime, endDateTime)
            val sessions = api.getSessions(startDate, endDate)
            val activity = api.getDailyActivity(startDate, endDate).maxByOrNull { it.day ?: "" }

            val fetchedAt = System.currentTimeMillis()
            val day = readiness?.day ?: sleep?.day ?: dailySleep?.day ?: today.toString()

            // Intra-day movement at *now*: met.items spans the whole Oura day (04:00 → 04:00 next day),
            // pre-sized to 1-min slots with trailing filler — so "last non-null" is the end-of-day
            // filler (a flat ~0.9), not the current minute. Index by elapsed time from the series start
            // and take the latest real sample at or before now.
            val metSeries = activity?.met
            val metStartTime = metSeries?.timestamp?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
            val elapsedMin = metStartTime?.let { ((now.toEpochSecond() - it.toEpochSecond()) / 60).toInt() }
            val metLatest = metSeries?.items?.let { items ->
                val upTo = elapsedMin?.let { (it + 1).coerceIn(0, items.size) } ?: items.size
                items.take(upTo).lastOrNull { it != null }
            }?.toFloat()
            // class_5_min shares the day anchor (5-min blocks); index the current block the same way.
            // (takeIf non-empty: an empty string would make coerceIn(0, -1) throw.)
            val activityClass = activity?.class5Min?.takeIf { it.isNotEmpty() }?.let { classes ->
                val idx5 = elapsedMin?.let { (it / 5).coerceIn(0, classes.length - 1) } ?: (classes.length - 1)
                classes.getOrNull(idx5)?.takeIf { it.isDigit() }?.digitToIntOrNull()
            }

            dao.upsertDaily(
                listOf(
                    OuraDailyEntity(
                        day = day,
                        readinessScore = readiness?.score,
                        sleepScore = dailySleep?.score,
                        restingHr = sleep?.lowestHeartRate,
                        hrvMs = sleep?.averageHrv,
                        metLatest = metLatest,
                        activityClass = activityClass,
                        fetchedAt = fetchedAt,
                    )
                )
            )
            if (heartRates.isNotEmpty()) {
                dao.upsertHeartRate(
                    heartRates.mapNotNull { hr ->
                        val ts = hr.timestamp ?: return@mapNotNull null
                        val bpm = hr.bpm ?: return@mapNotNull null
                        OuraHeartRateEntity(ts, bpm, hr.source, fetchedAt)
                    }
                )
            }
            val sessionEntities = sessions.mapNotNull { it.toEntity(fetchedAt) }
            if (sessionEntities.isNotEmpty()) dao.upsertSessions(sessionEntities)

            val state = currentState() ?: BiometricState.neutral()
            RefreshResult.Success(state)
        } catch (e: OuraAuthException) {
            RefreshResult.NotConnected
        } catch (e: Exception) {
            Log.w(TAG, "Oura refresh failed", e)
            RefreshResult.Error(e.message ?: "refresh failed")
        }
    }

    /**
     * Network-refresh only if the cache is older than [maxAgeMillis] and no refresh is already in
     * flight. Returns null when skipped (data still fresh, or a refresh is running) — the caller
     * should keep displaying [currentState]. The gate exists because Oura data can't change faster
     * than the ~5-min HR cadence + ring-sync, so polling tighter just re-pulls identical data.
     * (Oura's 5,000-req/5-min limit is never a factor here.)
     */
    suspend fun refreshIfStale(maxAgeMillis: Long = DEFAULT_STALE_MILLIS): RefreshResult? {
        if (!OuraConfig.isConfigured) return RefreshResult.NotConfigured
        if (!tokenStore.isAuthorized) return RefreshResult.NotConnected

        val lastFetch = withContext(Dispatchers.IO) { dao.latestDaily()?.fetchedAt }
        val fresh = lastFetch != null && (System.currentTimeMillis() - lastFetch) < maxAgeMillis
        if (fresh) return null

        // Skip if another refresh is already running; that one will land the fresh data.
        if (!refreshing.compareAndSet(false, true)) return null
        return try {
            refresh()
        } finally {
            refreshing.set(false)
        }
    }

    /** Build a [BiometricState] purely from the local cache (no network). */
    suspend fun currentState(): BiometricState? = withContext(Dispatchers.IO) {
        val daily = dao.latestDaily() ?: return@withContext null
        val latestHr = dao.latestHeartRate()
        val latestSession = dao.latestSession()
        fold(daily, latestHr, latestSession)
    }

    // --- Folding logic (pure, per-person) ---------------------------------------------------

    private fun fold(
        daily: OuraDailyEntity,
        latestHr: OuraHeartRateEntity?,
        latestSession: OuraSessionEntity?,
    ): BiometricState {
        // Readiness sets the BAND only (capacity = how wide a range to roam), NOT the center: being
        // well-recovered shouldn't read as "high energy" when you're calm — the center mirrors live
        // arousal below (D14 mirror; readiness is capacity, a different axis — SPEC §13). Well-
        // recovered → a wider band (room to range); depleted → narrow (keep it gentle). Missing → mid.
        val readiness = daily.readinessScore
        // Readiness is from the daily row's date; if that isn't today (e.g. no ring last night), the
        // score is stale and the readout drops the recovery word rather than show yesterday's as now.
        val readinessFresh = daily.day == LocalDate.now().toString()
        val baseBand: Float = if (readiness != null) {
            val r = readiness.coerceIn(0, 100) / 100f
            0.12f + r * 0.18f   // 0.12 (narrow, depleted) .. 0.30 (wide, fully recovered)
        } else {
            0.25f
        }

        // Choose the current-HR source: the latest 5-min sample, or a session's heart rate when
        // the session is more recent (higher fidelity, on-demand). Mirror applies either way —
        // a calm breathing session reads low HR → calm music.
        val source = chooseHrSource(latestHr, latestSession)

        // Intra-day activation = HR-leaning max of two live signals (D18): heart-rate-reserve above
        // resting, and current movement (MET) weighted DOWN by MET_WEIGHT. Max (not sum) avoids
        // double-counting during exercise and never misses activation; the HR lean follows the
        // "non-metabolic heart rate" literature — HR elevated while still (stress/arousal) is the
        // stronger cue and reads at full strength, while pure light movement reads a touch gentler.
        val restingHr = daily.restingHr
        val hrFrac = if (source != null && restingHr != null) {
            // Scale against a realistic *daytime* span, not max HR: resting → fully-activated lands at
            // restingHr + DAYTIME_HR_SPAN (~a brisk-walk heart rate), so ordinary HR swings actually
            // move the readout. Against max HR (190) the whole awake range compressed into the bottom
            // ~25% and pinned everything to "mellow".
            ((source.bpm - restingHr).toFloat() / DAYTIME_HR_SPAN).coerceIn(0f, 1f)
        } else null
        val metFrac = daily.metLatest?.let {
            ((it - MET_REST) / (MET_VIGOROUS - MET_REST)).coerceIn(0f, 1f) * MET_WEIGHT
        }
        val arousal = listOfNotNull(hrFrac, metFrac).maxOrNull()
        // Center MIRRORS current arousal (D14): calm body → calm music, no matter how recovered.
        // Resting (HR at rest, no movement) → a calm-but-awake baseline; rising HR/movement lifts it.
        // No live signal at all → a neutral center.
        val center = if (arousal != null) {
            (RESTING_CENTER + arousal * AROUSAL_SPAN).coerceIn(0f, 1f)
        } else {
            0.5f
        }
        val viaMovement = metFrac != null && metFrac == arousal && (hrFrac == null || metFrac > hrFrac)

        // NOTE: Oura exposes no intra-day stress (only a whole-day `day_summary` — see the OpenAPI
        // spec in docs/reference), so stress is not a signal here at all: a day-level label can't
        // track "now" and would pin energy until midnight. HR + movement carry the live state.

        val syncedAt = source?.atMillis ?: daily.fetchedAt
        // Prefer the session's intra-session HRV when it's the source, else the overnight HRV.
        val hrv = if (source?.viaSession == true) latestSession?.avgHrv ?: daily.hrvMs else daily.hrvMs

        return BiometricState(
            energyCenter = center,
            energyBand = baseBand,
            valenceFree = true, // D15
            source = BiometricState.Source.OURA,
            syncedAt = syncedAt,
            diagnostics = BiometricState.Diagnostics(
                readinessScore = readiness,
                readinessFresh = readinessFresh,
                restingHr = restingHr,
                latestHr = source?.bpm,
                hrvMs = hrv,
                arousal = arousal,
                note = listOfNotNull(
                    if (source?.viaSession == true) "via session (${latestSession?.type ?: "?"})" else null,
                    if (viaMovement) "via movement (MET %.1f)".format(daily.metLatest) else null,
                ).joinToString("; ").ifBlank { null },
            ),
        )
    }

    /** The chosen current-HR reading: latest sample vs. session HR, whichever is more recent. */
    private data class HrSource(val bpm: Int, val atMillis: Long, val viaSession: Boolean)

    private fun chooseHrSource(
        latestHr: OuraHeartRateEntity?,
        latestSession: OuraSessionEntity?,
    ): HrSource? {
        val sampleSource = latestHr?.let { hr ->
            parseIsoMillis(hr.timestamp)?.let { HrSource(hr.bpm, it, viaSession = false) }
        }
        val sessionSource = latestSession?.let { s ->
            val bpm = s.lastHr
            val at = s.atMillis
            if (bpm != null && at != null) HrSource(bpm, at, viaSession = true) else null
        }
        return listOfNotNull(sampleSource, sessionSource).maxByOrNull { it.atMillis }
    }

    /** Reduce a session document to the compact cache row orbn actually uses. */
    private fun Session.toEntity(fetchedAt: Long): OuraSessionEntity? {
        val sessionId = id ?: return null
        val lastHr = heartRate?.items?.lastOrNull { it != null }?.roundToInt()
        val hrvValues = heartRateVariability?.items?.filterNotNull()
        val avgHrv = hrvValues?.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
        val atMillis = parseIsoMillis(endDatetime) ?: parseIsoMillis(startDatetime)
        return OuraSessionEntity(
            id = sessionId,
            type = type,
            lastHr = lastHr,
            avgHrv = avgHrv,
            atMillis = atMillis,
            fetchedAt = fetchedAt,
        )
    }

    private fun parseIsoMillis(ts: String?): Long? =
        ts?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

    private companion object {
        const val TAG = "OrbnOura"
        /** Freshness gate: don't re-fetch within this window (≈ Oura's 5-min HR cadence). */
        const val DEFAULT_STALE_MILLIS = 5 * 60 * 1000L
        const val HR_WINDOW_HOURS = 6L
        /**
         * BPM above resting that reads as fully activated. Sized to the *daytime* range (rest → brisk
         * activity), not max HR — so everyday HR changes are expressive rather than pinned to the
         * bottom of the scale. This is the main sensitivity knob for the energy readout.
         */
        const val DAYTIME_HR_SPAN = 40f
        /** Max upward energy shift from a fully elevated heart rate / movement. */
        const val RESTING_CENTER = 0.30f // energy center at rest (no arousal) — calm but awake
        const val AROUSAL_SPAN = 0.55f   // how far live arousal lifts the center (0.30 .. 0.85)
        /** MET endpoints for mapping current movement to a 0..1 activation fraction. */
        const val MET_REST = 1f       // ~sitting/quiet
        const val MET_VIGOROUS = 8f   // brisk/vigorous activity → full activation
        /** Movement's vote in the HR-leaning max: pure movement reads gentler than HR elevation. */
        const val MET_WEIGHT = 0.6f
        val ISO: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    }
}
