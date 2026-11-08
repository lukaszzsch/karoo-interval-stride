package com.daviddokakis.karoointerval

import android.content.Context
import io.hammerhead.karooext.KarooSystemService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters

/**
 * Holds the selected workout and the Stride token, shared by the extension service
 * and the setup screen (same process). Everything is kept in app-private storage.
 */
class PlanStore private constructor(context: Context) {

    private val prefs = context.getSharedPreferences("interval_screen", Context.MODE_PRIVATE)

    private val _plan = MutableStateFlow(loadSelected())
    val plan: StateFlow<WorkoutPlan?> = _plan

    /** Hide workouts already ridden from the week list (default on). */
    var hideDone: Boolean
        get() = prefs.getBoolean("hide_done", true)
        set(value) = prefs.edit().putBoolean("hide_done", value).apply()

    /** FTP typed in on the Account page ("" = work it out from Stride's planned power). */
    var ftp: String
        get() = prefs.getString(KEY_FTP, "") ?: ""
        set(value) = prefs.edit().putString(KEY_FTP, value.trim()).apply()

    private val ftpOverride: Int? get() = ftp.toIntOrNull()?.takeIf { it in 50..700 }

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value.trim()).apply()

    val hasCredentials: Boolean get() = apiKey.isNotBlank()

    /** The first-run introduction was finished or skipped (Settings can show it again). */
    var introDone: Boolean
        get() = prefs.getBoolean(KEY_INTRO_DONE, false)
        set(value) = prefs.edit().putBoolean(KEY_INTRO_DONE, value).apply()

    /** Target tile shows the range instead of the single target (remembered between rides). */
    var targetShowsRange: Boolean
        get() = prefs.getBoolean(KEY_TARGET_RANGE, false)
        set(value) = prefs.edit().putBoolean(KEY_TARGET_RANGE, value).apply()

    /** Free ride: the HR tile shows the lap's average heart rate instead of the current one (remembered). */
    var hrShowsLap: Boolean
        get() = prefs.getBoolean(KEY_HR_LAP, false)
        set(value) = prefs.edit().putBoolean(KEY_HR_LAP, value).apply()

    /** Free-ride power chart span (index into PowerHistory.SPANS, remembered). */
    var freeChartSpan: Int
        get() = prefs.getInt("free_chart_span", 0)
        set(value) = prefs.edit().putInt("free_chart_span", value).apply()

    /** The interval clock counts up (time in the interval) instead of down (remembered). */
    var clockShowsElapsed: Boolean
        get() = prefs.getBoolean("clock_elapsed", false)
        set(value) = prefs.edit().putBoolean("clock_elapsed", value).apply()

    /** Lap tile shows NP instead of the lap average (remembered between rides). */
    var lapShowsNp: Boolean
        get() = prefs.getBoolean(KEY_LAP_NP, false)
        set(value) = prefs.edit().putBoolean(KEY_LAP_NP, value).apply()

    private val _karooStyle = MutableStateFlow(prefs.getBoolean(KEY_KAROO_STYLE, false))

    /**
     * "Karoo native styling" (Account page): Karoo's condensed font, uppercase labels with green
     * icons and no tile backgrounds on the interval screen. A flow, so a ride already showing the
     * screen picks up the change.
     */
    val karooStyle: StateFlow<Boolean> = _karooStyle

    fun setKarooStyle(on: Boolean) {
        prefs.edit().putBoolean(KEY_KAROO_STYLE, on).apply()
        _karooStyle.value = on
    }

    private val _appearance = MutableStateFlow(
        Appearance.entries.firstOrNull { it.name == prefs.getString(KEY_APPEARANCE, null) } ?: Appearance.AUTO,
    )

    /** Dark / Light / Auto (Settings page). Auto follows the Karoo's own In-Ride Appearance. */
    val appearance: StateFlow<Appearance> = _appearance

    fun setAppearance(value: Appearance) {
        prefs.edit().putString(KEY_APPEARANCE, value.name).apply()
        _appearance.value = value
    }

    /** How the watts show the target range (remembered between rides). */
    var wattsMode: WattsMode
        get() {
            prefs.getString(KEY_WATTS_MODE, null)?.let { name ->
                return WattsMode.entries.firstOrNull { it.name == name } ?: WattsMode.PLAIN
            }
            // Earlier versions stored an on/off setting for coloured numbers.
            return if (prefs.getBoolean(KEY_WATTS_COLORED, false)) WattsMode.TEXT else WattsMode.PLAIN
        }
        set(value) = prefs.edit().putString(KEY_WATTS_MODE, value.name).remove(KEY_WATTS_COLORED).apply()

    fun select(workout: PlannedWorkout) {
        prefs.edit()
            .putString(KEY_SELECTED_JSON, workout.json)
            .putString(KEY_SELECTED_DATE, LocalDate.now().toString())
            .apply()
        _plan.value = workout.plan
    }

    fun clearSelection() {
        prefs.edit().remove(KEY_SELECTED_JSON).remove(KEY_SELECTED_DATE).apply()
        _plan.value = null
    }

    /**
     * Fetches this week's planned rides (Monday to Sunday). Never picks a workout by itself;
     * the rider always chooses. A selected workout that was edited on Stride is updated.
     */
    suspend fun refreshWeek(karoo: KarooSystemService): List<PlannedWorkout> {
        val today = LocalDate.now()
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val workouts = Stride(apiKey, ftpOverride, prefs.getInt(KEY_LAST_FTP, 0).takeIf { it > 0 })
            .plannedWorkouts(monday, monday.plusDays(6)) { url, headers -> karoo.karooHttpGet(url, headers) }
        workouts.firstNotNullOfOrNull { it.plan.ftp }?.let { prefs.edit().putInt(KEY_LAST_FTP, it).apply() }
        prefs.edit()
            .putString(KEY_WEEK_JSON, JSONArray(workouts.map { JSONObject(it.json) }).toString())
            .putString(KEY_WEEK_SYNCED, LocalDateTime.now().toString())
            .apply()
        val current = _plan.value
        if (current != null) {
            // The selected workout was edited on Stride: pick up the new version.
            workouts.firstOrNull { it.plan.id == current.id && it.plan != current }?.let(::select)
        }
        return workouts
    }

    /** The week from the last successful sync, so the list also shows without internet. */
    fun cachedWeek(): List<PlannedWorkout> {
        val json = prefs.getString(KEY_WEEK_JSON, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { Stride.plannedFrom(array.getJSONObject(it)) }
        } catch (e: Exception) {
            Timber.w(e, "Couldn't read cached week")
            emptyList()
        }
    }

    /** When the week was last synced, or null if never. */
    val lastSynced: LocalDateTime?
        get() = prefs.getString(KEY_WEEK_SYNCED, null)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }

    fun saveProgress(plan: WorkoutPlan, stepIndex: Int, elapsedSec: Int) {
        prefs.edit()
            .putString(KEY_PROGRESS, "${LocalDate.now()}|${plan.id}|$stepIndex|$elapsedSec")
            .apply()
    }

    /** Where to resume [plan] if the app restarted mid-ride today, else (0, 0). */
    fun restoreProgress(plan: WorkoutPlan): Pair<Int, Int> {
        val parts = prefs.getString(KEY_PROGRESS, null)?.split("|") ?: return 0 to 0
        if (parts.size != 4 || parts[0] != LocalDate.now().toString() || parts[1] != plan.id) return 0 to 0
        val index = parts[2].toIntOrNull()?.takeIf { it in plan.steps.indices } ?: return 0 to 0
        return index to (parts[3].toIntOrNull() ?: 0)
    }

    fun clearProgress() {
        prefs.edit().remove(KEY_PROGRESS).apply()
    }

    /** Only today's selection is restored, so yesterday's workout doesn't show up on today's ride. */
    private fun loadSelected(): WorkoutPlan? {
        if (prefs.getString(KEY_SELECTED_DATE, null) != LocalDate.now().toString()) return null
        val json = prefs.getString(KEY_SELECTED_JSON, null) ?: return null
        return try {
            Stride.parseEvent(JSONObject(json))
        } catch (e: Exception) {
            Timber.w(e, "Couldn't restore selected workout")
            null
        }
    }

    companion object {
        private const val KEY_FTP = "ftp"
        private const val KEY_LAST_FTP = "last_ftp"
        private const val KEY_API_KEY = "stride_token"
        private const val KEY_SELECTED_JSON = "selected_json"
        private const val KEY_SELECTED_DATE = "selected_date"
        private const val KEY_PROGRESS = "progress"
        private const val KEY_WATTS_COLORED = "watts_colored"
        private const val KEY_WATTS_MODE = "watts_mode"
        private const val KEY_TARGET_RANGE = "target_shows_range"
        private const val KEY_LAP_NP = "lap_shows_np"
        private const val KEY_HR_LAP = "hr_shows_lap"
        private const val KEY_KAROO_STYLE = "karoo_style"
        private const val KEY_APPEARANCE = "appearance"
        private const val KEY_WEEK_JSON = "week_json"
        private const val KEY_WEEK_SYNCED = "week_synced"
        private const val KEY_INTRO_DONE = "intro_done"

        @Volatile
        private var instance: PlanStore? = null

        fun get(context: Context): PlanStore =
            instance ?: synchronized(this) {
                instance ?: PlanStore(context.applicationContext).also { instance = it }
            }
    }
}
