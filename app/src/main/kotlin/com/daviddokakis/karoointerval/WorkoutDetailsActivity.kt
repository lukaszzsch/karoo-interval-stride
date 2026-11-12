package com.daviddokakis.karoointerval

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A workout's details (a tap on its card on the Workouts page, from BetterIntervals 0.10):
 * the picture with the FTP line on one scale for every workout (130 % of FTP at the top), a tap
 * on a block shows its interval; the planned numbers (time, TSS, IF, average power, NP, kcal);
 * time in the Karoo's 6 power zones; every interval. "Wybierz trening" picks it, as the card did.
 */
class WorkoutDetailsActivity : AppearanceActivity() {

    private lateinit var store: PlanStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = PlanStore.get(this)
        val id = intent.getStringExtra(EXTRA_ID)
        val date = intent.getStringExtra(EXTRA_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val planned = store.cachedWeek().firstOrNull { it.plan.id == id && (date == null || it.date == date) }
            ?: store.cachedWeek().firstOrNull { it.plan.id == id }
        val plan = planned?.plan ?: store.plan.value?.takeIf { it.id == id }
        if (plan == null) {
            finish()
            return
        }
        val session = IntervalExtension.session?.state?.value
        val ftp = plan.ftp ?: session?.profileFtp
        val zones = session?.powerZones.orEmpty()
        val selected = store.plan.value?.id == plan.id

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(24))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg))
        }
        // Back on the left, "Wybierz trening" on the right
        root.addView(
            row(
                iconButton(R.drawable.ic_back, getString(R.string.cd_back)) { finish() },
                View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) },
                if (selected || planned == null) {
                    text(getString(R.string.selected), 14f, bold = true).apply {
                        setTextColor(getColor(R.color.watts_in_range))
                        setPadding(0, 0, dp(8), 0)
                    }
                } else {
                    button(getString(R.string.select_workout), primary = true) {
                        store.select(planned)
                        setResult(RESULT_OK)
                        finish()
                    }
                },
            ).apply { setPadding(dp(4), dp(8), dp(12), 0) },
        )
        root.addView(ScrollView(this).apply { addView(content, MATCH_PARENT, WRAP_CONTENT) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)

        content.addView(text(plan.name, 20f, bold = true))
        val dateText = planned?.date?.let { if (it == LocalDate.now()) getString(R.string.today) else it.format(DAY_FORMAT) }
        content.addView(
            text(listOfNotNull(dateText, formatClock(plan.totalSec), ftp?.let { getString(R.string.details_ftp, it) }).joinToString(" · "), 13f, secondary = true),
        )

        // The picture: tap a block for its interval
        content.addView(
            DetailsChart(this, plan, ftp, zones).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(CHART_DP + BUBBLE_DP)).apply { topMargin = dp(6) }
            },
        )
        content.addView(text(getString(R.string.details_tap_hint), 12f, secondary = true))

        // The numbers, 3 × 2
        val stats = PlanStats.of(plan, ftp)
        content.addView(sectionHeader(getString(R.string.details_numbers)))
        stats.tiles(this).chunked(3).forEach { rowTiles ->
            content.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(6) }
                    rowTiles.forEachIndexed { i, (label, value) ->
                        addView(
                            statTile(label, value),
                            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (i < rowTiles.lastIndex) marginEnd = dp(6) },
                        )
                    }
                },
            )
        }

        // Time in zones (the Karoo's 6, from the rider profile, else its default split of the FTP)
        if (ftp != null || zones.size >= 2) {
            content.addView(sectionHeader(getString(R.string.details_zones)))
            val inZone = IntArray(6)
            plan.steps.forEach { st ->
                if (!st.hasTarget) return@forEach
                for (i in 0 until st.durationSec) {
                    val w = if (st.isRamp) (st.startWatts + (st.endWatts - st.startWatts) * (i + 0.5) / st.durationSec).roundToInt() else st.avgWatts
                    inZone[powerZoneIndex(w, zones, ftp)]++
                }
            }
            val total = inZone.sum().coerceAtLeast(1)
            val night = Zones.isNight(this)
            inZone.forEachIndexed { z, sec -> content.addView(zoneRow(z, sec, total, night)) }
        }

        // Every interval
        content.addView(sectionHeader(getString(R.string.details_intervals, plan.steps.size)))
        var start = 0
        plan.steps.forEachIndexed { i, st ->
            content.addView(intervalRow(i, st, start, ftp, zones))
            start += st.durationSec
        }
    }

    private fun sectionHeader(value: String) = text(value, 13f, bold = true, secondary = true).apply {
        isAllCaps = true
        setPadding(0, dp(16), 0, dp(6))
    }

    private fun statTile(label: String, value: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_tile)
        setPadding(dp(8), dp(6), dp(8), dp(8))
        addView(text(label, 11f, secondary = true).apply { isAllCaps = true; setPadding(0, 0, 0, 0) })
        addView(
            text(value, 22f, bold = true).apply {
                setPadding(0, 0, 0, 0)
                maxLines = 1
            },
        )
    }

    /** "Z3 ████████░░░░ 22:30 · 31 %" */
    private fun zoneRow(z: Int, sec: Int, total: Int, night: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(2), 0, dp(2))
        addView(text("Z${z + 1}", 13f, bold = true).apply { setPadding(0, 0, 0, 0) }, LinearLayout.LayoutParams(dp(30), WRAP_CONTENT))
        val track = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply { cornerRadius = dp(3).toFloat(); setColor(getColor(R.color.track)) }
            addView(
                View(context).apply {
                    background = GradientDrawable().apply {
                        cornerRadius = dp(3).toFloat()
                        setColor(Zones.boosted(getColor(ZONE_COLORS[z]), night))
                    }
                },
                LinearLayout.LayoutParams(0, MATCH_PARENT, sec.toFloat().coerceAtLeast(0.0001f)),
            )
            addView(View(context), LinearLayout.LayoutParams(0, MATCH_PARENT, (total - sec).toFloat().coerceAtLeast(0.0001f)))
        }
        addView(track, LinearLayout.LayoutParams(0, dp(12), 1f).apply { marginEnd = dp(8) })
        addView(
            text(if (sec > 0) "${formatClock(sec)} · ${(sec * 100f / total).roundToInt()}%" else "–", 13f, secondary = sec == 0).apply {
                setPadding(0, 0, 0, 0)
                gravity = Gravity.END
            },
            LinearLayout.LayoutParams(dp(96), WRAP_CONTENT),
        )
    }

    /** "3 · 4:00 · 250 W · 100 % · Z4", then the cadence target and the coach's note. */
    private fun intervalRow(i: Int, st: PlanStep, startSec: Int, ftp: Int?, zones: List<IntRange>) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(5), 0, dp(5))
        val night = Zones.isNight(context)
        addView(
            View(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(2).toFloat()
                    val c = if (st.hasTarget && (ftp != null || zones.size >= 2)) Zones.boosted(getColor(ZONE_COLORS[powerZoneIndex(st.avgWatts, zones, ftp)]), night) else getColor(R.color.done)
                    setColor(c)
                }
            },
            LinearLayout.LayoutParams(dp(5), MATCH_PARENT).apply { marginEnd = dp(8) },
        )
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(stepLine(i, st, ftp), 14f, bold = true).apply { setPadding(0, 0, 0, 0) })
                val sub = listOfNotNull(
                    getString(R.string.details_from, formatClock(startSec)),
                    st.cadenceText,
                    st.note,
                ).joinToString(" · ")
                addView(text(sub, 12f, secondary = true).apply { setPadding(0, dp(1), 0, 0) })
            },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
        )
    }

    companion object {
        const val EXTRA_ID = "plan_id"
        const val EXTRA_DATE = "plan_date"
        private const val CHART_DP = 80
        /** Room above the picture for the tapped interval's bubble. */
        private const val BUBBLE_DP = 44
        private val DAY_FORMAT = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.forLanguageTag("pl"))
        val ZONE_COLORS = intArrayOf(R.color.pz1, R.color.pz2, R.color.pz3, R.color.pz4, R.color.pz5, R.color.pz6)

        /** "3 · 4:00 · 250 W · 100% · Z4" (a ramp "150→200 W", a range "180–200 W"). */
        fun stepLine(i: Int, st: PlanStep, ftp: Int?): String {
            val watts = when {
                !st.hasTarget -> null
                st.isRamp -> "${st.startWatts}→${st.endWatts} W"
                st.isRange && st.rangeLow != null && st.rangeHigh != null -> "${minOf(st.rangeLow, st.rangeHigh)}–${maxOf(st.rangeLow, st.rangeHigh)} W"
                else -> "${st.avgWatts} W"
            }
            val pct = if (st.hasTarget && ftp != null && ftp > 0) {
                if (st.isRamp) "${st.startWatts * 100 / ftp}→${st.endWatts * 100 / ftp}%" else "${st.avgWatts * 100 / ftp}%"
            } else {
                null
            }
            return listOfNotNull("${i + 1}", formatClock(st.durationSec), watts, pct).joinToString(" · ")
        }
    }
}

