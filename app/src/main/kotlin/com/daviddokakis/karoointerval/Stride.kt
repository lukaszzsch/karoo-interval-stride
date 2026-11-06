package com.daviddokakis.karoointerval

import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalDate
import kotlin.math.roundToInt

/** A planned workout as listed by Stride. */
data class PlannedWorkout(
    val plan: WorkoutPlan,
    val date: LocalDate,
    /** Stride training score of the planned session, if set. */
    val load: Int?,
    /** Stored form: `{"activity": …, "workout": …, "ftp": …}`, re-read by [Stride.plannedFrom]. */
    val json: String,
)

/**
 * Minimal Stride (stride.is) client: lists this week's planned rides and loads the structured
 * workout of each one that has it. Auth is a personal access token (Stride → Settings → API),
 * sent as `Authorization: Bearer <token>`.
 *
 * Stride gives targets as % of FTP. Watts use, in order: the FTP typed in on the Account page,
 * else the FTP worked out from the session's planned average power (Stride computes that from
 * the athlete's FTP, so planned_avg_power ÷ the workout's average % gives it back).
 *
 * Requests go through the Karoo ([karooHttpGet]), which uses Wi-Fi when connected and otherwise
 * the phone connected through the Hammerhead Companion app.
 */
class Stride(
    private val token: String,
    private val ftpOverride: Int?,
    /** FTP worked out at an earlier sync, for sessions without a structured workout. */
    private val lastFtp: Int? = null,
) {

    /** Stride answered, but with an error (wrong token, no subscription, server problem). */
    class ApiException(message: String) : Exception(message)

    /** Planned ride workouts from [oldest] to [newest] (inclusive, local dates). */
    suspend fun plannedWorkouts(
        oldest: LocalDate,
        newest: LocalDate,
        get: suspend (url: String, headers: Map<String, String>) -> HttpResult,
    ): List<PlannedWorkout> {
        val headers = mapOf("Authorization" to "Bearer $token", "Accept" to "application/json")
        val list = get("$BASE/athletes/me/activities", headers)
        check(list, "lista aktywności")
        val activities = JSONObject(list.body).optJSONArray("activities") ?: JSONArray()

        val out = mutableListOf<PlannedWorkout>()
        val free = mutableListOf<JSONObject>()
        for (i in 0 until activities.length()) {
            val activity = activities.optJSONObject(i) ?: continue
            if (!isRide(activity)) continue
            if (!activity.optBoolean("has_workout", false)) {
                // "Ride by feel": no structure, only a duration (and an intensity). Added below,
                // once the structured workouts have told us the FTP.
                val date = plannedDate(activity)
                if (date != null && date >= oldest && date <= newest && activity.optInt("planned_duration_seconds") > 0) free += activity
                continue
            }
            val date = plannedDate(activity) ?: continue
            if (date < oldest || date > newest) continue

            val id = activity.optString("id")
            if (id.isBlank()) continue
            val result = get("$BASE/activities/$id/workout", headers)
            if (result.statusCode == 403 || result.statusCode == 404) continue // e.g. garmin_data_withheld
            check(result, "trening")
            val workout = JSONObject(result.body)
            val stored = JSONObject()
                .put("activity", activity)
                .put("workout", workout)
            ftpOverride?.let { stored.put("ftp", it) }
            val planned = plannedFrom(stored)
                ?: if (ftpOf(stored) == null && hasSegments(workout)) {
                    throw ApiException("Brak FTP dla „${activity.optString("name")}”. Wpisz FTP w Koncie.")
                } else {
                    null
                }
            planned?.let(out::add)
        }
        val ftp = ftpOverride ?: out.firstNotNullOfOrNull { it.plan.ftp } ?: lastFtp
        for (activity in free) {
            val stored = JSONObject().put("activity", activity)
            ftp?.let { stored.put("ftp", it) }
            plannedFrom(stored)?.let(out::add)
        }
        return out.sortedBy { it.date }
    }

    /** What happened when putting a rating on Stride. */
    sealed class RateResult {
        data class Done(val activityName: String?) : RateResult()
        /** The ride isn't on Stride yet: try again later. */
        data object NotYet : RateResult()
        data class Failed(val reason: String) : RateResult()
    }

    /**
     * Finds the ride that started at [rideStart] (local time) among the athlete's activities
     * and sets its RPE and feel (PATCH /activities/{id}, needs activities.write).
     */
    suspend fun rate(
        rideStart: LocalDateTime,
        rpe: Int?,
        feel: Int?,
        http: suspend (method: String, url: String, headers: Map<String, String>, body: ByteArray?) -> HttpResult,
    ): RateResult {
        val headers = mapOf("Authorization" to "Bearer $token", "Accept" to "application/json")
        val list = http("GET", "$BASE/athletes/me/activities", headers, null)
        if (list.statusCode == 401 || list.statusCode == 403) return RateResult.Failed("Stride odrzucił token (HTTP ${list.statusCode})")
        if (list.statusCode !in 200..299) return RateResult.NotYet
        val activities = runCatching { JSONObject(list.body).optJSONArray("activities") }.getOrNull() ?: return RateResult.NotYet
        val id = findRide(activities, rideStart) ?: return RateResult.NotYet
        val body = JSONObject().apply {
            rpe?.takeIf { it in 1..10 }?.let { put("rpe", it) }
            feel?.takeIf { it in 1..5 }?.let { put("feel", it) }
        }.toString().toByteArray()
        val result = http("PATCH", "$BASE/activities/$id", headers + ("Content-Type" to "application/json"), body)
        return when (result.statusCode) {
            in 200..299 -> RateResult.Done(runCatching { JSONObject(result.body).optString("name") }.getOrNull()?.ifBlank { null })
            401, 403 -> RateResult.Failed(
                runCatching { JSONObject(result.body).optString("detail") }.getOrNull()?.ifBlank { null }
                    ?: "brak uprawnienia activities.write (HTTP ${result.statusCode})",
            )
            in 500..599 -> RateResult.NotYet
            else -> RateResult.Failed("Stride zwrócił HTTP ${result.statusCode}")
        }
    }

    private fun check(result: HttpResult, what: String) {
        val code = result.statusCode
        if (code == 401) throw ApiException("Stride odrzucił token API")
        if (code == 402 || code == 403) {
            val detail = runCatching { JSONObject(result.body).optString("detail") }.getOrNull()
            throw ApiException(detail?.ifBlank { null } ?: "Stride odmówił dostępu (HTTP $code). Potrzebny jest aktywny okres próbny lub subskrypcja Stride.")
        }
        if (code !in 200..299) throw ApiException("Stride zwrócił HTTP $code ($what)")
    }

    companion object {
        private const val BASE = "https://api.stride.is/v1"

        private fun isRide(activity: JSONObject): Boolean {
            val type = activity.optString("type").uppercase()
            return type.isEmpty() || type == "RIDE" || type == "VIRTUAL_RIDE" || type == "VIRTUALRIDE"
        }

        /**
         * Local date the session is planned for. Stride writes planned_at (and started_at) as the
         * athlete's local wall-clock time with a Z suffix, so the date is read as written.
         */
        private fun plannedDate(activity: JSONObject): LocalDate? {
            val at = activity.optString("planned_at").ifBlank { return null }
            return runCatching { LocalDate.parse(at.take(10)) }.getOrNull()
        }

        /** started_at as local wall-clock time (see [plannedDate]). */
        internal fun startedAt(activity: JSONObject): LocalDateTime? {
            val at = activity.optString("started_at").ifBlank { return null }
            return runCatching { LocalDateTime.parse(at.removeSuffix("Z").substringBefore('+').take(19)) }.getOrNull()
        }

        /**
         * The recorded activity of a ride that started at [rideStart] (local time): started
         * within 5 minutes of it, preferring the Karoo's own upload (source HAMMERHEAD). Garmin
         * copies are skipped: Stride doesn't let other apps change them.
         */
        internal fun findRide(activities: JSONArray, rideStart: LocalDateTime): String? {
            var best: Pair<String, Long>? = null
            for (i in 0 until activities.length()) {
                val a = activities.optJSONObject(i) ?: continue
                if (a.optString("source").uppercase() == "GARMIN" || a.optBoolean("recording_withheld", false)) continue
                val started = startedAt(a) ?: continue
                val diff = Math.abs(Duration.between(rideStart, started).seconds)
                if (diff > MATCH_SECONDS) continue
                val score = diff + if (a.optString("source").uppercase() == "HAMMERHEAD") 0 else MATCH_SECONDS
                if (best == null || score < best.second) best = a.optString("id") to score
            }
            return best?.first?.ifBlank { null }
        }

        private const val MATCH_SECONDS = 300L

        private fun hasSegments(workout: JSONObject): Boolean =
            (workout.optJSONArray("segments")?.length() ?: 0) > 0

        /** A planned ride from its stored form, or null if it has no usable workout. */
        fun plannedFrom(stored: JSONObject): PlannedWorkout? {
            val activity = stored.optJSONObject("activity") ?: return null
            val date = plannedDate(activity) ?: return null
            val load = activity.optDoubleOrNull("planned_training_score")?.roundToInt()
                ?: stored.optJSONObject("workout")?.optDoubleOrNull("training_score")?.roundToInt()
            return parseEvent(stored)?.let { PlannedWorkout(it, date, load, stored.toString()) }
        }

        /** The workout of a stored session as a [WorkoutPlan]. */
        fun parseEvent(stored: JSONObject): WorkoutPlan? {
            val activity = stored.optJSONObject("activity") ?: return null
            val workout = stored.optJSONObject("workout") ?: return freePlan(activity, stored)
            val segments = workout.optJSONArray("segments") ?: return null
            val ftp = ftpOf(stored)
            val steps = mutableListOf<PlanStep>()
            for (i in 0 until segments.length()) {
                val segment = segments.optJSONObject(i) ?: continue
                val step = stepOf(segment, ftp)
                if (step != null) steps += step else if (needsFtp(segment) && ftp == null) return null
            }
            if (steps.isEmpty()) return null
            return WorkoutPlan(
                id = activity.optString("id").ifBlank { workout.optString("id") },
                name = activity.optString("name").ifBlank { workout.optString("name", "Trening") },
                steps = steps,
                ftp = ftp,
            )
        }

        /**
         * A session without structure as one long step: its planned duration, and (when the FTP
         * is known) a broad target around its planned intensity. Without an FTP it has no target.
         */
        private fun freePlan(activity: JSONObject, stored: JSONObject): WorkoutPlan? {
            val duration = activity.optInt("planned_duration_seconds").takeIf { it > 0 } ?: return null
            val ftp = stored.optDoubleOrNull("ftp")?.roundToInt()?.takeIf { it > 0 }
            val intensity = activity.optDoubleOrNull("planned_intensity")?.takeIf { it > 0 }
            val step = if (ftp != null && intensity != null) {
                val target = (intensity * ftp).roundToInt()
                val band = (target * FREE_BAND).roundToInt()
                PlanStep(duration, target, target, rangeLow = target - band, rangeHigh = target + band, isRange = true)
            } else {
                PlanStep(duration, 0, 0)
            }
            return WorkoutPlan(
                id = activity.optString("id"),
                name = activity.optString("name").ifBlank { "Jazda" },
                steps = listOf(step.copy(note = activity.optString("description").trim().ifBlank { null })),
                ftp = ftp,
            )
        }

        /** ± around the target of a session without structure. */
        private const val FREE_BAND = 0.08

        /** FTP typed in by the rider, else worked out from the planned average power. */
        internal fun ftpOf(stored: JSONObject): Int? {
            stored.optDoubleOrNull("ftp")?.roundToInt()?.takeIf { it > 0 }?.let { return it }
            val activity = stored.optJSONObject("activity") ?: return null
            val segments = stored.optJSONObject("workout")?.optJSONArray("segments") ?: return null
            val avgWatts = activity.optDoubleOrNull("planned_avg_power_watts") ?: return null
            var seconds = 0.0
            var percentSeconds = 0.0
            for (i in 0 until segments.length()) {
                val s = segments.optJSONObject(i) ?: continue
                if (s.optString("target_unit") != "percent_of_ftp") continue
                val d = durationOf(s) ?: continue
                val low = s.optDoubleOrNull("low_target") ?: continue
                val high = s.optDoubleOrNull("high_target") ?: low
                seconds += d
                percentSeconds += d * (low + high) / 2
            }
            if (seconds <= 0 || percentSeconds <= 0) return null
            return (avgWatts * 100 * seconds / percentSeconds).roundToInt().takeIf { it in 50..700 }
        }

        private fun durationOf(segment: JSONObject): Int? {
            val unit = segment.optString("duration_unit", "seconds")
            if (unit != "seconds" && unit != "s") return null // distance-based steps aren't supported
            return segment.optDoubleOrNull("duration")?.roundToInt()?.takeIf { it > 0 }
        }

        private fun needsFtp(segment: JSONObject): Boolean =
            segment.optString("target_unit") == "percent_of_ftp"

        /** One Stride segment as a step with a target range (Stride always gives low..high). */
        private fun stepOf(segment: JSONObject, ftp: Int?): PlanStep? =
            powerStepOf(segment, ftp)?.copy(
                note = segment.optString("text").trim().ifBlank { null },
                cadenceLow = segment.optDoubleOrNull("cadence_low")?.roundToInt()?.takeIf { it > 0 },
                cadenceHigh = segment.optDoubleOrNull("cadence_high")?.roundToInt()?.takeIf { it > 0 },
            )

        private fun powerStepOf(segment: JSONObject, ftp: Int?): PlanStep? {
            val duration = durationOf(segment) ?: return null
            val toWatts: (Double) -> Int? = when (segment.optString("target_unit")) {
                "percent_of_ftp" -> { v -> ftp?.let { (v * it / 100.0).roundToInt() } }
                "watts", "w", "W" -> { v -> v.roundToInt() }
                else -> { _ -> null }
            }
            val low = segment.optDoubleOrNull("low_target")?.let(toWatts)
            val high = segment.optDoubleOrNull("high_target")?.let(toWatts) ?: low
            if (low == null || high == null) {
                // No power target (e.g. free/RPE step): keep it in the timeline without a target.
                return if (needsFtp(segment)) null else PlanStep(duration, 0, 0)
            }
            if (segment.optBoolean("ramp", false)) return PlanStep(duration, low, high, ramp = true)
            val target = (low + high) / 2
            return PlanStep(duration, target, target, rangeLow = low, rangeHigh = high, isRange = low != high)
        }

        internal fun JSONObject.optDoubleOrNull(key: String): Double? =
            if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null
    }
}
