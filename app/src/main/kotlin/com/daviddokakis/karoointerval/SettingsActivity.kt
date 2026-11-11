package com.daviddokakis.karoointerval

import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Settings: Appearance (Dark / Light / Auto) and Karoo style first, both as Karoo's own segmented
 * control (as in Karoo Settings → Display), then the Stride account as one row that opens
 * the Account page.
 */
class SettingsActivity : AppearanceActivity() {

    private lateinit var store: PlanStore
    private lateinit var form: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = PlanStore.get(this)
        form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(24))
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
        root.addView(text(getString(R.string.settings_title), 22f, bold = true).apply { setPadding(dp(12), 0, dp(12), dp(4)) })
        root.addView(ScrollView(this).apply { addView(form, MATCH_PARENT, WRAP_CONTENT) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
        render()
    }

    override fun onResume() {
        super.onResume()
        render() // the account row shows whether a key is set
    }

    private fun render() {
        form.removeAllViews()

        // Appearance: Dark / Light / Auto. The page itself switches right away.
        form.addView(settingsHeading(R.drawable.ic_appearance, getString(R.string.appearance)))
        val current = store.appearance.value
        form.addView(
            segmented(
                listOf(R.string.appearance_dark to Appearance.DARK, R.string.appearance_light to Appearance.LIGHT, R.string.appearance_auto to Appearance.AUTO),
                current,
            ) { picked ->
                if (picked != store.appearance.value) {
                    store.setAppearance(picked)
                    recreate()
                }
            },
        )
        form.addView(settingsHelp(getString(R.string.appearance_help)))
        form.addView(settingsDivider())

        // Power smoothing: Instant / 3 s / 5 s / 10 s
        form.addView(settingsHeading(R.drawable.ic_bolt_filled, getString(R.string.power_smoothing)))
        form.addView(
            segmented(Smoothing.entries.map { it.label to it }, store.powerSmoothing.value) { picked ->
                store.setPowerSmoothing(picked)
                render()
            },
        )
        form.addView(settingsHelp(getString(R.string.power_smoothing_help)))
        form.addView(settingsDivider())

        // Top row: Auto / Heart rate / Cadence / Target
        form.addView(settingsHeading(R.drawable.ic_heart, getString(R.string.top_row)))
        form.addView(
            segmented(TopRow.entries.map { it.label to it }, store.topRow.value) { picked ->
                store.setTopRow(picked)
                render()
            },
        )
        form.addView(settingsHelp(getString(R.string.top_row_help)))
        form.addView(settingsDivider())

        // Karoo style: Off / On
        form.addView(settingsHeading(R.drawable.ic_style, getString(R.string.karoo_style)))
        form.addView(
            segmented(listOf(R.string.off to false, R.string.on to true), store.karooStyle.value) { on ->
                store.setKarooStyle(on)
                render()
            },
        )
        form.addView(settingsHelp(getString(R.string.karoo_style_help)))
        form.addView(settingsDivider())

        // Stride: one row, opens the Account page
        val status = getString(if (store.hasCredentials) R.string.connected else R.string.not_connected)
        form.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                isClickable = true
                setOnClickListener { startActivity(Intent(this@SettingsActivity, AccountActivity::class.java)) }
                addView(settingsIcon(R.drawable.ic_person))
                addView(
                    LinearLayout(this@SettingsActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(settingsRowTitle(getString(R.string.intervals_account)))
                        addView(settingsHelp(status).apply { setPadding(0, 0, 0, 0) })
                    },
                    LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
                )
                addView(settingsIcon(R.drawable.ic_chevron))
            },
        )
        form.addView(settingsDivider())

        // Smart trainer (ERG over Bluetooth): one row, opens the Trainer page
        val erg = ErgSettings.get(this)
        val ergStatus = when {
            !erg.enabled.value -> getString(R.string.off)
            erg.trainer.value == null -> getString(R.string.erg_no_trainer)
            else -> erg.trainer.value!!.second
        }
        form.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                isClickable = true
                setOnClickListener { startActivity(Intent(this@SettingsActivity, TrainerActivity::class.java)) }
                addView(settingsIcon(R.drawable.ic_bolt_filled))
                addView(
                    LinearLayout(this@SettingsActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(settingsRowTitle(getString(R.string.erg_title)))
                        addView(settingsHelp(ergStatus).apply { setPadding(0, 0, 0, 0) })
                    },
                    LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
                )
                addView(settingsIcon(R.drawable.ic_chevron))
            },
        )
        form.addView(settingsDivider())

        // The first-run introduction, again (e.g. to see how to add the field to a profile)
        form.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                isClickable = true
                setOnClickListener { startActivity(Intent(this@SettingsActivity, IntroActivity::class.java).putExtra(IntroActivity.EXTRA_AGAIN, true)) }
                addView(settingsIcon(R.drawable.ic_info))
                addView(settingsRowTitle(getString(R.string.intro_again)), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(settingsIcon(R.drawable.ic_chevron))
            },
        )
    }
}
