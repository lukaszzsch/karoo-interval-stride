package com.daviddokakis.karoointerval

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.roundToInt

/**
 * The current ride's power, one sample per reading (about a second) while recording, for the
 * free-ride power chart. Cleared when the ride ends.
 */
object PowerHistory {
    private val samples = ArrayList<Float>(4 * 3600)
    private const val MAX_SAMPLES = 8 * 3600

    @Synchronized
    fun add(watts: Double) {
        if (samples.size >= MAX_SAMPLES) {
            // Very long ride: halve the resolution instead of dropping the start.
            val merged = ArrayList<Float>(samples.size / 2 + 1)
            for (i in samples.indices step 2) merged += if (i + 1 < samples.size) (samples[i] + samples[i + 1]) / 2 else samples[i]
            samples.clear()
            samples.addAll(merged)
        }
        samples += watts.toFloat().coerceAtLeast(0f)
    }

    @Synchronized
    fun clear() = samples.clear()

    /** The ride cut into [columns] equal stretches, each its average power (empty: no data). */
    @Synchronized
    fun columns(columns: Int): List<Float> {
        if (samples.isEmpty() || columns <= 0) return emptyList()
        val n = minOf(columns, samples.size)
        return List(n) { c ->
            val from = c * samples.size / n
            val to = maxOf(from + 1, (c + 1) * samples.size / n)
            var sum = 0f
            for (i in from until to) sum += samples[i]
            sum / (to - from)
        }
    }
}

/** Draws [PowerHistory] as bars coloured by power zone (grey without an FTP). */
class PowerHistoryChart(private val context: Context) {
    fun render(width: Int, height: Int, ftp: Int?, density: Float): Bitmap? {
        val barW = 3f * density
        val cols = PowerHistory.columns((width / barW).toInt())
        if (cols.isEmpty()) return null
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        // Scale: the ride's highest column, at least 120% of FTP so easy rides don't look maxed out.
        val top = maxOf(cols.max(), (ftp ?: 0) * 1.2f, 100f)
        val w = width.toFloat() / cols.size
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        cols.forEachIndexed { i, watts ->
            val h = (watts / top * height).coerceIn(if (watts > 0) 1f else 0f, height.toFloat())
            paint.color = context.getColor(Zones.barStyle(watts.roundToInt(), ftp).fill)
            canvas.drawRect(i * w, height - h, (i + 1) * w - (if (w > 2 * density) 0.5f * density else 0f), height.toFloat(), paint)
        }
        return bmp
    }
}
