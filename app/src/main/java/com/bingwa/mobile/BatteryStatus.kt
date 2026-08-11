package com.bingwa.mobile

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

object BatteryStatus {
    data class Info(
        val percent: Int?,
        val isCharging: Boolean?
    )

    fun read(context: Context): Info {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val percent = try {
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
        } catch (_: Exception) {
            null
        }

        val charging = try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        } catch (_: Exception) {
            null
        }

        return Info(percent = percent, isCharging = charging)
    }

    fun formatForSms(info: Info): String {
        val pct = info.percent?.let { "$it%" } ?: "Unknown"
        val chg = when (info.isCharging) {
            true -> "Charging"
            false -> "Not charging"
            null -> "Unknown"
        }
        return "Battery level: $pct ($chg)."
    }
}

