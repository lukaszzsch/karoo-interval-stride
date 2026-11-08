package com.daviddokakis.karoointerval

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Draws the power gauge: a grey arc with the target range in green, a needle at the current
 * watts, and the watts number under the arc. Everything is one bitmap of the gauge's space.
 *
 * The gauge fills its space: it always uses the full width, and when the space is short (a route
 * is loaded) the arc gets flatter (smaller sweep, larger radius) instead of the whole gauge
 * shrinking. The watts take the biggest size that fits under the arc, and sit up level with the
 * arc's ends when there's room.
 *
 * The arc spans the target ± 4 × the range's half width, and at least ± 10 % of the target, so
 * the green band takes about a quarter of the arc. The needle stops at the ends.
 */
class GaugeRenderer(private val context: Context) {

    private fun color(id: Int) = ContextCompat.getColor(context, id)

    private class Layout(val sweep: Float, val r: Float, val cy: Float, val sw: Float, val textSize: Float, val baseline: Float)

    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private fun styleNumber(karooStyle: Boolean) {
        if (karooStyle) {
            // Karoo style: the watts in Plex Sans Medium (sans-serif on the Karoo), 90% wide, tighter.
            numberPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            numberPaint.textScaleX = 0.9f
            numberPaint.letterSpacing = -0.05f
        } else {
            numberPaint.typeface = Typeface.DEFAULT_BOLD
            numberPaint.textScaleX = 1f
            numberPaint.letterSpacing = 0f
        }
    }

    /** The arc that gives the watts the biggest size in a [w] × [h] space (flatter when short). */
    private fun layout(w: Float, h: Float, text: String): Layout {
        val sw = w * 0.07f
        val gap = w * 0.014f
        // Room around the arc for the needle, which sticks out past the arc's edge (plus its
        // see-through outline); without it the needle is cut off at the top and the ends.
        val m = NEEDLE_OVERHANG * w
        var best: Layout? = null
        var sweep = 140f
        while (sweep >= 70f) {
            val half = Math.toRadians(sweep / 2.0)
            val r = ((w - sw - 2f * m) / 2f / sin(half)).toFloat()
            val cy = m + sw / 2f + r
            val endY = (cy - r * cos(half) + sw / 2f).toFloat()   // bottom of the arc's rounded ends
            if (endY <= h) {
                val inner = r - sw / 2f - gap
                var lo = 20f
                var hi = w * 0.36f
                repeat(18) {
                    val f = (lo + hi) / 2f
                    numberPaint.textSize = f
                    val tw = numberPaint.measureText(text)
                    val base = min(h - gap, endY + 0.22f * f)
                    val top = base - CAP * f
                    val x = min(tw / 2f, inner - 1f)
                    val arcY = cy - sqrt(max(0f, inner * inner - x * x))
                    if (tw / 2f < inner && top >= arcY) lo = f else hi = f
                }
                if (best == null || lo > best.textSize + 0.5f) {
                    best = Layout(sweep, r, cy, sw, lo, min(h - gap, endY + 0.22f * lo))
                }
            }
            sweep -= 2f
        }
        return best ?: Layout(140f, w * 0.4f, m + w * 0.43f, sw, w * 0.2f, h - gap)
    }

    /**
     * @param width bitmap width in px.
     * @param height bitmap height in px: the gauge's space on screen.
     * @param numberColor colour of the watts digits (plain text colour, or a range colour).
     * @param karooStyle Karoo native styling: Karoo green band and Karoo's number font.
     */
    fun render(
        width: Int, height: Int, watts: Int?, target: Int?, range: IntRange?, numberColor: Int,
        karooStyle: Boolean = false,
    ): Bitmap {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val text = watts?.toString() ?: "--"
        styleNumber(karooStyle)
        val g = layout(w.toFloat(), h.toFloat(), text)

        val cx = w / 2f
        val oval = RectF(cx - g.r, g.cy - g.r, cx + g.r, g.cy + g.r)
        val start = 270f - g.sweep / 2f     // Android angles: clockwise from 3 o'clock; 270 = top
        val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = g.sw
            strokeCap = Paint.Cap.ROUND
        }
        arc.color = color(R.color.gauge_track)
        c.drawArc(oval, start, g.sweep, false, arc)

