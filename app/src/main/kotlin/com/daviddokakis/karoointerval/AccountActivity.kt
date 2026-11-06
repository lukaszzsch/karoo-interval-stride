package com.daviddokakis.karoointerval

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView

/** Stride account: API token and optional FTP. Back (top left) discards, Save (bottom) keeps. */
class AccountActivity : AppearanceActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = PlanStore.get(this)

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(12))
        }
        form.addView(text(getString(R.string.setup_help), 14f, secondary = true))
        form.addView(text(getString(R.string.setup_api_key), 13f, secondary = true).apply { setPadding(0, dp(12), 0, 0) })
        val key = edit(store.apiKey, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        form.addView(key)
        form.addView(text(getString(R.string.setup_ftp), 13f, secondary = true).apply { setPadding(0, dp(12), 0, 0) })
        val ftp = edit(store.ftp, InputType.TYPE_CLASS_NUMBER).apply { hint = getString(R.string.setup_ftp_hint) }
        form.addView(ftp)
        form.addView(text(getString(R.string.setup_ftp_help), 13f, secondary = true))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg))
        }
        // Back button, with the title below it
        root.addView(
            iconButton(R.drawable.ic_back, getString(R.string.cd_back)) { finish() }.apply {
                (layoutParams as LinearLayout.LayoutParams).setMargins(dp(4), dp(8), 0, 0)
            },
        )
        root.addView(
            text(getString(R.string.account_title), 22f, bold = true).apply { setPadding(dp(12), 0, dp(12), dp(4)) },
        )
        root.addView(
            ScrollView(this).apply { addView(form, MATCH_PARENT, WRAP_CONTENT) },
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f),
        )
        root.addView(
            button(getString(R.string.save), primary = true) {
                store.ftp = ftp.text.toString()
                store.apiKey = key.text.toString()
                setResult(RESULT_OK)
                finish()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    setMargins(dp(12), dp(8), dp(12), dp(12))
                }
            },
        )
        setContentView(root)
    }
}
