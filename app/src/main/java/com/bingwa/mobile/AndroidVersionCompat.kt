package com.bingwa.mobile

import android.content.Context
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Compatibility layer for different Android versions.
 * Ensures app functions reliably on Android 5.0+ (API 21+)
 */
object AndroidVersionCompat {
    const val TAG = "AndroidVersionCompat"

    /**
     * Check if device supports silent USSD execution.
     * Works on API 21+
     */
    fun isSilentUssdSupported(context: Context): Boolean {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                ?: return false

            // Android 8+ has official API
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                return true
            }

            // Android 6-7 check for reflection capability
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                return hasReflectionMethod(tm)
            }

            // Android 5 - try reflection
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                return hasReflectionMethod(tm)
            }

            false
        } catch (e: Exception) {
            Log.w(TAG, "Error checking USSD support", e)
            false
        }
    }

    /**
     * Check if method exists via reflection (for older Android versions)
     */
    private fun hasReflectionMethod(tm: TelephonyManager): Boolean {
        return try {
            val cb = try {
                Class.forName("android.telephony.TelephonyManager\$UssdResponseCallback")
            } catch (_: Exception) {
                Class.forName("android.telephony.UssdResponseCallback")
            }
            tm.javaClass.getMethod("sendUssdRequest", String::class.java, cb, android.os.Handler::class.java)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Get safe TelephonyManager subscription ID.
     * Handles API version differences.
     */
    fun getSafeSubscriptionId(tm: TelephonyManager, subId: Int): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            subId
        } else {
            // Pre-N: keep the provided ID because newer static helpers are unavailable.
            subId
        }
    }

    /**
     * Create TelephonyManager for subscription ID safely across Android versions.
     */
    fun createTelephonyManagerForSubscription(baseTm: TelephonyManager, subId: Int): TelephonyManager? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                baseTm.createForSubscriptionId(subId)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create TelephonyManager for subscription", e)
                null
            }
        } else {
            // Pre-N: return base TelephonyManager
            baseTm
        }
    }

    /**
     * Check if SharedPreferences access is available (always true on API 21+)
     */
    fun canAccessSharedPreferences(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP

    /**
     * Get minimum API level string for logging
     */
    fun getApiLevelInfo(): String {
        val name = Build.VERSION.RELEASE
        val level = Build.VERSION.SDK_INT
        return "Android $name (API $level)"
    }

    /**
     * Check if device supports gesture API
     */
    fun supportsGestures(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
    }

    /**
     * Check if device supports foreground services
     */
    fun supportsForegroundServices(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    }

    /**
     * Check if device supports exact alarm scheduling
     */
    fun supportsExactAlarms(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    }

    /**
     * Safely execute version-specific code with fallback
     */
    inline fun <T> withVersionFallback(
        minApi: Int,
        execute: () -> T,
        fallback: () -> T
    ): T {
        return if (Build.VERSION.SDK_INT >= minApi) {
            try {
                execute()
            } catch (e: Exception) {
                Log.w(TAG, "Version-specific code failed, using fallback", e)
                fallback()
            }
        } else {
            fallback()
        }
    }
}
