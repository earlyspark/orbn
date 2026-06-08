package com.earlyspark.orbn.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The one-line biometric status string, shown on the home screen AND in the visualizer — kept in one
 * place so the two never drift. Plain language (D24): `feeling balanced · well recovered · synced
 * 2:56 PM`, not raw numbers. The raw energy/valence numbers live only in the "why this track" detail.
 */
fun biometricReadout(state: BiometricState?, connected: Boolean = true): String {
    // No state yet: distinguish "authorized but nothing synced" from "never connected" so the
    // visualizer overlay can't claim a connection the user never made (matches the home line).
    if (state == null) return if (connected) "Oura connected · tap to sync" else "tap to connect Oura"
    val energy = "feeling ${energyWord(state.energyCenter)}"
    val readiness = readinessWord(state.diagnostics.readinessScore)
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
