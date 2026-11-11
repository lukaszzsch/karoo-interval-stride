package com.daviddokakis.karoointerval

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.core.content.ContextCompat
import kotlin.math.ceil

/**
 * Draws the workout profile in zone colours: one bar per step
 * (width = duration, height = watts).
 *
 * - [Style.SCREEN] (interval screen): watt guide lines, ridden parts whitened, the current step
 *   outlined, a "now" marker, the step a jump lands on in solid blue with a ▼, time labels.
 *   Supports zooming into the current step.
 * - [Style.CARD] (workout list thumbnail): bars only, transparent background.
 */
class ChartRenderer(private val context: Context) {

    enum class Style { SCREEN, CARD }

    private fun color(id: Int) = ContextCompat.getColor(context, id)

    private val karooZoneColors = intArrayOf(R.color.pz1, R.color.pz2, R.color.pz3, R.color.pz4, R.color.pz5, R.color.pz6)

    /** A bar in the colour of the Karoo power zone of [watts]; grey without zones or an FTP. */
    private fun karooBar(watts: Int, zones: List<IntRange>, ftp: Int?): Zones.BarStyle =
        if (zones.size < 2 && (ftp == null || ftp <= 0)) {
            Zones.BarStyle(R.color.done, null)
        } else {
            Zones.BarStyle(karooZoneColors[powerZoneIndex(watts, zones, ftp)], null)
        }

    /** [c] mixed 60% toward white: how ridden parts of the profile look. */
    private fun whitened(c: Int): Int {
        fun mix(v: Int) = (v + (255 - v) * 0.6f).toInt()
        return android.graphics.Color.argb(255, mix(android.graphics.Color.red(c)), mix(android.graphics.Color.green(c)), mix(android.graphics.Color.blue(c)))
    }

    fun render(state: ScreenState, width: Int, height: Int, scale: Float, look: Style = Style.SCREEN): Bitmap {
        val card = look == Style.CARD
        // The screen chart has no panel: it's drawn on the page colour (follows light/dark mode).
        val bmp = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        // Transparent background: the Karoo page (screen) or the card colour (list) shows through.
        val plan = state.plan ?: return bmp
        if (plan.steps.isEmpty() || plan.totalSec <= 0) return bmp

        val d = context.resources.displayMetrics.density * scale
        // The step a jump lands on and its ▼: blue, or the Karoo's jump purple in Karoo style.
        val landColor = if (state.karooStyle) R.color.karoo_purple else R.color.land
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = color(R.color.text_secondary)
            textSize = 10f * d
        }
        val top = if (card) 2f * d else 9f * d
        val bottom = if (card) height.toFloat() else height - label.textSize - 6f * d
        val sidePad = 0f
        val plotW = width - 2 * sidePad

        // Time window: the whole workout, or (zoomed) the current interval plus a little of the
        // intervals either side for context.
        val current = plan.steps.getOrNull(state.stepIndex)
        // While a jump is being chosen or counted down, show the whole workout so the target is visible.
        val zoomed = !card && state.chartZoomed && current != null && state.landTarget == null
        val (winStart, winEnd) = if (zoomed && current != null) {
            val start = plan.startSecOf(state.stepIndex)
            val end = start + current.durationSec
            val pad = (current.durationSec * ZOOM_CONTEXT).toInt().coerceIn(ZOOM_MIN_PAD_SEC, ZOOM_MAX_PAD_SEC)
            (start - pad).coerceAtLeast(0) to (end + pad).coerceAtMost(plan.totalSec)
        } else {
            0 to plan.totalSec
        }
        val span = (winEnd - winStart).coerceAtLeast(1).toFloat()
        val visibleMax = if (zoomed) {
            var t0 = 0
            plan.steps.filter { s ->
                val visible = t0 < winEnd && t0 + s.durationSec > winStart
                t0 += s.durationSec
                visible
            }.maxOfOrNull { maxOf(it.startWatts, it.endWatts) } ?: plan.maxWatts
        } else {
            plan.maxWatts
        }
        val ftp = state.ftp
        val maxWatts = (ceil(visibleMax * 1.1 / 100.0) * 100).toFloat().coerceAtLeast(100f)
        fun x(sec: Float) = sidePad + (sec - winStart) / span * plotW
        fun y(watts: Int) = bottom - watts / maxWatts * (bottom - top)

