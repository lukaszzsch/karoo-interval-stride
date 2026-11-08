package com.daviddokakis.karoointerval

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.ActiveRideProfile
import io.hammerhead.karooext.models.FitEffect
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.SystemNotification
import io.hammerhead.karooext.models.WriteToSessionMesg
import timber.log.Timber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

/** Entry point the Karoo system binds to. */
class IntervalExtension : KarooExtension(EXTENSION_ID, "1") {

    private lateinit var karoo: KarooSystemService
    private lateinit var workout: WorkoutSession
    private var syncJob: Job? = null
    private lateinit var ratings: RatingStore
    private lateinit var store: PlanStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val types: List<DataTypeImpl> by lazy {
        listOf(IntervalDataType(extension, workout))
    }

    override fun onCreate() {
        super.onCreate()
        if (Timber.treeCount == 0) Timber.plant(Timber.DebugTree())
        store = PlanStore.get(this)
        ratings = RatingStore.get(this)
        karoo = KarooSystemService(this)
        workout = WorkoutSession(karoo, store)
        session = workout
        karoo.connect { connected -> Timber.i("Karoo system connected: $connected") }
        workout.start()
        startRating()
        startErg()

        // Refresh this week's list in the background so it's ready when the rider picks a workout.
        if (store.hasCredentials) {
            syncJob = CoroutineScope(Dispatchers.IO).launch {
                runCatching { store.refreshWeek(karoo) }
                    .onFailure { Timber.w(it, "Couldn't sync this week's workouts") }
            }
        }
    }

    override fun onBonusAction(actionId: String) {
        when (actionId) {
            ACTION_NEXT -> workout.requestJump(+1, fromRemote = true)
            ACTION_PREVIOUS -> workout.requestJump(-1, fromRemote = true)
            ACTION_PAUSE -> workout.togglePause()
            else -> Timber.w("Unknown action $actionId")
        }
    }

    /**
     * Ride rating (RPE and feel): asks at the end of the workout and on a manual pause, writes
     * the rating into the FIT file while the ride is open, and puts it on the Stride activity
     * once the ride has been uploaded.
     */
    private fun startRating() {
        val rideState = karoo.consumerFlow<RideState>()

        // Ride opened / ended: remember the start, queue the rating at the end.
        scope.launch {
            rideState.map { it !is RideState.Idle }.distinctUntilChanged().collect { open ->
                if (open) ratings.rideStarted(System.currentTimeMillis()) else ratings.rideEnded(System.currentTimeMillis())
            }
        }
        // The current rating on the interval screen ("Trening ukończony · RPE 7 · Dobrze").
        scope.launch {
            ratings.rating.collect { r -> workout.setRatingSummary(r.summary(this@IntervalExtension)) }
        }
        // Ask when the workout finishes, or on any manual pause of the ride, if not rated yet
        // (auto-pauses at traffic lights don't ask).
        scope.launch {
            workout.state.map { it.finished }.distinctUntilChanged().filter { it }.collect {
                if (ratings.rating.value.rpe == null) askForRating()
            }
        }
        scope.launch {
            var wasManualPause = false
            rideState.collect { ride ->
                val manualPause = ride is RideState.Paused && !ride.auto
                if (manualPause && !wasManualPause && ratings.rating.value.rpe == null) {
                    askForRating()
                }
                wasManualPause = manualPause
            }
        }
        // Halfway through the workout outdoors: "Połowa treningu", to know when to turn back on a
        // ride without a route. Once per workout and ride; not in indoor profiles.
        scope.launch {
            var shownFor: String? = null
            combine(workout.state, rideState) { st, ride -> st to ride }.collect { (st, ride) ->
                if (ride is RideState.Idle) {
                    shownFor = null
                    return@collect
                }
                val plan = st.plan ?: return@collect
                if (st.finished || shownFor == plan.id || plan.totalSec < MIN_HALFWAY_SEC) return@collect
                val erg = ErgSettings.get(this@IntervalExtension)
                val profile = erg.profiles.value.firstOrNull { it.id == erg.activeProfileId.value }
                if (profile?.indoor == true) return@collect
                val done = plan.startSecOf(st.stepIndex) + st.elapsedInStepSec
                if (done * 2 >= plan.totalSec) {
                    shownFor = plan.id
                    karoo.dispatch(
                        InRideAlert(
                            id = "halfway-${System.currentTimeMillis()}",
                            icon = R.drawable.ic_interval,
                            title = getString(R.string.halfway_title),
                            detail = getString(R.string.halfway_detail, formatClock(plan.totalSec - done)),
                            autoDismissMs = 10_000L,
                            backgroundColor = R.color.halfway_alert,
                            textColor = R.color.erg_alert_text,
                        ),
                    )
                }
            }
        }
        // After the ride: put the rating on Stride, retrying until the upload shows up.
        scope.launch {
            ratings.pending.collectLatest { pending -> pending?.let { syncRating(it) } }
        }
    }

