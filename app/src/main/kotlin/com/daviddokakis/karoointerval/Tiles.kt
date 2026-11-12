package com.daviddokakis.karoointerval

/**
 * What a workout-screen tile can show (Settings › Lewy / Prawy kafelek, from BetterIntervals 0.10).
 * A tile goes through the metrics switched on for it, one per tap; with none switched on it's left
 * out and the rest takes the room. [label] is the tile's own (short), [setting] the switch's.
 */
enum class TileMetric(val label: Int, val setting: Int) {
    LAP_AVG(R.string.lap_avg, R.string.metric_lap_avg),
    LAP_NP(R.string.np, R.string.metric_lap_np),
    RIDE_AVG(R.string.ride_avg, R.string.metric_ride_avg),
    TARGET(R.string.target, R.string.metric_target),
    TARGET_RANGE(R.string.target_range, R.string.metric_target_range),
    HEART_RATE(R.string.hr, R.string.metric_hr),
    CADENCE(R.string.cadence, R.string.metric_cadence),
    CALORIES(R.string.calories, R.string.metric_calories),
    SPEED(R.string.speed, R.string.metric_speed),
    ;

    val isTarget: Boolean get() = this == TARGET || this == TARGET_RANGE

    companion object {
        /** What each tile did before it could be set: lap avg ↔ lap NP, target ↔ target range. */
        val LEFT_DEFAULT = listOf(LAP_AVG, LAP_NP)
        val RIGHT_DEFAULT = listOf(TARGET, TARGET_RANGE)

        /** "LAP_AVG,LAP_NP" ↔ the list (in the enum's order); "" is a tile switched off. */
        fun parse(value: String?): List<TileMetric>? = value?.let { v ->
            val names = v.split(',').filter { it.isNotBlank() }.toSet()
            entries.filter { it.name in names }
        }

        fun format(list: List<TileMetric>): String = list.joinToString(",") { it.name }
    }
}
