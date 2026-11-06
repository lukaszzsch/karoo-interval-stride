package com.daviddokakis.karoointerval

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Normalised power for one lap: the 30-second rolling average of power, raised to the 4th power,
 * averaged, then the 4th root. Shows nothing until the lap has 30 s of data.
 */
class LapNp {
    private val window = ArrayDeque<Pair<Long, Double>>()
    private var windowSum = 0.0
    private var startMs = -1L
    private var sum4 = 0.0
    private var count = 0

    /** Current lap NP in watts, or null in the first 30 s of a lap. */
    var value: Int? = null
        private set

    fun reset() {
        window.clear()
        windowSum = 0.0
        startMs = -1L
        sum4 = 0.0
        count = 0
        value = null
    }

    fun add(nowMs: Long, watts: Double) {
        if (startMs < 0) startMs = nowMs
        window.addLast(nowMs to watts)
        windowSum += watts
        while (window.first().first <= nowMs - WINDOW_MS) windowSum -= window.removeFirst().second
        if (nowMs - startMs >= WINDOW_MS - 1000) {
            sum4 += (windowSum / window.size).pow(4)
            count++
            value = (sum4 / count).pow(0.25).roundToInt()
        }
    }

    private companion object {
        const val WINDOW_MS = 30_000L
    }
}