    private fun askForRating() {
        karoo.dispatch(
            SystemNotification(
                // A new id each time: the Karoo may not show a notification again under the same id.
                id = "$NOTIFICATION_RATE-${System.currentTimeMillis()}",
                header = getString(R.string.rate_notification_header),
                message = getString(R.string.rate_notification_message),
                subText = getString(R.string.rate_notification_sub),
                action = getString(R.string.rate_notification_action),
                actionIntent = RateActivity.ACTION_RATE,
            ),
        )
    }

    private suspend fun syncRating(pending: PendingRating) {
        val token = store.apiKey
        if (token.isBlank()) {
            finishSync(getString(R.string.rate_sync_no_token), error = true)
            return
        }
        val rideStart = Instant.ofEpochMilli(pending.rideStartMs)
        val stride = Stride(token, null)
        var wait = FIRST_RETRY_MS
        // Give the Karoo a moment to upload before the first look.
        delay(wait)
        while (System.currentTimeMillis() - pending.rideEndMs < GIVE_UP_MS) {
            val result = runCatching {
                stride.rate(rideStart, pending.rpe, pending.feel) { method, url, headers, body ->
                    karoo.karooHttp(method, url, headers, body)
                }
            }.getOrElse { Stride.RateResult.NotYet }
            when (result) {
                is Stride.RateResult.Done -> {
                    val rated = RideRating(pending.rpe, pending.feel).summary(this) ?: ""
                    finishSync(getString(R.string.rate_sync_done, rated), error = false)
                    return
                }
                is Stride.RateResult.Failed -> {
                    finishSync(getString(R.string.rate_sync_failed, result.reason), error = true)
                    return
                }
                Stride.RateResult.NotYet -> {
                    delay(wait)
                    wait = (wait * 2).coerceAtMost(MAX_RETRY_MS)
                }
            }
        }
        finishSync(getString(R.string.rate_sync_gave_up), error = true)
    }

    private fun finishSync(status: String, error: Boolean) {
        ratings.clearPending(status)
        karoo.dispatch(
            SystemNotification(
                id = NOTIFICATION_SYNC,
                header = getString(R.string.rate_notification_header),
                message = status,
                style = if (error) SystemNotification.Style.ERROR else SystemNotification.Style.EVENT,
            ),
        )
    }

    /** What the trainer should do now: hold [watts], let go (null), or not be driven at all. */
    private data class ErgWanted(val address: String?, val active: Boolean, val watts: Int?)

