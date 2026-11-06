package com.daviddokakis.karoointerval

import android.app.Activity
import android.content.Context
import android.content.res.Configuration

/** Dark / Light / Auto, like the Karoo's own In-Ride Appearance setting. */
enum class Appearance {
    DARK, LIGHT, AUTO;

    /** [base] with its night mode forced to this appearance (Auto: unchanged). */
    fun apply(base: Context): Context {
        val night = when (this) {
            DARK -> Configuration.UI_MODE_NIGHT_YES
            LIGHT -> Configuration.UI_MODE_NIGHT_NO
            AUTO -> return base
        }
        val config = Configuration(base.resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        return base.createConfigurationContext(config)
    }
}

/**
 * An app page that follows the Appearance setting: resources (colours in values/ and
 * values-night/) resolve in the chosen mode. Pages recreate themselves when it changes.
 */
abstract class AppearanceActivity : Activity() {
    private var shownWith: Appearance? = null

    override fun attachBaseContext(newBase: Context) {
        val appearance = PlanStore.get(newBase).appearance.value
        shownWith = appearance
        super.attachBaseContext(appearance.apply(newBase))
    }

    override fun onRestart() {
        super.onRestart()
        if (PlanStore.get(this).appearance.value != shownWith) recreate()
    }
}
