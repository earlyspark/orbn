package com.earlyspark.orbn.oura

import android.util.Log
import com.earlyspark.orbn.data.OuraDailyEntity
import com.earlyspark.orbn.data.OuraDao
import com.earlyspark.orbn.data.OuraHeartRateEntity
import com.earlyspark.orbn.data.OuraSessionEntity
import com.earlyspark.orbn.data.OuraStressObsEntity
import com.earlyspark.orbn.model.BiometricState
import com.earlyspark.orbn.model.BodyTimeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
 *  - Oura's daytime stress contributes a small, decaying *delta* lean (StressSignal, amends F6):
 *    the day-cumulative counters advance on each ring sync, so the change between observations —
 *    when attributable to its window — leans the center intense (stress) or calm (recovery).
 *    It is a capped secondary vote; the day-level `day_summary` label remains unused.
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

    /**
     * Ticks (the fetch's epoch millis) whenever a refresh lands new data in the cache. UI layers
     * collect this and repaint from cache, so the readout stays current no matter which path ran
     * the fetch — without it, the home line went stale when PlaybackService's track-boundary
     * refresh won the dedupe race and finished after the activity had already painted.
     */
    private val _refreshCompletedAt = MutableStateFlow(0L)
    val refreshCompletedAt: StateFlow<Long> = _refreshCompletedAt

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

            // Daytime-stress counters for the delta signal (StressSignal, amends F6). A supporting
            // input only, so a failed fetch never fails the refresh — the previous state carries
            // and its lean decays out. (Counter history is QA-inspectable via `oura_stress_obs`.)
            val stressDoc = runCatching { api.getDailyStress(startDate, endDate) }
                .onFailure { Log.w(TAG, "daily_stress fetch failed (isolated): $it") }
                .getOrNull()?.maxByOrNull { it.day ?: "" }

            val readiness = api.getDailyReadiness(startDate, endDate).maxByOrNull { it.day ?: "" }
            val dailySleep = api.getDailySleep(startDate, endDate).maxByOrNull { it.day ?: "" }
            val sleep = api.getSleepPeriods(startDate, endDate).maxByOrNull { it.day ?: "" }
            val heartRates = api.getHeartRate(startDateTime, endDateTime)
            val sessions = api.getSessions(startDate, endDate)
            // The current *Oura* day (their day runs 04:00 → 04:00): between local midnight and
            // 4 AM the API already serves tomorrow's daily_activity document (met anchored at a
            // future 4 AM, items empty), so a naive max-by-day picks an empty doc and the
            // elapsed-time index goes negative. Select the doc for the Oura day instead.
            val ouraDay = (if (now.hour < 4) today.minusDays(1) else today).toString()
            val activity = api.getDailyActivity(startDate, endDate)
                .filter { it.day != null && it.day!! <= ouraDay }
                .maxByOrNull { it.day ?: "" }

            val fetchedAt = System.currentTimeMillis()
            val day = readiness?.day ?: sleep?.day ?: dailySleep?.day ?: today.toString()

            // Difference the stress counters against the previous observation (cached row).
            val prevDaily = dao.latestDaily()
            val stressOutcome = StressSignal.update(
                prev = prevDaily?.let {
                    StressSignal.State(
                        stressHighSec = it.stressHighSec,
                        recoveryHighSec = it.recoveryHighSec,
                        changedAt = it.stressChangedAt,
                        nudge = it.stressNudge,
                        nudgeAt = it.stressNudgeAt,
                    )
                },
                prevDay = prevDaily?.day,
                day = stressDoc?.day,
                stressHighSec = stressDoc?.stressHigh,
                recoveryHighSec = stressDoc?.recoveryHigh,
                now = fetchedAt,
            )
            val stressState = stressOutcome.state
            // Record every counter movement — the delta history behind the body-timeline bands.
            stressOutcome.observation?.let { o ->
                dao.insertStressObs(
                    OuraStressObsEntity(
                        observedAt = o.observedAt,
                        day = stressDoc?.day ?: today.toString(),
                        stressHighSec = stressState.stressHighSec ?: 0L,
                        recoveryHighSec = stressState.recoveryHighSec ?: 0L,
                        dStressSec = o.dStressSec,
                        dRecoverySec = o.dRecoverySec,
                        windowStartAt = o.windowStartAt,
                        attributable = o.attributable,
                    )
                )
            }

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

            // Body timeline: persist the day-so-far MET series, downsampled 1-min → 5-min means.
            // Trim at "now" (same elapsed-index logic as metLatest) so the pre-sized trailing
            // filler (F16) never lands in the cache; "-" marks an empty bucket (all-null minutes).
            val metSeriesCsv = metSeries?.items?.let { items ->
                val upTo = elapsedMin?.let { (it + 1).coerceIn(0, items.size) } ?: items.size
                items.take(upTo).chunked(5) { chunk ->
                    val real = chunk.filterNotNull()
                    if (real.isEmpty()) "-" else "%.2f".format(real.average())
                }.joinToString(",")
            }
            // Never clobber a stored series with emptiness (a thin/odd API response must not
            // destroy the day's accumulated movement history — bitten live at 01:03 on 06-11).
            val keepPrevSeries = metSeriesCsv.isNullOrBlank() && prevDaily?.day == day

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
                        stressHighSec = stressState.stressHighSec,
                        recoveryHighSec = stressState.recoveryHighSec,
                        stressChangedAt = stressState.changedAt,
                        stressNudge = stressState.nudge,
                        stressNudgeAt = stressState.nudgeAt,
                        metSeriesStart = if (keepPrevSeries) prevDaily?.metSeriesStart else metSeries?.timestamp,
                        metSeries = if (keepPrevSeries) prevDaily?.metSeries else metSeriesCsv,
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
            _refreshCompletedAt.value = fetchedAt // cache updated → tell UI collectors to repaint
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

    /**
     * Assemble today's [BodyTimeline] purely from the local cache (no network): HR samples since
     * local midnight, the persisted 5-min MET series, and stress/recovery bands placed from the
     * attributable delta history. Null when there is nothing at all to draw.
     */
    suspend fun bodyTimeline(): BodyTimeline? = withContext(Dispatchers.IO) {
        // The window runs on Oura's day (04:00 → 04:00 next day): the MET series is anchored at
        // 4 AM, so a midnight-anchored window left the movement lane starting 4 h into the plot.
        // Before 4 AM the current Oura day is still yesterday's.
        val zone = java.time.ZoneId.systemDefault()
        val nowDt = java.time.LocalDateTime.now()
        val today = if (nowDt.hour < 4) LocalDate.now().minusDays(1) else LocalDate.now()
        val startMillis = today.atTime(4, 0).atZone(zone).toInstant().toEpochMilli()
        val nowMillis = System.currentTimeMillis()

        // HR timestamps are stored as the API sent them (UTC ISO), so query in UTC strings —
        // lexicographic range works within a consistent format.
        val utc = java.time.ZoneOffset.UTC
        val utcFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        val fromIso = java.time.Instant.ofEpochMilli(startMillis).atZone(utc).format(utcFmt)
        val toIso = java.time.Instant.ofEpochMilli(nowMillis).atZone(utc).format(utcFmt)
        val hr = dao.heartRateBetween(fromIso, toIso).mapNotNull { sample ->
            parseIsoMillis(sample.timestamp)?.let { BodyTimeline.HrPoint(it, sample.bpm) }
        }

        val daily = dao.daily(today.toString())
        val metStart = parseIsoMillis(daily?.metSeriesStart)
        val met = if (metStart != null && !daily?.metSeries.isNullOrBlank()) {
            daily!!.metSeries!!.split(',').mapIndexedNotNull { i, v ->
                v.toFloatOrNull()?.let { BodyTimeline.MetPoint(metStart + i * 5 * 60_000L, it) }
            }
        } else emptyList()

        val bands = dao.stressObsForDay(today.toString())
            .filter { it.attributable }
            .flatMap { BodyTimeline.placeBands(it.dStressSec, it.dRecoverySec, it.observedAt) }
            .filter { it.endMillis > startMillis }

        val timeline = BodyTimeline(startMillis, nowMillis, hr, met, bands)
        if (timeline.isEmpty) null else timeline
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
        // Daytime-stress delta lean (StressSignal, amends F6): a capped, decaying secondary vote —
        // stress accrued since the last sync leans the center intense, recovery leans it calm
        // (mirroring, D14). Zero whenever the signal abstains (no delta, backfill smear, stale).
        val stressLean = StressSignal.lean(
            daily.stressNudge, daily.stressNudgeAt, System.currentTimeMillis(),
        )
        // Center MIRRORS current arousal (D14): calm body → calm music, no matter how recovered.
        // Resting (HR at rest, no movement) → a calm-but-awake baseline; rising HR/movement lifts it.
        // No live signal at all → a neutral center. The stress lean nudges either case.
        val center = if (arousal != null) {
            (RESTING_CENTER + arousal * AROUSAL_SPAN + stressLean).coerceIn(0f, 1f)
        } else {
            (0.5f + stressLean).coerceIn(0f, 1f)
        }
        val viaMovement = metFrac != null && metFrac == arousal && (hrFrac == null || metFrac > hrFrac)

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
                    if (stressLean != 0f) "stress lean %+.2f".format(stressLean) else null,
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
