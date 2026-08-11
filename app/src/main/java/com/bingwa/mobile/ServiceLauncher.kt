package com.bingwa.mobile

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

object ServiceLauncher {
    private const val TAG = "ServiceLauncher"
    private const val FALLBACK_START_DELAY_MS = 5_000L
    private const val FAST_RETRY_DELAY_MS = 750L
    private const val BOOT_RECOVERY_DELAY_MS = 20_000L

    fun startBalanceChecker(context: Context): Boolean {
        return startForegroundServiceSafely(
            context = context,
            intent = Intent(context, BalanceChecker::class.java),
            label = "Background balance monitoring",
            fallbackDelayMs = if (BingwaMobileApp.wasInForegroundRecently()) FAST_RETRY_DELAY_MS else FALLBACK_START_DELAY_MS,
            preferExactFallback = BingwaMobileApp.wasInForegroundRecently()
        )
    }

    fun startAutomationService(context: Context, intent: Intent): Boolean {
        return startForegroundServiceSafely(
            context = context,
            intent = intent,
            label = "USSD automation",
            fallbackDelayMs = FAST_RETRY_DELAY_MS,
            preferExactFallback = true
        )
    }

    fun scheduleBalanceCheckerStart(context: Context, delayMs: Long = BOOT_RECOVERY_DELAY_MS): Boolean {
        return scheduleServiceStart(
            context = context,
            intent = Intent(context, BalanceChecker::class.java),
            label = "Background balance monitoring",
            delayMs = delayMs,
            preferExact = false
        )
    }

    fun scheduleRelayHotspotStart(context: Context, delayMs: Long = BOOT_RECOVERY_DELAY_MS): Boolean {
        return scheduleServiceStart(
            context = context,
            intent = Intent(context, HotspotRelayService::class.java),
            label = "Relay hotspot service",
            delayMs = delayMs,
            preferExact = false
        )
    }

    fun startForegroundServiceSafely(
        context: Context,
        intent: Intent,
        label: String,
        fallbackDelayMs: Long = FALLBACK_START_DELAY_MS,
        preferExactFallback: Boolean = false
    ): Boolean {
        return runCatching {
            ContextCompat.startForegroundService(context, intent)
            true
        }.getOrElse { error ->
            Log.e(TAG, "Unable to start $label", error)
            scheduleServiceStart(
                context = context,
                intent = intent,
                label = label,
                delayMs = fallbackDelayMs,
                preferExact = preferExactFallback
            )
            OfferNotifications.notify(
                context,
                "Phone blocked background start",
                "$label could not start in the background. Open Bingwa Mobile and remove battery restrictions on this phone."
            )
            false
        }
    }

    private fun scheduleServiceStart(
        context: Context,
        intent: Intent,
        label: String,
        delayMs: Long,
        preferExact: Boolean
    ): Boolean {
        return runCatching {
            val safeContext = context.applicationContext
            val component = intent.component ?: return@runCatching false
            val requestCode = resolveFallbackRequestCode(intent, label)
            val pi = PendingIntent.getService(
                safeContext,
                requestCode,
                Intent(intent).setComponent(component),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            AlarmCompat.scheduleRtcWakeup(
                context = safeContext,
                triggerAtMillis = System.currentTimeMillis() + delayMs,
                pendingIntent = pi,
                preferExact = preferExact,
                allowWhileIdle = true
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to schedule fallback start for $label", error)
        }.getOrDefault(false)
    }

    private fun resolveFallbackRequestCode(intent: Intent, label: String): Int {
        val txId = intent.getIntExtra("txId", -1)
        if (txId >= 0) return txId
        return (label.hashCode() and Int.MAX_VALUE).coerceAtLeast(1)
    }
}
