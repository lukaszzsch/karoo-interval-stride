package com.daviddokakis.karoointerval

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Trainer page: ERG on / off, and finding the trainer over Bluetooth (FTMS). The Karoo keeps
 * reading the trainer over ANT+ in Read-Only mode; this app sets the target power.
 */
@SuppressLint("MissingPermission") // asked for before scanning
class TrainerActivity : AppearanceActivity() {

    private lateinit var erg: ErgSettings
    private lateinit var form: LinearLayout
    private val found = linkedMapOf<String, String>() // address → name
    private var scanning = false
    private var scanError: String? = null
    private val main = Handler(Looper.getMainLooper())

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address
            val name = runCatching { result.device.name }.getOrNull() ?: result.scanRecord?.deviceName ?: address
            if (found.put(address, name) == null) main.post { render() }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            scanError = getString(R.string.erg_scan_failed, errorCode)
            main.post { render() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        erg = ErgSettings.get(this)
        form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(12))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg))
        }
        root.addView(
            iconButton(R.drawable.ic_back, getString(R.string.cd_back)) { finish() }.apply {
                (layoutParams as LinearLayout.LayoutParams).setMargins(dp(4), dp(8), 0, 0)
            },
        )
        root.addView(text(getString(R.string.erg_title), 22f, bold = true).apply { setPadding(dp(12), 0, dp(12), dp(4)) })
        root.addView(ScrollView(this).apply { addView(form, MATCH_PARENT, WRAP_CONTENT) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
        render()
    }

    private fun render() {
        form.removeAllViews()
        form.addView(settingsHeading(R.drawable.ic_bolt_filled, getString(R.string.erg_control)))
        form.addView(
            segmented(listOf(R.string.off to false, R.string.on to true), erg.enabled.value) { on ->
                erg.setEnabled(on)
                if (on && !ErgSettings.hasPermissions(this)) requestPermissions(ErgSettings.PERMISSIONS, REQUEST_PERMISSIONS)
                render()
            },
        )
        form.addView(settingsHelp(getString(R.string.erg_help)))
        form.addView(settingsDivider())

        // Where ERG applies: every indoor profile, or only the profiles picked here.
        form.addView(settingsHeading(R.drawable.ic_bolt_filled, getString(R.string.erg_profiles)))
        form.addView(
            segmented(listOf(R.string.erg_profiles_indoor to false, R.string.erg_profiles_chosen to true), erg.onlyChosen.value) { only ->
                erg.setOnlyChosen(only)
                render()
            },
        )
        val profiles = erg.profiles.value
        if (erg.onlyChosen.value) {
            if (profiles.isEmpty()) form.addView(settingsHelp(getString(R.string.erg_profiles_none)))
            for (p in profiles.sortedBy { it.name.lowercase() }) {
                val on = p.id in erg.chosen.value
                form.addView(
                    button((if (on) "✓ " else "") + p.name + if (p.indoor) " (indoor)" else "", primary = on) {
                        erg.toggleChosen(p.id)
                        render()
                    }.apply {
                        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) }
                    },
                )
            }
            form.addView(settingsHelp(getString(R.string.erg_profiles_chosen_help)))
        } else {
            form.addView(settingsHelp(getString(R.string.erg_profiles_indoor_help)))
        }
        profiles.firstOrNull { it.id == erg.activeProfileId.value }?.let { active ->
            form.addView(
                settingsHelp(
                    getString(
                        if (erg.allowedIn(active)) R.string.erg_profile_active_on else R.string.erg_profile_active_off,
                        active.name,
                    ),
                ),
            )
        }
        form.addView(settingsDivider())

        form.addView(settingsRowTitle(getString(R.string.erg_trainer)).apply { setPadding(0, dp(12), 0, 0) })
        form.addView(settingsHelp(erg.trainer.value?.let { "${it.second} (${it.first})" } ?: getString(R.string.erg_no_trainer)))

        form.addView(
            button(getString(if (scanning) R.string.erg_scanning else R.string.erg_scan), primary = erg.trainer.value == null) {
                if (!scanning) startScan()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }
            },
        )
        scanError?.let { form.addView(settingsHelp(it)) }
        for ((address, name) in found) {
            val chosen = erg.trainer.value?.first == address
            form.addView(
                button(if (chosen) "✓ $name" else name, primary = chosen) {
                    erg.setTrainer(address, name)
                    stopScan()
                    render()
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) }
                },
            )
        }
        if (scanning && found.isEmpty()) form.addView(settingsHelp(getString(R.string.erg_scan_hint)))
    }

    private fun startScan() {
        if (!ErgSettings.hasPermissions(this)) {
            requestPermissions(ErgSettings.PERMISSIONS, REQUEST_PERMISSIONS_SCAN)
            return
        }
        val scanner = getSystemService(BluetoothManager::class.java).adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            scanError = getString(R.string.erg_bluetooth_off)
            render()
            return
        }
        scanError = null
        found.clear()
        scanning = true
        // Only machines that offer FTMS (trainers).
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(FtmsTrainer.FTMS_SERVICE)).build()
        scanner.startScan(listOf(filter), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
        main.postDelayed({ stopScan(); render() }, SCAN_MS)
        render()
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { getSystemService(BluetoothManager::class.java).adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!ErgSettings.hasPermissions(this)) {
            scanError = getString(R.string.erg_no_permission)
            render()
        } else if (requestCode == REQUEST_PERMISSIONS_SCAN) {
            startScan()
        } else {
            render()
        }
    }

    override fun onDestroy() {
        stopScan()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 41
        private const val REQUEST_PERMISSIONS_SCAN = 42
        private const val SCAN_MS = 15_000L
    }
}
