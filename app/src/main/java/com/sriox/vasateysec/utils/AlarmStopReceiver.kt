package com.sriox.vasateysec.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == AlarmSoundPlayer.ACTION_STOP_ALARM) {
            AlarmSoundPlayer.stopAlarm(context)
        }
    }
}
