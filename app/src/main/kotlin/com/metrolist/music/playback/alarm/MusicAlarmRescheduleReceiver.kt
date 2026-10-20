package com.metrolist.music.playback.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// Vegas: no LOCKED_BOOT_COMPLETED; starting the app before the first unlock crashes App.onCreate,
// which needs credential-encrypted storage.
class MusicAlarmRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        MusicAlarmScheduler.scheduleFromPreferences(context)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}
