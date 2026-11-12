package com.daviddokakis.karoointerval

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Handles taps on the buttons drawn in the data field. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val session = IntervalExtension.session ?: return
        when (intent.action) {
            ACTION_NEXT -> session.requestJump(+1)
            ACTION_PREVIOUS -> session.requestJump(-1)
            ACTION_PAUSE -> session.togglePause()
            ACTION_TOGGLE_CHART -> session.toggleChartZoom()
            ACTION_JUMP_NOW -> session.jumpNow()
            ACTION_CANCEL_SELECTION -> session.cancelSelection()
            ACTION_CONFIRM_SELECTION -> session.confirmSelection()
            ACTION_CANCEL_MOVE -> session.cancelMove()
            ACTION_DISMISS_MOVE_CANCELLED -> session.dismissMoveCancelled()
            ACTION_TOGGLE_TARGET -> session.toggleTargetView()
            ACTION_TOGGLE_LAP_NP -> session.toggleLapNp()
            ACTION_TOGGLE_CLOCK -> session.toggleClock()
            ACTION_FREE_POWER -> session.toggleFreePower()
            ACTION_FREE_HR -> session.toggleFreeHr()
            ACTION_TOGGLE_FREE_CHART -> session.toggleFreeChart()
            ACTION_TOGGLE_LAP_HR -> session.toggleLapHr()
            ACTION_TOGGLE_WATTS_COLOR -> session.toggleWattsColor()
            ACTION_LEFT_TILE -> session.cycleTile(left = true)
            ACTION_RIGHT_TILE -> session.cycleTile(left = false)
            ACTION_TOGGLE_INFO -> session.toggleInfo()
        }
    }

    companion object {
        const val ACTION_NEXT = "com.daviddokakis.karoointerval.NEXT"
        const val ACTION_PREVIOUS = "com.daviddokakis.karoointerval.PREVIOUS"
        const val ACTION_PAUSE = "com.daviddokakis.karoointerval.PAUSE"
        const val ACTION_TOGGLE_CHART = "com.daviddokakis.karoointerval.TOGGLE_CHART"
        const val ACTION_JUMP_NOW = "com.daviddokakis.karoointerval.JUMP_NOW"
        const val ACTION_CANCEL_SELECTION = "com.daviddokakis.karoointerval.CANCEL_SELECTION"
        const val ACTION_CONFIRM_SELECTION = "com.daviddokakis.karoointerval.CONFIRM_SELECTION"
        const val ACTION_CANCEL_MOVE = "com.daviddokakis.karoointerval.CANCEL_MOVE"
        const val ACTION_DISMISS_MOVE_CANCELLED = "com.daviddokakis.karoointerval.DISMISS_MOVE_CANCELLED"
        const val ACTION_TOGGLE_TARGET = "com.daviddokakis.karoointerval.TOGGLE_TARGET"
        const val ACTION_TOGGLE_LAP_NP = "com.daviddokakis.karoointerval.TOGGLE_LAP_NP"
        const val ACTION_TOGGLE_CLOCK = "com.daviddokakis.karoointerval.TOGGLE_CLOCK"
        const val ACTION_FREE_POWER = "com.daviddokakis.karoointerval.FREE_POWER"
        const val ACTION_FREE_HR = "com.daviddokakis.karoointerval.FREE_HR"
        const val ACTION_TOGGLE_FREE_CHART = "com.daviddokakis.karoointerval.TOGGLE_FREE_CHART"
        const val ACTION_TOGGLE_LAP_HR = "com.daviddokakis.karoointerval.TOGGLE_LAP_HR"
        const val ACTION_TOGGLE_WATTS_COLOR = "com.daviddokakis.karoointerval.TOGGLE_WATTS_COLOR"
        const val ACTION_LEFT_TILE = "com.daviddokakis.karoointerval.LEFT_TILE"
        const val ACTION_RIGHT_TILE = "com.daviddokakis.karoointerval.RIGHT_TILE"
        const val ACTION_TOGGLE_INFO = "com.daviddokakis.karoointerval.TOGGLE_INFO"
    }
}
