package com.daviddokakis.karoointerval

import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * "How was the ride?": RPE 1–10 and feel 1–5. Opened from the notification at the end of the
 * workout (or on a manual pause), and from the Workouts page. The rating goes into the FIT file
 * while the ride is open, and to the Stride activity once the ride is uploaded.
 */
class RateActivity : AppearanceActivity() {

    private lateinit var ratings: RatingStore
    private lateinit var content: LinearLayout
    private var rpe: Int? = null
    private var feel: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ratings = RatingStore.get(this)
        ratings.rating.value.let {
            rpe = it.rpe
            feel = it.feel
        }
        content = LinearLayout(this).apply {
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
        root.addView(
            ScrollView(this).apply { addView(content, MATCH_PARENT, WRAP_CONTENT) },
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f),
        )
        root.addView(
            button(getString(R.string.rate_done), primary = true) { save(); finish() }.apply {
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    setMargins(dp(12), dp(8), dp(12), dp(12))
                }
            },
        )
        setContentView(root)
        render()
    }

    private fun save() {
        ratings.setRating(RideRating(rpe, feel))
    }

    private fun render() {
        content.removeAllViews()
        content.addView(text(getString(R.string.rate_title), 22f, bold = true).apply { setPadding(0, 0, 0, dp(2)) })
        content.addView(text(getString(R.string.rate_hint), 13f, secondary = true))

        // RPE: two rows of five, the chosen one highlighted, its description under them.
        content.addView(text(getString(R.string.rate_rpe), 15f, bold = true).apply { setPadding(0, dp(12), 0, dp(4)) })
        for (rowStart in listOf(1, 6)) {
            content.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    for (n in rowStart until rowStart + 5) {
                        addView(
                            button("$n", primary = rpe == n) {
                                rpe = n
                                save()
                                render()
                            },
                            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) },
                        )
                    }
                },
            )
        }
        content.addView(
            text(rpe?.let { RideRating.rpeLabel(this, it) } ?: " ", 14f, secondary = true).apply { setPadding(0, dp(4), 0, 0) },
        )

        // Feel: great at the top, terrible at the bottom.
        content.addView(text(getString(R.string.rate_feel), 15f, bold = true).apply { setPadding(0, dp(12), 0, dp(4)) })
        for (n in 5 downTo 1) {
            content.addView(
                button(RideRating.feelLabel(this, n), primary = feel == n) {
                    feel = n
                    save()
                    render()
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(0, dp(2), 0, dp(2)) }
                },
            )
        }

        ratings.lastStatus?.let { status ->
            content.addView(
                TextView(this).apply {
                    text = getString(R.string.rate_last_sync, status)
                    textSize = 12f
                    setTextColor(getColor(R.color.text_secondary))
                    setPadding(0, dp(12), 0, 0)
                },
            )
        }
    }

    override fun onPause() {
        save()
        super.onPause()
    }

    companion object {
        /** Intent action of the "Rate" notification (see the manifest). */
        const val ACTION_RATE = "com.daviddokakis.karoointerval.stride.RATE_RIDE"
    }
}