/**
 * The details page's picture: the steps in the Karoo's zone colours (+15 % contrast, ramps fading
 * from zone to zone) on one scale for every workout: 130 % of FTP at the top (or the highest step
 * + 5 % for sprints), so the dashed FTP line always shows. A tap on a block fades the rest and
 * shows its interval in a bubble above; a tap on it again, or anywhere else, closes it.
 */
private class DetailsChart(
    context: Context,
    private val plan: WorkoutPlan,
    private val ftp: Int?,
    private val zones: List<IntRange>,
) : View(context) {

    private var selected: Int? = null
    private val d = resources.displayMetrics.density
    private val night = Zones.isNight(context)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ftpLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * d
        color = context.getColor(R.color.text_secondary)
        pathEffect = DashPathEffect(floatArrayOf(4 * d, 3 * d), 0f)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10 * d
        color = context.getColor(R.color.text_secondary)
    }
    private val bubbleBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.text) }
    private val bubbleText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13 * d
        typeface = Typeface.DEFAULT_BOLD
        color = context.getColor(R.color.bg)
        textAlign = Paint.Align.CENTER
    }

    /** The bubble's room above the bars. */
    private val bubbleH = 44 * d

    private fun topWatts(): Float {
        val max = plan.maxWatts.toFloat()
        val f = ftp?.takeIf { it > 0 }?.toFloat() ?: return (max * 1.1f).coerceAtLeast(100f)
        return maxOf(f * 1.3f, max * 1.05f)
    }

    private fun x(sec: Float) = sec / plan.totalSec.coerceAtLeast(1) * width

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (plan.totalSec <= 0) return
        val top = bubbleH
        val bottom = height.toFloat()
        val scaleTop = topWatts()
        fun y(w: Int) = bottom - w / scaleTop * (bottom - top)
        var t = 0f
        val gap = 0.6f * d
        plan.steps.forEachIndexed { i, st ->
            val x0 = x(t) + gap
            val x1 = (x(t + st.durationSec) - gap).coerceAtLeast(x0 + 0.5f)
            val ya = if (st.isRamp) y(st.startWatts) else y(st.avgWatts)
            val yb = if (st.isRamp) y(st.endWatts) else ya
            val path = Path().apply { moveTo(x0, bottom); lineTo(x0, ya); lineTo(x1, yb); lineTo(x1, bottom); close() }
            val colorA = colorFor(if (st.isRamp) st.startWatts else st.avgWatts, st.hasTarget)
            val colorB = if (st.isRamp) colorFor(st.endWatts, true) else colorA
            fill.shader = if (colorA != colorB) android.graphics.LinearGradient(x0, 0f, x1, 0f, colorA, colorB, android.graphics.Shader.TileMode.CLAMP) else null
            fill.color = colorA
            fill.alpha = if (selected != null && selected != i) FADED else 255
            canvas.drawPath(path, fill)
            t += st.durationSec
        }
        fill.shader = null
        fill.alpha = 255
        // FTP: a dashed line with its value
        ftp?.takeIf { it > 0 }?.let { f ->
            val fy = y(f)
            canvas.drawLine(0f, fy, width.toFloat(), fy, ftpLine)
            canvas.drawText("FTP $f", 2 * d, fy - 3 * d, label)
        }
        // The tapped interval's bubble: "3 · 4:00 · 250 W · 100% · Z4", with a pointer to its block
        selected?.let { i ->
            val st = plan.steps.getOrNull(i) ?: return@let
            val start = plan.startSecOf(i).toFloat()
            val cx = x(start + st.durationSec / 2f)
            val zone = if (st.hasTarget && (ftp != null || zones.size >= 2)) " · Z${powerZoneIndex(st.avgWatts, zones, ftp) + 1}" else ""
            var line = WorkoutDetailsActivity.stepLine(i, st, ftp) + zone
            val maxW = width - 8 * d
            while (bubbleText.measureText(line) + 16 * d > maxW && line.length > 4) line = line.dropLast(2) + "…"
            val w = bubbleText.measureText(line) + 16 * d
            val bx = (cx - w / 2).coerceIn(0f, width - w)
            val rect = RectF(bx, 4 * d, bx + w, 32 * d)
            canvas.drawRoundRect(rect, 8 * d, 8 * d, bubbleBg)
            val tip = Path().apply {
                moveTo(cx.coerceIn(rect.left + 8 * d, rect.right - 8 * d) - 6 * d, rect.bottom)
                lineTo(cx.coerceIn(rect.left + 8 * d, rect.right - 8 * d) + 6 * d, rect.bottom)
                lineTo(cx.coerceIn(rect.left + 2 * d, rect.right - 2 * d), rect.bottom + 7 * d)
                close()
            }
            canvas.drawPath(tip, bubbleBg)
            canvas.drawText(line, rect.centerX(), rect.centerY() + bubbleText.textSize * 0.35f, bubbleText)
        }
    }

    private fun colorFor(watts: Int, hasTarget: Boolean): Int =
        if (!hasTarget || (ftp == null && zones.size < 2)) {
            context.getColor(R.color.done)
        } else {
            Zones.boosted(context.getColor(WorkoutDetailsActivity.ZONE_COLORS[powerZoneIndex(watts, zones, ftp)]), night)
        }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) return true
        if (event.action != MotionEvent.ACTION_UP) return super.onTouchEvent(event)
        val sec = event.x / width.coerceAtLeast(1) * plan.totalSec
        var t = 0
        var hit: Int? = null
        if (event.y >= bubbleH) {
            plan.steps.forEachIndexed { i, st ->
                if (hit == null && sec >= t && sec < t + st.durationSec) hit = i
                t += st.durationSec
            }
        }
        selected = if (hit == null || hit == selected) null else hit
        performClick()
        invalidate()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    companion object {
        private const val FADED = 77
    }
}

