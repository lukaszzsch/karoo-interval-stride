package com.daviddokakis.karoointerval

import android.content.Context
import android.os.SystemClock
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/** The full-page "Interval screen" data field. */
class IntervalDataType(
    extension: String,
    private val session: WorkoutSession,
) : DataTypeImpl(extension, TYPE_ID) {

    /** Numeric fallback (current watts) for places that show the field as a plain number. */
    override fun startStream(emitter: Emitter<StreamState>) {
        val job = CoroutineScope(Dispatchers.Default).launch {
            session.state.collect { s ->
                val p = s.power3s
                emitter.onNext(
                    if (p == null) {
                        StreamState.NotAvailable
                    } else {
                        StreamState.Streaming(DataPoint(dataTypeId, values = mapOf(DataType.Field.SINGLE to p.toDouble())))
                    },
                )
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Timber.d("Interval screen view started: $config")
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        val renderer = ScreenRenderer(context, config)
        session.viewStarted()
        val job = CoroutineScope(Dispatchers.Default).launch {
            // Karoo drops view updates that come less than ~0.9 s apart. Redraw every second, but
            // redraw as soon as allowed when something the rider is waiting for changes (countdown
            // number, interval, pause, chart zoom). During a countdown only redraw on a new number,
            // so each number appears (and beeps) exactly one second after the previous one.
            var lastRender = 0L
            var shown: Key? = null
            while (isActive) {
                val s = session.state.value
                val key = Key(
                    s.countdown, s.stepIndex, s.workoutPaused, s.chartZoomed, s.plan?.id, s.selectTarget,
                    s.selectProgress, s.moveCancelled, s.targetShowsRange, s.wattsMode, s.karooStyle, s.appearance, s.navigating, s.hrShowsLap, s.lapShowsNp,
                    s.autoConfirmLeft, s.finishArmed, s.finished, s.ergActive, s.intensityPct, s.clockShowsElapsed, s.freeChartSpan,
                )
                val since = SystemClock.elapsedRealtime() - lastRender
                val changed = key != shown
                val counting = s.countdown != null || shown?.countdown != null
                val due = if (counting) changed && since >= MIN_GAP_MS else since >= 1000 || (changed && since >= MIN_GAP_MS)
                if (due) {
                    try {
                        emitter.updateView(renderer.render(s))
                        lastRender = SystemClock.elapsedRealtime()
                        if (s.countdown != null && s.countdown != shown?.countdown) session.onCountdownShown()
                        shown = key
                    } catch (e: Exception) {
                        Timber.e(e, "Interval screen render failed")
                        lastRender = SystemClock.elapsedRealtime()
                    }
                }
                delay(POLL_MS)
            }
        }
        emitter.setCancellable {
            job.cancel()
            session.viewStopped()
        }
    }

    /** What triggers an immediate redraw when it changes. */
    private data class Key(
        val countdown: Int?,
        val step: Int,
        val paused: Boolean,
        val zoomed: Boolean,
        val plan: String?,
        val selectTarget: Int?,
        val selectProgress: Int,
        val moveCancelled: Boolean,
        val targetRange: Boolean,
        val wattsMode: WattsMode,
        val karooStyle: Boolean,
        val appearance: Appearance,
        val navigating: Boolean,
        val hrShowsLap: Boolean,
        val lapNp: Boolean,
        val autoConfirmLeft: Int?,
        val finishArmed: Boolean,
        val finished: Boolean,
        val ergActive: Boolean,
        val intensityPct: Int,
        val clockShowsElapsed: Boolean,
        val freeChartSpan: Int,
    )

    companion object {
        const val TYPE_ID = "interval-screen"
        private const val MIN_GAP_MS = 920L
        private const val POLL_MS = 40L
    }
}