    /**
     * ERG: while a workout runs in a ride and ERG is on, drives the chosen trainer over Bluetooth
     * FTMS to the current target; lets go of the resistance on pauses and steps without a target,
     * and disconnects when the workout ends. Checked once a second, reconnecting after a drop.
     */
    private fun startErg() {
        val settings = ErgSettings.get(this)
        val wanted = MutableStateFlow(ErgWanted(null, false, null))
        /** The trainer is connected and under control right now. */
        val connected = MutableStateFlow(false)
        // The active ride profile decides whether ERG applies (indoor profiles, or the chosen ones).
        scope.launch {
            karoo.consumerFlow<ActiveRideProfile>().collect { e ->
                settings.profileActive(ErgSettings.Profile(e.profile.id, e.profile.name, e.profile.indoor))
            }
        }
        val allowed = combine(settings.profiles, settings.activeProfileId, settings.onlyChosen, settings.chosen) { list, active, _, _ ->
            settings.allowedIn(list.firstOrNull { it.id == active })
        }
        // Today's intensity: on screen and in ERG (st.target is already scaled); back to 100%
        // when the ride ends, so a weak day doesn't carry over.
        // Only with a trainer in ERG: otherwise targets stay as planned.
        scope.launch {
            // Only while the trainer is actually connected: outdoors the targets stay as planned.
            combine(settings.intensity, settings.enabled, settings.trainer, allowed, connected) { pct, on, trainer, ok, up ->
                if (on && trainer != null && ok && up) pct else 100
            }.collect { workout.setIntensity(it) }
        }
        scope.launch {
            karoo.consumerFlow<RideState>().map { it is RideState.Idle }.distinctUntilChanged().filter { it }
                .collect { if (workout.state.value.workoutLoaded || settings.intensity.value != 100) settings.setIntensity(100) }
        }
        scope.launch {
            combine(workout.state, karoo.consumerFlow<RideState>(), settings.enabled, settings.trainer, allowed) { st, ride, on, trainer, ok ->
                ErgWanted(
                    address = if (on && ok) trainer?.first else null,
                    active = ride !is RideState.Idle && st.workoutLoaded && !st.finished,
                    watts = if (ride is RideState.Recording && !st.workoutPaused) st.target?.takeIf { it > 0 } else null,
                )
            }.collect { wanted.value = it }
        }
        scope.launch {
            var trainer: FtmsTrainer? = null
            var sent: Int? = null
            var released = false
            var retryAt = 0L
            var sentAt = 0L
            var warnedAt = 0L
            fun drop() {
                trainer?.close()
                trainer = null
                sent = null
                released = false
                workout.setErgActive(false)
            }
            while (true) {
                val w = wanted.value
                try {
                    if (w.address == null || !w.active || !ErgSettings.hasPermissions(this@IntervalExtension)) {
                        if (trainer != null) {
                            runCatching { trainer?.pause() }
                            drop()
                        }
                    } else {
                        if (trainer?.isUp != true) {
                            if (trainer != null) drop()
                            if (System.currentTimeMillis() >= retryAt) {
                                Timber.i("ERG: connecting to ${w.address}")
                                val t = FtmsTrainer(this@IntervalExtension, w.address)
                                trainer = t
                                t.open()
                                // Tell the rider the trainer is driven now (the first seconds of a ride).
                                ergAlert(getString(R.string.erg_alert_connected), getString(R.string.erg_alert_connected_detail, settings.trainer.value?.second ?: ""), ok = true)
                                warnedAt = 0L
                            }
                        }
                        val t = trainer
                        if (t != null && t.isUp) {
                            if (w.watts != null) {
                                if (released) {
                                    t.resume()
                                    released = false
                                }
                                // Send on every change, and again every 15 s in case the trainer missed one.
                                val now = System.currentTimeMillis()
                                if (sent != w.watts || now - sentAt >= ERG_RESEND_MS) {
                                    t.setTargetPower(w.watts)
                                    sent = w.watts
                                    sentAt = now
                                }
                                workout.setErgActive(true)
                            } else if (!released) {
                                t.pause()
                                released = true
                                sent = null
                                workout.setErgActive(false)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Timber.w(e, "ERG failed")
                    drop()
                    retryAt = System.currentTimeMillis() + ERG_RETRY_MS
                    // The workout runs but the trainer can't be reached: say so (at most once a minute).
                    if (System.currentTimeMillis() - warnedAt >= ERG_WARN_EVERY_MS) {
                        warnedAt = System.currentTimeMillis()
                        ergAlert(getString(R.string.erg_alert_failed), getString(R.string.erg_alert_failed_detail), ok = false)
                    }
                }
                connected.value = trainer?.isUp == true
                delay(ERG_TICK_MS)
            }
        }
    }

    /** A 5-second banner over the ride screens about the trainer connection. */
    private fun ergAlert(title: String, detail: String, ok: Boolean) {
        karoo.dispatch(
            InRideAlert(
                id = "erg-${System.currentTimeMillis()}",
                icon = R.drawable.ic_bolt_filled,
                title = title,
                detail = detail,
                autoDismissMs = 5_000L,
                backgroundColor = if (ok) R.color.erg_alert_ok else R.color.erg_alert_warn,
                textColor = R.color.erg_alert_text,
            ),
        )
    }

    /** Writes the rating into the ride's FIT session whenever it changes while a ride is open. */
    override fun startFit(emitter: Emitter<FitEffect>) {
        val job = scope.launch {
            combine(karoo.consumerFlow<RideState>(), ratings.rating) { ride, r ->
                if (ride is RideState.Idle) emptyList() else r.toSessionValues()
            }
                .filter { it.isNotEmpty() }
                .distinctUntilChanged()
                .collect { emitter.onNext(WriteToSessionMesg(it)) }
        }
        emitter.setCancellable { job.cancel() }
    }

    override fun onDestroy() {
        scope.cancel()
        syncJob?.cancel()
        workout.stop()
        session = null
        karoo.disconnect()
        super.onDestroy()
    }

    companion object {
        const val EXTENSION_ID = "intervalscreenstride"
        const val ACTION_NEXT = "skip"
        const val ACTION_PREVIOUS = "previous"
        const val ACTION_PAUSE = "pause"
        private const val NOTIFICATION_RATE = "ride_rating"
        private const val NOTIFICATION_SYNC = "ride_rating_sync"
        private const val ERG_TICK_MS = 1_000L
        /** Workouts shorter than this get no halfway banner. */
        private const val MIN_HALFWAY_SEC = 20 * 60
        private const val ERG_RETRY_MS = 10_000L
        private const val ERG_RESEND_MS = 15_000L
        private const val ERG_WARN_EVERY_MS = 60_000L
        private const val FIRST_RETRY_MS = 30_000L
        private const val MAX_RETRY_MS = 10 * 60_000L
        private const val GIVE_UP_MS = 12 * 60 * 60_000L

        /** Shared with [ActionReceiver], which runs in the same process. */
        @Volatile
        var session: WorkoutSession? = null
            private set
    }
}
