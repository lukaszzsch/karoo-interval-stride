package com.daviddokakis.karoointerval

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.FitEffect
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
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

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
        val rideStart = LocalDateTime.ofInstant(Instant.ofEpochMilli(pending.rideStartMs), ZoneId.systemDefault())
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
        private const val FIRST_RETRY_MS = 30_000L
        private const val MAX_RETRY_MS = 10 * 60_000L
        private const val GIVE_UP_MS = 12 * 60 * 60_000L

        /** Shared with [ActionReceiver], which runs in the same process. */
        @Volatile
        var session: WorkoutSession? = null
            private set
    }
}
