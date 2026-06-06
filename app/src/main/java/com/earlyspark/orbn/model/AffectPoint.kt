package com.earlyspark.orbn.model

/**
 * A point on the shared affect plane (valence × energy), both 0..1.
 *  - valence = pleasantness (sad ↔ happy)
 *  - energy  = activation / intensity (calm ↔ energetic)
 *
 * Both tracks and the biometric target live in this space; matching compares them here.
 */
data class AffectPoint(
    val valence: Float,
    val energy: Float,
)
