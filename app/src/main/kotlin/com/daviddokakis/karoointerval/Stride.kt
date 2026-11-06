package com.daviddokakis.karoointerval

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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
class Stride(private val token: String, private val ftpOverride: Int?) {

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
        check(list, "list activities")
        val activities = JSONObject(list.body).optJSONArray("activities") ?: JSONArray()

        val out = mutableListOf<PlannedWorkout>()
        for (i in 0 until activities.length()) {
            val activity = activities.optJSONObject(i) ?: continue
            if (!activity.optBoolean("has_workout", false)) continue
            if (!isRide(activity)) continue
            val date = plannedDate(activity) ?: continue
            if (date < oldest || date > newest) continue

            val id = activity.optString("id")
            if (id.isBlank()) continue
            val result = get("$BASE/activities/$id/workout", headers)
            if (result.statusCode == 403 || result.statusCode == 404) continue // e.g. garmin_data_withheld
            check(result, "load workout")
            val workout = JSONObject(result.body)
            val stored = JSONObject()
                .put("activity", activity)
                .put("workout", workout)
            ftpOverride?.let { stored.put("ftp", it) }
            val planned = plannedFrom(stored)
                ?: if (ftpOf(stored) == null && hasSegments(workout)) {
                    throw ApiException("No FTP for \"${activity.optString("name")}\". Enter your FTP under Account.")
                } else {
                    null
                }
            planned?.let(out::add)
        }
        return out.sortedBy { it.date }
    }

    private fun check(result: HttpResult, what: String) {
        val code = result.statusCode
        if (code == 401) throw ApiException("Stride rejected the API token")
        if (code == 402 || code == 403) {
            val detail = runCatching { JSONObject(result.body).optString("detail") }.getOrNull()
            throw ApiException(detail?.ifBlank { null } ?: "Stride refused access (HTTP $code). An active Stride trial or subscription is needed.")
        }
        if (code !in 200..299) throw ApiException("Stride returned HTTP $code ($what)")
    }

    companion object {
        private const val BASE = "https://api.stride.is/v1"

        private fun isRide(activity: JSONObject): Boolean {
            val type = activity.optString("type").uppercase()
            return type.isEmpty() || type == "RIDE" || type == "VIRTUAL_RIDE" || type == "VIRTUALRIDE"
        }

        /** Local date the session is planned for (planned_at is UTC). */
        private fun plannedDate(activity: JSONObject): LocalDate? {
            val at = activity.optString("planned_at").ifBlank { return null }
            return runCatching { Instant.parse(at).atZone(ZoneId.systemDefault()).toLocalDate() }.getOrNull()
                ?: runCatching { LocalDate.parse(at.take(10)) }.getOrNull()
        }

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
            val workout = stored.optJSONObject("workout") ?: return null
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
                name = activity.optString("name").ifBlank { workout.optString("name", "Workout") },
                steps = steps,
                ftp = ftp,
            )
        }

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
