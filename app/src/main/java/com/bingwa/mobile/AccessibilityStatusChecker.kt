package com.bingwa.mobile

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager

object AccessibilityStatusChecker {
    private const val TAG = "AccStatus"

    fun isAccessibilityEnabled(context: Context): Boolean = try {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        val enabledByManager = manager
            ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            ?.any { info ->
                val serviceInfo = info.resolveInfo?.serviceInfo
                serviceInfo?.packageName == context.packageName &&
                    serviceInfo?.name == UssdNavigationService::class.java.name
            } == true
        if (enabledByManager) {
            true
        } else {
            isEnabledInSecureSettings(context)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Error checking accessibility", e)
        isEnabledInSecureSettings(context)
    }

    private fun isEnabledInSecureSettings(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        if (enabled.isBlank()) return false
        val componentName = ComponentName(context, UssdNavigationService::class.java)
        val flat = componentName.flattenToString()
        val shortFlat = componentName.flattenToShortString()
        return enabled.split(':').any { candidate ->
            candidate.equals(flat, ignoreCase = true) ||
                candidate.equals(shortFlat, ignoreCase = true)
        }
    }

    fun getStatusMessage(context: Context): String =
        if (isAccessibilityEnabled(context)) "Accessibility Service Active" else "Accessibility Service Required"
}
