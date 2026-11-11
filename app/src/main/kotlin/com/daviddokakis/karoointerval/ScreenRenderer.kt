package com.daviddokakis.karoointerval

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import io.hammerhead.karooext.models.ViewConfig

/** Turns a [ScreenState] into the RemoteViews shown in the Karoo data field. */
class ScreenRenderer(private val context: Context, private val config: ViewConfig) {

    /**
     * Where colours come from: [context] (Auto: the Karoo's own day/night mode), or a context forced
     * to Dark or Light (Settings → Appearance). The chart and gauge are drawn from it too.
     */
    private var res: Context = context
    private var resFor: Appearance = Appearance.AUTO
    private var chart = ChartRenderer(context)
    private var powerChart = PowerHistoryChart(context)
    private var gauge = GaugeRenderer(context)

    private fun useAppearance(a: Appearance) {
        if (a == resFor) return
        resFor = a
        res = a.apply(context)
        chart = ChartRenderer(res)
        powerChart = PowerHistoryChart(res)
        gauge = GaugeRenderer(res)
    }

    /**
     * Dark / Light forced: the Karoo resolves the layout's XML colours in its own mode, so paint the
     * field's background and re-apply every XML colour (ThemeOverrides, generated from the layouts)
     * from [res]. Auto: nothing to do.
     */
    private fun applyAppearance(v: RemoteViews, layout: Int) {
        if (resFor == Appearance.AUTO || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        v.setInt(R.id.field_root, "setBackgroundColor", color(R.color.bg))
        for (op in ThemeOverrides.forLayout(layout)) {
            val c = color(op.color)
            when (op.kind) {
                ThemeOverrides.Kind.TEXT -> v.setTextColor(op.id, c)
                ThemeOverrides.Kind.TINT -> v.setInt(op.id, "setColorFilter", c)
                ThemeOverrides.Kind.BG -> v.setColorStateList(op.id, "setBackgroundTintList", opaque(c))
                ThemeOverrides.Kind.BG_COLOR -> v.setInt(op.id, "setBackgroundColor", c)
                ThemeOverrides.Kind.PROGRESS -> {
                    v.setColorStateList(op.id, "setProgressTintList", opaque(c))
                    if (op.color2 != 0) v.setColorStateList(op.id, "setProgressBackgroundTintList", opaque(color(op.color2)))
                }
            }
        }
    }

    /**
     * A background swapped in while rendering. With Dark / Light forced it's tinted with its colour
     * in that mode (the drawable's own alpha, e.g. a tonal wash or a fade, is kept).
     */
    private fun setBg(v: RemoteViews, id: Int, drawable: Int) {
        v.setInt(id, "setBackgroundResource", drawable)
        if (resFor == Appearance.AUTO || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        ThemeOverrides.drawableColor[drawable]?.let { v.setColorStateList(id, "setBackgroundTintList", opaque(color(it))) }
    }

    /** A tint that replaces a drawable's colour but keeps its alpha. */
    private fun opaque(c: Int) = android.content.res.ColorStateList.valueOf(c or 0xFF000000.toInt())
    private val density = context.resources.displayMetrics.density

    /** The layout is designed for a Karoo 3 full page (~255x385 dp); scale to the real field size. */
    private val scale: Float = run {
        val (w, h) = config.viewSize
        if (w <= 0 || h <= 0) 1f else minOf(w / (REF_W_DP * density), h / (REF_H_DP * density)).coerceIn(0.5f, 1.6f)
    }
    private var blinkOn = true
    private val chartHeightPx = (CHART_DP * density * scale).toInt()

    private val nextIntent: PendingIntent by lazy { actionIntent(ActionReceiver.ACTION_NEXT, 1) }
    private val pauseIntent: PendingIntent by lazy { actionIntent(ActionReceiver.ACTION_PAUSE, 2) }
    private val previousIntent: PendingIntent by lazy { actionIntent(ActionReceiver.ACTION_PREVIOUS, 3) }
    private val chartIntent: PendingIntent by lazy { actionIntent(ActionReceiver.ACTION_TOGGLE_CHART, 5) }
    private val jumpNowIntent: PendingIntent by lazy { actionIntent(ActionReceiver.ACTION_JUMP_NOW, 6) }
    private val cancelSelectionIntent: PendingIntent by lazy { actionIntent(ActionReceiver.ACTION_CANCEL_SELECTION, 8) }
    private val confirmIntent: PendingIntent by lazy { actionIntent(ActionReceiver.ACTION_CONFIRM_SELECTION, 9) }
    private val cancelMoveIntent: PendingIntent by lazy { actionIntent(ActionReceiver.ACTION_CANCEL_MOVE, 10) }
    private val dismissCancelledIntent: PendingIntent by lazy { actionIntent(ActionReceiver.ACTION_DISMISS_MOVE_CANCELLED, 11) }
    private val targetToggleIntent: PendingIntent by lazy { actionIntent(ActionReceiver.ACTION_TOGGLE_TARGET, 12) }
    private val lapHrToggleIntent: PendingIntent by lazy { actionIntent(ActionReceiver.ACTION_TOGGLE_LAP_HR, 14) }
    private val lapNpToggleIntent: PendingIntent by lazy { actionIntent(ActionReceiver.ACTION_TOGGLE_LAP_NP, 13) }
    private val clockToggleIntent: PendingIntent by lazy { actionIntent(ActionReceiver.ACTION_TOGGLE_CLOCK, 16) }
    private val freeChartIntent: PendingIntent by lazy { actionIntent(ActionReceiver.ACTION_TOGGLE_FREE_CHART, 17) }
    private val freePowerIntent: PendingIntent by lazy { actionIntent(ActionReceiver.ACTION_FREE_POWER, 18) }
    private val freeHrIntent: PendingIntent by lazy { actionIntent(ActionReceiver.ACTION_FREE_HR, 19) }
    private val wattsColorIntent: PendingIntent by lazy { actionIntent(ActionReceiver.ACTION_TOGGLE_WATTS_COLOR, 7) }
    private val rateIntent: PendingIntent by lazy {
        PendingIntent.getActivity(
            context,
            15,
            Intent(context, RateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
    private val settingsIntent: PendingIntent by lazy {
        PendingIntent.getActivity(
            context,
            4,
            Intent(context, SetupActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(SetupActivity.EXTRA_FROM_RIDE, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun render(state: ScreenState): RemoteViews {
        // Debug builds show demo data; in release builds this returns the real state unchanged.
        val s = DemoData.apply(state)
        useAppearance(s.appearance)
        if (s.plan == null) return renderFreeRide(s)
        // The Gauge watts mode has its own layout; the other four share the classic one. Each has
        // a "Karoo native styling" twin (setting on the Account page).
        val gaugeMode = s.wattsMode == WattsMode.GAUGE
        val karoo = s.karooStyle
        val layout = when {
            gaugeMode && karoo -> R.layout.field_interval_gauge_karoo
            gaugeMode -> R.layout.field_interval_gauge
            karoo -> R.layout.field_interval_karoo
            else -> R.layout.field_interval
        }
        val v = RemoteViews(context.packageName, layout)
        applyAppearance(v, layout)
        // The cell values' texts, so a Karoo-style cell can shrink a value that's too wide.
        lapText = (if (s.lapShowsNp) s.lapNp else s.lapAvg)?.toString() ?: "--"
        targetText = s.targetRange.let { band ->
            if (s.targetShowsRange && band != null) "${band.first}–${band.last}" else s.target?.toString() ?: "--"
        }
        hrText = s.heartRate?.toString() ?: "--"
        cadText = s.cadence?.takeIf { it > 0 }?.toString() ?: "--"
        cadTileShown = (s.cadence ?: 0) > 0 || s.currentStep?.cadenceRange != null
        // While a jump is chosen or counting down, the Target tile shows "174 → 189": the Lap avg
        // tile steps aside so it fits (the gauge layouts show the target in the top row instead).
        lapHiddenForJump = !gaugeMode && s.landStep != null
        intensityPct = s.intensityPct
        val top = topOf(s, gaugeMode, free = false)
        if (top == TopRow.CADENCE) cadTileShown = false
        if (gaugeMode && top != TopRow.TARGET) hrText = gaugeTargetText(s) // the HR tile shows the target
        applyTextSizes(v, gaugeMode, karoo)

        v.setViewVisibility(R.id.content, View.VISIBLE)
        v.setViewVisibility(R.id.btn_settings, View.VISIBLE)
        v.setViewVisibility(R.id.btn_pause, View.VISIBLE)
        v.setOnClickPendingIntent(R.id.btn_settings, settingsIntent)

        // 3 s average power. Tap to cycle how the target range shows: plain, coloured number,
        // coloured background behind the top of the screen, both, or the power gauge.
        val range = rangeState(s)
        val wattsColor = if (s.wattsMode.colorsText && range != null) {
            when (range) {
                RangeState.UNDER -> R.color.watts_under
                RangeState.IN -> R.color.watts_in_range
                RangeState.OVER -> R.color.watts_over
            }
        } else {
            R.color.text
        }
        if (gaugeMode) {
            val gaugeW = (config.viewSize.first - 16 * density).toInt()
            if (gaugeW > 0) {
                v.setImageViewBitmap(R.id.gauge, gauge.render(gaugeW, gaugeHeightPx(karoo, s.navigating), s.power3s, s.target, s.targetRange, color(wattsColor), karoo))
            }
        } else {
            v.setTextViewText(R.id.watts, s.power3s?.toString() ?: "--")
            v.setTextColor(R.id.watts, color(wattsColor))
        }
        val upperBg = if (s.wattsMode.colorsBackground && range != null) {
            when (range) {
                RangeState.UNDER -> R.drawable.bg_range_under
                RangeState.IN -> R.drawable.bg_range_in
                RangeState.OVER -> R.drawable.bg_range_over
            }
        } else {
            0 // no background
        }
        // The range tint is the background of the upper part: the bottom layer, behind the watts,
        // tiles and everything else there, in every theme.
        if (upperBg != 0) setBg(v, R.id.upper, upperBg) else v.setInt(R.id.upper, "setBackgroundResource", 0)
        // On a tint, the tile labels use the full text colour so they stay readable (>= 7:1).
        // Karoo native styling: labels in the text colour, like Karoo's own fields.
        val labelColor = color(if (upperBg != 0 || karoo) R.color.text else R.color.text_secondary)
        v.setTextColor(R.id.lap_avg_label, labelColor)
        v.setTextColor(if (gaugeMode) R.id.hr_label else R.id.target_label, labelColor)
        // Karoo style: green icons turn black (the text colour) on the range tint, so they show.
        if (karoo) {
            val iconColor = color(if (upperBg != 0) R.color.text else R.color.karoo_green)
            val icons = if (gaugeMode) listOf(R.id.icon_timer, R.id.icon_lap, R.id.icon_bolt)
            else listOf(R.id.icon_timer, R.id.icon_lap, R.id.icon_bolt, R.id.icon_target)
            icons.forEach { v.setInt(it, "setColorFilter", iconColor) }
        }
        v.setOnClickPendingIntent(if (gaugeMode) R.id.gauge else R.id.watts, wattsColorIntent)
        v.setTextViewText(R.id.hr, s.heartRate?.toString() ?: "--")

        // Pause / resume (top right). Paused by you: green half-pill with a filled play symbol.
        // Ride paused by Karoo: hidden, since the workout resumes with the ride.
        val ridePaused = s.paused
        // Before START the green play pill is the start button (tap again to cancel the countdown).
        val resumeShown = (s.workoutPaused || s.waiting) && !ridePaused
        // Small grey pause button normally; the big green resume half-pill while you've paused.
        v.setViewVisibility(
            R.id.btn_pause,
            when {
                resumeShown -> View.GONE
                ridePaused -> View.INVISIBLE
                else -> View.VISIBLE
            },
        )
        v.setViewVisibility(R.id.btn_resume, if (resumeShown) View.VISIBLE else View.GONE)
        // Tonal: the text colour at 55%, so a range tint shows through (a translucent tint colour
        // alone comes out solid on the Karoo).
        v.setInt(R.id.btn_pause_icon, "setColorFilter", color(R.color.text))
        v.setInt(R.id.btn_pause_icon, "setImageAlpha", ICON_ALPHA)
        v.setOnClickPendingIntent(R.id.btn_pause, pauseIntent)
        v.setOnClickPendingIntent(R.id.btn_resume, pauseIntent)

        // Interval number, or a yellow box while paused, or "Workout complete"
        if (ridePaused || s.workoutPaused) {
            v.setViewVisibility(R.id.interval_label, View.GONE)
            v.setViewVisibility(R.id.paused_box, View.VISIBLE)
            if (karoo) v.setViewVisibility(R.id.icon_timer, View.GONE)
            if (karoo) v.setViewVisibility(R.id.paused_spacer, View.VISIBLE)
            v.setTextViewText(
                R.id.paused_text,
                context.getString(if (ridePaused) R.string.workout_auto_paused else R.string.workout_paused),
            )
            // Blink the pause icon: flip on every redraw (Karoo redraws a field about once a second).
            blinkOn = !blinkOn
            v.setInt(R.id.paused_icon, "setImageAlpha", if (blinkOn) 255 else 70)
            // The resume button pulses in step, between its two greens.
            if (resumeShown) {
                val (on, off) = if (karoo) R.drawable.bg_resume_karoo to R.drawable.bg_resume_karoo_alt else R.drawable.bg_resume to R.drawable.bg_resume_alt
                setBg(v, R.id.btn_resume, if (blinkOn) on else off)
            }
        } else {
            v.setViewVisibility(R.id.interval_label, View.VISIBLE)
            v.setViewVisibility(R.id.paused_box, View.GONE)
            if (karoo) v.setViewVisibility(R.id.icon_timer, View.VISIBLE)
            if (karoo) v.setViewVisibility(R.id.paused_spacer, View.GONE)
            if (s.waiting) {
                // Waiting for START: "Tap ▶ to start" (or the start countdown), the pill pulsing.
                v.setTextViewText(
                    R.id.interval_label,
                    s.countdown?.let { context.getString(R.string.start_countdown, it) } ?: context.getString(R.string.tap_to_start),
                )
                v.setTextColor(R.id.interval_label, color(R.color.watts_in_range))
                blinkOn = !blinkOn
                if (resumeShown) {
                    val (on, off) = if (karoo) R.drawable.bg_resume_karoo to R.drawable.bg_resume_karoo_alt else R.drawable.bg_resume to R.drawable.bg_resume_alt
                    setBg(v, R.id.btn_resume, if (blinkOn) on else off)
                }
            } else if (s.finished) {
                // Tap "Workout complete · Rate the ride" to rate.
                v.setOnClickPendingIntent(R.id.interval_label, rateIntent)
                v.setTextViewText(
                    R.id.interval_label,
                    listOfNotNull(context.getString(R.string.workout_complete), s.ratingSummary ?: context.getString(R.string.rate_prompt_short))
                        .joinToString(SEP),
                )
                v.setTextColor(R.id.interval_label, color(R.color.watts_in_range))
            } else {
                v.setTextViewText(R.id.interval_label, intervalLabel(s))
                v.setTextColor(R.id.interval_label, color(if (karoo) R.color.text else R.color.text_secondary))
            }
        }
        // Time left in the interval, with a minus so it reads as counting down.
        // Tap the clock: time left in the interval (−1:35, default) ↔ time in it so far (3:25).
        v.setTextViewText(
            R.id.time_left,
            if (s.waiting) {
                s.currentStep?.let { formatClock(it.durationSec) } ?: "--:--"
            } else if (s.clockShowsElapsed) {
                s.currentStep?.let { formatClock(s.elapsedInStepSec.coerceAtMost(it.durationSec)) } ?: "--:--"
            } else {
                s.intervalRemainingSec?.let { "\u2212" + formatClock(it) } ?: "--:--"
            },
        )
        v.setOnClickPendingIntent(R.id.time_left, clockToggleIntent)
        val total = s.intervalTotalSec ?: 0
        val progress = if (total > 0) (s.elapsedInStepSec * 1000 / total).coerceIn(0, 1000) else 0
        v.setProgressBar(R.id.interval_progress, 1000, progress, false)

        // Tiles
        // Lap tile: tap to switch between the lap average and lap NP.
        v.setTextViewText(R.id.lap_avg_label, context.getString(if (s.lapShowsNp) R.string.np else R.string.lap_avg))
        v.setTextViewText(R.id.lap_avg, (if (s.lapShowsNp) s.lapNp else s.lapAvg)?.toString() ?: "--")
        v.setOnClickPendingIntent(R.id.lap_tile, lapNpToggleIntent)
        v.setViewVisibility(R.id.lap_tile, if (lapHiddenForJump) View.GONE else View.VISIBLE)

        // Cadence tile: live rpm (or --), the step's target in the label, the number blue / green
        // / red below / in / above the target. Shown when there's a cadence sensor or a target.
        v.setViewVisibility(R.id.cad_tile, if (cadTileShown) View.VISIBLE else View.GONE)
        if (cadTileShown) {
            val step = s.currentStep
            v.setTextViewText(
                R.id.cad_label,
                step?.cadenceRange?.let { context.getString(R.string.cadence_target, it) } ?: context.getString(R.string.cadence),
            )
            v.setTextViewText(R.id.cad_value, cadText)
            val live = s.cadence?.takeIf { it > 0 }
            val cadColor = when (if (live != null) step?.cadenceState(live) else null) {
                -1 -> R.color.watts_under
                0 -> R.color.watts_in_range
                1 -> R.color.watts_over
                else -> R.color.text
            }
            v.setTextColor(R.id.cad_value, color(cadColor))
            v.setTextColor(R.id.cad_label, labelColor)
        }
        val band = s.targetRange
        if (gaugeMode) {
            // Target (top row): the range, or the target when the step has no range.
            v.setTextViewText(
                R.id.target,
                if (band != null && band.first != band.last) "${band.first} – ${band.last}" else s.target?.toString() ?: "--",
            )
        } else {
            // Target tile: tap to switch between the target and the target range.
            // (Karoo native styling keeps the label "TARGET": "TARGET RANGE" doesn't fit at Karoo's
            // label size, and the value shows whether it's a range.)
            if (s.targetShowsRange && band != null) {
                v.setTextViewText(R.id.target_label, context.getString(if (karoo) R.string.target else R.string.target_range))
                v.setTextViewText(R.id.target, "${band.first}–${band.last}")
            } else {
                v.setTextViewText(R.id.target_label, context.getString(R.string.target))
                v.setTextViewText(R.id.target, s.target?.toString() ?: "--")
            }
            // The trainer holds this target in ERG.
            // The trainer holds this target in ERG; a changed intensity shows as "95%".
            val pct = if (s.intensityPct != 100) " ${s.intensityPct}%" else ""
            when {
                s.ergActive -> v.setTextViewText(R.id.target_label, context.getString(R.string.target_erg) + pct)
                pct.isNotEmpty() -> v.setTextViewText(R.id.target_label, context.getString(R.string.target) + pct)
            }
            v.setOnClickPendingIntent(R.id.target_tile, targetToggleIntent)
        }
        // Where a jump would land (being chosen or counting down): shown next to the target.
        val land = s.landStep
        if (land != null) {
            val next = "→ ${s.scaled(land.targetAt(0))}"
            v.setTextViewText(
                R.id.target_next,
                // The number in the text colour; only the arrow in the jump colour (Karoo purple, or blue).
                android.text.SpannableString(next).apply {
                    val arrow = color(if (karoo) R.color.karoo_purple else R.color.land)
                    setSpan(android.text.style.ForegroundColorSpan(arrow), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                },
            )
            v.setViewVisibility(R.id.target_next, View.VISIBLE)
        } else {
            v.setViewVisibility(R.id.target_next, View.GONE)
        }

        // Next up box / countdown box
        val jump = s.jumpStep
        if (s.countdown != null && jump != null) {
            v.setViewVisibility(R.id.next_box, View.GONE)
            v.setViewVisibility(R.id.countdown_box, View.VISIBLE)
            v.setViewVisibility(R.id.tiles_row, View.VISIBLE)
            v.setViewVisibility(R.id.confirm_row, View.GONE)
            v.setViewVisibility(R.id.move_cancelled, View.GONE)
            v.setTextViewText(R.id.countdown, s.countdown.toString())
            v.setTextViewText(R.id.countdown_value, summary(jump))
            // The box fills with its darker colour as the seconds run down.
            val total = WorkoutSession.COUNTDOWN_SECONDS
            v.setProgressBar(R.id.countdown_fill, 1000, ((total - s.countdown + 1) * 1000 / total).coerceIn(0, 1000), false)
        } else {
            v.setViewVisibility(R.id.next_box, View.VISIBLE)
            v.setViewVisibility(R.id.countdown_box, View.GONE)
            val choosing = s.selectTarget?.let { s.plan?.steps?.getOrNull(it) }
            val next = s.nextStep
            when {
                s.waiting -> {
                    // "First up" or, after ⏮ / ⏭, "Start at 3 / 16": the interval it starts with.
                    v.setTextViewText(
                        R.id.next_label,
                        if (s.stepIndex == 0) context.getString(R.string.first_up) else context.getString(R.string.start_at, s.stepIndex + 1, s.stepCount),
                    )
                    v.setViewVisibility(R.id.next_label, View.VISIBLE)
                    v.setTextViewText(R.id.next_value, s.currentStep?.let { summary(it) } ?: "")
                    v.setTextColor(R.id.next_value, color(R.color.text))
                }
                s.finished && s.summary != null -> {
                    // Workout complete: its time and average power, NP and heart rate.
                    val sm = s.summary
                    v.setTextViewText(
                        R.id.next_label,
                        (listOf(context.getString(R.string.summary_label)) + listOfNotNull(sm.np?.let { "NP $it" }, sm.avgHr?.let { context.getString(R.string.bpm_value, it) })).joinToString(SEP),
                    )
                    v.setViewVisibility(R.id.next_label, View.VISIBLE)
                    // Heart rate drift: green under 5 % (the aerobic base held), orange above.
                    val drift = sm.driftPct?.let { context.getString(R.string.drift_value, String.format(java.util.Locale.forLanguageTag("pl"), "%.1f", it)) }
                    val line = listOfNotNull(formatClock(sm.timeSec), sm.avgPower?.let { "$it W" }, drift).joinToString(SEP)
                    v.setTextViewText(
                        R.id.next_value,
                        if (drift == null) {
                            line
                        } else {
                            android.text.SpannableString(line).apply {
                                val c = color(if (sm.driftPct!! < 5.0) R.color.watts_in_range else R.color.watts_over)
                                setSpan(android.text.style.ForegroundColorSpan(c), line.length - drift.length, line.length, 0)
                            }
                        },
                    )
                    v.setTextColor(R.id.next_value, color(R.color.text))
                }
                choosing != null -> {
                    // How far the jump goes, counted in intervals from where you are.
                    val distance = (s.selectTarget ?: s.stepIndex) - s.stepIndex
                    val label = when {
                        s.selectRestart -> context.getString(R.string.restarting_interval)
                        distance > 0 -> context.resources.getQuantityString(R.plurals.jumping_ahead, distance, distance)
                        else -> context.resources.getQuantityString(R.plurals.jumping_back, -distance, -distance)
                    }
                    v.setTextViewText(R.id.next_label, label)
                    v.setViewVisibility(R.id.next_label, View.VISIBLE)
                    v.setTextViewText(R.id.next_value, summary(choosing))
                    v.setTextColor(R.id.next_value, color(R.color.text))
                }
                next != null -> {
                    v.setTextViewText(R.id.next_label, withDetails(context.getString(R.string.next_up), next, karoo))
                    v.setViewVisibility(R.id.next_label, View.VISIBLE)
                    v.setTextViewText(R.id.next_value, summary(next))
                    v.setTextColor(R.id.next_value, color(R.color.text))
                }
                s.finishArmed -> {
                    v.setViewVisibility(R.id.next_label, View.GONE)
                    v.setTextViewText(R.id.next_value, context.getString(R.string.finish_confirm))
                    v.setTextColor(R.id.next_value, color(R.color.text))
                }
                else -> {
                    v.setViewVisibility(R.id.next_label, View.GONE)
                    v.setTextViewText(R.id.next_value, context.getString(R.string.next_last))
                    v.setTextColor(R.id.next_value, color(R.color.text_secondary))
                }
            }
            v.setViewVisibility(R.id.select_fill, View.GONE)
            // One 52 dp row: "Confirm move" + ✕ while choosing, "Move cancelled" for a moment after
            // its ✕, otherwise the Lap avg / Target tiles.
            val showConfirm = choosing != null
            val showCancelled = !showConfirm && s.moveCancelled
            v.setViewVisibility(R.id.confirm_row, if (showConfirm) View.VISIBLE else View.GONE)
            // Chosen with a remote button: the button fills and confirms itself.
            val left = s.autoConfirmLeft
            if (showConfirm && left != null) {
                val total = WorkoutSession.AUTO_CONFIRM_SECONDS
                v.setViewVisibility(R.id.confirm_fill, View.VISIBLE)
                v.setProgressBar(R.id.confirm_fill, 1000, (total - left + 1) * 1000 / total, false)
                v.setTextViewText(R.id.confirm_text, context.getString(R.string.confirming, left))
            } else {
                v.setViewVisibility(R.id.confirm_fill, View.GONE)
                v.setTextViewText(R.id.confirm_text, context.getString(R.string.confirm_move))
            }
            v.setViewVisibility(R.id.move_cancelled, if (showCancelled) View.VISIBLE else View.GONE)
            v.setViewVisibility(R.id.tiles_row, if (showConfirm || showCancelled) View.GONE else View.VISIBLE)
            // Buttons keep their space when hidden, so the text stays centred. While choosing,
            // both stay available so you can tap back and forth.
            // On the last interval ⏭ ends the workout (tap twice), until it's finished.
            v.setViewVisibility(R.id.btn_skip, if (s.hasNext || choosing != null || !s.finished) View.VISIBLE else View.INVISIBLE)
            v.setViewVisibility(R.id.btn_previous, View.VISIBLE)
        }
        // The skip button is grey and not tappable while the ride or workout is paused, so the play
        // button (Karoo's, or our resume button) is the one to press. Blue normally; with Karoo
        // native styling inverted (black in light mode, white in dark), so Karoo's yellow stays
        // for its own navigation (route pill, page arrows).
        val skipEnabled = !(ridePaused || s.workoutPaused)
        val skipBg = when {
            !skipEnabled -> R.drawable.bg_btn_skip_disabled
            karoo -> R.drawable.bg_btn_skip_inverted
            else -> R.drawable.bg_btn_skip
        }
        val skipFg = when {
            !skipEnabled -> R.color.skip_disabled_fg
            karoo -> R.color.bg
            else -> R.color.skip_fg
        }
        setBg(v, R.id.skip_bg, skipBg)
        v.setInt(R.id.skip_icon, "setColorFilter", color(skipFg))
        // Taps may or may not reach a data field on Karoo; the bonus actions (remote buttons) always work.
        v.setOnClickPendingIntent(R.id.btn_skip, if (skipEnabled) nextIntent else null)
        v.setOnClickPendingIntent(R.id.btn_previous, previousIntent)
        v.setOnClickPendingIntent(R.id.next_mid, if (s.waiting) pauseIntent else cancelSelectionIntent)
        v.setOnClickPendingIntent(R.id.countdown_box, jumpNowIntent)
        v.setOnClickPendingIntent(R.id.btn_confirm, confirmIntent)
        v.setOnClickPendingIntent(R.id.btn_confirm_x, cancelMoveIntent)
        v.setOnClickPendingIntent(R.id.move_cancelled, dismissCancelledIntent)

        applyTopRow(v, s, top, gaugeMode, karoo, free = false)

        // Chart
        val chartW = (config.viewSize.first - 16 * density).toInt()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            v.setViewLayoutHeight(R.id.chart, chartHeightPx.toFloat(), TypedValue.COMPLEX_UNIT_PX)
        }
        if (chartW > 0) v.setImageViewBitmap(R.id.chart, chart.render(s, chartW, chartHeightPx, scale))
        v.setOnClickPendingIntent(R.id.chart, chartIntent)

        return v
    }

    /**
     * What the top row shows. AUTO: heart rate above the big number, the target above the gauge.
     * Without a workout there's no target: the gauge's top row keeps the last lap, the number
     * layouts heart rate.
     */
    private fun topOf(s: ScreenState, gaugeMode: Boolean, free: Boolean): TopRow = when (s.topRow) {
        TopRow.AUTO -> if (gaugeMode) TopRow.TARGET else TopRow.HR
        TopRow.TARGET -> if (free && !gaugeMode) TopRow.HR else TopRow.TARGET
        else -> s.topRow
    }

    /** The target as the gauge's top row writes it: "161 – 189", or the single target. */
    private fun gaugeTargetText(s: ScreenState): String {
        val band = s.targetRange
        return if (band != null && band.first != band.last) "${band.first} – ${band.last}" else s.target?.toString() ?: "--"
    }

    /**
     * Puts heart rate, cadence or the target in the top row (Settings › Top row). What moves up
     * leaves its tile: with the target at the top of a number layout the Target tile shows heart
     * rate; with heart rate or cadence above the gauge, its HR tile shows the target. Cadence at
     * the top hides the cadence tile.
     */
    private fun applyTopRow(v: RemoteViews, s: ScreenState, top: TopRow, gaugeMode: Boolean, karoo: Boolean, free: Boolean) {
        val cad = s.cadence?.takeIf { it > 0 }?.toString() ?: "--"
        val hr = s.heartRate?.toString() ?: "--"
        if (!gaugeMode) {
            val heart = if (karoo) R.id.th_interval_karoo_10 else R.id.th_interval_7
            when (top) {
                TopRow.CADENCE -> {
                    v.setViewVisibility(heart, View.GONE)
                    v.setTextViewText(R.id.hr, "$cad rpm")
                }
                TopRow.TARGET -> if (!free) {
                    v.setViewVisibility(heart, View.GONE)
                    val land = s.landStep?.let { " \u2192 ${s.scaled(it.targetAt(0))}" } ?: ""
                    v.setTextViewText(R.id.hr, context.getString(R.string.target) + " " + gaugeTargetText(s) + land)
                    v.setTextViewText(R.id.target_label, context.getString(if (karoo) R.string.hr else R.string.heart_rate))
                    v.setTextViewText(R.id.target, hr)
                    v.setViewVisibility(R.id.target_next, View.GONE)
                }
                else -> Unit
            }
            return
        }
        // Gauge layouts: the top row is the target block.
        if (top == TopRow.TARGET) return
        v.setViewVisibility(R.id.target_block, View.VISIBLE)
        v.setTextViewText(R.id.target_label, context.getString(if (top == TopRow.CADENCE) R.string.cadence else R.string.heart_rate))
        v.setTextViewText(R.id.target, if (top == TopRow.CADENCE) cad else hr)
        v.setViewVisibility(R.id.target_next, View.GONE)
        if (!free) {
            // The HR tile shows the target instead.
            v.setViewVisibility(if (karoo) R.id.th_interval_gauge_karoo_3 else R.id.th_interval_gauge_1, View.GONE)
            v.setTextViewText(R.id.hr_label, context.getString(R.string.target))
            v.setTextViewText(R.id.hr, gaugeTargetText(s))
        }
    }

    private enum class RangeState { UNDER, IN, OVER }

    /**
     * Where 3 s power sits against the target range, or null when the colours shouldn't show:
     * workout or ride paused, workout finished, or no power or target.
     */
    private fun rangeState(s: ScreenState): RangeState? {
        if (s.finished || s.workoutPaused || s.paused || s.waiting) return null
        val p = s.power3s ?: return null
        val range = s.targetRange ?: return null
        return when {
            p < range.first -> RangeState.UNDER
            p > range.last -> RangeState.OVER
            else -> RangeState.IN
        }
    }

    /**
     * Free ride (no workout loaded), on the same layout as a workout so the top half doesn't
     * change: watts (number or gauge, as chosen), "Lap 3" with the lap time where the interval
     * and its clock are, and the tiles (lap avg / NP, Last lap or HR, cadence once it reports).
     * The next box and the workout profile make way for the ride's power chart, coloured by the
     * Karoo's power zones; tap it for the span. The gauge layouts show the last lap up top.
     */
    private fun renderFreeRide(s: ScreenState): RemoteViews {
        val gaugeMode = s.wattsMode == WattsMode.GAUGE
        val karoo = s.karooStyle
        val layout = when {
            gaugeMode && karoo -> R.layout.field_interval_gauge_karoo
            gaugeMode -> R.layout.field_interval_gauge
            karoo -> R.layout.field_interval_karoo
            else -> R.layout.field_interval
        }
        val v = RemoteViews(context.packageName, layout)
        applyAppearance(v, layout)
        val cadence = s.cadence?.takeIf { it > 0 }
        if (cadence != null) freeCadSeen = true
        val lastLapTime = s.lastLapTimeSec?.takeIf { it > 0 }
        // Power tile: lap avg → lap NP → ride avg; HR tile (gauge): now → lap avg → ride avg.
        lapText = when (s.freePowerTile) {
            1 -> s.freeLapNp
            2 -> s.rideAvgPower
            else -> s.lapPowerAvg
        }?.toString() ?: "--"
        targetText = s.lastLapPowerAvg?.takeIf { lastLapTime != null }?.toString() ?: "--"
        val freeHr = when (s.freeHrTile) {
            1 -> s.lapHrAvg
            2 -> s.rideAvgHr
            else -> s.heartRate
        }
        hrText = freeHr?.toString() ?: "--"
        cadText = cadence?.toString() ?: "--"
        cadTileShown = freeCadSeen
        val top = topOf(s, gaugeMode, free = true)
        if (top == TopRow.CADENCE) cadTileShown = false
        // The classic layouts' Target tile has nothing to show without a workout: hidden, so the
        // tiles are one fewer (the gauge layouts' tiles are Lap avg, HR and Cadence).
        lapHiddenForJump = !gaugeMode
        intensityPct = 100
        applyTextSizes(v, gaugeMode, karoo)

        v.setViewVisibility(R.id.content, View.VISIBLE)
        v.setViewVisibility(R.id.btn_settings, View.VISIBLE)
        v.setOnClickPendingIntent(R.id.btn_settings, settingsIntent)
        // Nothing to pause without a workout (the corner keeps its space).
        v.setViewVisibility(R.id.btn_pause, View.INVISIBLE)
        v.setViewVisibility(R.id.btn_resume, View.GONE)

        // Watts in the colour of the Karoo power zone they're in, like the range colours in a
        // workout: tap cycles plain, coloured number, coloured background, both, and the gauge.
        val zone = s.power3s?.takeIf { it > 0 && !s.paused }?.let { powerZoneIndex(it, s.powerZones, s.profileFtp) }
        val zoneText = zone?.let { intArrayOf(R.color.zone_text_1, R.color.zone_text_2, R.color.zone_text_3, R.color.zone_text_4, R.color.zone_text_5, R.color.zone_text_6)[it] }
        val wattsColor = if (s.wattsMode.colorsText && zoneText != null) zoneText else R.color.text
        if (gaugeMode) {
            // No target band without a workout: the arc shows the 6 power zones, the zone you're in
            // drawn wide with the needle in it, and the number takes the zone colour.
            val gaugeW = (config.viewSize.first - 16 * density).toInt()
            if (gaugeW > 0) {
                v.setImageViewBitmap(R.id.gauge, gauge.renderZones(gaugeW, gaugeHeightPx(karoo, s.navigating), s.power3s, s.powerZones, s.profileFtp, color(zoneText ?: R.color.text), karoo))
            }
        } else {
            v.setTextViewText(R.id.watts, s.power3s?.toString() ?: "--")
            v.setTextColor(R.id.watts, color(wattsColor))
        }
        val upperBg = if (s.wattsMode.colorsBackground && zone != null) {
            intArrayOf(R.drawable.bg_zone_1, R.drawable.bg_zone_2, R.drawable.bg_zone_3, R.drawable.bg_zone_4, R.drawable.bg_zone_5, R.drawable.bg_zone_6)[zone]
        } else {
            0
        }
        if (upperBg != 0) setBg(v, R.id.upper, upperBg) else v.setInt(R.id.upper, "setBackgroundResource", 0)
        v.setOnClickPendingIntent(if (gaugeMode) R.id.gauge else R.id.watts, wattsColorIntent)
        val labelColor = color(if (upperBg != 0 || karoo) R.color.text else R.color.text_secondary)
        v.setTextColor(R.id.lap_avg_label, labelColor)
        v.setTextColor(if (gaugeMode) R.id.hr_label else R.id.target_label, labelColor)
        if (karoo) {
            val icons = if (gaugeMode) listOf(R.id.icon_timer, R.id.icon_lap, R.id.icon_bolt)
            else listOf(R.id.icon_timer, R.id.icon_lap, R.id.icon_bolt, R.id.icon_target)
            icons.forEach { v.setInt(it, "setColorFilter", color(R.color.karoo_green)) }
        }
        // Top HR (classic) shows the heart rate now; the gauge's HR tile goes through its values.
        v.setTextViewText(R.id.hr, if (gaugeMode) hrText else s.heartRate?.toString() ?: "--")
        if (gaugeMode) {
            v.setTextViewText(
                R.id.hr_label,
                context.getString(
                    when (s.freeHrTile) {
                        1 -> R.string.lap_hr
                        2 -> R.string.ride_hr
                        else -> R.string.hr
                    },
                ),
            )
            v.setOnClickPendingIntent(R.id.hr_tile, freeHrIntent)
        }

        // "Lap 3" and the lap time where the interval and its clock are; "Paused" while paused.
        if (s.paused) {
            v.setViewVisibility(R.id.interval_label, View.GONE)
            v.setViewVisibility(R.id.paused_box, View.VISIBLE)
            if (karoo) v.setViewVisibility(R.id.icon_timer, View.GONE)
            if (karoo) v.setViewVisibility(R.id.paused_spacer, View.VISIBLE)
            v.setTextViewText(R.id.paused_text, context.getString(R.string.paused))
            blinkOn = !blinkOn
            v.setInt(R.id.paused_icon, "setImageAlpha", if (blinkOn) 255 else 70)
        } else {
            v.setViewVisibility(R.id.interval_label, View.VISIBLE)
            v.setViewVisibility(R.id.paused_box, View.GONE)
            if (karoo) v.setViewVisibility(R.id.icon_timer, View.VISIBLE)
            if (karoo) v.setViewVisibility(R.id.paused_spacer, View.GONE)
            v.setTextViewText(R.id.interval_label, s.lapNumber?.let { context.getString(R.string.lap_number, it) } ?: "")
            v.setTextColor(R.id.interval_label, color(if (karoo) R.color.text else R.color.text_secondary))
        }
        v.setTextViewText(R.id.time_left, s.lapTimeSec?.let(::formatClock) ?: "--:--")
        // No interval to fill: the bar keeps its space so the tiles stay where they are.
        v.setViewVisibility(R.id.interval_progress, View.INVISIBLE)

        // Tiles: lap avg / NP (tap), cadence once it reports.
        v.setViewVisibility(R.id.tiles_row, View.VISIBLE)
        v.setViewVisibility(R.id.confirm_row, View.GONE)
        v.setViewVisibility(R.id.move_cancelled, View.GONE)
        v.setTextViewText(
            R.id.lap_avg_label,
            context.getString(
                when (s.freePowerTile) {
                    1 -> R.string.np
                    2 -> R.string.ride_avg
                    else -> R.string.lap_avg
                },
            ),
        )
        v.setTextViewText(R.id.lap_avg, lapText)
        v.setOnClickPendingIntent(R.id.lap_tile, freePowerIntent)
        v.setViewVisibility(R.id.lap_tile, View.VISIBLE)
        v.setViewVisibility(R.id.cad_tile, if (cadTileShown) View.VISIBLE else View.GONE)
        if (cadTileShown) {
            v.setTextViewText(R.id.cad_label, context.getString(R.string.cadence))
            v.setTextViewText(R.id.cad_value, cadText)
            v.setTextColor(R.id.cad_value, color(R.color.text))
            v.setTextColor(R.id.cad_label, labelColor)
        }
        v.setViewVisibility(R.id.target_next, View.GONE)
        val lastLap = lastLapTime?.let { t -> listOfNotNull(formatClock(t), s.lastLapPowerAvg?.let { "$it W" }).joinToString(" · ") }
        if (gaugeMode) {
            // Top row: the last lap ("Ost. okr. 5:00 · 180 W"), hidden until the first lap ends.
            v.setViewVisibility(R.id.target_block, if (lastLap != null) View.VISIBLE else View.INVISIBLE)
            v.setTextViewText(R.id.target_label, context.getString(R.string.last_lap_label))
            v.setTextViewText(R.id.target, lastLap ?: "")
        } else {
            v.setViewVisibility(R.id.target_tile, View.GONE)
        }

        applyTopRow(v, s, top, gaugeMode, karoo, free = true)

        // Bottom: the power chart in the space of the next box (48 dp + 6 dp) and the profile.
        v.setViewVisibility(R.id.next_box, View.GONE)
        v.setViewVisibility(R.id.countdown_box, View.GONE)
        v.setViewVisibility(R.id.chart, View.GONE)
        val chartW = (config.viewSize.first - 16 * density).toInt()
        val chartH = ((48 + 6) * density + chartHeightPx).toInt().coerceAtLeast(1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            v.setViewLayoutHeight(R.id.free_chart, chartH.toFloat(), TypedValue.COMPLEX_UNIT_PX)
        }
        if (chartW > 0) {
            v.setImageViewBitmap(R.id.free_chart, powerChart.render(chartW, chartH, s.powerZones, s.profileFtp, s.freeChartSpan, density * scale))
            v.setViewVisibility(R.id.free_chart, View.VISIBLE)
        }
        v.setOnClickPendingIntent(R.id.free_chart, freeChartIntent)
        return v
    }

    /**
     * Free ride (no workout loaded): big 3 s watts, lap average and heart rate tiles (the heart
     * rate tile takes the colour of the current HR zone), then Karoo's lap number, lap time and
     * last lap. The gear opens the Workouts page to load a workout.
     */
    private fun renderFree(s: ScreenState): RemoteViews {
        // Karoo native styling: watts first, a LAP TIME row, Lap avg and HR as full-size Karoo data
        // fields and Last lap at the bottom (field_free_karoo.xml).
        val karoo = s.karooStyle
        val layout = if (karoo) R.layout.field_free_karoo else R.layout.field_free
        val v = RemoteViews(context.packageName, layout)
        applyAppearance(v, layout)
        (
            if (karoo) {
                listOf(
                    R.id.free_lap_avg to 48f, R.id.free_hr to 48f, R.id.free_lap_label to 15f, R.id.free_hr_label to 15f,
                    R.id.free_cad to 48f, R.id.free_cad_label to 15f,
                    R.id.free_lap_time to 23f,
                )
            } else {
                listOf(
                    R.id.free_lap_avg to 36f, R.id.free_hr to 36f,
                    R.id.free_lap_label to 13f, R.id.free_hr_label to 13f,
                    R.id.free_cad to 36f, R.id.free_cad_label to 13f,
                    R.id.free_lap_number to 15f, R.id.free_lap_time to 40f, R.id.free_last_lap to 14f,
                )
            }
        ).forEach { (id, sp) -> v.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_PX, sp * density * scale) }

        v.setTextViewText(R.id.free_watts, s.power3s?.toString() ?: "--")

        // The ride's power at the bottom, coloured by the Karoo's 6 power zones; tap for the span.
        val chartW = (config.viewSize.first - 16 * density).toInt()
        // Taller than the workout profile: without a workout the big watts number can spare it.
        val chartH = (FREE_CHART_DP * density * scale).toInt().coerceAtLeast(1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            v.setViewLayoutHeight(R.id.free_chart, chartH.toFloat(), TypedValue.COMPLEX_UNIT_PX)
        }
        val history = if (chartW > 0) {
            powerChart.render(chartW, chartH, s.powerZones, s.profileFtp, s.freeChartSpan, density * scale)
        } else {
            null
        }
        v.setOnClickPendingIntent(R.id.free_chart, freeChartIntent)
        if (history != null) {
            v.setViewVisibility(R.id.free_chart, View.VISIBLE)
            v.setImageViewBitmap(R.id.free_chart, history)
        } else {
            v.setViewVisibility(R.id.free_chart, View.GONE)
        }
        v.setTextViewText(R.id.free_lap_label, context.getString(if (s.lapShowsNp) R.string.np else R.string.lap_avg))
        v.setTextViewText(R.id.free_lap_avg, (if (s.lapShowsNp) s.freeLapNp else s.lapPowerAvg)?.toString() ?: "--")
        v.setOnClickPendingIntent(R.id.free_lap_tile, lapNpToggleIntent)
        // HR tile: tap to switch between the current heart rate and the lap's average ("LAP HR").
        v.setTextViewText(R.id.free_hr, s.shownHr?.toString() ?: "--")
        v.setTextViewText(
            R.id.free_hr_label,
            context.getString(if (s.hrShowsLap) R.string.lap_hr else if (karoo) R.string.hr else R.string.heart_rate),
        )
        v.setOnClickPendingIntent(R.id.free_hr_tile, lapHrToggleIntent)

        // Cadence: a third tile once a cadence sensor (or power meter) reports it.
        val cadence = s.cadence?.takeIf { it > 0 }
        v.setViewVisibility(R.id.free_cad_tile, if (cadence != null || freeCadSeen) View.VISIBLE else View.GONE)
        if (cadence != null) freeCadSeen = true
        v.setTextViewText(R.id.free_cad_label, context.getString(R.string.cadence))
        v.setTextViewText(R.id.free_cad, cadence?.toString() ?: "--")

        // The tile's tint follows the heart rate it shows.
        val zoneBg = when (s.zoneOf(s.shownHr)) {
            1 -> R.drawable.bg_hr_z1
            2 -> R.drawable.bg_hr_z2
            3 -> R.drawable.bg_hr_z3
            4 -> R.drawable.bg_hr_z4
            5 -> R.drawable.bg_hr_z5
            else -> null
        }
        // Soft tint of the HR zone colour; text keeps the normal colours and the heart stays red.
        setBg(v, R.id.free_hr_tile, zoneBg ?: if (karoo) R.drawable.bg_tile_tonal else R.drawable.bg_tile)

        v.setTextViewText(R.id.free_lap_number, s.lapNumber?.let { context.getString(R.string.lap_number, it) } ?: "")
        v.setTextViewText(R.id.free_lap_time, s.lapTimeSec?.let(::formatClock) ?: "--:--")
        // Last lap: time, average watts and average heart rate ("8:20 · 271 W · 142 bpm"). Karoo
        // style has its own "LAST LAP" label above, so only the values.
        val last = s.lastLapTimeSec?.takeIf { it > 0 }?.let { time ->
            val parts = listOfNotNull(
                formatClock(time),
                s.lastLapPowerAvg?.let { "$it W" },
                s.lastLapHrAvg?.takeIf { it > 0 }?.let { context.getString(R.string.bpm_value, it) },
            ).joinToString(" · ")
            if (karoo) parts else context.getString(R.string.last_lap_no_power, parts)
        }
        v.setTextViewText(R.id.free_last_lap, last ?: "")
        v.setOnClickPendingIntent(R.id.free_btn_settings, settingsIntent)
        return v
    }

    /**
     * "Interval 3 / 20", or "3 / 20 · Spokojny start" when the step has a coach's note (scrolled
     * when it's too long). Cadence has its own tile.
     */
    private fun intervalLabel(s: ScreenState): CharSequence {
        val step = s.currentStep
        if (step?.note == null) return context.getString(R.string.interval_of, s.stepIndex + 1, s.stepCount)
        val prefix = "${s.stepIndex + 1} / ${s.stepCount}"
        // Room for the label: the row minus the time left on its right (and Karoo style's timer icon).
        val timePx = measure("\u2212" + "00:00", if (s.karooStyle) 23f else 30f, bold = true, karoo = false)
        val avail = config.viewSize.first - (16 + 8 + if (s.karooStyle) 16 else 0) * density - timePx
        return ticker(intervalTicker, prefix, step.note, avail, 13f, s.karooStyle)
    }

    /** "Next up · 92–98 rpm · Dwuminutówka 2" when the step has a cadence target or note. */
    private fun withDetails(label: String, step: PlanStep, karoo: Boolean): String {
        val prefix = (listOf(label) + listOfNotNull(step.cadenceText)).joinToString(SEP)
        // Between the 56 dp ⏮ and ⏭ buttons.
        val avail = config.viewSize.first - (16 + 112 + 16) * density
        return ticker(nextTicker, prefix, step.note, avail, 11f, karoo)
    }

    /** When the note being scrolled first showed, per label. */
    private class Ticker(var text: String? = null, var since: Long = 0L)
    private val intervalTicker = Ticker()
    private val nextTicker = Ticker()

    /**
     * "[prefix] · [note]". A note too long for [availPx] scrolls word by word, one step per redraw
     * (the field redraws about once a second): it waits at the start, moves along until its end
     * shows, waits there, then starts again. When the prefix leaves too little room for a
     * readable piece of the note, the line takes turns: the prefix alone, then the note across
     * the whole width.
     */
    private fun ticker(t: Ticker, prefix: String, note: String?, availPx: Float, sp: Float, karoo: Boolean): String {
        if (note.isNullOrBlank()) return prefix
        val full = prefix + SEP + note
        val bold = !karoo
        if (measure(full, sp, bold, karoo) <= availPx) return full
        val now = SystemClock.elapsedRealtime()
        // Keyed on the note only: the prefix's live cadence changes every second.
        if (t.text != note) {
            t.text = note
            t.since = now
        }
        val beside = availPx - measure(prefix + SEP, sp, bold, karoo)
        val frames: List<String> = if (beside >= availPx * BESIDE_MIN) {
            noteWindows(note, beside, sp, karoo).let { w ->
                List(HOLD) { w.first() } + w + List(HOLD) { w.last() }
            }.map { prefix + SEP + it }
        } else {
            List(PREFIX_HOLD) { prefix } +
                noteWindows(note, availPx, sp, karoo).let { w -> List(HOLD - 1) { w.first() } + w + List(HOLD) { w.last() } }
        }
        val frame = ((now - t.since) / 1000L % frames.size).toInt()
        return frames[frame]
    }

    /**
     * The note cut into pieces that fit [roomPx], each starting one word later than the one
     * before ("…" marks text cut on either side), until the end of the note shows.
     */
    private fun noteWindows(note: String, roomPx: Float, sp: Float, karoo: Boolean): List<String> {
        val words = note.split(' ').filter { it.isNotEmpty() }
        val fits = { text: String -> measure(text, sp, !karoo, karoo) <= roomPx }
        val out = mutableListOf<String>()
        for (from in words.indices) {
            var to = from + 1 // at least one word (the TextView ellipsizes a word too long alone)
            while (to < words.size) {
                val candidate = (if (from > 0) "\u2026" else "") + words.subList(from, to + 1).joinToString(" ") +
                    (if (to + 1 < words.size) "\u2026" else "")
                if (!fits(candidate)) break
                to++
            }
            out += (if (from > 0) "\u2026" else "") + words.subList(from, to).joinToString(" ") +
                (if (to < words.size) "\u2026" else "")
            if (to >= words.size) break
        }
        return out.ifEmpty { listOf(note) }
    }

    private val measurePaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG)

    /** Width in px of [text] at [sp], roughly as the field draws it. */
    private fun measure(text: String, sp: Float, bold: Boolean, karoo: Boolean): Float {
        measurePaint.textSize = sp * density * scale
        measurePaint.typeface = when {
            karoo -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
            bold -> Typeface.DEFAULT_BOLD
            else -> Typeface.DEFAULT
        }
        return measurePaint.measureText(if (karoo) text.uppercase() else text)
    }

    private fun summary(step: PlanStep): String = if (!step.hasTarget) {
        formatDuration(step.durationSec)
    } else if (step.isRange && step.rangeLow != null && step.rangeHigh != null) {
        context.getString(R.string.step_summary_ramp, formatDuration(step.durationSec), w(step.rangeLow), w(step.rangeHigh))
    } else if (step.isRamp) {
        context.getString(R.string.step_summary_ramp, formatDuration(step.durationSec), w(step.startWatts), w(step.endWatts))
    } else {
        context.getString(R.string.step_summary, formatDuration(step.durationSec), w(step.startWatts))
    }

    private fun summaryWithZone(step: PlanStep, ftp: Int?): String {
        val zone = Zones.zoneOf(step.avgWatts, ftp) ?: return summary(step)
        return "${summary(step)} · Z$zone"
    }

    /** Karoo-style cell values: IBM Plex Sans Medium (sans-serif on the Karoo), 90% wide, tighter. */
    private val numberPaint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        textScaleX = 0.9f
        letterSpacing = -0.05f
    }

    /**
     * The gauge's space on screen (the ImageView's height). The Karoo doesn't tell the app when a
     * route makes the field shorter, so it's worked out from the layout (measured on a Karoo 3)
     * and the navigation state; the bitmap is drawn at that size so the arc can use all of it.
     */
    private fun gaugeHeightPx(karoo: Boolean, navigating: Boolean): Int {
        val dp = (if (karoo) GAUGE_KAROO_DP else GAUGE_REGULAR_DP) - (if (navigating) ROUTE_DP else 0f)
        return (dp * density * scale).toInt().coerceAtLeast(1)
    }

    /** Largest size up to [CELL_VALUE_SP] at which [text] fits in a Karoo-style cell. */
    private fun cellValueSp(text: String): Float {
        val tiles = (if (cadTileShown) 3f else 2f) - (if (lapHiddenForJump) 1f else 0f)
        val cellPx = ((config.viewSize.first - 16 * density) / tiles - 3 * density - 12 * density).coerceAtLeast(1f)
        var sp = CELL_VALUE_SP
        while (sp > 16f) {
            numberPaint.textSize = sp * density * scale
            if (numberPaint.measureText(text) <= cellPx) break
            sp -= 1f
        }
        return sp
    }

    private var lapText = ""
    private var targetText = ""
    private var hrText = ""
    private var cadText = ""
    private var cadTileShown = false
    /** Cadence reported at least once: the free-ride tile stays (shows -- when it drops to 0). */
    private var freeCadSeen = false
    private var lapHiddenForJump = false
    /** Today's intensity: step summaries ("3m @ 180–207W") show the scaled watts too. */
    private var intensityPct = 100

    private fun w(watts: Int?): Int? = watts?.let { if (intensityPct == 100) it else Math.round(it * intensityPct / 100f) }

    private fun applyTextSizes(v: RemoteViews, gaugeMode: Boolean, karoo: Boolean) {
        // Must match the sp sizes in the layouts (the reference size).
        val sizes = mutableListOf(
            R.id.paused_text to 13f, R.id.next_label to 11f, R.id.confirm_text to 17f,
            R.id.move_cancelled_text to 16f, R.id.countdown to 31f,
        )
        if (karoo) {
            // Karoo native styling: one compact block under the watts, the same on every screen:
            // 13sp labels, 28sp values (smaller only if a value doesn't fit), 23sp time.
            sizes += listOf(R.id.lap_avg to cellValueSp(lapText), R.id.lap_avg_label to 13f, R.id.interval_label to 13f, R.id.time_left to 23f)
            sizes += listOf(R.id.cad_value to cellValueSp(cadText), R.id.cad_label to 13f)
            sizes += if (gaugeMode) {
                // top row: target range in the top-row HR's type; HR is a cell
                listOf(R.id.hr to cellValueSp(hrText), R.id.hr_label to 13f, R.id.target to 20f, R.id.target_next to 14f)
            } else {
                // top row: heart rate (Plex Sans SemiBold); Target is a cell
                listOf(R.id.hr to 20f, R.id.target to cellValueSp(targetText), R.id.target_label to 13f)
            }
        } else {
            sizes += listOf(R.id.interval_label to 13f, R.id.time_left to 30f, R.id.lap_avg to 22f)
            sizes += listOf(R.id.cad_value to 22f, R.id.cad_label to 11f)
            sizes += if (gaugeMode) {
                listOf(R.id.hr to 22f, R.id.hr_label to 11f, R.id.lap_avg_label to 11f, R.id.target_label to 11f, R.id.target to 18f, R.id.target_next to 14f)
            } else {
                listOf(R.id.hr to 18f, R.id.target to 22f, R.id.target_next to 16f)
            }
        }
        sizes.forEach { (id, sp) -> v.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_PX, sp * density * scale) }
        if (karoo) {
            // A shrunk cell value has less room under its baseline, so it would sit lower than a
            // full-size one: pad it up by the difference, so every value sits on the same line.
            listOf(R.id.lap_avg to lapText, R.id.target to targetText, R.id.hr to hrText, R.id.cad_value to cadText).forEach { (id, text) ->
                if (id == R.id.hr && !gaugeMode) return@forEach      // the top-row HR, not a cell
                if (id == R.id.target && gaugeMode) return@forEach   // the top-row target, not a cell
                v.setViewPadding(id, 0, 0, 0, baselineLift(cellValueSp(text)))
            }
        }
    }

    /** How much higher a cell value at [sp] has to sit to share the full-size value's baseline. */
    private fun baselineLift(sp: Float): Int {
        numberPaint.textSize = CELL_VALUE_SP * density * scale
        val full = numberPaint.fontMetrics.descent
        numberPaint.textSize = sp * density * scale
        return (full - numberPaint.fontMetrics.descent).toInt().coerceAtLeast(0)
    }

    private fun color(id: Int) = ContextCompat.getColor(res, id)

    private fun actionIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, ActionReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val SEP = " \u00b7 "
        /** Redraws (about seconds) a scrolled note waits at its start and at its end. */
        private const val HOLD = 2
        /** The prefix alone shows this many redraws when the line takes turns. */
        private const val PREFIX_HOLD = 3
        /** Below this share of the line, the note doesn't go beside the prefix. */
        private const val BESIDE_MIN = 0.45f
        /** Gear and pause icons: 55% of the text colour. */
        private const val ICON_ALPHA = 140
        private const val REF_W_DP = 255f
        private const val REF_H_DP = 385f
        private const val CHART_DP = 56f
        /** Free-ride power chart height (the workout profile is [CHART_DP]). */
        private const val FREE_CHART_DP = 96f
        private const val CELL_VALUE_SP = 28f
        /** Height of the gauge's space on a full page without a route (measured), and how much a route takes. */
        private const val GAUGE_KAROO_DP = 128.7f   // 241 px on a Karoo 3 (162 px with a route)
        private const val GAUGE_REGULAR_DP = 119.2f  // 224 px on a Karoo 3 (145 px with a route)
        private const val ROUTE_DP = 42.1f          // a route makes the field 79 px shorter


    }
}
