package com.daviddokakis.karoointerval

import android.os.SystemClock
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * DEBUG BUILDS ONLY (src/debug): made-up live data so the screens can be judged without riding.
 * Values change every 3 seconds. Release builds use the pass-through in src/release instead, so
 * none of this ships.
 */
object DemoData {
    private const val STEP_MS = 3_000L
    private val startedAt = SystemClock.elapsedRealtime()

    /** Returns [s] with power, heart rate, lap and interval progress replaced by demo values. */
    fun apply(s: ScreenState): ScreenState {
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        val tick = (elapsedMs / STEP_MS).toInt()

        // Watts step through 280–325 W around a 300 W target: below, in and above the range.
        val watts = WATTS_CYCLE[tick % WATTS_CYCLE.size]
        // Heart rate climbs from 105 to 190 bpm and starts over, passing through every zone.
        val hr = 105 + (tick * 4) % 86
        // Lap: 10-minute laps, with the average drifting a little.
        val lapSec = (elapsedMs / 1000).toInt()
        val lapAvg = (301 + 4 * sin(tick * 0.3)).roundToInt()

        // With a workout loaded: make the current interval 5 min at 300 W (range 293–308 W, like
        // intervals.icu's ±2.5%), halfway done, then let it run on.
        val plan = s.plan?.let { p ->
            p.copy(steps = p.steps.mapIndexed { i, st -> if (i == s.stepIndex) DEMO_STEP else st })
        }
        val step = plan?.steps?.getOrNull(s.stepIndex)
        val elapsedInStep = if (step != null && step.durationSec > 1) {
            val half = step.durationSec / 2
            half + (lapSec % (step.durationSec - half))
        } else {
            s.elapsedInStepSec
        }

        return s.copy(
            plan = plan,
            elapsedInStepSec = elapsedInStep,
            power3s = watts,
            heartRate = hr,
            lapAvg = lapAvg,
            // NP a bit above the average, as with surging power; "--" in the lap's first 30 s.
            lapNp = if (lapSec % 600 < 30) null else lapAvg + 7,
            freeLapNp = if (lapSec % 600 < 30) null else lapAvg + 7,
            lapNumber = 3 + lapSec / 600,
            lapTimeSec = lapSec % 600,
            lapPowerAvg = lapAvg,
            lastLapTimeSec = 500,
            lastLapPowerAvg = 271,
            // Lap average HR drifts a little below the current one; last lap 142 bpm.
            lapHrAvg = 138 + (tick % 7),
            lastLapHrAvg = 142,
            hrZones = s.hrZones.ifEmpty { DEFAULT_HR_ZONES },
        )
    }

    private val WATTS_CYCLE = listOf(280, 288, 295, 300, 306, 312, 320, 325, 310, 298, 290)
    private val DEMO_STEP = PlanStep(300, 300, 300, rangeLow = 293, rangeHigh = 308)

    /** Used when the Karoo profile has no heart rate zones. */
    private val DEFAULT_HR_ZONES = listOf(0..122, 123..142, 143..160, 161..175, 176..220)
}
