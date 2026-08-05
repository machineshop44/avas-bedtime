package com.avas.bedtime.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.avas.bedtime.session.BedtimeService

/**
 * Debug-only entry point so adb can stress START/STOP/RESTART without exporting
 * [BedtimeService]. Not packaged into release builds.
 */
class StressReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val mapped = when (action) {
            ACTION_START -> BedtimeService.ACTION_START
            ACTION_STOP -> BedtimeService.ACTION_STOP
            ACTION_RESTART -> BedtimeService.ACTION_RESTART
            else -> {
                Log.w(TAG, "Ignoring $action")
                return
            }
        }
        val service = Intent(context, BedtimeService::class.java).setAction(mapped)
        Log.i(TAG, "Forwarding $mapped")
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(service)
        } else {
            context.startService(service)
        }
    }

    companion object {
        private const val TAG = "StressReceiver"
        const val ACTION_START = "com.avas.bedtime.DEBUG_START"
        const val ACTION_STOP = "com.avas.bedtime.DEBUG_STOP"
        const val ACTION_RESTART = "com.avas.bedtime.DEBUG_RESTART"
    }
}