/**
 * A planned workout's numbers: time, TSS, IF, average power, NP (30 s rolling power) and calories
 * (the work in kJ, about the same in kcal: a rider turns about a quarter of the energy into work).
 */
class PlanStats(val totalSec: Int, val tss: Int?, val intensity: Double?, val avg: Int?, val np: Int?, val kcal: Int?) {

    fun tiles(c: Context): List<Pair<String, String>> = listOf(
        c.getString(R.string.details_time) to shortTime(totalSec),
        "TSS" to (tss?.toString() ?: "--"),
        "IF" to (intensity?.let { String.format(Locale.US, "%.2f", it) } ?: "--"),
        c.getString(R.string.details_avg) to (avg?.toString() ?: "--"),
        "NP" to (np?.toString() ?: "--"),
        "kcal" to (kcal?.toString() ?: "--"),
    )

    companion object {
        /** "2h20", "1h05", "45m": short, so the six numbers share one size. */
        fun shortTime(sec: Int): String {
            val h = sec / 3600
            val m = (sec % 3600) / 60
            return if (h > 0) "${h}h%02d".format(m) else "${m}m"
        }

        fun of(plan: WorkoutPlan, ftp: Int?): PlanStats {
            val anyTarget = plan.steps.any { it.hasTarget }
            val power = if (!anyTarget || plan.totalSec <= 0) null else IntArray(plan.totalSec).also { arr ->
                var t = 0
                plan.steps.forEach { st ->
                    for (i in 0 until st.durationSec) {
                        arr[t + i] = if (st.isRamp) (st.startWatts + (st.endWatts - st.startWatts) * (i + 0.5) / st.durationSec).roundToInt() else st.avgWatts
                    }
                    t += st.durationSec
                }
            }
            val avg = power?.average()?.roundToInt()
            val np = power?.takeIf { it.size >= 30 }?.let { p ->
                var sum = 0.0
                var acc = 0.0
                var n = 0
                for (i in p.indices) {
                    sum += p[i]
                    if (i >= 30) sum -= p[i - 30]
                    if (i >= 29) {
                        acc += (sum / 30).pow(4)
                        n++
                    }
                }
                (acc / n).pow(0.25).roundToInt()
            }
            val f = ftp?.takeIf { it > 0 }
            val intensity = if (np != null && f != null) np.toDouble() / f else null
            val tss = if (np != null && f != null && intensity != null) (plan.totalSec * np * intensity / (f * 36.0)).roundToInt() else null
            val kcal = power?.sum()?.let { (it / 1000.0).roundToInt() }?.takeIf { it > 0 }
            return PlanStats(plan.totalSec, tss, intensity, avg, np, kcal)
        }
    }
}
