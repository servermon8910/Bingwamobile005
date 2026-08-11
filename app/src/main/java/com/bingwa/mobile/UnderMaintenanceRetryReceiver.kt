package com.bingwa.mobile

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class UnderMaintenanceRetryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                .safeGetBoolean("automation_enabled", true)) {
            ServiceLauncher.startBalanceChecker(context)
        }
        val cfg = RelayManager.load(context)
        if (cfg.enabled && cfg.role == "RELAY" && cfg.method == "HOTSPOT") {
            RelayManager.startRelayHotspotService(context)
        }
    }

    companion object {
        fun schedule(context: Context) {
            val pi = PendingIntent.getBroadcast(context, 0,
                Intent(context, UnderMaintenanceRetryReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val interval = when {
                Build.VERSION.SDK_INT >= 35 -> 15 * 60 * 1000L
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> 5 * 60 * 1000L
                else -> 2 * 60 * 1000L
            }
            AlarmCompat.scheduleRtcWakeup(
                context = context,
                triggerAtMillis = System.currentTimeMillis() + interval,
                pendingIntent = pi,
                preferExact = Build.VERSION.SDK_INT < 35,
                allowWhileIdle = true
            )
        }

        fun cancel(context: Context) {
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pi = PendingIntent.getBroadcast(context, 0,
                Intent(context, UnderMaintenanceRetryReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            alarm.cancel(pi)
        }
    }
}
