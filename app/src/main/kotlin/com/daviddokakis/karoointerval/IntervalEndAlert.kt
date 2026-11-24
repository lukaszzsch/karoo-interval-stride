package com.daviddokakis.karoointerval

/**
 * How the last seconds of an interval are signalled (Settings › Koniec interwału): beeps in the
 * last 3 s (as before), the clock flashing amber in the last 5 s (for riding with headphones),
 * or both.
 */
enum class IntervalEndAlert(val label: Int) {
    SOUND(R.string.end_alert_sound),
    FLASH(R.string.end_alert_flash),
    BOTH(R.string.end_alert_both),
    ;

    val beeps: Boolean get() = this != FLASH
    val flashes: Boolean get() = this != SOUND

    companion object {
        /** The clock flashes for this many seconds before the interval ends. */
        const val FLASH_SECONDS = 5
    }
}
