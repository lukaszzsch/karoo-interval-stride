package com.daviddokakis.karoointerval

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import timber.log.Timber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Entry point the Karoo system binds to. */
class IntervalExtension : KarooExtension(EXTENSION_ID, "1") {

    private lateinit var karoo: KarooSystemService
    private lateinit var workout: WorkoutSession
    private var syncJob: Job? = null

    override val types: List<DataTypeImpl> by lazy {
        listOf(IntervalDataType(extension, workout))
    }

    override fun onCreate() {
        super.onCreate()
        if (Timber.treeCount == 0) Timber.plant(Timber.DebugTree())
        val store = PlanStore.get(this)
        karoo = KarooSystemService(this)
        workout = WorkoutSession(karoo, store)
        session = workout
        karoo.connect { connected -> Timber.i("Karoo system connected: $connected") }
        workout.start()

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

    override fun onDestroy() {
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

        /** Shared with [ActionReceiver], which runs in the same process. */
        @Volatile
        var session: WorkoutSession? = null
            private set
    }
}
