package com.earlyspark.orbn.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The one-line biometric status string (`energy 0.65 · readiness 81 · synced 2:56 PM`), shown on the
 * home screen AND in the visualizer — kept in one place so the two never drift.
 */
fun biometricReadout(state: BiometricState?): String {
    if (state == null) return "Oura connected · tap to sync"
    val energy = "energy %.2f".format(state.energyCenter)
    val readiness = state.diagnostics.readinessScore?.let { "readiness $it" }
    val synced = state.syncedAt?.let { "synced ${syncedAtLabel(it)}" }
    return listOfNotNull(energy, readiness, synced).joinToString("  ·  ")
}

/**
 * Absolute local-clock time of the freshest Oura datum (its real timestamp, not orbn's fetch time).
 * Shown as wall-clock time so it never goes stale on screen; the date is added only when the data
 * isn't from today, so it can't be misread as recent.
 */
private fun syncedAtLabel(ts: Long): String {
    val zone = ZoneId.systemDefault()
    val dt = Instant.ofEpochMilli(ts).atZone(zone)
    val time = dt.format(DateTimeFormatter.ofPattern("h:mm a"))
    return if (dt.toLocalDate() == LocalDate.now(zone)) time
    else dt.format(DateTimeFormatter.ofPattern("MMM d, h:mm a"))
}
