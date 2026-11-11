package com.daviddokakis.karoointerval

import io.hammerhead.karooext.models.DataType

/** How the big watts are smoothed: Karoo's instant, 3 s, 5 s or 10 s average power. */
enum class Smoothing(val label: Int, val power: String) {
    INSTANT(R.string.smoothing_instant, DataType.Type.POWER),
    S3(R.string.smoothing_3s, DataType.Type.SMOOTHED_3S_AVERAGE_POWER),
    S5(R.string.smoothing_5s, DataType.Type.SMOOTHED_5S_AVERAGE_POWER),
    S10(R.string.smoothing_10s, DataType.Type.SMOOTHED_10S_AVERAGE_POWER),
}

/**
 * What the top row shows: heart rate, cadence or the target. AUTO is the layout's own choice
 * (heart rate above the big number, the target above the gauge). Whatever isn't at the top
 * goes in a tile.
 */
enum class TopRow(val label: Int) {
    AUTO(R.string.top_auto),
    HR(R.string.top_hr),
    CADENCE(R.string.top_cadence),
    TARGET(R.string.top_target),
}

/** Totals of a finished workout, for the "Workout complete" summary. */
data class WorkoutSummary(
    val timeSec: Int,
    val avgPower: Int?,
    val np: Int?,
    val avgHr: Int?,
    /**
     * Heart rate drift (aerobic decoupling, Pw:HR) in %: how much the watts per heartbeat fell
     * from the first half of the workout to the second. Null for workouts under 20 minutes or
     * without power and heart rate.
     */
    val driftPct: Double? = null,
)
