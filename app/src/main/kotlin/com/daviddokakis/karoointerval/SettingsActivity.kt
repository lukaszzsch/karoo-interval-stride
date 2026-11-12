package com.daviddokakis.karoointerval

import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.CheckBox
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

    /**
     * One switch per metric. Never set, the tile works as it always did (shown ticked as such);
     * the first change makes it the rider's own list, "Przywróć domyślne" goes back.
     */
    private fun tileSection(title: Int, help: Int, value: List<TileMetric>?, default: List<TileMetric>, set: (List<TileMetric>?) -> Unit) {
        form.addView(settingsHeading(R.drawable.ic_style, getString(title)))
        val on = value ?: default
        TileMetric.entries.forEach { m ->
            form.addView(
                CheckBox(this).apply {
                    text = getString(m.setting)
                    setTextColor(getColor(R.color.text))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    buttonTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.watts_in_range))
                    minHeight = dp(44)
                    isChecked = m in on
                    setOnCheckedChangeListener { _, checked ->
                        val now = on.toMutableSet().apply { if (checked) add(m) else remove(m) }
                        set(TileMetric.entries.filter { it in now })
                        form.post { render() }
                    }
                },
            )
        }
        if (value != null) {
            form.addView(
                button(getString(R.string.tiles_reset), primary = false) {
                    set(null)
                    render()
                }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(6) },
            )
        }
        form.addView(settingsHelp(getString(help)))
        form.addView(settingsDivider())
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

        // Cadence smoothing: Instant / 3 s / 5 s / 10 s
        form.addView(settingsHeading(R.drawable.ic_bolt_filled, getString(R.string.cadence_smoothing)))
        form.addView(
            segmented(Smoothing.entries.map { it.label to it }, store.cadenceSmoothing.value) { picked ->
                store.setCadenceSmoothing(picked)
                render()
            },
        )
        form.addView(settingsHelp(getString(R.string.cadence_smoothing_help)))
        form.addView(settingsDivider())

        // Tiles: Tonal / Outline
        form.addView(settingsHeading(R.drawable.ic_style, getString(R.string.tiles_style)))
        form.addView(
            segmented(listOf(R.string.tiles_tonal to false, R.string.tiles_outline to true), store.tileOutline.value) { on ->
                store.setTileOutline(on)
                render()
            },
        )
        form.addView(settingsHelp(getString(R.string.tiles_style_help)))
        form.addView(settingsDivider())

        // Left and right tile of the workout screen: which metrics each goes through (a tap shows the next)
        tileSection(R.string.left_tile, R.string.left_tile_help, store.leftTile.value, TileMetric.LEFT_DEFAULT) { store.setLeftTile(it) }
        tileSection(R.string.right_tile, R.string.right_tile_help, store.rightTile.value, TileMetric.RIGHT_DEFAULT) { store.setRightTile(it) }

        // Update notices: Off / On
        form.addView(settingsHeading(R.drawable.ic_interval, getString(R.string.update_notices)))
        form.addView(
            segmented(listOf(R.string.off to false, R.string.on to true), store.updateNotices) { on ->
                store.updateNotices = on
                render()
            },
        )
        form.addView(settingsHelp(getString(R.string.update_notices_help)))
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
