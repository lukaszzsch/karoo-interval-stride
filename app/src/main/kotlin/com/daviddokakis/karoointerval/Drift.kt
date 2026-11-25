package com.daviddokakis.karoointerval

/**
 * Heart rate drift (aerobic decoupling, Pw:HR) as it builds up: one (watts, bpm) pair per power
 * reading (about a second). [value] compares the watts per beat of the first half with the
 * second half, in %: positive when the heart worked harder for the same power later on.
 * Running sums make [value] cheap enough to read every second on a long ride.
 */
class Drift(private val minSamples: Int = MIN_SAMPLES) {
    private var power = DoubleArray(INITIAL)
    private var hr = DoubleArray(INITIAL)
    // Running sums: power[i] / hr[i] hold the totals of samples 0 until i (exclusive), [0] = 0.
    var size = 0
        private set

    fun add(watts: Double, bpm: Double) {
        if (size + 1 >= power.size) {
            power = power.copyOf(power.size * 2)
            hr = hr.copyOf(hr.size * 2)
        }
        power[size + 1] = power[size] + watts
        hr[size + 1] = hr[size] + bpm
        size++
    }

    fun reset() {
        size = 0
    }

    /** In %; null until there are [minSamples] (20 minutes) or without power and heart rate. */
    fun value(): Double? {
        if (size < minSamples) return null
        val half = size / 2
        fun ratio(from: Int, to: Int): Double? {
            val p = power[to] - power[from]
            val h = hr[to] - hr[from]
            return if (p > 0 && h > 0) p / h else null
        }
        val first = ratio(0, half) ?: return null
        val second = ratio(half, size) ?: return null
        return (first - second) / first * 100.0
    }

    companion object {
        /** At least 20 minutes of power with heart rate (one sample a second). */
        const val MIN_SAMPLES = 20 * 60
        private const val INITIAL = 4096
    }
}

/** "3,2%" (Polish decimal comma). */
fun formatDrift(pct: Double): String = String.format(java.util.Locale.forLanguageTag("pl"), "%.1f%%", pct)
