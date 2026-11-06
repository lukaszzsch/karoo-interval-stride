package com.daviddokakis.karoointerval

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ImageSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * First-run introduction, shown once instead of going straight to the Account page:
 * 1 connect Stride, 2 Appearance and Karoo style, 3 how to add the field to a ride profile,
 * 4 tapping the watts (the app's key trick). Skip on pages 1–3. Settings can show it again.
 */
class IntroActivity : AppearanceActivity() {

    private lateinit var store: PlanStore
    private lateinit var dots: LinearLayout
    private lateinit var skip: TextView
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var back: TextView
    private lateinit var next: TextView
    private var page = 0

    // Page 1's fields, saved on Next (or Skip).
    private var keyField: EditText? = null
    private var ftpField: EditText? = null

    private val again get() = intent.getBooleanExtra(EXTRA_AGAIN, false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = PlanStore.get(this)
        page = savedInstanceState?.getInt(STATE_PAGE) ?: 0

        dots = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        skip = TextView(this).apply {
            text = getString(R.string.intro_skip)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(R.color.text_secondary))
            gravity = Gravity.CENTER
            minWidth = dp(48)
            setPadding(dp(8), 0, 0, 0)
            isClickable = true
            setOnClickListener { saveAccount(); done() }
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            addView(dots, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(skip, LinearLayout.LayoutParams(WRAP_CONTENT, dp(44)))
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(12))
        }
        scroll = ScrollView(this).apply { addView(content, MATCH_PARENT, WRAP_CONTENT) }
        back = button(getString(R.string.cd_back), primary = false) { go(page - 1) }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(72), WRAP_CONTENT).apply { marginEnd = dp(6) }
        }
        next = button("", primary = true) { onNext() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
            addView(back)
            addView(next)
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(getColor(R.color.bg))
                addView(top)
                addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
                addView(bottom)
            },
        )
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_PAGE, page)
    }

    @Deprecated("Karoo's hardware back button")
    override fun onBackPressed() {
        if (page > 0) go(page - 1) else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun onNext() {
        if (page == 0) saveAccount()
        if (page < PAGES - 1) go(page + 1) else done()
    }

    private fun go(to: Int) {
        page = to.coerceIn(0, PAGES - 1)
        render()
        scroll.scrollTo(0, 0)
    }

    private fun saveAccount() {
        val key = keyField?.text?.toString() ?: return
        if (key.isNotBlank()) {
            store.apiKey = key
            store.ftp = ftpField?.text?.toString().orEmpty()
        }
    }

    /** Finished or skipped: never shown again on its own. The Workouts page underneath syncs. */
    private fun done() {
        store.introDone = true
        finish()
    }

    private fun render() {
        renderDots()
        skip.visibility = if (page < PAGES - 1) View.VISIBLE else View.INVISIBLE
        back.visibility = if (page > 0) View.VISIBLE else View.GONE
        next.text = getString(
            when {
                page < PAGES - 1 -> R.string.intro_next
                store.hasCredentials && !again -> R.string.intro_pick_workout
                else -> R.string.intro_done
            },
        )
        content.removeAllViews()
        keyField = null
        ftpField = null
        when (page) {
            0 -> welcome()
            1 -> look()
            2 -> addToRide()
            else -> watts()
        }
    }

    private fun renderDots() {
        dots.removeAllViews()
        dots.contentDescription = getString(R.string.intro_step, page + 1, PAGES)
        repeat(PAGES) { i ->
            dots.addView(
                View(this).apply {
                    background = GradientDrawable().apply {
                        cornerRadius = dp(3).toFloat()
                        setColor(getColor(if (i == page) R.color.text else R.color.track))
                    }
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(dp(if (i == page) 16 else 6), dp(6)).apply { marginEnd = dp(5) },
            )
        }
    }

    private fun title(res: Int) {
        content.addView(text(getString(res), 22f, bold = true).apply { setPadding(0, 0, 0, dp(6)) })
    }

    private fun body(res: Int, secondary: Boolean = true) =
        text(getString(res), 14f, secondary = secondary).also { content.addView(it) }

    private fun label(res: Int) {
        content.addView(text(getString(res), 13f, secondary = true).apply { setPadding(0, dp(10), 0, 0) })
    }

    // 1 Welcome + connect Stride (API token first; FTP is optional)
    private fun welcome() {
        title(R.string.intro_welcome_title)
        body(R.string.intro_welcome)
        label(R.string.setup_api_key)
        keyField = edit(store.apiKey, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).apply {
            hint = getString(R.string.intro_key_hint)
            typeface = Typeface.DEFAULT // a password field defaults to monospace
            compact()
        }.also { content.addView(it) }
        content.addView(settingsHelp(getString(R.string.intro_key_help)).apply { setPadding(0, dp(2), 0, 0) })
        label(R.string.setup_ftp)
        ftpField = edit(store.ftp, InputType.TYPE_CLASS_NUMBER).apply {
            hint = getString(R.string.setup_ftp_hint)
            compact()
        }.also { content.addView(it) }
        content.addView(settingsHelp(getString(R.string.setup_ftp_help)).apply { setPadding(0, dp(2), 0, 0) })
    }

    private fun EditText.compact() {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setPadding(paddingLeft, dp(6), paddingRight, dp(8))
    }

    // 2 Appearance and Karoo style, as on the Settings page. Taps apply at once.
    private fun look() {
        title(R.string.intro_look_title)
        content.addView(settingsHeading(R.drawable.ic_appearance, getString(R.string.appearance)).apply { setPadding(0, dp(2), 0, dp(8)) })
        content.addView(
            segmented(
                listOf(R.string.appearance_dark to Appearance.DARK, R.string.appearance_light to Appearance.LIGHT, R.string.appearance_auto to Appearance.AUTO),
                store.appearance.value,
            ) { picked ->
                if (picked != store.appearance.value) {
                    store.setAppearance(picked)
                    recreate() // the page itself follows; the page number survives
                }
            },
        )
        content.addView(settingsHelp(getString(R.string.appearance_help)).apply { setPadding(0, dp(6), 0, dp(6)) })
        content.addView(settingsDivider())
        content.addView(settingsHeading(R.drawable.ic_style, getString(R.string.karoo_style)).apply { setPadding(0, dp(6), 0, dp(8)) })
        content.addView(
            segmented(listOf(R.string.off to false, R.string.on to true), store.karooStyle.value) { on ->
                store.setKarooStyle(on)
                render()
            },
        )
        content.addView(settingsHelp(getString(R.string.intro_karoo_style_help)).apply { setPadding(0, dp(8), 0, 0) })
    }

    // 3 How to add the field to a ride profile, with the Karoo's own screens
    private fun addToRide() {
        title(R.string.intro_ride_title)
        body(R.string.intro_ride_1, secondary = false)
        body(R.string.intro_ride_2, secondary = false).apply { setPadding(0, dp(8), 0, dp(4)) }
        content.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(10), 0, 0)
                addView(
                    screen(R.drawable.intro_data_pages, "1", R.string.cd_intro_data_pages, RectF(389f, 685f, 485f, 781f), oval = true),
                    LinearLayout.LayoutParams(dp(SHOT_DP + 8), WRAP_CONTENT).apply { marginEnd = dp(14) },
                )
                addView(
                    screen(R.drawable.intro_select_data, "2", R.string.cd_intro_select_data, RectF(14f, 539f, 466f, 653f), oval = false),
                    LinearLayout.LayoutParams(dp(SHOT_DP + 8), WRAP_CONTENT),
                )
            },
        )
    }

    /** A Karoo screenshot with the tap ringed and a step number on its corner. */
    private fun screen(res: Int, number: String, description: Int, ring: RectF, oval: Boolean) = FrameLayout(this).apply {
        setPadding(dp(8), dp(8), 0, 0)
        clipToPadding = false
        addView(
            RingImageView(this@IntroActivity, ring, oval).apply {
                setImageResource(res)
                adjustViewBounds = true
                contentDescription = getString(description)
                roundedWithBorder()
            },
            FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
        )
        addView(
            TextView(this@IntroActivity).apply {
                text = number
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(getColor(R.color.bg))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(getColor(R.color.text))
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            FrameLayout.LayoutParams(dp(20), dp(20)).apply { leftMargin = -dp(8); topMargin = -dp(8) },
        )
    }

    // 4 Tap the watts: all five versions, in the order a tap moves through them
    private fun watts() {
        title(R.string.intro_watts_title)
        body(R.string.intro_watts)
        val items = listOf(
            R.drawable.intro_watts_plain to R.string.intro_watts_number,
            R.drawable.intro_watts_text to R.string.intro_watts_colour,
            R.drawable.intro_watts_background to R.string.intro_watts_background,
            R.drawable.intro_watts_both to R.string.intro_watts_both,
            R.drawable.intro_watts_gauge to R.string.intro_watts_gauge,
        )
        listOf(items.take(3), items.drop(3)).forEachIndexed { row, list ->
            content.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(0, dp(if (row == 0) 6 else 4), 0, 0)
                    list.forEachIndexed { i, (image, label) ->
                        addView(
                            wattsVersion(image, "${row * 3 + i + 1} ${getString(label)}"),
                            LinearLayout.LayoutParams(dp(74), WRAP_CONTENT).apply { if (i > 0) marginStart = dp(5) },
                        )
                    }
                },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
            )
        }
        // "Change the rest any time in ⚙ Settings.", the gear inline
        val gear = getDrawable(R.drawable.ic_settings)!!.mutate().apply {
            setTint(getColor(R.color.settings_help))
            setBounds(0, 0, dp(16), dp(16))
        }
        val line = SpannableString("${getString(R.string.intro_change_later)} \u2699 ${getString(R.string.intro_settings)}")
        val at = line.indexOf('\u2699')
        line.setSpan(ImageSpan(gear, ImageSpan.ALIGN_BASELINE), at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        content.addView(settingsHelp("").apply { text = line; setPadding(0, dp(12), 0, 0) })
    }

    private fun wattsVersion(image: Int, label: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(
            ImageView(this@IntroActivity).apply {
                setImageResource(image)
                adjustViewBounds = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                roundedWithBorder()
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
        )
        addView(
            TextView(this@IntroActivity).apply {
                text = label
                gravity = Gravity.CENTER
                maxLines = 1
                setAutoSizeTextTypeUniformWithConfiguration(9, 12, 1, TypedValue.COMPLEX_UNIT_SP)
                setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
                setTextColor(getColor(R.color.text))
                setPadding(0, dp(2), 0, 0)
            },
            LinearLayout.LayoutParams(MATCH_PARENT, dp(20)),
        )
    }

    /** Rounded corners and a thin border, so a screenshot reads as a picture on the page. */
    private fun ImageView.roundedWithBorder() {
        val r = dp(5).toFloat()
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, r)
        }
        clipToOutline = true
        foreground = GradientDrawable().apply {
            cornerRadius = r
            setStroke(dp(1), getColor(R.color.settings_divider))
        }
    }

    /** An ImageView of a 480×800 Karoo screenshot with [ring] (screenshot px) outlined on top. */
    private class RingImageView(context: Context, private val ring: RectF, private val oval: Boolean) : ImageView(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * context.resources.displayMetrics.density
            color = context.getColor(R.color.land)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val k = width / SHOT_W
            val r = RectF(ring.left * k, ring.top * k, ring.right * k, ring.bottom * k)
            if (oval) canvas.drawOval(r, paint) else canvas.drawRoundRect(r, 14 * k, 14 * k, paint)
        }
    }

    companion object {
        /** Opened from Settings: the last button just closes it. */
        const val EXTRA_AGAIN = "again"
        private const val STATE_PAGE = "page"
        private const val PAGES = 4
        private const val SHOT_W = 480f
        /** Width of a Karoo screenshot on page 3. */
        private const val SHOT_DP = 84
    }
}
