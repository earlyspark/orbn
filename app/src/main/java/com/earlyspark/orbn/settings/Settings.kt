package com.earlyspark.orbn.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * User-facing toggles, persisted in the shared "orbn_ui" SharedPreferences store (the same file
 * QueueBuilder uses for the manual mood). Both default to off. Callers re-read on resume —
 * returning from the settings screen always passes through the caller's onResume, so no reactive
 * plumbing is needed.
 */
object Settings {

    private const val KEY_REDUCE_MOTION = "reduce_motion"
    private const val KEY_SUPPRESS_VIZ_DRAWERS = "suppress_viz_drawers"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences("orbn_ui", Context.MODE_PRIVATE)

    /** Don't animate the homepage: stops the mascot's idle loops and the pulse/wobble reactions. */
    fun reduceMotion(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REDUCE_MOTION, false)

    fun setReduceMotion(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_REDUCE_MOTION, value).apply()
    }

    /** Ignore the visualizer's three sheet-opening swipes (mood / history / why-this-track). */
    fun suppressVizDrawers(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SUPPRESS_VIZ_DRAWERS, false)

    fun setSuppressVizDrawers(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SUPPRESS_VIZ_DRAWERS, value).apply()
    }
}
