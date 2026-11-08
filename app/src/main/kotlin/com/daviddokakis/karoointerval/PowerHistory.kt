package com.daviddokakis.karoointerval

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface

/**
 * The current ride's power, one sample per reading (about a second) while recording, for the
 * free-ride power chart. Cleared when the ride ends.
 */
object PowerHistory {
    /** Chart spans in seconds, tapped through in this order (null = whole ride). */
    val SPANS: List<Int?> = listOf(null, 60 * 60, 20 * 60, 5 * 60)

    private val samples = ArrayList<Float>(4 * 3600)
    private const val MAX_SAMPLES = 8 * 3600

    /** Seconds each sample stands for (doubles when a very long ride is thinned out). */
    private var secPerSample = 1

    @Synchronized
    fun add(watts: Double) {
        if (samples.size >= MAX_SAMPLES) {
            // Very long ride: halve the resolution instead of dropping the start.
            val merged = ArrayList<Float>(samples.size / 2 + 1)
            for (i in samples.indices step 2) merged += if (i + 1 < samples.size) (samples[i] + samples[i + 1]) / 2 else samples[i]
            samples.clear()
            samples.addAll(merged)
            secPerSample *= 2
        }
        samples += watts.toFloat().coerceAtLeast(0f)
    }

    @Synchronized
    fun clear() {
        samples.clear()
        secPerSample = 1
    }

    /**
     * The last [spanSec] seconds (null: the whole ride) cut into [columns] equal stretches, each
     * its average power. A span not filled yet keeps its columns' width: the bars grow from the
     * left as the ride goes on. Empty without data.
     */
    @Synchronized
    fun columns(columns: Int, spanSec: Int?): List<Float> {
        if (samples.isEmpty() || columns <= 0) return emptyList()
        val wanted = spanSec?.let { maxOf(1, it / secPerSample) }
        val from = if (wanted == null) 0 else maxOf(0, samples.size - wanted)
        val count = samples.size - from
        // Whole ride: the columns fill the width. A fixed span: as many columns as the share of
        // the span already ridden.
        val n = if (wanted == null) minOf(columns, count) else maxOf(1, minOf(columns, (columns.toLong() * count / wanted).toInt()))
        return List(minOf(n, count)) { c ->
            val a = from + c * count / n
            val b = maxOf(a + 1, from + (c + 1) * count / n)
            var sum = 0f
            for (i in a until b) sum += samples[i]
            sum / (b - a)
        }
    }
}

/**
 * Draws [PowerHistory] as bars coloured by power zone: the Karoo profile's 7 zones, or Coggan's
 * (55 / 75 / 90 / 105 / 120 / 150 % of FTP) when the profile has none. The span shows top left.
 */
class PowerHistoryChart(private val context: Context) {

    private val zoneColors = intArrayOf(
        R.color.pz1, R.color.pz2, R.color.pz3, R.color.pz4, R.color.pz5, R.color.pz6, R.color.pz7,
    )

    fun render(width: Int, height: Int, zones: List<IntRange>, ftp: Int?, span: Int, density: Float): Bitmap? {
        val barW = 3f * density
        val spanSec = PowerHistory.SPANS.getOrNull(span)
        val total = (width / barW).toInt()
        val cols = PowerHistory.columns(total, spanSec)
        if (cols.isEmpty()) return null
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val labelH = 12f * density
        val plotH = height - labelH
        // Scale: the highest column, at least 120% of FTP so easy rides don't look maxed out.
        val top = maxOf(cols.max(), (ftp ?: 0) * 1.2f, 100f)
        // Whole ride: columns fill the width; a fixed span: each column a fixed width.
        val w = if (spanSec == null) width.toFloat() / cols.size else width.toFloat() / total
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        cols.forEachIndexed { i, watts ->
            val h = (watts / top * plotH).coerceIn(if (watts > 0) 1f else 0f, plotH)
            paint.color = context.getColor(zoneColors[zoneIndex(watts.toInt(), zones, ftp)])
            val gap = if (w > 2 * density) 0.5f * density else 0f
            canvas.drawRect(i * w, height - h, (i + 1) * w - gap, height.toFloat(), paint)
        }
        // Span label, top left: "Cała jazda" / "60 min" / "20 min" / "5 min".
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getColor(R.color.text_secondary)
            textSize = 10f * density
            typeface = Typeface.DEFAULT_BOLD
        }
        val text = spanSec?.let { context.getString(R.string.chart_span_min, it / 60) } ?: context.getString(R.string.chart_span_all)
        canvas.drawText(text, 0f, 10f * density, label)
        return bmp
    }

    /** 0..6: the zone [watts] falls in. */
    private fun zoneIndex(watts: Int, zones: List<IntRange>, ftp: Int?): Int {
        if (zones.size >= 2) {
            val i = zones.indexOfFirst { watts in it }
            return when {
                i >= 0 -> i
                watts > zones.last().last -> zones.size - 1
                else -> 0
            }.coerceIn(0, zoneColors.size - 1)
        }
        if (ftp == null || ftp <= 0) return 0
        val pct = watts * 100 / ftp
        return when {
            pct <= 55 -> 0
            pct <= 75 -> 1
            pct <= 90 -> 2
            pct <= 105 -> 3
            pct <= 120 -> 4
            pct <= 150 -> 5
            else -> 6
        }
    }
}
