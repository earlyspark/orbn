package com.earlyspark.orbn.oura

import android.util.Log
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
 *  - **Daily baseline** comes from Oura's already-personalized readiness score → an energy center
 *    and band. Low recovery → lower, narrower (mirror, D14).
 *  - **Intra-day nudge** is heart-rate-reserve style: how far the current HR sits above the user's
 *    resting HR, scaled by a personal HR span. Raw bpm is never compared across people.
 *  - **Current HR** is the latest 5-min daytime sample, or — when more recent — the heart rate
 *    from a logged session (a higher-fidelity on-demand read).
 *  - High daily stress caps the energy (don't push a stressed body).
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

            val readiness = api.getDailyReadiness(startDate, endDate).maxByOrNull { it.day ?: "" }
            val dailySleep = api.getDailySleep(startDate, endDate).maxByOrNull { it.day ?: "" }
            val sleep = api.getSleepPeriods(startDate, endDate).maxByOrNull { it.day ?: "" }
            val stress = api.getDailyStress(startDate, endDate).maxByOrNull { it.day ?: "" }
            val heartRates = api.getHeartRate(startDateTime, endDateTime)
            val sessions = api.getSessions(startDate, endDate)

            val fetchedAt = System.currentTimeMillis()
            val day = readiness?.day ?: sleep?.day ?: dailySleep?.day ?: today.toString()

            dao.upsertDaily(
                listOf(
                    OuraDailyEntity(
                        day = day,
                        readinessScore = readiness?.score,
                        sleepScore = dailySleep?.score,
                        restingHr = sleep?.lowestHeartRate,
                        hrvMs = sleep?.averageHrv,
                        stressSummary = stress?.daySummary,
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
        // Daily baseline from readiness (mirror, D14). Missing → neutral.
        val readiness = daily.readinessScore
        val baseCenter: Float
        val baseBand: Float
        if (readiness != null) {
            val r = readiness.coerceIn(0, 100) / 100f
            baseCenter = 0.25f + r * 0.45f // 0.25 (depleted) .. 0.70 (fully recovered)
            baseBand = 0.12f + r * 0.18f   // 0.12 (narrow) .. 0.30 (wide)
        } else {
            baseCenter = 0.5f
            baseBand = 0.25f
        }

        // Choose the current-HR source: the latest 5-min sample, or a session's heart rate when
        // the session is more recent (higher fidelity, on-demand). Mirror applies either way —
        // a calm breathing session reads low HR → calm music.
        val source = chooseHrSource(latestHr, latestSession)

        // Intra-day arousal = heart-rate-reserve fraction above resting (D18).
        val restingHr = daily.restingHr
        var arousal: Float? = null
        var center = baseCenter
        if (source != null && restingHr != null) {
            val span = (DEFAULT_MAX_HR - restingHr).coerceAtLeast(1)
            val frac = ((source.bpm - restingHr).toFloat() / span).coerceIn(0f, 1f)
            arousal = frac
            center = (baseCenter + frac * AROUSAL_WEIGHT).coerceIn(0f, 1f)
        }

        // Stress is a HIGH-energy state (tense/keyed-up), NOT a calm one — so mirroring it pushes
        // energy UP, not down (corrects the earlier mislabel; see F-finding / D14 stress handling).
        // Floored so a stressful day lands energetic even when the resting HR is low. Valence stays
        // free (D15) — sad music is low-energy, so a high target naturally squeezes it out.
        val stressful = daily.stressSummary.equals("stressful", ignoreCase = true)
        if (stressful) {
            center = maxOf(center, STRESS_ENERGY_FLOOR)
        }

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
                restingHr = restingHr,
                latestHr = source?.bpm,
                hrvMs = hrv,
                stressSummary = daily.stressSummary,
                arousal = arousal,
                note = listOfNotNull(
                    if (source?.viaSession == true) "via session (${latestSession?.type ?: "?"})" else null,
                    if (stressful) "stress → energy boosted" else null,
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
        /** Cold-start ceiling for the HRR denominator until the user's own HR history refines it (M5). */
        const val DEFAULT_MAX_HR = 190
        /** Max upward energy shift from a fully elevated heart rate. */
        const val AROUSAL_WEIGHT = 0.25f
        /** Energy floor on high-stress days — stress is a keyed-up state, so mirror it energetic. */
        const val STRESS_ENERGY_FLOOR = 0.75f
        val ISO: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    }
}
