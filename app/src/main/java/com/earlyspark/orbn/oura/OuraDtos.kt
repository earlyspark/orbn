package com.earlyspark.orbn.oura

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire types for the Oura OAuth token endpoint and v2 `usercollection` endpoints.
 *
 * All fields are nullable with defaults and parsing ignores unknown keys (see [OuraApiClient]),
 * so the app tolerates Oura adding/renaming fields and partial responses without crashing. The
 * exact inner field names are taken from the canonical docs and a maintained community client;
 * any drift surfaces as a null here rather than an exception, and is verified against live data.
 */

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("token_type") val tokenType: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    @SerialName("scope") val scope: String? = null,
)

/** Common paginated envelope: `{ "data": [...], "next_token": ... }`. */
@Serializable
data class OuraEnvelope<T>(
    val data: List<T> = emptyList(),
    @SerialName("next_token") val nextToken: String? = null,
)

@Serializable
data class DailyReadiness(
    val id: String? = null,
    val day: String? = null,
    val score: Int? = null,
    val timestamp: String? = null,
    val contributors: ReadinessContributors? = null,
)

@Serializable
data class ReadinessContributors(
    @SerialName("resting_heart_rate") val restingHeartRate: Int? = null,
    @SerialName("hrv_balance") val hrvBalance: Int? = null,
    @SerialName("recovery_index") val recoveryIndex: Int? = null,
    @SerialName("body_temperature") val bodyTemperature: Int? = null,
)

@Serializable
data class DailySleep(
    val id: String? = null,
    val day: String? = null,
    val score: Int? = null,
    val timestamp: String? = null,
)

/**
 * Detailed sleep period — carries the HRV (`average_hrv`, rmssd-based, ms) and the lowest
 * heart rate of the night, which orbn uses as the resting-HR baseline for HRR normalization.
 */
@Serializable
data class SleepPeriod(
    val id: String? = null,
    val day: String? = null,
    @SerialName("average_heart_rate") val averageHeartRate: Double? = null,
    @SerialName("average_hrv") val averageHrv: Int? = null,
    @SerialName("lowest_heart_rate") val lowestHeartRate: Int? = null,
    @SerialName("bedtime_start") val bedtimeStart: String? = null,
    @SerialName("bedtime_end") val bedtimeEnd: String? = null,
)

@Serializable
data class HeartRateSample(
    val bpm: Int? = null,
    val source: String? = null,
    val timestamp: String? = null,
)

/**
 * A logged "Moment" (breathing / meditation / nap / rest) the user started in the Oura app.
 * Unlike the coarse 5-min daytime heart rate, a session carries true [heartRate] and
 * [heartRateVariability] time series for its window — a higher-fidelity, on-demand read of where
 * the body is right now. Only exists when the user actually starts a session.
 */
@Serializable
data class Session(
    val id: String? = null,
    val day: String? = null,
    @SerialName("start_datetime") val startDatetime: String? = null,
    @SerialName("end_datetime") val endDatetime: String? = null,
    val type: String? = null,
    val mood: String? = null,
    @SerialName("heart_rate") val heartRate: SampleData? = null,
    @SerialName("heart_rate_variability") val heartRateVariability: SampleData? = null,
)

/**
 * Oura time-series envelope: [items] sampled every [interval] seconds starting at [timestamp].
 * Items may contain nulls (gaps), so the list element is nullable.
 */
@Serializable
data class SampleData(
    val interval: Double? = null,
    val items: List<Double?> = emptyList(),
    val timestamp: String? = null,
)

/**
 * Daily activity — a day-level summary that also carries genuine intra-day movement: [met] is a
 * ~1-min MET (metabolic-equivalent) time series, and [class5Min] is a string of per-5-min activity
 * classes (0 non-wear .. 5 high). The latest MET is orbn's closest-to-realtime movement signal.
 */
@Serializable
data class DailyActivity(
    val day: String? = null,
    val steps: Int? = null,
    @SerialName("class_5_min") val class5Min: String? = null,
    val met: SampleData? = null,
)
