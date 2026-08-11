package com.bingwa.mobile

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SetupDoctorSummary(
    val passedChecks: Int,
    val totalChecks: Int,
    val missingCriticalPermissions: List<String>,
    val notificationsGranted: Boolean,
    val accessibilityEnabled: Boolean,
    val batteryUnrestricted: Boolean,
    val exactAlarmReady: Boolean,
    val simCount: Int,
    val manufacturerAdvice: String
) {
    val issueCount: Int get() = totalChecks - passedChecks
}

private fun defaultSetupDoctorSummary(context: Context): SetupDoctorSummary {
    val manufacturer = Build.MANUFACTURER.takeIf { it.isNotBlank() } ?: "this phone"
    return SetupDoctorSummary(
        passedChecks = 0,
        totalChecks = 6,
        missingCriticalPermissions = emptyList(),
        notificationsGranted = false,
        accessibilityEnabled = false,
        batteryUnrestricted = false,
        exactAlarmReady = false,
        simCount = 0,
        manufacturerAdvice = setupDoctorAdvice(manufacturer)
    )
}

fun collectSetupDoctorSummarySafely(context: Context): SetupDoctorSummary =
    runCatching { collectSetupDoctorSummary(context) }
        .getOrElse { defaultSetupDoctorSummary(context) }

fun collectSetupDoctorSummary(context: Context): SetupDoctorSummary {
    val criticalPermissions = listOf(
        Manifest.permission.READ_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_PHONE_STATE
    )
    val missingCriticalPermissions = criticalPermissions.filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }
    val notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val accessibilityEnabled = AccessibilityStatusChecker.isAccessibilityEnabled(context)
    val batteryUnrestricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
    } else {
        true
    }
    val exactAlarmReady = AlarmCompat.canScheduleExactAlarms(context)
    val simCount = getAvailableSims(context).size
    val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
    val passedChecks = listOf(
        missingCriticalPermissions.isEmpty(),
        notificationsGranted,
        accessibilityEnabled,
        batteryUnrestricted,
        exactAlarmReady,
        simCount > 0
    ).count { it }
    return SetupDoctorSummary(
        passedChecks = passedChecks,
        totalChecks = 6,
        missingCriticalPermissions = missingCriticalPermissions,
        notificationsGranted = notificationsGranted,
        accessibilityEnabled = accessibilityEnabled,
        batteryUnrestricted = batteryUnrestricted,
        exactAlarmReady = exactAlarmReady,
        simCount = simCount,
        manufacturerAdvice = setupDoctorAdvice(manufacturer)
    )
}

@Composable
fun rememberSetupDoctorSummary(refreshKey: Int): SetupDoctorSummary {
    val context = LocalContext.current.applicationContext
    val initialValue = remember(context) { defaultSetupDoctorSummary(context) }
    val summary by produceState(
        initialValue = initialValue,
        key1 = context,
        key2 = refreshKey
    ) {
        value = withContext(Dispatchers.Default) {
            collectSetupDoctorSummarySafely(context)
        }
    }
    return summary
}

private fun setupDoctorAdvice(manufacturer: String): String {
    return when {
        manufacturer.contains("tecno", ignoreCase = true) ||
            manufacturer.contains("infinix", ignoreCase = true) ||
            manufacturer.contains("itel", ignoreCase = true) ->
            "Allow auto-start, remove battery restrictions, and keep Bingwa unlocked in recent apps."
        manufacturer.contains("samsung", ignoreCase = true) ->
            "Set battery usage to Unrestricted and keep Bingwa out of Sleeping apps."
        manufacturer.contains("xiaomi", ignoreCase = true) ||
            manufacturer.contains("redmi", ignoreCase = true) ||
            manufacturer.contains("poco", ignoreCase = true) ->
            "Enable Autostart, remove battery restrictions, and lock Bingwa in recents."
        manufacturer.contains("oppo", ignoreCase = true) ||
            manufacturer.contains("realme", ignoreCase = true) ||
            manufacturer.contains("vivo", ignoreCase = true) ->
            "Enable auto-launch and background activity so automation is not stopped."
        else ->
            "Keep Bingwa out of battery saver and allow its background activity for best reliability."
    }
}

@Composable
fun SetupDoctorCard(onOpen: () -> Unit) {
    var refreshKey by remember { mutableIntStateOf(0) }
    val summary = rememberSetupDoctorSummary(refreshKey)
    val healthy = summary.issueCount == 0
    val accent = when {
        healthy -> C.green
        summary.issueCount >= 3 -> C.red
        else -> C.orange
    }
    val headline = when {
        healthy -> "This phone is ready for automation"
        summary.issueCount == 1 -> "1 setup issue needs attention"
        else -> "${summary.issueCount} setup issues need attention"
    }
    val detail = when {
        summary.missingCriticalPermissions.isNotEmpty() -> "Phone or SMS permissions are still missing."
        !summary.accessibilityEnabled -> "Accessibility automation is still turned off."
        !summary.batteryUnrestricted -> "Battery rules may stop automation in the background."
        !summary.exactAlarmReady -> "Exact alarms are not approved for scheduled retries."
        summary.simCount == 0 -> "No active SIM is detected right now."
        !summary.notificationsGranted -> "Notifications are blocked on Android 13+."
        else -> summary.manufacturerAdvice
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = C.card,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(accent.copy(alpha = 0.14f))
                        .border(1.dp, accent.copy(alpha = 0.2f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    // FIXED: Use Icons.Outlined.Settings directly (no alias needed)
                    Icon(Icons.Outlined.Settings, null, tint = accent, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Setup Doctor", color = C.t1, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text("Live compatibility checks for this phone", color = C.t2, fontSize = 11.sp)
                }
                StatusPill(text = "${summary.passedChecks}/${summary.totalChecks} ready", color = accent)
            }

            Text(headline, color = C.t1, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = C.t2, fontSize = 11.sp)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDot("Permissions", summary.missingCriticalPermissions.isEmpty(), C.green, C.red)
                StatusDot("Access", summary.accessibilityEnabled, C.green, C.orange)
                StatusDot("Battery", summary.batteryUnrestricted, C.green, C.orange)
                StatusDot("SIM", summary.simCount > 0, C.green, C.orange)
            }

            Text(summary.manufacturerAdvice, color = C.t3, fontSize = 10.sp, lineHeight = 14.sp)

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { refreshKey++ },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent)
                ) {
                    Text("Refresh", color = C.bg, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = onOpen,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, C.border),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = C.t1)
                ) {
                    Text("Open Setup Doctor", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.2f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (color == C.green) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
            null,
            tint = color,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StatusDot(label: String, healthy: Boolean, okColor: Color, warnColor: Color) {
    val color = if (healthy) okColor else warnColor
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, color = C.t2, fontSize = 10.sp)
    }
}