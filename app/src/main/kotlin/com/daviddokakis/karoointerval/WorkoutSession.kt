package com.daviddokakis.karoointerval

import android.os.SystemClock
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.MarkLap
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.PlayBeepPattern
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.roundToInt

/**
 * Runs the loaded workout: keeps time while the ride is recording, moves between steps,
 * handles the 5 s countdown when jumping back or forward, and collects power/HR for the screen.
 *
 * Karoo's own workout player isn't used, so steps can be changed in both directions.
 * All work runs on a single-threaded dispatcher, so the mutable fields need no locking.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutSession(
    private val karoo: KarooSystemService,
    private val store: PlanStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    private val _state = MutableStateFlow(ScreenState())
    val state: StateFlow<ScreenState> = _state

    private var recording = false
    private var elapsedInStepMs = 0L
    private var lastTick = 0L
    private var lapSum = 0.0
    private var lapSamples = 0
    private val lapNp = LapNp()
    private val freeLapNp = LapNp()
    private var countdownJob: Job? = null
    private var finishArmJob: Job? = null

    /** The workout is over (last interval done, or ended early with ⏭): one lap, a beep. */
    private fun finishWorkout() {
        val step = _state.value.currentStep ?: return
        _state.update { it.copy(finished = true, finishArmed = false, elapsedInStepSec = step.durationSec, summary = summaryNow()) }
        karoo.dispatch(MarkLap)
        beep(GO_TONE)
        store.clearProgress()
    }
    /** Jump being chosen (3 s confirmation), see [jumpStops]. */
    private var selectionPos: Int? = null
    private var selectJob: Job? = null
    private var autoConfirmJob: Job? = null
    private var moveCancelledJob: Job? = null
    private var started = false

    /** Interval screens currently showing. While one is, it plays the countdown beeps (see [onCountdownShown]). */
    private val viewsActive = java.util.concurrent.atomic.AtomicInteger(0)

    fun viewStarted() { viewsActive.incrementAndGet() }
    fun viewStopped() { viewsActive.decrementAndGet() }

    /**
     * Called by the interval screen right after a new countdown number is on screen, so the beep
     * matches what the rider sees (Karoo only redraws the field about once a second).
     */
    fun onCountdownShown() {
        scope.launch { beep(TICK_TONE) }
    }

    /** Tapping the watts cycles plain → coloured number → coloured background → both. */
    fun toggleWattsColor() {
        scope.launch {
            val mode = _state.value.wattsMode.next()
            store.wattsMode = mode
            _state.update { it.copy(wattsMode = mode) }
            Timber.i("Watts mode -> $mode")
        }
    }

    /** Tapping the chart toggles between the whole workout and a zoom on the current interval. */
    fun toggleChartZoom() {
        scope.launch { _state.update { it.copy(chartZoomed = !it.chartZoomed) } }
    }

    fun start() {
        if (started) return
        started = true

        scope.launch {
            // Raw power feeds the lap average…
            karoo.streamDataFlow(DataType.Type.POWER).collect { onPower(it.single()) }
        }
        scope.launch {
            // …and the big number shows Karoo's power, smoothed as chosen in Settings (instant,
            // 3 s, 5 s or 10 s); switching takes effect right away.
            store.powerSmoothing.collectLatest { smoothing ->
                karoo.streamDataFlow(smoothing.power).collect { s ->
                    _state.update { it.copy(power3s = s.single()?.roundToInt()) }
                }
            }
        }
        scope.launch {
            karoo.streamDataFlow(DataType.Type.HEART_RATE).collect { s ->
                // The finished workout's average heart rate counts while it runs.
                s.single()?.let { hr -> if (workoutRunning()) { hrSum += hr; hrSamples++ } }
            }
        }
        collectInt(DataType.Type.AVERAGE_POWER) { v -> _state.update { it.copy(rideAvgPower = v) } }
        collectInt(DataType.Type.AVERAGE_HR) { v -> _state.update { it.copy(rideAvgHr = v) } }
        collectInt(DataType.Type.NORMALIZED_POWER) { v -> _state.update { it.copy(rideNp = v) } }
        collectInt(DataType.Type.TRAINING_STRESS_SCORE) { v -> _state.update { it.copy(rideTss = v) } }
        scope.launch {
            karoo.streamDataFlow(DataType.Type.INTENSITY_FACTOR).collect { s ->
                _state.update { it.copy(rideIf = s.single()?.takeIf { v -> v > 0 }) }
            }
        }
        scope.launch { store.topRow.collect { t -> _state.update { it.copy(topRow = t) } } }
        scope.launch {
            karoo.streamDataFlow(DataType.Type.HEART_RATE).collect { s ->
                _state.update { it.copy(heartRate = s.single()?.roundToInt()) }
            }
        }
        scope.launch {
            karoo.consumerFlow<RideState>().collect { ride ->
                recording = ride is RideState.Recording
                _state.update { it.copy(paused = ride is RideState.Paused) }
                // No ride (not started, finished or discarded): start the workout from the top
                // next time. Progress is only kept while a ride is recording or paused.
                if (ride is RideState.Idle) {
                    resetToStart()
                    PowerHistory.clear()
                }
            }
        }
        // Navigating (a route or a destination): the Karoo shows its route pill and makes the field
        // shorter without telling the app, so the gauge is sized for the shorter field.
        scope.launch {
            karoo.consumerFlow<OnNavigationState>().collect { nav ->
                _state.update { it.copy(navigating = nav.state !is OnNavigationState.NavigationState.Idle) }
            }
        }
        scope.launch {
            karoo.consumerFlow<UserProfile>().collect { profile ->
                _state.update {
                    it.copy(
                        profileFtp = profile.ftp.takeIf { ftp -> ftp > 0 },
                        hrZones = profile.heartRateZones.map { z -> z.min..z.max },
                        powerZones = profile.powerZones.map { z -> z.min..z.max },
                    )
                }
            }
        }
        // Karoo's lap data for the free-ride screen (times come in milliseconds).
        collectInt(DataType.Type.LAP_NUMBER) { v ->
            if (v != _state.value.lapNumber) freeLapNp.reset()
            _state.update { it.copy(lapNumber = v, freeLapNp = freeLapNp.value) }
        }
        collectInt(DataType.Type.ELAPSED_TIME_LAP, divideBy = 1000) { v -> _state.update { it.copy(lapTimeSec = v) } }
        collectInt(DataType.Type.POWER_LAP) { v -> _state.update { it.copy(lapPowerAvg = v) } }
        collectInt(DataType.Type.ELAPSED_TIME_LAST_LAP, divideBy = 1000) { v -> _state.update { it.copy(lastLapTimeSec = v) } }
        collectInt(DataType.Type.AVERAGE_POWER_LAST_LAP) { v -> _state.update { it.copy(lastLapPowerAvg = v) } }
        collectInt(DataType.Type.AVERAGE_LAP_HR) { v -> _state.update { it.copy(lapHrAvg = v) } }
        collectInt(DataType.Type.AVERAGE_HR_LAST_LAP) { v -> _state.update { it.copy(lastLapHrAvg = v) } }
        // Cadence, smoothed as chosen in Settings (instant by default, like Karoo's Cadence field).
        scope.launch {
            store.cadenceSmoothing.collectLatest { smoothing ->
                karoo.streamDataFlow(smoothing.cadence).collect { s ->
                    _state.update { it.copy(cadence = s.single()?.roundToInt()) }
                }
            }
        }
        scope.launch { store.tileOutline.collect { on -> _state.update { it.copy(tileOutline = on) } } }
        scope.launch {
            store.plan.collect { plan -> loadPlan(plan) }
        }
        scope.launch { tickLoop() }
        scope.launch { store.karooStyle.collect { on -> _state.update { it.copy(karooStyle = on) } } }
        scope.launch { store.appearance.collect { a -> _state.update { it.copy(appearance = a) } } }
        _state.update { it.copy(wattsMode = store.wattsMode, targetShowsRange = store.targetShowsRange, lapShowsNp = store.lapShowsNp, hrShowsLap = store.hrShowsLap, clockShowsElapsed = store.clockShowsElapsed, freeChartSpan = store.freeChartSpan, freePowerTile = store.freePowerTile, freeHrTile = store.freeHrTile) }
    }

    /** Today's intensity in % (targets and ERG follow it). */
    fun setIntensity(pct: Int) {
        if (_state.value.intensityPct != pct) _state.update { it.copy(intensityPct = pct) }
    }

    /** Whether the trainer is in ERG at the target (shown as "Cel · ERG"). */
    fun setErgActive(active: Boolean) {
        if (_state.value.ergActive != active) _state.update { it.copy(ergActive = active) }
    }

    /** The ride's rating, shown with "Workout complete". */
    fun setRatingSummary(summary: String?) {
        _state.update { it.copy(ratingSummary = summary) }
    }

    fun stop() {
        saveProgress()
        scope.cancel()
    }

    /**
     * ⏭ ([delta] +1) / ⏮ (-1): move the jump target one stop. Stops, in order:
     * …, previous intervals, "restart the current interval", "stay" (current, nothing happens),
     * next intervals, …  Nothing moves until "Confirm move" (the choice cancels itself after
     * [SELECT_TIMEOUT_MS]); then the 5 s countdown runs. A tap on the countdown starts the chosen
     * interval right away, its ✕ cancels.
     *
     * [fromRemote]: a remote button press confirms by itself after [AUTO_CONFIRM_SECONDS] (each
     * press starts the wait over). On-screen taps still need "Confirm move".
     */
    fun requestJump(delta: Int, fromRemote: Boolean = false) {
        scope.launch {
            val st = _state.value
            if (st.plan == null) return@launch
            // Before START: ⏮ / ⏭ pick the interval to start at (round at the ends); they also
            // cancel a start countdown.
            if (st.waiting) {
                if (countdownJob?.isActive == true) {
                    cancelCountdown()
                    beep(CANCEL_TONE)
                    return@launch
                }
                val index = Math.floorMod(st.stepIndex + delta, st.stepCount)
                elapsedInStepMs = 0
                _state.update { it.copy(stepIndex = index, elapsedInStepSec = 0) }
                return@launch
            }
            // ⏭ on the last interval: end the workout early, after a second tap to confirm.
            if (delta > 0 && !st.hasNext && !st.finished && selectionPos == null && countdownJob?.isActive != true) {
                if (st.finishArmed) {
                    finishArmJob?.cancel()
                    finishWorkout()
                } else {
                    _state.update { it.copy(finishArmed = true) }
                    finishArmJob = scope.launch {
                        delay(FINISH_CONFIRM_MS)
                        _state.update { it.copy(finishArmed = false) }
                    }
                }
                return@launch
            }
            if (countdownJob?.isActive == true) {
                jumpNow()
                return@launch
            }
            val stops = jumpStops(st.stepIndex, st.stepCount)
            val stay = stops.indexOf(st.stepIndex * 2)
            val from = selectionPos?.let { stops.indexOf(it) }?.takeIf { it >= 0 } ?: stay
            val to = (from + delta).coerceIn(0, stops.lastIndex)
            Timber.i("Jump tap delta=$delta current=${st.stepIndex} selection=$selectionPos from=${stops[from]} to=${stops[to]}")
            if (to == stay) {
                cancelSelection() // back where we are: nothing happens
            } else if (to != from || selectionPos == null) {
                select(stops[to])
                if (fromRemote) startAutoConfirm() else stopAutoConfirm()
            } else if (fromRemote) {
                startAutoConfirm() // pressed again at the end of the list: wait starts over
            }
        }
    }

    /** Tap on the middle of the box while choosing: never mind, stay where we are. */
    fun cancelSelection() {
        scope.launch {
            Timber.i("Selection cancelled")
            stopAutoConfirm()
            selectJob?.cancel()
            selectJob = null
            selectionPos = null
            _state.update { it.copy(selectTarget = null, selectRestart = false, selectProgress = 0) }
        }
    }

    /** Tap on the countdown: skip the rest of it and start the chosen interval now. */
    fun jumpNow() {
        scope.launch {
            // Tap on the start countdown: start right away.
            if (_state.value.waiting && countdownJob?.isActive == true) {
                countdownJob?.cancel()
                countdownJob = null
                beginWorkout()
                return@launch
            }
            Timber.i("Jump now tapped (countdown running: ${countdownJob?.isActive})")
            val target = _state.value.jumpTarget ?: return@launch
            if (countdownJob?.isActive != true) return@launch
            countdownJob?.cancel()
            countdownJob = null
            goToStep(target, natural = false)
        }
    }

    /** Positions: interval i is 2·i; "restart current" is 2·current − 1. */
    private fun jumpStops(current: Int, count: Int): List<Int> =
        (0 until count).flatMap { i -> if (i == current) listOf(2 * i - 1, 2 * i) else listOf(2 * i) }

    private fun select(pos: Int) {
        Timber.i("Select pos=$pos")
        moveCancelledJob?.cancel()
        moveCancelledJob = null
        _state.update { it.copy(moveCancelled = false) }
        selectionPos = pos
        val target = (pos + 1) / 2
        val restart = pos % 2 != 0
        _state.update { it.copy(selectTarget = target, selectRestart = restart, selectProgress = 1) }
        // Nothing moves until "Confirm move". Untouched, the choice quietly cancels.
        selectJob?.cancel()
        selectJob = scope.launch {
            delay(SELECT_TIMEOUT_MS)
            Timber.i("Selection timed out")
            stopAutoConfirm()
            selectJob = null
            selectionPos = null
            _state.update { it.copy(selectTarget = null, selectRestart = false, selectProgress = 0) }
        }
    }

    /** Remote choice: fill "Confirm move" over [AUTO_CONFIRM_SECONDS], then confirm it. */
    private fun startAutoConfirm() {
        autoConfirmJob?.cancel()
        autoConfirmJob = scope.launch {
            for (n in AUTO_CONFIRM_SECONDS downTo 1) {
                _state.update { it.copy(autoConfirmLeft = n) }
                delay(1000)
            }
            autoConfirmJob = null
            val target = _state.value.selectTarget ?: return@launch
            Timber.i("Selection auto-confirmed -> countdown to ${target + 1}")
            clearSelection()
            startCountdown(target)
        }
    }

    private fun stopAutoConfirm() {
        autoConfirmJob?.cancel()
        autoConfirmJob = null
        _state.update { it.copy(autoConfirmLeft = null) }
    }

    /** "Confirm move": lock in the chosen interval and start the 5 s countdown. */
    fun confirmSelection() {
        scope.launch {
            val target = _state.value.selectTarget ?: return@launch
            Timber.i("Selection confirmed -> countdown to ${target + 1}")
            clearSelection()
            startCountdown(target)
        }
    }

    /** ✕ next to "Confirm move": drop the choice, nothing moves, show "Move cancelled" for a moment. */
    fun cancelMove() {
        scope.launch {
            if (_state.value.selectTarget == null) return@launch
            Timber.i("Move cancelled")
            clearSelection()
            beep(CANCEL_TONE)
            showMoveCancelled()
        }
    }

    /** Tap on "Move cancelled": hide it right away. */
    fun dismissMoveCancelled() {
        scope.launch {
            moveCancelledJob?.cancel()
            moveCancelledJob = null
            _state.update { it.copy(moveCancelled = false) }
        }
    }

    /** Tap on the free-ride power chart: whole ride → 60 → 20 → 5 min → whole ride. */
    fun toggleFreeChart() {
        scope.launch {
            val span = (_state.value.freeChartSpan + 1) % PowerHistory.SPANS.size
            store.freeChartSpan = span
            _state.update { it.copy(freeChartSpan = span) }
        }
    }

    /** Tap on the interval clock: time left (−1:35) ↔ time in the interval (3:25). */
    fun toggleClock() {
        scope.launch {
            val up = !_state.value.clockShowsElapsed
            store.clockShowsElapsed = up
            _state.update { it.copy(clockShowsElapsed = up) }
        }
    }

    /** Tap on the lap tile: switch between the lap average and lap NP. */
    fun toggleLapNp() {
        scope.launch {
            val np = !_state.value.lapShowsNp
            store.lapShowsNp = np
            _state.update { it.copy(lapShowsNp = np) }
        }
    }

    /** Free ride: tap on the power tile: lap avg → lap NP → ride avg → ride NP → IF → TSS. */
    fun toggleFreePower() {
        scope.launch {
            val v = (_state.value.freePowerTile + 1) % FREE_POWER_VALUES
            store.freePowerTile = v
            _state.update { it.copy(freePowerTile = v) }
        }
    }

    /** Free ride: tap on the HR tile: heart rate now → lap avg → ride avg. */
    fun toggleFreeHr() {
        scope.launch {
            val v = (_state.value.freeHrTile + 1) % 3
            store.freeHrTile = v
            _state.update { it.copy(freeHrTile = v) }
        }
    }

    /** Free ride: tap on the HR tile switches between the current heart rate and the lap's average. */
    fun toggleLapHr() {
        scope.launch {
            val lap = !_state.value.hrShowsLap
            store.hrShowsLap = lap
            _state.update { it.copy(hrShowsLap = lap) }
        }
    }

    /** Tap on the Target tile: switch between the target and the target range. */
    fun toggleTargetView() {
        scope.launch {
            val range = !_state.value.targetShowsRange
            store.targetShowsRange = range
            _state.update { it.copy(targetShowsRange = range) }
        }
    }

    private fun showMoveCancelled() {
        moveCancelledJob?.cancel()
        _state.update { it.copy(moveCancelled = true) }
        moveCancelledJob = scope.launch {
            delay(MOVE_CANCELLED_MS)
            moveCancelledJob = null
            _state.update { it.copy(moveCancelled = false) }
        }
    }

    /**
     * Pauses or resumes the interval timer (e.g. for traffic) so the time left in the interval
     * is kept. The ride keeps recording and the lap average keeps counting. Pausing the ride
     * itself (Karoo's own pause) also stops the interval timer.
     */
    fun togglePause() {
        scope.launch {
            // Before START, the play button (and the remote's pause / resume) starts the workout:
            // a 5 s countdown, and a second tap cancels it.
            if (_state.value.waiting) {
                if (countdownJob?.isActive == true) {
                    cancelCountdown()
                    beep(CANCEL_TONE)
                } else {
                    startCountdown(_state.value.stepIndex)
                }
                return@launch
            }
            val nowPaused = !_state.value.workoutPaused
            if (nowPaused) {
                cancelCountdown()
                clearSelection()
            }
            _state.update { it.copy(workoutPaused = nowPaused) }
            beep(if (nowPaused) CANCEL_TONE else GO_TONE)
            Timber.i("Workout ${if (nowPaused) "paused" else "resumed"}")
        }
    }

    private fun loadPlan(plan: WorkoutPlan?) {
        cancelCountdown()
        resetLap()
        val (index, elapsedSec) = plan?.let { store.restoreProgress(it) } ?: (0 to 0)
        elapsedInStepMs = elapsedSec * 1000L
        resetTotals()
        // A workout picked fresh waits for START; one resumed mid-way (app restarted in a ride)
        // carries on.
        val waiting = plan != null && index == 0 && elapsedSec == 0
        _state.update {
            it.copy(plan = plan, stepIndex = index, elapsedInStepSec = elapsedSec, finished = false, lapAvg = null, workoutPaused = false, waiting = waiting, summary = null)
        }
        Timber.i("Loaded workout ${plan?.name} at step $index + ${elapsedSec}s")
    }

    private fun resetToStart() {
        val st = _state.value
        if (st.plan == null || (st.stepIndex == 0 && elapsedInStepMs == 0L && !st.finished && !st.workoutPaused && st.waiting)) return
        cancelCountdown()
        resetLap()
        elapsedInStepMs = 0
        store.clearProgress()
        resetTotals()
        _state.update { it.copy(stepIndex = 0, elapsedInStepSec = 0, finished = false, lapAvg = null, workoutPaused = false, waiting = true, summary = null) }
        Timber.i("No ride active: workout reset to the first interval, waiting for START")
    }

    private suspend fun tickLoop() {
        lastTick = SystemClock.elapsedRealtime()
        var sinceSave = 0L
        while (scope.isActive) {
            delay(TICK_MS)
            val now = SystemClock.elapsedRealtime()
            val dt = now - lastTick
            lastTick = now
            val st = _state.value
            val step = st.currentStep ?: continue
            if (!recording || st.finished || st.workoutPaused || st.waiting) continue
            workoutMs += dt

            val before = (step.durationSec * 1000L - elapsedInStepMs) / 1000
            elapsedInStepMs += dt
            val remainingMs = step.durationSec * 1000L - elapsedInStepMs
            val after = remainingMs / 1000

            // Warn with short beeps in the last 3 seconds, unless a jump countdown is beeping already.
            if (countdownJob?.isActive != true && after != before && after in 1..3) beep(WARN_TONE)

            if (remainingMs <= 0) {
                if (st.hasNext) {
                    goToStep(st.stepIndex + 1, natural = true)
                } else {
                    finishWorkout()
                }
            } else {
                _state.update { it.copy(elapsedInStepSec = (elapsedInStepMs / 1000).toInt()) }
            }

            sinceSave += dt
            if (sinceSave >= SAVE_EVERY_MS) {
                sinceSave = 0
                saveProgress()
            }
        }
    }

    private fun startCountdown(target: Int) {
        countdownJob = scope.launch {
            for (n in COUNTDOWN_SECONDS downTo 1) {
                _state.update { it.copy(countdown = n, jumpTarget = target) }
                // With the screen showing, it beeps when the number appears; otherwise beep here.
                if (viewsActive.get() == 0) beep(TICK_TONE)
                delay(1000)
            }
            countdownJob = null
            if (_state.value.waiting) beginWorkout() else goToStep(target, natural = false)
        }
    }

    /** START (after its countdown): the workout's clock runs from the chosen interval. */
    private fun beginWorkout() {
        Timber.i("Workout started at step ${_state.value.stepIndex + 1}")
        elapsedInStepMs = 0
        resetLap()
        resetTotals()
        _state.update { it.copy(waiting = false, countdown = null, jumpTarget = null, elapsedInStepSec = 0) }
        karoo.dispatch(MarkLap)
        // A short rising tune.
        karoo.dispatch(PlayBeepPattern(listOf(PlayBeepPattern.Tone(1000, 120), PlayBeepPattern.Tone(null, 60), PlayBeepPattern.Tone(1500, 120), PlayBeepPattern.Tone(null, 60), PlayBeepPattern.Tone(2000, 250))))
        saveProgress()
    }

    /** Running for the summary: started, not finished, ride recording, workout not paused. */
    private fun workoutRunning(): Boolean {
        val st = _state.value
        return recording && st.workoutLoaded && !st.waiting && !st.finished && !st.workoutPaused
    }

    private var workoutMs = 0L
    private var powerSum = 0.0
    private var powerSamples = 0
    private var hrSum = 0.0
    private var hrSamples = 0
    private val workoutNp = LapNp()
    private val driftSamples = ArrayList<Pair<Float, Float>>()

    private fun resetTotals() {
        workoutMs = 0
        powerSum = 0.0
        powerSamples = 0
        hrSum = 0.0
        hrSamples = 0
        workoutNp.reset()
        driftSamples.clear()
    }

    /**
     * Pw:HR decoupling: (watts per beat in the first half − in the second half) ÷ the first
     * half's, in %. Positive: the heart worked harder for the same power later on.
     */
    private fun driftPct(): Double? {
        if (driftSamples.size < MIN_DRIFT_SAMPLES) return null
        val half = driftSamples.size / 2
        fun ratio(part: List<Pair<Float, Float>>): Double? {
            val p = part.sumOf { it.first.toDouble() } / part.size
            val hr = part.sumOf { it.second.toDouble() } / part.size
            return if (p > 0 && hr > 0) p / hr else null
        }
        val first = ratio(driftSamples.subList(0, half)) ?: return null
        val second = ratio(driftSamples.subList(half, driftSamples.size)) ?: return null
        return (first - second) / first * 100.0
    }

    private fun summaryNow() = WorkoutSummary(
        timeSec = (workoutMs / 1000).toInt(),
        avgPower = if (powerSamples > 0) (powerSum / powerSamples).roundToInt() else null,
        np = workoutNp.value,
        avgHr = if (hrSamples > 0) (hrSum / hrSamples).roundToInt() else null,
        driftPct = driftPct(),
    )

    private fun goToStep(index: Int, natural: Boolean) {
        // A step change (natural or not) clears any pending jump, so a forward jump that was
        // counting down when the interval ended doesn't skip the interval we just entered.
        if (natural) {
            cancelCountdown()
            clearSelection()
        }
        Timber.i("Step ${_state.value.stepIndex + 1} -> ${index + 1} (natural=$natural)")
        elapsedInStepMs = 0
        resetLap()
        _state.update {
            it.copy(stepIndex = index, elapsedInStepSec = 0, finished = false, lapAvg = null, countdown = null, jumpTarget = null)
        }
        karoo.dispatch(MarkLap) // one lap per interval in the ride file
        beep(GO_TONE)
        saveProgress()
    }

    private fun clearSelection() {
        stopAutoConfirm()
        selectJob?.cancel()
        selectJob = null
        selectionPos = null
        _state.update { it.copy(selectTarget = null, selectRestart = false, selectProgress = 0) }
    }

    private fun cancelCountdown() {
        countdownJob?.cancel()
        countdownJob = null
        _state.update { it.copy(countdown = null, jumpTarget = null) }
    }

    private fun resetLap() {
        lapSum = 0.0
        lapSamples = 0
        lapNp.reset()
        _state.update { it.copy(lapNp = null) }
    }

    private fun onPower(watts: Double?) {
        val st = _state.value
        // The lap average keeps counting while the workout is paused; only the interval timer stops.
        if (watts != null && workoutRunning()) {
            // Power with the heart rate of the moment, for the heart rate drift at the end.
            st.heartRate?.takeIf { it > 0 }?.let { hr -> driftSamples.add(watts.toFloat() to hr.toFloat()) }
            powerSum += watts
            powerSamples++
            workoutNp.add(SystemClock.elapsedRealtime(), watts)
        }
        if (watts != null && recording && st.workoutLoaded && !st.finished) {
            lapSum += watts
            lapSamples++
            lapNp.add(SystemClock.elapsedRealtime(), watts)
        }
        if (watts != null && recording) {
            freeLapNp.add(SystemClock.elapsedRealtime(), watts)
            PowerHistory.add(watts) // the free-ride power chart
        }
        _state.update {
            it.copy(
                power = watts?.roundToInt(),
                lapAvg = if (lapSamples > 0) (lapSum / lapSamples).roundToInt() else it.lapAvg,
                lapNp = lapNp.value,
                freeLapNp = freeLapNp.value,
            )
        }
    }

    private fun saveProgress() {
        val st = _state.value
        val plan = st.plan ?: return
        if (!st.finished) store.saveProgress(plan, st.stepIndex, st.elapsedInStepSec)
    }

    private fun collectInt(dataTypeId: String, divideBy: Int = 1, onValue: (Int?) -> Unit) {
        scope.launch {
            karoo.streamDataFlow(dataTypeId).collect { s -> onValue(s.single()?.let { (it / divideBy).roundToInt() }) }
        }
    }

    private fun beep(tone: PlayBeepPattern.Tone) {
        karoo.dispatch(PlayBeepPattern(listOf(tone)))
    }

    companion object {
        const val COUNTDOWN_SECONDS = 5
        /** Values the free-ride power tile goes through. */
        private const val FREE_POWER_VALUES = 6
        /** Heart rate drift needs at least 20 minutes of power with heart rate (about 1 a second). */
        private const val MIN_DRIFT_SAMPLES = 20 * 60
        /** A choice made with a remote button confirms itself after this many seconds. */
        const val AUTO_CONFIRM_SECONDS = 5
        /** An unconfirmed jump choice cancels itself after this long. */
        private const val SELECT_TIMEOUT_MS = 10_000L
        /** How long "Move cancelled" shows. */
        private const val MOVE_CANCELLED_MS = 3_000L
        private const val TICK_MS = 250L
        private const val SAVE_EVERY_MS = 15_000L

        private val TICK_TONE = PlayBeepPattern.Tone(frequency = 2000, durationMs = 100)
        private val WARN_TONE = PlayBeepPattern.Tone(frequency = 1500, durationMs = 80)
        /** How long the "tap ⏭ again to end the workout" prompt waits. */
        private const val FINISH_CONFIRM_MS = 4_000L
        private val GO_TONE = PlayBeepPattern.Tone(frequency = 3000, durationMs = 400)
        private val CANCEL_TONE = PlayBeepPattern.Tone(frequency = 1000, durationMs = 200)
    }
}