        val center = target ?: range?.let { (it.first + it.last) / 2 }
        if (center != null && center > 0) {
            val bandHalf = range?.let { (it.last - it.first) / 2f } ?: 0f
            val half = max(center * 0.10f, bandHalf * 4f)
            val lo = center - half
            val hi = center + half
            fun fraction(v: Float) = ((v - lo) / (hi - lo)).coerceIn(0f, 1f)

            if (range != null) {
                arc.color = color(if (karooStyle) R.color.karoo_green else R.color.gauge_band)
                val a = fraction(range.first.toFloat())
                val b = fraction(range.last.toFloat())
                c.drawArc(oval, start + a * g.sweep, (b - a) * g.sweep, false, arc)
            }

            if (watts != null) {
                // Needle across the arc: a transparent gap first (so whatever is behind the field
                // shows through), then the solid bar.
                val rad = Math.toRadians((start + fraction(watts.toFloat()) * g.sweep).toDouble())
                val r0 = g.r - g.sw / 2f - w * 0.0125f
                val r1 = g.r + g.sw / 2f + w * 0.0125f
                val x0 = cx + r0 * cos(rad).toFloat()
                val y0 = g.cy + r0 * sin(rad).toFloat()
                val x1 = cx + r1 * cos(rad).toFloat()
                val y1 = g.cy + r1 * sin(rad).toFloat()
                val needle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                }
                needle.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                needle.strokeWidth = w * 0.036f
                c.drawLine(x0, y0, x1, y1, needle)
                needle.xfermode = null
                needle.color = color(R.color.text)
                needle.strokeWidth = w * 0.02f
                c.drawLine(x0, y0, x1, y1, needle)
            }
        }

        numberPaint.color = numberColor
        numberPaint.textSize = g.textSize
        c.drawText(text, cx, g.baseline, numberPaint)
        return bmp
    }

    /**
     * Free ride: the arc split into the Karoo's 6 power zones (equal segments, Karoo colours), the
     * zone you're in drawn full width and the others thinner, a needle at the watts (placed within
     * its zone's segment) and the watts number under the arc. Without zones or an FTP it falls
     * back to the plain grey arc.
     */
    fun renderZones(
        width: Int, height: Int, watts: Int?, zones: List<IntRange>, ftp: Int?, numberColor: Int,
        karooStyle: Boolean = false,
    ): Bitmap {
        val bounds = zoneBounds(zones, ftp) ?: return render(width, height, watts, null, null, numberColor, karooStyle)
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val text = watts?.toString() ?: "--"
        styleNumber(karooStyle)
        val g = layout(w.toFloat(), h.toFloat(), text)

        val cx = w / 2f
        val oval = RectF(cx - g.r, g.cy - g.r, cx + g.r, g.cy + g.r)
        val start = 270f - g.sweep / 2f
        val n = bounds.size
        val seg = g.sweep / n
        val gapDeg = 1.6f
        val active = watts?.takeIf { it > 0 }?.let { powerZoneIndex(it, zones, ftp) }
        val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.BUTT
        }
        val colors = intArrayOf(R.color.pz1, R.color.pz2, R.color.pz3, R.color.pz4, R.color.pz5, R.color.pz6)
        for (i in 0 until n) {
            arc.color = color(colors[i.coerceAtMost(colors.size - 1)])
            // The zone you're in: full width; the others thinner, kept on the arc's centre line.
            arc.strokeWidth = if (i == active) g.sw else g.sw * 0.5f
            c.drawArc(oval, start + i * seg + gapDeg / 2f, seg - gapDeg, false, arc)
        }

        if (watts != null) {
            // Where the watts sit: their zone's segment, in proportion within the zone.
            val i = powerZoneIndex(watts.coerceAtLeast(0), zones, ftp).coerceIn(0, n - 1)
            val z = bounds[i]
            val inZone = ((watts - z.first).toFloat() / maxOf(1, z.last - z.first)).coerceIn(0f, 1f)
            val rad = Math.toRadians((start + (i + inZone) * seg).toDouble())
            val r0 = g.r - g.sw / 2f - w * 0.0125f
            val r1 = g.r + g.sw / 2f + w * 0.0125f
            val x0 = cx + r0 * cos(rad).toFloat()
            val y0 = g.cy + r0 * sin(rad).toFloat()
            val x1 = cx + r1 * cos(rad).toFloat()
            val y1 = g.cy + r1 * sin(rad).toFloat()
            val needle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
            }
            needle.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            needle.strokeWidth = w * 0.036f
            c.drawLine(x0, y0, x1, y1, needle)
            needle.xfermode = null
            needle.color = color(R.color.text)
            needle.strokeWidth = w * 0.02f
            c.drawLine(x0, y0, x1, y1, needle)
        }

        numberPaint.color = numberColor
        numberPaint.textSize = g.textSize
        c.drawText(text, cx, g.baseline, numberPaint)
        return bmp
    }

    /**
     * The 6 zones as watt ranges: the Karoo profile's, else the Karoo's default split of the FTP.
     * The open top zone gets a span of 30% of its start, so the needle can move within it.
     */
    private fun zoneBounds(zones: List<IntRange>, ftp: Int?): List<IntRange>? {
        val list = when {
            zones.size >= 2 -> zones.take(6)
            ftp != null && ftp > 0 -> listOf(0, 56, 76, 91, 106, 121).let { pcts ->
                pcts.mapIndexed { i, p ->
                    val lo = ftp * p / 100
                    val hi = if (i + 1 < pcts.size) ftp * pcts[i + 1] / 100 - 1 else Int.MAX_VALUE
                    lo..hi
                }
            }
            else -> return null
        }
        return list.mapIndexed { i, z ->
            val open = i == list.size - 1 && (z.last <= z.first || z.last - z.first > z.first)
            if (open) z.first..(z.first + maxOf(30, z.first * 3 / 10)) else z
        }
    }

    companion object {
        /** Digit height as a share of the text size (IBM Plex). */
        private const val CAP = 0.71f

        /**
         * How far the needle reaches past the arc's outer edge, as a share of the width: its
         * length past the edge (0.0125) plus half its see-through outline (0.018).
         */
        private const val NEEDLE_OVERHANG = 0.0125f + 0.018f
    }
}
