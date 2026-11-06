package com.daviddokakis.karoointerval

import android.content.Context
import io.hammerhead.karooext.models.DeveloperField
import io.hammerhead.karooext.models.FieldValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * The rider's rating of a ride: RPE 1–10 and feel 1 (terrible) – 5 (great), Stride's scales.
 * Either part may be unset.
 */
data class RideRating(val rpe: Int? = null, val feel: Int? = null) {
    val isEmpty: Boolean get() = rpe == null && feel == null

    /** "RPE 7 · Dobrze", or null when nothing is rated. */
    fun summary(context: Context): String? =
        listOfNotNull(rpe?.let { "RPE $it" }, feel?.let { feelLabel(context, it) })
            .joinToString(" · ").ifBlank { null }

    /**
     * Garmin's session fields, written as developer fields like karoo-rpe does: `workout_rpe`
     * (RPE × 10) and `workout_feel` (0 / 25 / 50 / 75 / 100). Karoo firmware currently drops the
     * native field mapping, so platforms ignore them for now; Stride gets the rating via its API.
     */
    fun toSessionValues(): List<FieldValue> = buildList {
        rpe?.takeIf { it in 1..10 }?.let { add(FieldValue(RPE_DEV_FIELD.fieldDefinitionNumber.toInt(), it * 10.0, RPE_DEV_FIELD)) }
        feel?.takeIf { it in 1..5 }?.let { add(FieldValue(FEEL_DEV_FIELD.fieldDefinitionNumber.toInt(), (it - 1) * 25.0, FEEL_DEV_FIELD)) }
    }

    companion object {
        private const val FIT_UINT8: Short = 2

        val RPE_DEV_FIELD = DeveloperField(
            fieldDefinitionNumber = 0,
            fitBaseTypeId = FIT_UINT8,
            fieldName = "workout_rpe",
            units = "",
            nativeFieldNum = 193,
        )
        val FEEL_DEV_FIELD = DeveloperField(
            fieldDefinitionNumber = 1,
            fitBaseTypeId = FIT_UINT8,
            fieldName = "workout_feel",
            units = "",
            nativeFieldNum = 192,
        )

        fun feelLabel(context: Context, feel: Int): String = context.getString(
            when (feel) {
                5 -> R.string.feel_5
                4 -> R.string.feel_4
                3 -> R.string.feel_3
                2 -> R.string.feel_2
                else -> R.string.feel_1
            },
        )

        fun rpeLabel(context: Context, rpe: Int): String = context.getString(
            when (rpe) {
                1 -> R.string.rpe_1
                2 -> R.string.rpe_2
                3 -> R.string.rpe_3
                4 -> R.string.rpe_4
                5, 6 -> R.string.rpe_5
                7, 8 -> R.string.rpe_7
                9 -> R.string.rpe_9
                else -> R.string.rpe_10
            },
        )
    }
}

/** A finished ride's rating, waiting to be put on its Stride activity. */
data class PendingRating(
    val rpe: Int?,
    val feel: Int?,
    /** When the ride started (epoch ms), to find it on Stride. */
    val rideStartMs: Long,
    /** When the ride ended (epoch ms); sync gives up 12 hours later. */
    val rideEndMs: Long,
) {
    fun toJson(): String = JSONObject().apply {
        rpe?.let { put("rpe", it) }
        feel?.let { put("feel", it) }
        put("start", rideStartMs)
        put("end", rideEndMs)
    }.toString()

    companion object {
        fun fromJson(json: String): PendingRating? = runCatching {
            val o = JSONObject(json)
            PendingRating(
                rpe = if (o.has("rpe")) o.getInt("rpe") else null,
                feel = if (o.has("feel")) o.getInt("feel") else null,
                rideStartMs = o.getLong("start"),
                rideEndMs = o.getLong("end"),
            )
        }.getOrNull()
    }
}

/**
 * The current ride's rating and the queue for Stride, in app-private storage. Shared by the
 * extension service and the rating screen (same process).
 */
class RatingStore private constructor(context: Context) {

    private val prefs = context.getSharedPreferences("ride_rating", Context.MODE_PRIVATE)

    private val _rating = MutableStateFlow(
        RideRating(
            rpe = prefs.getInt(KEY_RPE, 0).takeIf { it in 1..10 },
            feel = prefs.getInt(KEY_FEEL, 0).takeIf { it in 1..5 },
        ),
    )
    val rating: StateFlow<RideRating> = _rating

    private val _pending = MutableStateFlow(prefs.getString(KEY_PENDING, null)?.let(PendingRating::fromJson))
    val pending: StateFlow<PendingRating?> = _pending

    /** Last result of sending a rating to Stride, shown on the rating screen. */
    var lastStatus: String?
        get() = prefs.getString(KEY_STATUS, null)
        set(value) = prefs.edit().putString(KEY_STATUS, value).apply()

    fun setRating(rating: RideRating) {
        prefs.edit().putInt(KEY_RPE, rating.rpe ?: 0).putInt(KEY_FEEL, rating.feel ?: 0).apply()
        _rating.value = rating
    }

    /** A ride opened: remember when (kept if a ride is already open, e.g. after a restart). */
    fun rideStarted(nowMs: Long) {
        if (prefs.getLong(KEY_RIDE_START, 0L) == 0L) prefs.edit().putLong(KEY_RIDE_START, nowMs).apply()
    }

    /**
     * The ride ended (or was discarded): its rating can no longer go into the FIT file, so it is
     * queued for Stride and cleared, so it can't leak into the next ride.
     */
    fun rideEnded(nowMs: Long) {
        val start = prefs.getLong(KEY_RIDE_START, 0L)
        val r = _rating.value
        val edit = prefs.edit().remove(KEY_RIDE_START).putInt(KEY_RPE, 0).putInt(KEY_FEEL, 0)
        if (start > 0L && !r.isEmpty) {
            val p = PendingRating(r.rpe, r.feel, start, nowMs)
            edit.putString(KEY_PENDING, p.toJson())
            _pending.value = p
        }
        edit.apply()
        _rating.value = RideRating()
    }

    fun clearPending(status: String) {
        prefs.edit().remove(KEY_PENDING).putString(KEY_STATUS, status).apply()
        _pending.value = null
    }

    companion object {
        private const val KEY_RPE = "rpe"
        private const val KEY_FEEL = "feel"
        private const val KEY_RIDE_START = "ride_start"
        private const val KEY_PENDING = "pending"
        private const val KEY_STATUS = "status"

        @Volatile
        private var instance: RatingStore? = null

        fun get(context: Context): RatingStore =
            instance ?: synchronized(this) {
                instance ?: RatingStore(context.applicationContext).also { instance = it }
            }
    }
}
