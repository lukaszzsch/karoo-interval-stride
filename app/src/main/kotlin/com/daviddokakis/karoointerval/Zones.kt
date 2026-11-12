package com.daviddokakis.karoointerval

import androidx.annotation.ColorRes

/** Power zones as % of FTP. */
object Zones {
    /** Zone number (intervals.icu style, used in labels like "Z4"). */
    fun zoneOf(watts: Int, ftp: Int?): Int? {
        if (ftp == null || ftp <= 0) return null
        val pct = watts * 100 / ftp
        return when {
            pct <= 55 -> 1
            pct <= 75 -> 2
            pct <= 90 -> 3
            pct <= 105 -> 4
            else -> 5
        }
    }

    /**
     * The graphs' extra contrast against the page: +15 % (BetterIntervals' pick, 2026-11-11), since
     * the Karoo's screen shows colours lighter than they're drawn.
     */
    const val CONTRAST = 0.15f

    /** [c] with more contrast against the page: more saturated, darker on white, brighter on black. */
    fun boosted(c: Int, night: Boolean, amount: Float = CONTRAST): Int {
        if (amount == 0f) return c
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(c, hsv)
        hsv[1] = (hsv[1] * (1 + amount * 1.5f)).coerceAtMost(1f)
        hsv[2] = if (night) (hsv[2] * (1 + amount * 0.6f)).coerceAtMost(1f) else hsv[2] * (1 - amount * 0.8f)
        return android.graphics.Color.HSVToColor(android.graphics.Color.alpha(c), hsv)
    }

    /** Whether [context] draws in night mode (its own configuration: Settings › Appearance applied). */
    fun isNight(context: android.content.Context): Boolean =
        (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    /** Fill and optional outline for a chart bar. */
    data class BarStyle(@ColorRes val fill: Int, @ColorRes val edge: Int?)

    /** Chart bar colour for a step: the zone colour (Z1 teal … Z5 red). */
    fun barStyle(watts: Int, ftp: Int?): BarStyle = BarStyle(
        when (zoneOf(watts, ftp)) {
            1 -> R.color.z1
            2 -> R.color.z2
            3 -> R.color.z3
            4 -> R.color.z4
            5 -> R.color.z5
            else -> R.color.done
        },
        null,
    )
}