        // Watt guide lines (screen only)
        if (!card) {
            val grid = Paint().apply { color = color(R.color.track); strokeWidth = 0.5f * d }
            var w = 100
            while (w < maxWatts) {
                canvas.drawLine(0f, y(w), width.toFloat(), y(w), grid)
                canvas.drawText(w.toString(), 2f * d, y(w) - 2f * d, label)
                w += 100
            }
        }

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            strokeWidth = 1.5f * d
        }
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            strokeWidth = 2f * d
            color = color(R.color.text)
        }
        val gap = if (card) 0.5f * d else 0.75f * d
        var t = 0f
        plan.steps.forEachIndexed { i, step ->
            val x0 = x(t) + gap
            val x1 = (x(t + step.durationSec) - gap).coerceAtLeast(x0 + 0.5f)
            // Ramps slope; ranges and single targets are drawn flat at their middle.
            val ys = if (step.isRamp) y(step.startWatts) to y(step.endWatts) else y(step.avgWatts).let { it to it }
            val path = Path().apply {
                moveTo(x0, bottom)
                lineTo(x0, ys.first)
                lineTo(x1, ys.second)
                lineTo(x1, bottom)
                close()
            }
            // The Karoo's 6 power zones and colours (zones from the rider profile, else the
            // Karoo's default split of the FTP), as on the Karoo's own power zone pages.
            val bar = karooBar(step.avgWatts, state.powerZones, state.profileFtp ?: ftp)
            // Everything already ridden is whitened (its zone colour 60% toward white): finished
            // steps whole, the current step up to the "now" marker. The jump target is solid blue.
            val done = !card && i < state.stepIndex
            val isTarget = !card && i == state.landTarget
            val alpha = 255
            fill.color = when {
                isTarget -> color(landColor)
                done -> whitened(color(bar.fill))
                else -> color(bar.fill)
            }
            canvas.drawPath(path, fill)
            if (!card && !isTarget && i == state.stepIndex) {
                val nx = x((t + state.elapsedInStepSec).coerceAtMost(t + step.durationSec))
                if (nx > x0) {
                    canvas.save()
                    canvas.clipRect(x0, 0f, nx, bottom)
                    fill.color = whitened(color(bar.fill))
                    canvas.drawPath(path, fill)
                    canvas.restore()
                }
            }
            bar.edge?.let { edge ->
                // Karoo draws easy bars with a bright outline, just inside the bar.
                val inset = edgePaint.strokeWidth / 2
                val edgePath = Path().apply {
                    moveTo(x0 + inset, bottom)
                    lineTo(x0 + inset, ys.first + inset)
                    lineTo(x1 - inset, ys.second + inset)
                    lineTo(x1 - inset, bottom)
                }
                edgePaint.color = color(edge)
                edgePaint.alpha = alpha
                canvas.drawPath(edgePath, edgePaint)
            }
            if (!card && i == state.stepIndex) canvas.drawPath(path, outline)
            if (isTarget) {
                // ▼ above the target bar
                val cx = (x0 + x1) / 2
                val tipY = maxOf(minOf(ys.first, ys.second) - 2f * d, 13f * d) // stays inside the chart
                val tri = Path().apply {
                    moveTo(cx - 8f * d, tipY - 12f * d)
                    lineTo(cx + 8f * d, tipY - 12f * d)
                    lineTo(cx, tipY)
                    close()
                }
                canvas.drawPath(tri, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(landColor) })
            }
            t += step.durationSec
        }

        if (card) return bmp

        // "Now" marker
        val nowSec = plan.startSecOf(state.stepIndex) + state.elapsedInStepSec
        val nx = x(nowSec.toFloat())
        val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(R.color.text); strokeWidth = 2f * d }
        canvas.drawLine(nx, top - 6f * d, nx, bottom, marker)
        val tri = Path().apply {
            moveTo(nx - 5f * d, top - 8f * d)
            lineTo(nx + 5f * d, top - 8f * d)
            lineTo(nx, top - 2f * d)
            close()
        }
        marker.style = Paint.Style.FILL
        canvas.drawPath(tri, marker)

        // Time axis: just the end time, at the far right.
        val baseline = height - 2f * d
        label.textAlign = Paint.Align.RIGHT
        canvas.drawText(formatClock(winEnd), width.toFloat(), baseline, label)
        return bmp
    }

    companion object {
        /** Zoomed view shows this share of the interval's length before and after it… */
        private const val ZOOM_CONTEXT = 0.15
        /** …but at least / at most this many seconds. */
        private const val ZOOM_MIN_PAD_SEC = 20
        private const val ZOOM_MAX_PAD_SEC = 120
    }
}
