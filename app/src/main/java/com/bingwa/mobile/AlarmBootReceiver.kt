package com.bingwa.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class AlarmBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (
            Intent.ACTION_BOOT_COMPLETED == intent.action ||
            Intent.ACTION_MY_PACKAGE_REPLACED == intent.action
        ) {
            val bootForegroundRestartRestricted =
                Build.VERSION.SDK_INT >= 35 && context.applicationInfo.targetSdkVersion >= 35
            val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            if (
                prefs.safeGetBoolean("automation_enabled", true)
            ) {
                val started = if (bootForegroundRestartRestricted) {
                    ServiceLauncher.scheduleBalanceCheckerStart(context)
                } else {
                    ServiceLauncher.startBalanceChecker(context)
                }
                if (bootForegroundRestartRestricted && !started) {
                    OfferNotifications.notify(
                        context,
                        "Open Bingwa to resume automation",
                        "Android delayed the automation restart after a reboot or app update on this phone. Open Bingwa Mobile once to resume automation."
                    )
                }
            }
            if (prefs.safeGetBoolean("auto_retry", false)) {
                UnderMaintenanceRetryReceiver.schedule(context)
            }
            ScheduledOfferDispatchStore.rescheduleAll(context)
            val cfg = RelayManager.load(context)
            if (cfg.enabled && cfg.role == "RELAY" && cfg.method == "HOTSPOT") {
                if (bootForegroundRestartRestricted) {
                    ServiceLauncher.scheduleRelayHotspotStart(context)
                } else {
                    RelayManager.startRelayHotspotService(context)
                }
            }
        }
    }
}
