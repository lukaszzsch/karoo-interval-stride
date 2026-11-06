package com.daviddokakis.karoointerval

import kotlin.math.roundToInt

/**
 * One step of the workout.
 * - [ramp] true: target moves from [startWatts] to [endWatts] over the step.
 * - otherwise a single target ([startWatts] == [endWatts]).
 * [rangeLow]..[rangeHigh] is the "in range" band for a non-ramp step (Stride sends one
 * for every target); [isRange] marks steps that were *written* as a range, e.g. 80–85%.
 */
data class PlanStep(
    val durationSec: Int,
    val startWatts: Int,
    val endWatts: Int,
    val ramp: Boolean = false,
    val rangeLow: Int? = null,
    val rangeHigh: Int? = null,
    val isRange: Boolean = false,
    /** Coach's note for the step (Stride segment text), e.g. "Dwuminutówka 1". */
    val note: String? = null,
    /** Target cadence in rpm, if the step has one. */
    val cadenceLow: Int? = null,
    val cadenceHigh: Int? = null,
) {
    /** "85–90 rpm", or null when the step has no cadence target. */
    val cadenceText: String?
        get() = when {
            cadenceLow != null && cadenceHigh != null && cadenceLow != cadenceHigh -> "$cadenceLow\u2013$cadenceHigh rpm"
            cadenceLow != null -> "$cadenceLow rpm"
            cadenceHigh != null -> "$cadenceHigh rpm"
            else -> null
        }

    val avgWatts: Int get() = (startWatts + endWatts) / 2
    val isRamp: Boolean get() = ramp && startWatts != endWatts

    /** Target at [elapsedSec] into the step. */
    fun targetAt(elapsedSec: Int): Int = if (isRamp && durationSec > 0) {
        val f = elapsedSec.coerceIn(0, durationSec).toDouble() / durationSec
        (startWatts + (endWatts - startWatts) * f).roundToInt()
    } else {
        avgWatts
    }

    /** Acceptable range at [elapsedSec]: the step's own range, or ±[TOLERANCE] around the target. */
    fun rangeAt(elapsedSec: Int): IntRange {
        if (!isRamp && rangeLow != null && rangeHigh != null) return minOf(rangeLow, rangeHigh)..maxOf(rangeLow, rangeHigh)
        val t = targetAt(elapsedSec)
        val tol = maxOf(5, (t * TOLERANCE).roundToInt())
        return (t - tol)..(t + tol)
    }

    companion object {
        /** Same idea as intervals.icu's default power range for outdoor workouts. */
        const val TOLERANCE = 0.03
    }
}

/** A workout loaded from Stride, run by this app (not by Karoo's workout player). */
data class WorkoutPlan(
    val id: String,
    val name: String,
    val steps: List<PlanStep>,
    val ftp: Int?,
) {
    val totalSec: Int = steps.sumOf { it.durationSec }
    fun startSecOf(index: Int): Int = steps.take(index).sumOf { it.durationSec }
    val maxWatts: Int = steps.maxOfOrNull { maxOf(it.startWatts, it.endWatts) } ?: 0
}
