package com.daviddokakis.karoointerval

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Debug builds only. Fakes a remote button press, the same as the Karoo's bonus actions:
 * `adb shell am broadcast -a com.daviddokakis.karoointerval.DEBUG_REMOTE --es id skip`
 * (skip / previous / pause).
 */
class DebugRemoteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val session = IntervalExtension.session ?: return
        when (intent.getStringExtra("id")) {
            "skip" -> session.requestJump(+1, fromRemote = true)
            "previous" -> session.requestJump(-1, fromRemote = true)
            "pause" -> session.togglePause()
        }
    }
}
