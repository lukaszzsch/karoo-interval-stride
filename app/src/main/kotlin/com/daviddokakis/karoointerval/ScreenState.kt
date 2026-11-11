package com.daviddokakis.karoointerval

/** Everything the interval screen needs to draw one frame. */
data class ScreenState(
    val plan: WorkoutPlan? = null,
    /** Instant power (lap average is built from this). */
    val power: Int? = null,
    /** 3-second average power, shown as the big number. */
    val power3s: Int? = null,
    val heartRate: Int? = null,
    /** Current cadence in rpm, or null without a cadence sensor. */
    val cadence: Int? = null,
    /** This ride's rating ("RPE 7 · Dobrze"), or null when not rated. */
    val ratingSummary: String? = null,
    /** ⏭ was tapped on the last interval: tap again within a few seconds to end the workout. */
    val finishArmed: Boolean = false,
    /** The trainer is holding the target in ERG. */
    val ergActive: Boolean = false,
    /** Today's intensity: every target at this % of the plan (100 = as planned). */
    val intensityPct: Int = 100,
    /** 0-based index of the current step. */
    val stepIndex: Int = 0,
    val elapsedInStepSec: Int = 0,
    val finished: Boolean = false,
    val lapAvg: Int? = null,
    /** Normalised power of the current interval lap (null in its first 30 s). */
    val lapNp: Int? = null,
    /** Lap tile shows NP instead of the lap average (tap to toggle, remembered). */
    val lapShowsNp: Boolean = false,
    /** Interval clock counts up ("3:25") instead of down ("−1:35"). */
    val clockShowsElapsed: Boolean = false,
    /** Free ride: the HR tile shows the lap's average heart rate (tap to toggle, remembered). */
    val hrShowsLap: Boolean = false,
    /** Karoo's average heart rate this lap, and in the last lap (free ride). */
    val lapHrAvg: Int? = null,
    val lastLapHrAvg: Int? = null,
    /** The ride is paused (Karoo's own pause). */
    val paused: Boolean = false,
    /** The workout is paused from this screen: interval clock and lap average stop, ride keeps recording. */
    val workoutPaused: Boolean = false,
    /** Seconds left before jumping, or null when no jump is pending. */
    val countdown: Int? = null,
    /** Interval being chosen with ⏮/⏭ (before the countdown), or null. */
    val selectTarget: Int? = null,
    /** The choice is "restart the current interval". */
    val selectRestart: Boolean = false,
    /** Confirmation fill, 1..4 (0 = not choosing). */
    val selectProgress: Int = 0,
    /** Seconds until a remote-made choice confirms itself, or null (on-screen choice / none). */
    val autoConfirmLeft: Int? = null,
    /** Step the pending jump will land on. */
    val jumpTarget: Int? = null,
    /** How the target range is shown (tap the watts to cycle). */
    val wattsMode: WattsMode = WattsMode.PLAIN,
    /** The Karoo is navigating (route pill shown, field shorter). */
    val navigating: Boolean = false,
    /** Dark / Light / Auto setting (Auto: follow the Karoo). */
    val appearance: Appearance = Appearance.AUTO,
    /** "Karoo native styling" setting: Karoo's font, labels and icons on the interval screen. */
    val karooStyle: Boolean = false,
    /** Target tile shows the target range instead of the single target (tap to toggle). */
    val targetShowsRange: Boolean = false,
    /** "Move cancelled" is showing (after ✕ on the countdown). */
    val moveCancelled: Boolean = false,
    /** Chart shows the current interval zoomed in (with the edges of its neighbours) instead of the whole workout. */
    val chartZoomed: Boolean = false,
    // Karoo's own lap data, shown when no workout is loaded (free ride).
    val lapNumber: Int? = null,
    val lapTimeSec: Int? = null,
    val lapPowerAvg: Int? = null,
    /** Normalised power of Karoo's current lap (free ride). */
    val freeLapNp: Int? = null,
    val lastLapTimeSec: Int? = null,
    val lastLapPowerAvg: Int? = null,
    /** Heart rate zones from the Karoo rider profile (min..max bpm), Z1 first. */
    val hrZones: List<IntRange> = emptyList(),
    /** The rider's power zones from the Karoo profile (6, as on the Karoo). */
    val powerZones: List<IntRange> = emptyList(),
    /** Free-ride power chart span: 0 whole ride, 1 last 60 min, 2 last 20 min, 3 last 5 min. */
    val freeChartSpan: Int = 0,
    /** A workout is loaded but not started yet (START WORKOUT): its clock doesn't run. */
    val waiting: Boolean = false,
    /** The finished workout's time, average power, NP and heart rate. */
    val summary: WorkoutSummary? = null,
    /** Free ride: power tile value (0 lap avg, 1 lap NP, 2 ride avg) and HR tile (0 now, 1 lap, 2 ride). */
    val freePowerTile: Int = 0,
    val freeHrTile: Int = 0,
    /** The whole ride's average power and heart rate (Karoo). */
    val rideAvgPower: Int? = null,
    val rideAvgHr: Int? = null,
    /** The whole ride's NP, IF and TSS (Karoo, from the profile FTP). */
    val rideNp: Int? = null,
    val rideIf: Double? = null,
    val rideTss: Int? = null,
    /** What the top row shows. */
    val topRow: TopRow = TopRow.AUTO,
    /** Tiles as an outline instead of a grey fill. */
    val tileOutline: Boolean = false,
    /** FTP from the Karoo rider profile, used when the plan has none. */
    val profileFtp: Int? = null,
) {
    val workoutLoaded: Boolean get() = plan != null
    val stepCount: Int get() = plan?.steps?.size ?: 0
    val currentStep: PlanStep? get() = plan?.steps?.getOrNull(stepIndex)
    val nextStep: PlanStep? get() = plan?.steps?.getOrNull(stepIndex + 1)
    val jumpStep: PlanStep? get() = jumpTarget?.let { plan?.steps?.getOrNull(it) }
    /** Where a jump would land: the countdown's target, else the one being chosen. */
    val landTarget: Int? get() = jumpTarget ?: selectTarget
    val landStep: PlanStep? get() = landTarget?.let { plan?.steps?.getOrNull(it) }
    val hasNext: Boolean get() = stepIndex < stepCount - 1
    val hasPrevious: Boolean get() = stepIndex > 0
    val ftp: Int? get() = plan?.ftp ?: profileFtp

    val intervalTotalSec: Int? get() = currentStep?.durationSec
    val intervalRemainingSec: Int? get() = currentStep?.let { (it.durationSec - elapsedInStepSec).coerceAtLeast(0) }
    val workoutRemainingSec: Int?
        get() = plan?.let { (it.totalSec - it.startSecOf(stepIndex) - elapsedInStepSec).coerceAtLeast(0) }

    /** 1-based heart rate zone, or null when unknown or below Z1. */
    val hrZone: Int? get() = zoneOf(heartRate)

    /** Free ride: the heart rate the HR tile shows (current, or the lap's average). */
    val shownHr: Int? get() = if (hrShowsLap) lapHrAvg else heartRate

    /** 1-based zone of [hr], or null when unknown or below Z1. */
    fun zoneOf(hr: Int?): Int? {
        if (hr == null || hrZones.isEmpty()) return null
        val i = hrZones.indexOfFirst { hr in it }
        return when {
            i >= 0 -> i + 1
            hr > hrZones.last().last -> hrZones.size
            else -> null
        }
    }

    val target: Int? get() = currentStep?.takeIf { it.hasTarget }?.targetAt(elapsedInStepSec)?.let(::scaled)
    val targetRange: IntRange? get() = currentStep?.takeIf { it.hasTarget }?.rangeAt(elapsedInStepSec)
        ?.let { scaled(it.first)..scaled(it.last) }

    /** [watts] at today's [intensityPct] (95% on a weaker day). */
    fun scaled(watts: Int): Int = if (intensityPct == 100) watts else Math.round(watts * intensityPct / 100f)
}

/** Tap the watts to cycle: plain → coloured number → coloured background → both → plain. */
enum class WattsMode {
    /** White (dark mode) or black (light mode). */
    PLAIN,
    /** The watts number turns blue / green / red below / in / above the target range. */
    TEXT,
    /** The top of the screen gets a blue / green / red tint; the number stays plain. */
    BACKGROUND,
    /** Both: coloured number on the tinted background. */
    BOTH,
    /**
     * Power gauge: an arc with the target range in green and a needle at the watts. Uses its own
     * layout (field_interval_gauge.xml): target on top, Lap avg / HR tiles under the gauge.
     */
    GAUGE,
    ;

    val colorsText: Boolean get() = this == TEXT || this == BOTH
    val colorsBackground: Boolean get() = this == BACKGROUND || this == BOTH

    fun next(): WattsMode = entries[(ordinal + 1) % entries.size]
}
