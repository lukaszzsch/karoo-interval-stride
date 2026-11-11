package com.daviddokakis.karoointerval

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import android.net.wifi.WifiManager
import android.provider.Settings
import io.hammerhead.karooext.KarooSystemService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Workouts page (Karoo app list, or the gear button on the interval screen):
 * the current workout with a way to stop it, and this week's planned rides from Stride
 * to pick from. The Stride API token is entered once and kept; "Account" changes it.
 */
class SetupActivity : AppearanceActivity() {

    private lateinit var store: PlanStore
    private lateinit var content: LinearLayout
    /** API key at the last sync, to re-sync when it changes on the Account page. */
    private var syncedKey: String? = null
    private var loading = false
    private var error: String? = null
    /** Shown immediately from the last sync, then replaced when a sync succeeds. */
    private var week: List<PlannedWorkout> = emptyList()
    /** Last sync failed because there was no connection (no Wi-Fi and no phone). */
    private var offline = false
    private lateinit var karoo: KarooSystemService
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var syncJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = PlanStore.get(this)
        karoo = KarooSystemService(this)
        karoo.connect()
        week = store.cachedWeek()

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(24))
        }
        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(getColor(R.color.bg))
                addView(content, MATCH_PARENT, WRAP_CONTENT)
            },
        )
        render()
        when {
            store.hasCredentials -> load()
            // First run: the introduction (connect, look, add to a profile, tap the watts).
            // Riders who already have a key (updating from an earlier version) never see it.
            !store.introDone -> startActivity(Intent(this, IntroActivity::class.java))
            else -> openAccount()
        }
    }

    override fun onRestart() {
        super.onRestart()
        // Back from the Account page with a new key: sync with it.
        if (store.hasCredentials && store.apiKey != syncedKey) load() else render()
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun openAccount() {
        startActivity(Intent(this, AccountActivity::class.java))
    }

    /**
     * While the page is open, sync as soon as a Wi-Fi network with internet comes up after a sync
     * failed for lack of connection (e.g. the rider just switched Wi-Fi on). Registering also reports
     * a network that's already up, which covers coming back from the Wi-Fi panel.
     */
    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scope.launch {
                if (offline && !loading && store.hasCredentials) {
                    delay(WIFI_SETTLE_MS) // give the Karoo a moment to start using the new network
                    if (offline && !loading) load()
                }
            }
        }
    }

    /** Re-draws the "Enable Wi-Fi" hint when Wi-Fi is switched on or off. */
    private val wifiStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (offline) render()
        }
    }

    override fun onStart() {
        super.onStart()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { getSystemService(ConnectivityManager::class.java).registerNetworkCallback(request, wifiCallback) }
        ContextCompat.registerReceiver(
            this,
            wifiStateReceiver,
            IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStop() {
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(wifiCallback) }
        runCatching { unregisterReceiver(wifiStateReceiver) }
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        karoo.disconnect()
        super.onDestroy()
    }

    /**
     * Syncs this week from Stride through the Karoo (Wi-Fi, or the phone over Bluetooth).
     * [openWifiIfOffline]: when the user tapped Sync and there's no connection with Wi-Fi off,
     * open the Wi-Fi panel straight away (apps can't switch Wi-Fi on themselves).
     */
    private fun load(openWifiIfOffline: Boolean = false) {
        syncJob?.cancel()
        loading = true
        error = null
        render()
        syncedKey = store.apiKey
        syncJob = scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { store.refreshWeek(karoo) } }
            loading = false
            offline = false
            result.onSuccess { week = it }
            result.onFailure {
                if (it is CancellationException && it !is TimeoutCancellationException) throw it
                offline = it is java.io.IOException || it is TimeoutCancellationException
                // Offline is worded at render time, since it depends on whether Wi-Fi is on.
                error = when {
                    it is Stride.ApiException -> it.message
                    offline -> null
                    else -> getString(R.string.setup_error)
                }
            }
            render()
            if (offline && !wifiEnabled() && openWifiIfOffline) openWifi()
        }
    }

    /** Today's intensity: "Intensywność 95%" with − / + in 1% steps (targets and ERG follow). */
    private fun intensityRow(): LinearLayout {
        val erg = ErgSettings.get(this)
        val value = text(getString(R.string.intensity_value, erg.intensity.value), 17f, bold = true)
        fun change(by: Int) {
            erg.setIntensity(erg.intensity.value + by)
            value.text = getString(R.string.intensity_value, erg.intensity.value)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8) }
            addView(value, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(button("\u2212", primary = false) { change(-1) }, LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
            addView(button("+", primary = false) { change(+1) }, LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    /** "ERG w tej jeździe": Wł. / Swobodnie (trainer free, ride the targets yourself) until the ride ends. */
    private fun ergThisRideRows(erg: ErgSettings) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) }
        addView(text(getString(R.string.erg_this_ride), 14f, bold = true).apply { setPadding(0, 0, 0, dp(4)) })
        addView(
            segmented(listOf(R.string.on to false, R.string.erg_free_ride to true), erg.offThisRide.value) { off ->
                erg.setOffThisRide(off)
                render()
            },
        )
        addView(text(getString(R.string.erg_this_ride_help), 12f, secondary = true).apply { setPadding(0, dp(4), 0, 0) })
    }

    /** "Rate the ride (RPE)", or the current rating; opens the rating page. */
    private fun rateButton() = button(
        RatingStore.get(this).rating.value.summary(this)?.let { getString(R.string.rate_button_rated, it) }
            ?: getString(R.string.rate_button),
        primary = false,
    ) {
        startActivity(Intent(this, RateActivity::class.java))
    }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8) }

    private fun wifiEnabled(): Boolean =
        runCatching { (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).isWifiEnabled }
            .getOrDefault(true)

    /** Opens the Wi-Fi quick panel (or full Wi-Fi settings) so the rider can switch it on. */
    private fun openWifi() {
        runCatching { startActivity(Intent(Settings.Panel.ACTION_WIFI)) }
            .recoverCatching { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
    }

    private fun render() {
        content.removeAllViews()

        // Header: title + account, then Back to ride + Sync
        content.addView(
            row(
                text(getString(R.string.workouts_title), 22f, bold = true).apply {
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                },
                iconButton(R.drawable.ic_settings, getString(R.string.settings_title)) { openSettings() },
            ),
        )
        content.addView(
            row(
                button(getString(R.string.back_to_ride), primary = true) { goToRide() }.apply {
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(8) }
                },
                button(getString(if (loading) R.string.syncing else R.string.sync), primary = false) {
                    if (!loading && store.hasCredentials) load(openWifiIfOffline = true)
                },
            ).apply { setPadding(0, dp(8), 0, 0) },
        )
        store.lastSynced?.let {
            val time = if (it.toLocalDate() == LocalDate.now()) it.format(TIME_FORMAT) else it.format(DATE_TIME_FORMAT)
            content.addView(text(getString(R.string.synced_at, time), 12f, secondary = true))
        }

        // Current workout
        val current = store.plan.value
        content.addView(sectionHeader(getString(R.string.current_workout)))
        if (current == null) {
            content.addView(text(getString(R.string.no_current_workout), 14f, secondary = true))
        } else {
            val currentWorkout = week.firstOrNull { it.plan.id == current.id }
            content.addView(
                card(current, currentWorkout?.date, selected = false, onClick = null).apply {
                    addView(
                        button(getString(R.string.stop_workout), primary = false) {
                            store.clearSelection()
                            render()
                        }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8) },
                    )
                    addView(rateButton())
                    // ERG for this ride and today's intensity: only with a trainer in ERG.
                    ErgSettings.get(this@SetupActivity).let { erg ->
                        if (erg.usableNow) {
                            addView(ergThisRideRows(erg))
                            addView(intensityRow())
                        }
                    }
                },
            )
        }

        // This week
        content.addView(sectionHeader(getString(R.string.this_week)))
        when {
            !store.hasCredentials -> {
                content.addView(text(getString(R.string.need_api_key), 14f, secondary = true))
                content.addView(button(getString(R.string.connect_intervals), primary = true) { openAccount() })
            }
            loading && week.isEmpty() -> content.addView(text(getString(R.string.setup_loading), 14f, secondary = true))
            error != null -> content.addView(text(error!!, 14f, secondary = true))
            offline && !wifiEnabled() -> content.addView(text(getString(R.string.offline_wifi_off), 14f, secondary = true))
            offline -> content.addView(text(getString(R.string.waiting_for_wifi), 14f, secondary = true))
        }
        if (offline && !wifiEnabled()) {
            content.addView(
                button(getString(R.string.turn_on_wifi), primary = true) { openWifi() }
                    .apply { (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(8) },
            )
        }
        if (store.hasCredentials && !loading && error == null && week.isEmpty()) {
            content.addView(text(getString(R.string.none_this_week), 14f, secondary = true))
        }
        val today = LocalDate.now()
        // Workouts already ridden are hidden unless asked for (the selected one always shows).
        val doneCount = week.count { it.done && it.plan.id != current?.id }
        val shown = if (store.hideDone) week.filter { !it.done || it.plan.id == current?.id } else week
        // Today first, then the days still to come, then the days already past.
        val days = shown.groupBy { it.date }.toSortedMap()
        val order = days.keys.sortedWith(compareBy({ if (it == today) 0 else if (it > today) 1 else 2 }, { it }))
        order.map { it to days.getValue(it) }.forEach { (date, workouts) ->
            val label = if (date == today) getString(R.string.today_label, date.format(DAY_FORMAT)) else date.format(DAY_FORMAT)
            content.addView(
                text(label, 14f, bold = date == today, secondary = date != today).apply {
                    setPadding(0, dp(10), 0, dp(4))
                },
            )
            workouts.forEach { w ->
                content.addView(
                    card(w.plan, w.date, selected = w.plan.id == current?.id) {
                        store.select(w)
                        goToRide()
                    },
                )
            }
        }
        // "Pokaż zrobione (3)" / "Ukryj zrobione"
        if (doneCount > 0) {
            content.addView(
                button(
                    if (store.hideDone) getString(R.string.show_done, doneCount) else getString(R.string.hide_done),
                    primary = false,
                ) {
                    store.hideDone = !store.hideDone
                    render()
                }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(12) },
            )
        }
    }

    /**
     * Back to the ride screen. Opened from the interval screen, closing this page is enough;
     * opened from the app list, open the Karoo ride app.
     */
    private fun goToRide() {
        if (!intent.getBooleanExtra(EXTRA_FROM_RIDE, false)) {
            packageManager.getLaunchIntentForPackage(RIDE_APP)?.let {
                startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        finish()
    }

    /** A workout card: name, a small profile of the workout, then time and date below it. */
    private fun card(plan: WorkoutPlan, date: LocalDate?, selected: Boolean, onClick: (() -> Unit)?) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_tile)
            setPadding(dp(10), dp(8), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(8) }
            addView(text(plan.name, 16f, bold = true).apply { setPadding(0, 0, 0, dp(6)) })
            addView(thumbnail(plan))
            val dateText = date?.let { if (it == LocalDate.now()) getString(R.string.today) else it.format(DAY_FORMAT) }
            val details = listOfNotNull(formatClock(plan.totalSec), dateText).joinToString(" · ")
            addView(
                text(
                    if (selected) "$details · ${getString(R.string.selected)}" else details,
                    13f,
                    secondary = !selected,
                ).apply {
                    setPadding(0, dp(6), 0, 0)
                    if (selected) setTextColor(getColor(R.color.watts_in_range))
                },
            )
            if (onClick != null) setOnClickListener { onClick() }
        }

    private fun thumbnail(plan: WorkoutPlan): ImageView {
        val w = resources.displayMetrics.widthPixels - dp(44)
        val h = dp(48)
        return ImageView(this).apply {
            setImageBitmap(ChartRenderer(this@SetupActivity).render(ScreenState(plan = plan), w, h, 1f, ChartRenderer.Style.CARD))
            scaleType = ImageView.ScaleType.FIT_XY
            contentDescription = getString(R.string.cd_chart)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, h)
        }
    }

    private fun sectionHeader(value: String) = text(value, 13f, bold = true, secondary = true).apply {
        isAllCaps = true
        setPadding(0, dp(16), 0, dp(6))
    }

    companion object {
        /** Set when opened from the gear button on the interval screen. */
        const val EXTRA_FROM_RIDE = "from_ride"
        private const val RIDE_APP = "io.hammerhead.rideapp"
        private const val WIFI_SETTLE_MS = 2_000L
        /** Day names in Polish, whatever the Karoo's language (this build's UI is Polish). */
        private val POLISH = Locale.forLanguageTag("pl")
        private val DAY_FORMAT = DateTimeFormatter.ofPattern("EEE d MMM", POLISH)
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm", POLISH)
        private val DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("EEE HH:mm", POLISH)
    }
}
