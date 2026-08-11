@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@file:Suppress("DEPRECATION")

package com.bingwa.mobile

import android.Manifest
import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.provider.Telephony
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.cos
import kotlin.math.sin

private data class ManualSearchEntry(
    val name: String,
    val phone: String,
    val source: String,
    val lastSeen: Long = 0L
)

private data class StartupFallbackFeatureItem(
    val icon: ImageVector,
    val title: String,
    val detail: String,
    val accent: Color
)

private fun formatStartupFallbackErrorLabel(raw: String): String {
    val cleaned = raw.trim().ifBlank { "Startup issue" }
    return cleaned
        .replace('_', ' ')
        .replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(72)
}
// ─── MainActivity ─────────────────────────────────────────────────────────
class MainActivity : ComponentActivity() {
    private var pendingStartupPermissions: Array<String> = emptyArray()

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        val denied = perms.filterValues { !it }.keys
        if (denied.isNotEmpty()) Toast.makeText(this, "Permissions denied: ${denied.joinToString()}", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureWindowSafely()

        AppTheme.load(this)
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { OfferRepository.ensureSeeded(applicationContext) }
                .onFailure { Log.e("MainActivity", "Offer seeding failed", it) }
        }

        pendingStartupPermissions = mutableListOf<String>().apply {
            listOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS, Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE)
                .forEach { if (ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED) add(it) }
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

        setContent {
            SafeStartupRoot()
        }

        window.decorView.post {
            requestStartupPermissionsSafely()
            warmUpLaunchState()
        }
    }

    override fun onResume() {
        super.onResume()
        UssdNavigationService.onAppUiForegrounded()
        if (shouldAutoStartPhoneAutomation(this)) {
            ServiceLauncher.startBalanceChecker(this)
        }
    }

    private fun warmUpLaunchState() {
        runCatching { RelayManager.load(this) }
            .onFailure { Log.e("MainActivity", "Launch warm-up failed", it) }
    }

    private fun configureWindowSafely() {
        runCatching {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }.onFailure { Log.e("MainActivity", "Window setup failed", it) }
    }

    private fun requestStartupPermissionsSafely() {
        if (pendingStartupPermissions.isEmpty()) return
        runCatching { permissionLauncher.launch(pendingStartupPermissions) }
            .onFailure { Log.e("MainActivity", "Permission launch failed", it) }
    }
}

@Composable
private fun SafeStartupRoot() {
    val ctx = LocalContext.current
    val view = LocalView.current
    val startupResult = runCatching {
        val scheme = buildAppColorScheme(ThemeAccent.BYBIT, true)

        LaunchedEffect(scheme) { applyVolcanicPaletteFromScheme(scheme, true) }
        SideEffect {
            runCatching {
                view.context.findActivity()?.let { activity ->
                    WindowInsetsControllerCompat(activity.window, view).apply {
                        isAppearanceLightStatusBars = false
                        isAppearanceLightNavigationBars = false
                    }
                }
            }.onFailure { error ->
                Log.e("MainActivity", "Window inset styling failed", error)
            }
        }

        MaterialTheme(colorScheme = scheme) { BingwaApp() }
    }

    startupResult.onFailure { error ->
        Log.e("MainActivity", "Startup composition failed", error)
    }

    if (startupResult.isFailure) {
        MaterialTheme(colorScheme = buildAppColorScheme(ThemeAccent.BYBIT, true)) {
            StartupFallbackScreen(
                startupResult.exceptionOrNull()?.javaClass?.simpleName?.ifBlank { "Startup error" }
                    ?: "Startup error"
            ) {
                runCatching {
                    ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let(ctx::startActivity)
                }
            }
        }
    }
}

@Composable
private fun StartupFallbackScreen(errorLabel: String, onRetry: () -> Unit) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF06080D),
                        Color(0xFF0B1220),
                        Color(0xFF090C14)
                    )
                )
            )
            .padding(horizontal = 20.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        val compact = maxWidth < 380.dp
        val cardWidth = if (compact) Modifier.fillMaxWidth() else Modifier.widthIn(max = 460.dp)
        val panelColor = Color(0xFF121826)
        val borderColor = Color(0xFF273244)
        val accent = Color(0xFF67E8F9)
        val warmAccent = Color(0xFFF59E0B)
        val danger = Color(0xFFFB7185)
        val readableError = remember(errorLabel) { formatStartupFallbackErrorLabel(errorLabel) }
        val featureItems = listOf(
            StartupFallbackFeatureItem(
                icon = Icons.Rounded.Inventory2,
                title = "Saved offers and settings stay available",
                detail = "Your local data remains untouched while the normal startup path is retried.",
                accent = accent
            ),
            StartupFallbackFeatureItem(
                icon = Icons.Rounded.Sync,
                title = "One tap gets you back into the app",
                detail = "Use the retry action after the temporary startup problem clears.",
                accent = warmAccent
            ),
            StartupFallbackFeatureItem(
                icon = Icons.Rounded.Security,
                title = "Built to fail safely",
                detail = "This fallback view avoids a hard crash and keeps the app usable during recovery.",
                accent = Color(0xFFA78BFA)
            )
        )

        Box(
            modifier = Modifier
                .size(if (compact) 240.dp else 320.dp)
                .align(Alignment.TopStart)
                .offset(x = (-80).dp, y = (-40).dp)
                .background(
                    Brush.radialGradient(
                        listOf(accent.copy(alpha = 0.16f), Color.Transparent)
                    ),
                    CircleShape
                )
        )
        Box(
            modifier = Modifier
                .size(if (compact) 220.dp else 280.dp)
                .align(Alignment.BottomEnd)
                .offset(x = 90.dp, y = 110.dp)
                .background(
                    Brush.radialGradient(
                        listOf(warmAccent.copy(alpha = 0.14f), Color.Transparent)
                    ),
                    CircleShape
                )
        )

        Surface(
            modifier = cardWidth,
            shape = RoundedCornerShape(32.dp),
            color = panelColor.copy(alpha = 0.96f),
            border = BorderStroke(1.dp, borderColor.copy(alpha = 0.95f)),
            shadowElevation = 18.dp
        ) {
            Column(
                modifier = Modifier
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xFF172033),
                                panelColor,
                                Color(0xFF0E1420)
                            )
                        )
                    )
                    .padding(horizontal = if (compact) 18.dp else 24.dp, vertical = if (compact) 20.dp else 24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                StartupFallbackHeader(
                    compact = compact,
                    accent = accent,
                    warmAccent = warmAccent
                )
                StartupFallbackStatusCard(
                    errorLabel = readableError,
                    accent = accent,
                    warmAccent = warmAccent,
                    danger = danger
                )
                StartupFallbackHighlights(featureItems = featureItems)
                StartupFallbackFooter(onRetry = onRetry, accent = accent)
            }
        }
    }
}

@Composable
private fun StartupFallbackHeader(
    compact: Boolean,
    accent: Color,
    warmAccent: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = accent.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.24f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.AutoFixHigh, null, tint = accent, modifier = Modifier.size(14.dp))
                    Text(
                        "Safe startup mode",
                        color = accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Text(
                "Bingwa Mobile",
                color = Color(0xFFF8FAFC),
                fontSize = if (compact) 28.sp else 32.sp,
                fontWeight = FontWeight.Black
            )
            Text(
                "We switched to a protected launch screen so your setup stays intact while the app recovers.",
                color = Color(0xFFC0CAD9),
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        }
        Box(
            modifier = Modifier
                .padding(start = 12.dp)
                .size(if (compact) 54.dp else 60.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.linearGradient(
                        listOf(accent.copy(alpha = 0.24f), warmAccent.copy(alpha = 0.20f))
                    )
                )
                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.Shield,
                null,
                tint = Color(0xFFF8FAFC),
                modifier = Modifier.size(if (compact) 26.dp else 30.dp)
            )
        }
    }
}

@Composable
private fun StartupFallbackStatusCard(
    errorLabel: String,
    accent: Color,
    warmAccent: Color,
    danger: Color
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = Color(0xFF0C1422).copy(alpha = 0.96f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.verticalGradient(
                        listOf(
                            accent.copy(alpha = 0.06f),
                            Color.Transparent,
                            warmAccent.copy(alpha = 0.05f)
                        )
                    )
                )
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Recovery status",
                    color = Color(0xFFE5EDF8),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = accent.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.22f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(accent)
                        )
                        Text(
                            "Ready to retry",
                            color = accent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.White.copy(alpha = 0.06f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.82f)
                        .clip(RoundedCornerShape(999.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(accent.copy(alpha = 0.85f), warmAccent.copy(alpha = 0.90f))
                            )
                        )
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StartupFallbackPill(
                    icon = Icons.Rounded.VerifiedUser,
                    label = "Existing setup protected",
                    accent = accent
                )
                StartupFallbackPill(
                    icon = Icons.Rounded.Bolt,
                    label = "Fast relaunch",
                    accent = warmAccent
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StartupFallbackMiniStat(
                    label = "State",
                    value = "Protected",
                    tint = accent,
                    modifier = Modifier.weight(1f)
                )
                StartupFallbackMiniStat(
                    label = "Data",
                    value = "Intact",
                    tint = Color(0xFFA78BFA),
                    modifier = Modifier.weight(1f)
                )
                StartupFallbackMiniStat(
                    label = "Retry",
                    value = "Instant",
                    tint = warmAccent,
                    modifier = Modifier.weight(1f)
                )
            }
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = danger.copy(alpha = 0.10f),
                border = BorderStroke(1.dp, danger.copy(alpha = 0.22f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.ErrorOutline, null, tint = danger, modifier = Modifier.size(16.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "Issue detected",
                            color = danger,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            errorLabel,
                            color = Color(0xFFFFCDD8),
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StartupFallbackHighlights(featureItems: List<StartupFallbackFeatureItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Why this screen appears",
            color = Color(0xFF94A3B8),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.3.sp
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            featureItems.forEach { feature ->
                StartupFallbackFeature(
                    icon = feature.icon,
                    title = feature.title,
                    detail = feature.detail,
                    accent = feature.accent
                )
            }
        }
    }
}

@Composable
private fun StartupFallbackFooter(onRetry: () -> Unit, accent: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = onRetry,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .shadow(14.dp, RoundedCornerShape(20.dp)),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = accent,
                contentColor = Color(0xFF051018)
            ),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
        ) {
            Surface(
                shape = CircleShape,
                color = Color(0xFF051018).copy(alpha = 0.10f)
            ) {
                Box(
                    modifier = Modifier.size(28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.Start) {
                Text("Retry Launch", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                Text("Return to the full app as soon as startup completes", fontSize = 11.sp)
            }
        }
        Text(
            "Bingwa keeps your existing setup safe and automatically returns to the full experience when startup completes normally.",
            color = Color(0xFF94A3B8),
            textAlign = TextAlign.Center,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun StartupFallbackPill(icon: ImageVector, label: String, accent: Color) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = accent.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.18f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(13.dp))
            Text(
                label,
                color = accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun StartupFallbackMiniStat(
    label: String,
    value: String,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = tint.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.18f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                label.uppercase(),
                color = tint,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.4.sp
            )
            Text(
                value,
                color = Color(0xFFEAF2FF),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun StartupFallbackFeature(
    icon: ImageVector,
    title: String,
    detail: String,
    accent: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(accent.copy(alpha = 0.12f))
                .border(1.dp, accent.copy(alpha = 0.16f), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(18.dp))
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                title,
                color = Color(0xFFEAF2FF),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                detail,
                color = Color(0xFF93A4BB),
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }
    }
}

// ─── Helper Functions ────────────────────────────────────────────────────
fun vib(ctx: Context, durationMs: Long = 30L) {
    try {
        val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            v.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        else @Suppress("DEPRECATION") v.vibrate(durationMs)
    } catch (_: Exception) {}
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext?.findActivity()
    else -> null
}

private fun hasPermissions(context: Context, vararg permissions: String): Boolean =
    permissions.all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

private fun canUsePhoneAutomation(context: Context): Boolean {
    if (!hasPermissions(
            context,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE
        )
    ) return false
    return context.getSystemService(Context.TELEPHONY_SERVICE) is TelephonyManager
}

private fun shouldAutoStartPhoneAutomation(context: Context): Boolean {
    val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    return prefs.safeGetBoolean("automation_enabled", true) && canUsePhoneAutomation(context)
}

private fun requestBalanceCheckSafely(
    context: Context,
    selectionOverride: Int? = null,
    persistResult: Boolean = selectionOverride == null,
    ignoreCooldown: Boolean = false,
    specialHandling: Boolean = false
): Boolean =
    runCatching {
        BalanceChecker.requestBalanceCheck(
            context = context,
            selectionOverride = selectionOverride,
            persistResult = persistResult,
            ignoreCooldown = ignoreCooldown,
            specialHandling = specialHandling
        )
    }.getOrElse { error ->
        Log.e("MainActivity", "Unable to start balance refresh", error)
        false
    }

private fun hasTwoActiveSimSlots(context: Context): Boolean {
    val activeSlots = getAvailableSims(context)
        .map { info ->
            info.simSlotIndex.takeIf { it >= 0 } ?: info.subscriptionId
        }
        .distinct()
    return activeSlots.size >= 2
}

fun canonicalNameTokens(value: String): List<String> =
    value.trim()
        .replace(Regex("[^A-Za-z\\s'\\-]"), " ")
        .split(Regex("\\s+"))
        .map { it.trim('\'', '-', ' ') }
        .filter { it.length > 1 }
        .map { it.lowercase(Locale.getDefault()) }

fun formatClientName(raw: String, maxThreeNameLength: Int = 24): String {
    val tokens = canonicalNameTokens(raw)
    if (tokens.isEmpty()) return ""
    val selected = when {
        tokens.size <= 2 -> tokens
        tokens.size == 3 && tokens.joinToString(" ").length <= maxThreeNameLength -> tokens
        else -> listOf(tokens.first(), tokens.last())
    }
    return selected.joinToString(" ") { token ->
        token.replaceFirstChar { ch -> ch.titlecase(Locale.getDefault()) }
    }
}

fun namesLikelyMatch(first: String, second: String): Boolean {
    val a = canonicalNameTokens(first)
    val b = canonicalNameTokens(second)
    if (a.isEmpty() || b.isEmpty()) return false
    if (a == b) return true
    if (a.first() == b.first() && a.last() == b.last()) return true
    return a.intersect(b.toSet()).size >= 2
}

private fun inferTransactionSource(description: String, amount: String, clientName: String, ussdCode: String): String {
    if (amount.trim().startsWith("-") || description.contains("airtime", ignoreCase = true)) return TX_SOURCE_AIRTIME
    if (ussdCode.isNotBlank() && clientName.isNotBlank()) return TX_SOURCE_AUTOMATED
    return TX_SOURCE_SYSTEM
}

private fun inferRecentVisibility(description: String, amount: String, clientName: String, ussdCode: String): Boolean =
    inferTransactionSource(description, amount, clientName, ussdCode) == TX_SOURCE_AUTOMATED

private fun transactionFromStorage(obj: JSONObject, fallbackId: Int = -1): Transaction {
    val description = obj.optString("description", "")
    val amount = obj.optString("amount", "")
    val clientName = obj.optString("clientName", "")
    val ussdCode = obj.optString("ussdCode", "")
    val status = obj.optString("status", TransactionStatus.PENDING.value)
    val source = obj.optString("source").ifBlank {
        inferTransactionSource(description, amount, clientName, ussdCode)
    }
    val showInRecent = if (obj.has("showInRecent")) {
        obj.optBoolean("showInRecent", false)
    } else {
        inferRecentVisibility(description, amount, clientName, ussdCode)
    }
    return Transaction(
        id = obj.optInt("id", fallbackId),
        description = description,
        amount = amount,
        amountValue = Regex("""\d+(?:\.\d+)?""").find(amount)?.value?.toDoubleOrNull() ?: 0.0,
        date = obj.optString("date", ""),
        status = status,
        statusEnum = TransactionStatus.fromString(status),
        ussdCode = ussdCode,
        phoneNumber = obj.optString("phoneNumber", ""),
        response = obj.optString("response", ""),
        timestamp = obj.optLong("timestamp", 0L),
        clientName = clientName,
        ussdResponse = obj.optString("ussdResponse", ""),
        ussdTranscript = obj.optString("ussdTranscript", ""),
        source = source,
        showInRecent = showInRecent,
        offerId = obj.optInt("offerId", -1),
        completedAt = obj.optLong("completedAt", 0L),
        executionDurationMs = obj.optLong("executionDurationMs", 0L)
    )
}

private fun transactionToJson(tx: Transaction): JSONObject =
    JSONObject().apply {
        put("id", tx.id)
        put("description", tx.description)
        put("amount", tx.amount)
        put("date", tx.date)
        put("status", tx.status)
        put("ussdCode", tx.ussdCode)
        put("phoneNumber", tx.phoneNumber)
        put("clientName", tx.clientName)
        put("ussdResponse", tx.ussdResponse)
        put("ussdTranscript", tx.ussdTranscript)
        put("timestamp", tx.timestamp)
        put("source", tx.source)
        put("showInRecent", tx.showInRecent)
        put("offerId", tx.offerId)
        put("completedAt", tx.completedAt)
        put("executionDurationMs", tx.executionDurationMs)
    }

fun loadTransactionByIdFromPrefs(context: Context, txId: Int): Transaction? {
    return TransactionStore.findById(context, txId)
}

fun broadcastTransactionCreated(context: Context, txId: Int) {
    if (txId < 0) return
    context.sendBroadcast(
        Intent(ACTION_TX_CREATED)
            .setPackage(context.packageName)
            .apply { putExtra("txId", txId) }
    )
}

fun broadcastTransactionUpdated(context: Context, txId: Int) {
    if (txId < 0) return
    context.sendBroadcast(
        Intent("com.bingwa.mobile.TX_UPDATED")
            .setPackage(context.packageName)
            .apply { putExtra("txId", txId) }
    )
}

fun resolveClientNameByPhone(context: Context, phone: String): String {
    val normalized = SmsCommandHandler.normalizePhone(phone)
    if (!normalized.matches(Regex("^0\\d{9}$"))) return ""

    val contacts = SavedContactStore.load(context)
    contacts.firstOrNull { SmsCommandHandler.normalizePhone(it.phone) == normalized && it.name.isNotBlank() }?.let {
        return formatClientName(it.name)
    }

    for (tx in TransactionStore.load(context)) {
        if (SmsCommandHandler.normalizePhone(tx.phoneNumber) == normalized && tx.clientName.isNotBlank()) {
            return formatClientName(tx.clientName)
        }
    }
    return ""
}

fun upsertSavedContact(context: Context, phone: String, name: String) {
    val normalizedPhone = SmsCommandHandler.normalizePhone(phone)
    val formattedName = formatClientName(name)
    if (!normalizedPhone.matches(Regex("^0\\d{9}$"))) return
    if (formattedName.isBlank()) return

    SavedContactStore.upsert(context, normalizedPhone, formattedName)
}

fun choosePreferredClientName(current: String, candidate: String): String {
    val currentFormatted = formatClientName(current)
    val candidateFormatted = formatClientName(candidate)
    if (candidateFormatted.isBlank()) return currentFormatted
    if (currentFormatted.isBlank()) return candidateFormatted

    val currentTokens = canonicalNameTokens(currentFormatted)
    val candidateTokens = canonicalNameTokens(candidateFormatted)
    return when {
        candidateTokens.size > currentTokens.size -> candidateFormatted
        candidateTokens.size == currentTokens.size && candidateFormatted.length > currentFormatted.length -> candidateFormatted
        else -> currentFormatted
    }
}

private fun mergeManualSearchEntry(existing: ManualSearchEntry?, incoming: ManualSearchEntry): ManualSearchEntry {
    if (existing == null) return incoming
    val preferredName = choosePreferredClientName(existing.name, incoming.name)
    val preferredSource = when {
        existing.source == "Saved" || incoming.source != "Saved" -> existing.source
        else -> incoming.source
    }
    return ManualSearchEntry(
        name = preferredName,
        phone = existing.phone,
        source = preferredSource,
        lastSeen = maxOf(existing.lastSeen, incoming.lastSeen)
    )
}

private fun buildManualSearchEntries(
    context: Context,
    allTxns: List<Transaction>,
    smsContacts: List<SavedContact>
): List<ManualSearchEntry> {
    val merged = linkedMapOf<String, ManualSearchEntry>()

    fun addEntry(name: String, phone: String, source: String, lastSeen: Long = 0L) {
        val normalizedPhone = SmsCommandHandler.normalizePhone(phone)
        if (!normalizedPhone.matches(Regex("^0\\d{9}$"))) return
        val incoming = ManualSearchEntry(
            name = formatClientName(name),
            phone = normalizedPhone,
            source = source,
            lastSeen = lastSeen
        )
        merged[normalizedPhone] = mergeManualSearchEntry(merged[normalizedPhone], incoming)
    }

    SavedContactStore.load(context)
        .forEach { addEntry(it.name, it.phone, "Saved") }
    smsContacts.forEach { addEntry(it.name, it.phone, "M-PESA SMS") }
    allTxns.forEach { tx ->
        addEntry(tx.clientName, tx.phoneNumber, "History", tx.timestamp)
    }

    return merged.values.sortedWith(
        compareByDescending<ManualSearchEntry> { it.lastSeen }
            .thenBy { it.name.ifBlank { it.phone }.lowercase(Locale.getDefault()) }
    )
}

private fun rankManualSearchEntries(query: String, entries: List<ManualSearchEntry>): List<ManualSearchEntry> {
    val trimmed = query.trim()
    if (trimmed.isBlank()) return emptyList()

    val lowered = trimmed.lowercase(Locale.getDefault())
    val nameTokens = canonicalNameTokens(trimmed)
    val digits = trimmed.filter(Char::isDigit)

    return entries.asSequence()
        .mapNotNull { entry ->
            val loweredName = entry.name.lowercase(Locale.getDefault())
            val normalizedPhone = SmsCommandHandler.normalizePhone(entry.phone)
            var score = 0

            if (digits.isNotBlank()) {
                score += when {
                    normalizedPhone == digits -> 170
                    normalizedPhone.startsWith(digits) -> 125
                    normalizedPhone.contains(digits) -> 95
                    else -> 0
                }
            }

            if (lowered.isNotBlank()) {
                score += when {
                    loweredName == lowered -> 180
                    loweredName.startsWith(lowered) -> 140
                    loweredName.contains(lowered) -> 100
                    else -> 0
                }
            }

            if (nameTokens.isNotEmpty()) {
                val entryTokens = canonicalNameTokens(entry.name)
                if (nameTokens.all { token -> entryTokens.any { it.startsWith(token) || it == token } }) {
                    score += 80
                }
            }

            score += when (entry.source) {
                "Saved" -> 20
                "M-PESA SMS" -> 10
                else -> 0
            }

            if (score <= 0) null else entry to score
        }
        .sortedWith(
            compareByDescending<Pair<ManualSearchEntry, Int>> { it.second }
                .thenByDescending { it.first.lastSeen }
                .thenBy { it.first.name.ifBlank { it.first.phone }.lowercase(Locale.getDefault()) }
        )
        .map { it.first }
        .take(6)
        .toList()
}

private fun autoMatchManualEntries(phone: String, entries: List<ManualSearchEntry>): List<ManualSearchEntry> {
    val normalized = SmsCommandHandler.normalizePhone(phone)
    if (normalized.length < 3) return emptyList()

    return rankManualSearchEntries(phone, entries).sortedWith(
        compareByDescending<ManualSearchEntry> { SmsCommandHandler.normalizePhone(it.phone) == normalized }
            .thenByDescending { it.source == "Saved" }
            .thenByDescending { it.lastSeen }
            .thenBy { it.name.ifBlank { it.phone }.lowercase(Locale.getDefault()) }
    )
}

fun loadTransactions(ctx: Context, into: MutableList<Transaction>) {
    try {
        into.clear()
        into.addAll(TransactionStore.load(ctx))
    } catch (e: Exception) {
        Log.e("Transactions", "loadTransactions error", e)
    }
}

fun saveTransactions(ctx: Context, list: List<Transaction>) {
    try {
        TransactionStore.save(ctx, list)
    } catch (e: Exception) {
        Log.e("Transactions", "saveTransactions error", e)
    }
}

fun createPendingTransaction(
    ctx: Context,
    description: String,
    amount: String,
    phone: String,
    ussd: String,
    clientName: String = "",
    status: String = TransactionStatus.PENDING.value,
    source: String = TX_SOURCE_SYSTEM,
    showInRecent: Boolean = false,
    offerId: Int = -1
): Int {
    return try {
        TransactionStore.createPendingTransaction(
            context = ctx,
            description = description,
            amount = amount,
            phone = phone,
            ussd = ussd,
            clientName = clientName,
            status = status,
            source = source,
            showInRecent = showInRecent,
            offerId = offerId
        )
    } catch (e: Exception) {
        Log.e("Transactions", "createPendingTransaction error", e)
        -1
    }
}

private fun formatRemainingTimeWithSuffix(ms: Long, suffix: String): String {
    if (ms <= 0L) return "Expired"
    val totalSeconds = ms / 1_000L
    val days = totalSeconds / 86_400L
    val hours = (totalSeconds % 86_400L) / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return "${days}d ${hours}h ${minutes}m ${seconds}s $suffix"
}

private fun nextCountdownRefreshDelay(ms: Long): Long {
    if (ms <= 0L) return 15_000L
    val remainder = ms % 1_000L
    return when {
        remainder == 0L -> 1_000L
        remainder <= 80L -> 120L
        else -> (remainder + 40L).coerceAtMost(1_000L)
    }
}

fun formatRemainingTimeHome(ms: Long): String = formatRemainingTimeWithSuffix(ms, "left")

fun formatRemainingTimeDetailed(ms: Long): String = formatRemainingTimeWithSuffix(ms, "remaining")

fun formatSignatureLearnedAt(timestamp: Long): String =
    if (timestamp <= 0L) "Not learned yet"
    else SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(timestamp))

private fun OfferItem.clearPendingSignatureReview(): OfferItem = copy(
    pendingLearnedSignature = emptyList(),
    pendingSignatureLearnedAt = 0L,
    pendingSignatureLearningCaptures = emptyList()
)

private data class LearnedStepDetail(
    val stepIndex: Int,
    val menuTitle: String,
    val enteredInput: String,
    val selectedOptionLabel: String,
    val recordedTexts: List<String>,
    val menuOptionsSnapshot: List<String>
)

private fun buildLearnedStepDetails(
    learnedSteps: List<UssdSignatureStep>,
    learningCaptures: List<UssdLearningCapture>
): List<LearnedStepDetail> {
    val stepsByIndex = learnedSteps.associateBy { it.stepIndex }
    val capturesByIndex = learningCaptures.groupBy { it.stepIndex }
    val allStepIndexes = (stepsByIndex.keys + capturesByIndex.keys)
        .distinct()
        .sortedBy { if (it < 0) Int.MAX_VALUE else it }

    return allStepIndexes.map { stepIndex ->
        val step = stepsByIndex[stepIndex]
        val captures = capturesByIndex[stepIndex].orEmpty()
        val latestCapture = captures.lastOrNull()
        LearnedStepDetail(
            stepIndex = stepIndex,
            menuTitle = step?.menuTitle.orEmpty(),
            enteredInput = latestCapture?.enteredInput.orEmpty().ifBlank { step?.expectedInput.orEmpty() },
            selectedOptionLabel = latestCapture?.selectedOptionLabel.orEmpty().ifBlank { step?.selectedOptionLabel.orEmpty() },
            recordedTexts = (listOf(step?.menuText.orEmpty()) + captures.map { it.popupText.trim() })
                .filter { it.isNotBlank() }
                .distinct(),
            menuOptionsSnapshot = step?.menuOptionsSnapshot.orEmpty()
        )
    }
}

fun loadContacts(prefs: SharedPreferences): List<SavedContact> = SavedContactStore.load(prefs)

fun saveContacts(prefs: SharedPreferences, list: List<SavedContact>) {
    SavedContactStore.save(prefs, list)
}

fun firstNameFrom(fullName: String): String =
    fullName.trim().split(" ").firstOrNull { it.isNotBlank() }?.replaceFirstChar { it.uppercase() } ?: fullName

fun buildSmsMessage(ctx: Context, outcome: String, fullName: String, offer: String, amount: String, phone: String): String {
    val prefs = ctx.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    val key = when (outcome.lowercase()) {
        "success" -> "sms_tpl_success"
        "pending" -> "sms_tpl_pending"
        "limit_notice" -> "sms_tpl_limit_notice"
        "scheduled" -> "sms_tpl_scheduled"
        else -> "sms_tpl_failed"
    }
    val defaultTpl = when (outcome.lowercase()) {
        "success" -> DEFAULT_TPL_SUCCESS
        "pending" -> DEFAULT_TPL_PENDING
        "limit_notice" -> DEFAULT_TPL_LIMIT_NOTICE
        "scheduled" -> DEFAULT_TPL_SCHEDULED
        else -> DEFAULT_TPL_FAILED
    }
    val template = prefs.getString(key, defaultTpl) ?: defaultTpl
    val maskedPhone = if (phone.length >= 10) phone.take(4) + "XXXXXX" else phone
    return template
        .replace("{name}", firstNameFrom(fullName))
        .replace("{offer}", offer)
        .replace("{amount}", amount)
        .replace("{phone}", maskedPhone)
}

@SuppressLint("Range")
fun extractMpesaContacts(ctx: Context, daysBack: Int): List<SavedContact> {
    val list = mutableListOf<SavedContact>()
    val seen = mutableSetOf<String>()
    val cutoff = System.currentTimeMillis() - (daysBack * 86400000L)
    val receiver = MpesaReceiver()
    try {
        ctx.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            null, null, Telephony.Sms.DEFAULT_SORT_ORDER
        )?.use { c ->
            while (c.moveToNext()) {
                val address = c.getString(c.getColumnIndex(Telephony.Sms.ADDRESS)) ?: continue
                val body = c.getString(c.getColumnIndex(Telephony.Sms.BODY)) ?: continue
                val date = c.getLong(c.getColumnIndex(Telephony.Sms.DATE))
                if (date < cutoff) continue
                if (!address.equals("MPESA", true) && !body.contains("M-Pesa", true)) continue
                val rawPhone = receiver.extractPhoneOrMasked(body).ifBlank {
                    Regex("""(?:254|0)(?:7\d{8}|1\d{8})""").find(body)?.value.orEmpty()
                }
                val name = formatClientName(receiver.extractClientName(body))
                val phone = receiver.resolveMaskedNumber(ctx, rawPhone, name)
                if (!phone.matches(Regex("^0\\d{9}$"))) continue
                if (phone in seen) continue
                seen.add(phone)
                list.add(SavedContact(name, phone))
            }
        }
    } catch (e: Exception) {
        Log.e("MpesaImport", "SMS read error", e)
    }
    return list
}

@SuppressLint("MissingPermission")
fun getAvailableSims(ctx: Context): List<SubscriptionInfo> {
    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return emptyList()
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return emptyList()
    return try {
        (ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager)
            ?.activeSubscriptionInfoList
            ?: emptyList()
    } catch (_: Exception) { emptyList() }
}

fun calculateOverview(ctx: Context): List<Pair<String, Int>> {
    val prefs = ctx.getSharedPreferences("transactions", Context.MODE_PRIVATE)
    val arr = try { JSONArray(prefs.getString("list", "[]")) } catch (_: Exception) { return emptyList() }
    val map = mutableMapOf<String, Int>()
    for (i in 0 until arr.length()) {
        val desc = arr.getJSONObject(i).optString("description", "")
        if (desc.isNotBlank()) map[desc] = (map[desc] ?: 0) + 1
    }
    return map.entries.sortedByDescending { it.value }.map { it.key to it.value }
}

// ─── Shared UI Components ───────────────────────────────────────────────
@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = C.cyan.copy(alpha = 0.85f), unfocusedBorderColor = C.border.copy(alpha = 0.9f),
    focusedTextColor = C.t1, unfocusedTextColor = C.t1,
    cursorColor = C.cyan,
    focusedContainerColor = C.cardHi.copy(alpha = 0.98f),
    unfocusedContainerColor = C.card.copy(alpha = 0.96f),
    focusedPlaceholderColor = C.t3,
    unfocusedPlaceholderColor = C.t3,
    focusedLeadingIconColor = C.t2,
    unfocusedLeadingIconColor = C.t2,
    focusedTrailingIconColor = C.t2,
    unfocusedTrailingIconColor = C.t2
)

@Composable
fun dialogFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = C.cyan, unfocusedBorderColor = C.border,
    focusedTextColor = C.t1, unfocusedTextColor = C.t1,
    cursorColor = C.cyan, focusedContainerColor = C.cardHi, unfocusedContainerColor = C.cardHi
)

@Composable
fun VolcanicSurface(
    modifier: Modifier = Modifier,
    elevation: Dp = 8.dp,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = C.card,
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.65f)),
        shadowElevation = elevation
    ) {
        Box(
            modifier = Modifier.background(
                Brush.verticalGradient(
                    listOf(C.cardHi.copy(alpha = 0.96f), C.card.copy(alpha = 0.98f))
                )
            )
        ) {
            content()
        }
    }
}

@Composable
fun PageHeader(title: String, subtitle: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            title,
            fontSize = 34.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = (-1.2).sp,
            color = C.t1
        )
        Text(
            subtitle,
            color = C.t2,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ManualHeader(title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 24.dp, top = 6.dp, end = 24.dp, bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            title,
            fontSize = 30.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = (-1.0).sp,
            color = C.t1,
            textAlign = TextAlign.Center
        )
        Text(
            subtitle,
            color = C.t2,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String, accent: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .width(4.dp)
                        .height(24.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Brush.verticalGradient(listOf(accent, accent.copy(alpha = 0.35f))))
                )
                Spacer(Modifier.width(10.dp))
                Text(title, color = C.t1, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
            }
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = C.t2, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
    }
}

@Composable
private fun OverviewStatChip(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = C.surface.copy(alpha = 0.9f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.18f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(value, color = accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(label, color = C.t3, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun SettingsOverviewCard(
    autoEnabled: Boolean,
    remoteEnabled: Boolean,
    twoPhoneEnabled: Boolean
) {
    val accent = when {
        autoEnabled && twoPhoneEnabled -> C.green
        autoEnabled -> C.cyan
        else -> C.amber
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = C.cardHi.copy(alpha = 0.94f),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.18f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(accent.copy(alpha = 0.11f), C.cardHi.copy(alpha = 0.98f), C.surface.copy(alpha = 0.96f))
                    )
                )
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Control Center", color = C.t1, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                    Text(
                        "Manage automation, relay mode, alerts, and customer notifications from one place.",
                        color = C.t2,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(accent.copy(alpha = 0.12f))
                        .border(1.dp, accent.copy(alpha = 0.18f), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.Tune, null, tint = accent, modifier = Modifier.size(20.dp))
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OverviewStatChip("Automation", if (autoEnabled) "ON" else "OFF", if (autoEnabled) C.green else C.amber)
                OverviewStatChip("Remote", if (remoteEnabled) "ARMED" else "OFF", if (remoteEnabled) C.blue else C.t3)
                OverviewStatChip("Relay", if (twoPhoneEnabled) "2-PHONE" else "SINGLE", if (twoPhoneEnabled) C.purple else C.t3)
            }
        }
    }
}

@Composable
private fun PurchasePerkChip(icon: ImageVector, label: String, accent: Color) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = accent.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.18f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(13.dp))
            Text(
                label,
                color = accent,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TokenHeroInfoLine(icon: ImageVector, label: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(icon, null, tint = C.t3, modifier = Modifier.size(18.dp))
        Text(
            label,
            color = C.t2,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TokensHeroCard(
    balance: Int,
    activePlan: UnlimitedManager.Plan?,
    remainingMs: Long
) {
    val accent = C.amber
    val totalSeconds = (remainingMs / 1_000L).coerceAtLeast(0L)
    val days = totalSeconds / 86_400L
    val hours = (totalSeconds % 86_400L) / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    val remainingLabel = if (activePlan != null) "${days}d ${hours}h ${minutes}m ${seconds}s" else ""
    val heroAlignment = if (activePlan != null) Alignment.Start else Alignment.CenterHorizontally
    val detailTextAlign = if (activePlan != null) TextAlign.Start else TextAlign.Center
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val compact = maxWidth < 360.dp
        val balanceValueFontSize = balanceValueFontSize(
            value = balance.toString(),
            short = if (compact) 56.sp else 64.sp,
            medium = if (compact) 48.sp else 56.sp,
            long = if (compact) 42.sp else 48.sp,
            extraLong = if (compact) 34.sp else 40.sp
        )
        val detailFontSize = if (compact) 12.sp else 13.sp
        val remainingFontSize = balanceCaptionFontSize(
            value = remainingLabel,
            short = if (activePlan != null) {
                if (compact) 20.sp else 22.sp
            } else {
                if (compact) 18.sp else 22.sp
            },
            medium = if (activePlan != null) {
                if (compact) 18.sp else 20.sp
            } else {
                if (compact) 16.sp else 19.sp
            },
            long = if (compact) 16.sp else 18.sp
        )
        val activeProgress = if (activePlan != null && activePlan.durationMs > 0L) {
            (remainingMs.toFloat() / activePlan.durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFF14181A),
            shape = RoundedCornerShape(28.dp),
            border = BorderStroke(1.dp, accent.copy(alpha = 0.30f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            listOf(
                                Color(0xFF21211C),
                                Color(0xFF14181A),
                                Color(0xFF0F1418)
                            )
                        )
                    )
                    .padding(
                        horizontal = if (compact) 18.dp else 22.dp,
                        vertical = if (compact) 18.dp else 22.dp
                    ),
                horizontalAlignment = heroAlignment,
                verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp)
            ) {
                if (activePlan != null) {
                    val accentActive = C.green
                    val remainingText = buildAnnotatedString {
                        append("${days}d ${hours}h ${minutes}m ")
                        withStyle(SpanStyle(color = accentActive)) {
                            append("${seconds}s")
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(accentActive)
                            )
                            Text(
                                "Unlimited active",
                                color = accentActive,
                                fontSize = detailFontSize,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                        Text(
                            "Admin grant",
                            color = C.t2,
                            fontSize = detailFontSize,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        "REMAINING TIME",
                        color = C.t3,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        remainingText,
                        fontSize = remainingFontSize,
                        fontWeight = FontWeight.ExtraBold,
                        lineHeight = remainingFontSize * 1.05f,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(C.surface.copy(alpha = 0.92f))
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(activeProgress)
                                .height(10.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(accentActive.copy(alpha = 0.92f), accentActive.copy(alpha = 0.62f))
                                    )
                                )
                        )
                    }
                    val durationDays = (activePlan.durationMs / 86_400_000L).toInt().coerceAtLeast(0)
                    val windowLabel = when {
                        durationDays > 0 -> "${durationDays}-day"
                        else -> "${(activePlan.durationMs / 3_600_000L).toInt().coerceAtLeast(1)}-hour"
                    }
                    Text(
                        "${(activeProgress * 100f).toInt()}% of your $windowLabel window left",
                        color = C.t2,
                        fontSize = detailFontSize,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(C.border.copy(alpha = 0.65f))
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TokenHeroInfoLine(
                            icon = Icons.Outlined.Schedule,
                            label = "Stays active until the timer ends"
                        )
                        TokenHeroInfoLine(
                            icon = Icons.Outlined.History,
                            label = "Stored tokens return once this expires"
                        )
                    }
                } else {
                    Text(
                        balance.toString(),
                        fontSize = balanceValueFontSize,
                        fontWeight = FontWeight.Black,
                        lineHeight = balanceValueFontSize,
                        color = C.t1,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "AVAILABLE TOKENS",
                        color = accent,
                        fontSize = if (compact) 12.sp else 13.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 4.sp,
                        textAlign = detailTextAlign,
                        maxLines = 1
                    )
                    Spacer(Modifier.height(if (compact) 12.dp else 14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Outlined.Bolt, null, tint = accent, modifier = Modifier.size(16.dp))
                            Text(
                                "1 token = 1 USSD call",
                                color = C.t2,
                                fontSize = if (compact) 12.sp else 13.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .width(1.dp)
                                .height(18.dp)
                                .background(C.border.copy(alpha = 0.65f))
                        )
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Outlined.Shield, null, tint = accent, modifier = Modifier.size(16.dp))
                            Text(
                                "Never expire",
                                color = C.t2,
                                fontSize = if (compact) 12.sp else 13.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FieldLabel(
    text: String,
    uppercase: Boolean = false,
    mono: Boolean = false,
    color: Color = C.t3,
    fontSize: TextUnit = 10.sp
) {
    val style = TextStyle(
        color = color,
        fontSize = fontSize,
        fontWeight = FontWeight.Bold,
        fontFamily = if (mono) FontFamily.Monospace else FontFamily.SansSerif,
        letterSpacing = 0.1.sp
    )
    Text(
        text = if (uppercase) text.uppercase() else text,
        style = style,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun ManualSectionCard(
    title: String,
    subtitle: String,
    accent: Color,
    icon: ImageVector = Icons.Outlined.Tune,
    highlighted: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val borderColor by animateColorAsState(
        if (highlighted) accent.copy(alpha = 0.26f) else C.border.copy(alpha = 0.9f),
        label = "console_section_border"
    )
    val iconBg by animateColorAsState(
        if (highlighted) accent.copy(alpha = 0.12f) else C.surface,
        label = "console_section_icon_bg"
    )
    val accentWidth by animateDpAsState(
        if (highlighted) 24.dp else 16.dp,
        label = "console_section_accent"
    )
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = C.card.copy(alpha = 0.97f),
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = {
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(iconBg)
                            .border(1.dp, accent.copy(alpha = 0.18f), RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, null, tint = accent, modifier = Modifier.size(18.dp))
                    }
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(title, color = C.t1, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(subtitle, color = C.t2, fontSize = 12.sp, lineHeight = 18.sp)
                    }
                    Box(
                        Modifier
                            .width(accentWidth)
                            .height(4.dp)
                            .padding(top = 8.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(accent.copy(alpha = 0.9f))
                    )
                }
                Divider(color = C.w08)
                content()
            }
        )
    }
}

@Composable
private fun ManualHeroCard(
    dispatchReady: Boolean,
    bannerState: String?,
    enabledOfferCount: Int,
    directoryCount: Int,
    historyCount: Int,
    smsSearchLoading: Boolean
) {
    val statusColor = when {
        bannerState == "failed" -> C.red
        bannerState == "success" -> C.green
        bannerState == "pending" -> C.amber
        bannerState == "relayed" -> C.blue
        dispatchReady -> C.green
        smsSearchLoading -> C.cyan
        else -> C.cyan
    }
    val statusLabel = when {
        bannerState == "pending" -> "Dispatch in progress"
        bannerState == "success" -> "Last dispatch completed successfully"
        bannerState == "failed" -> "Last dispatch failed"
        bannerState == "relayed" -> "Request forwarded to relay"
        dispatchReady -> "Ready to execute dispatch"
        smsSearchLoading -> "Refreshing smart match directory"
        else -> "Enter customer details to prepare dispatch"
    }
    Surface(
        color = C.cardHi.copy(alpha = 0.9f),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.22f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            statusColor.copy(alpha = 0.14f),
                            C.cardHi.copy(alpha = 0.98f),
                            C.surface.copy(alpha = 0.96f)
                        )
                    )
                )
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = statusColor.copy(alpha = 0.10f),
                        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.18f))
                    ) {
                        Text(
                            "CONSOLE READY",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            color = statusColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.sp
                        )
                    }
                    Text("Ready to dispatch", color = C.t1, fontSize = 24.sp, fontWeight = FontWeight.Black)
                    Text(
                        "Simple manual dispatch with quick customer matching and clear status feedback.",
                        color = C.t2,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(statusColor.copy(alpha = 0.24f), statusColor.copy(alpha = 0.08f))
                            )
                        )
                        .border(1.dp, statusColor.copy(alpha = 0.24f), RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.Tune, null, tint = statusColor, modifier = Modifier.size(20.dp))
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ManualQuickStat("Enabled offers", enabledOfferCount.toString(), C.cyan)
                ManualQuickStat("Directory", directoryCount.toString(), C.green)
                ManualQuickStat("History", historyCount.toString(), C.blue)
            }
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = statusColor.copy(alpha = 0.10f),
                border = BorderStroke(1.dp, statusColor.copy(alpha = 0.20f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("STATUS", color = statusColor.copy(alpha = 0.72f), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text(statusLabel, color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ManualQuickStat(label: String, value: String, accent: Color) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = C.surface.copy(alpha = 0.88f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.20f))
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.verticalGradient(
                        listOf(accent.copy(alpha = 0.12f), Color.Transparent)
                    )
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(value, color = accent, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
            Text(label.uppercase(), color = C.t3, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        }
    }
}

@Composable
private fun RowScope.ManualTabChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val bg by animateColorAsState(
        if (selected) C.cardHi else Color.Transparent,
        label = "console_tab_bg"
    )
    val fg by animateColorAsState(
        if (selected) C.t1 else C.t2,
        label = "console_tab_fg"
    )
    val border by animateColorAsState(
        if (selected) C.borderHi.copy(alpha = 0.65f) else Color.Transparent,
        label = "console_tab_border"
    )
    Box(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(18.dp))
            .heightIn(min = 52.dp)
            .background(
                if (selected) {
                    Brush.linearGradient(
                        listOf(bg, bg)
                    )
                } else {
                    Brush.linearGradient(listOf(bg, bg))
                }
            )
            .border(1.dp, border, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text,
                color = fg,
                fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                fontSize = 15.sp
            )
        }
    }
}

@Composable
private fun ManualHistoryPreviewCard(
    tx: Transaction,
    onClick: () -> Unit
) {
    val statusColor = when (tx.status) {
        TransactionStatus.SUCCESS.value -> C.green
        TransactionStatus.FAILED.value, TransactionStatus.CANCELLED.value -> C.red
        TransactionStatus.PROCESSING.value, TransactionStatus.PENDING.value, TransactionStatus.RETRYING.value -> C.amber
        else -> C.t2
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = C.card.copy(alpha = 0.95f),
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.22f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        tx.clientName.ifBlank { "Unknown Customer" },
                        color = C.t1,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        tx.phoneNumber.ifBlank { "Phone not available" },
                        color = C.t2,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        tx.amount,
                        color = C.t1,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = statusColor.copy(alpha = 0.12f)
                    ) {
                        Text(
                            tx.status,
                            color = statusColor,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    tx.description.ifBlank { "Transaction" },
                    color = C.cyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    tx.date,
                    color = C.t3,
                    fontSize = 10.sp,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
fun FeedbackBanner(msg: String, color: Color) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = color.copy(alpha = 0.10f), border = BorderStroke(1.dp, color.copy(alpha = 0.3f))) {
        Row(Modifier.padding(12.dp)) {
            Icon(Icons.Filled.CheckCircle, null, tint = color, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(msg, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun PillBadge(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.10f)) {
        Text(text, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp), color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun MiniTag(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.10f)) {
        Text(text, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp), color = color, fontSize = 8.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SettingsGroup(title: String, accent: Color = C.amber, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .shadow(14.dp, RoundedCornerShape(30.dp), clip = false)
            .clip(RoundedCornerShape(30.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        Color(0xFF21211C),
                        Color(0xFF14181A),
                        Color(0xFF0F1418)
                    )
                )
            )
            .border(1.dp, accent.copy(alpha = 0.24f), RoundedCornerShape(30.dp))
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text(
                title.uppercase(),
                color = accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.6.sp
            )
        }
        Divider(color = accent.copy(alpha = 0.18f), thickness = 0.8.dp)
        content()
    }
}

@Composable
fun GroupDivider(accent: Color = C.amber) = Divider(
    color = accent.copy(alpha = 0.16f),
    thickness = 0.8.dp,
    modifier = Modifier.padding(horizontal = 20.dp)
)

@Composable
fun ToggleRow(icon: ImageVector, title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingsRowIcon(icon)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = C.t1, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(sub, color = C.t2, fontSize = 12.sp, lineHeight = 18.sp)
        }
        ToggleSwitch(checked, onChange)
    }
}

@Composable
private fun AutoFitSingleLineText(
    text: String,
    color: Color,
    fontWeight: FontWeight,
    maxFontSize: TextUnit,
    minFontSize: TextUnit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier) {
        val textMeasurer = rememberTextMeasurer()
        val maxWidthPx = with(LocalDensity.current) { maxWidth.roundToPx() }.coerceAtLeast(0)
        val fontSize = remember(text, maxWidthPx) {
            var size = maxFontSize
            while (size.value > minFontSize.value) {
                val layout = textMeasurer.measure(
                    text = AnnotatedString(text),
                    style = TextStyle(
                        color = color,
                        fontSize = size,
                        fontWeight = fontWeight
                    ),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    constraints = Constraints(maxWidth = maxWidthPx)
                )
                if (!layout.didOverflowWidth) break
                size = (size.value - 1f).sp
            }
            size
        }
        Text(
            text,
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip
        )
    }
}

@Composable
fun LinkRow(icon: ImageVector, title: String, sub: String, color: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF1A1F21))
                .border(1.dp, color.copy(alpha = 0.32f), RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AutoFitSingleLineText(
                text = title,
                color = C.t1,
                fontWeight = FontWeight.Bold,
                maxFontSize = 18.sp,
                minFontSize = 14.sp
            )
            Text(
                sub,
                color = C.t2,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            Icons.Filled.ChevronRight,
            null,
            tint = color,
            modifier = Modifier
                .size(22.dp)
        )
    }
}

@Composable
fun LimitRow(label: String, value: Int, onValueChange: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = C.t2, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(12.dp))
        OutlinedTextField(
            value = value.toString(), onValueChange = { onValueChange(it.toIntOrNull() ?: value) },
            modifier = Modifier.width(96.dp), shape = RoundedCornerShape(14.dp),
            colors = fieldColors(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
    }
}

@Composable
fun SimPickerRow(title: String, sub: String, sims: List<SubscriptionInfo>, current: Int, onSelect: (Int) -> Unit) {
    var exp by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingsRowIcon(Icons.Rounded.SimCard)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = C.t1, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                if (current == -1) "Default SIM" else sims.find { it.subscriptionId == current }?.displayName?.toString() ?: "SIM $current",
                color = C.t2, fontSize = 12.sp, lineHeight = 17.sp
            )
        }
        Box {
            TextButton(
                onClick = { exp = true },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("Change", color = C.cyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            DropdownMenu(expanded = exp, onDismissRequest = { exp = false }, modifier = Modifier.background(C.cardHi, RoundedCornerShape(12.dp)).border(1.dp, C.border, RoundedCornerShape(12.dp))) {
                DropdownMenuItem(text = { Text("Default", color = C.t1) }, onClick = { onSelect(-1); exp = false })
                sims.forEach { s ->
                    DropdownMenuItem(text = { Text("${s.displayName} · Slot ${s.simSlotIndex + 1}", color = C.t1) }, onClick = { onSelect(s.subscriptionId); exp = false })
                }
            }
        }
    }
}

@Composable
fun UssdSimPickerRow(title: String, sims: List<SubscriptionInfo>, current: Int, onSelect: (Int) -> Unit) {
    var exp by remember { mutableStateOf(false) }
    val normalized = remember(current, sims) { normalizeUssdSimSelection(current, sims) }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingsRowIcon(Icons.Rounded.SimCard)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = C.t1, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                describeUssdSimSelection(normalized, sims),
                color = C.t2,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }
        Box {
            TextButton(
                onClick = { exp = true },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("Change", color = C.cyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            DropdownMenu(
                expanded = exp,
                onDismissRequest = { exp = false },
                modifier = Modifier
                    .background(C.cardHi, RoundedCornerShape(12.dp))
                    .border(1.dp, C.border, RoundedCornerShape(12.dp))
            ) {
                DropdownMenuItem(
                    text = { Text("Slot 1 (Default)", color = C.t1) },
                    onClick = {
                        onSelect(USSD_SIM_SELECTION_SLOT_1)
                        exp = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("Slot 2", color = C.t1) },
                    onClick = {
                        onSelect(USSD_SIM_SELECTION_SLOT_2)
                        exp = false
                    }
                )
            }
        }
    }
}

@Composable
fun SettingsRowIcon(icon: ImageVector, tint: Color = C.t1) {
    Box(
        Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(C.surface.copy(alpha = 0.95f))
            .border(1.dp, C.border.copy(alpha = 0.85f), RoundedCornerShape(13.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun ToggleSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val target = if (checked) 25.dp else 3.dp
    val offset by animateDpAsState(target, animationSpec = tween(200), label = "toggle")
    Box(
        Modifier
            .width(52.dp)
            .height(30.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (checked) C.cyan.copy(alpha = 0.92f) else C.surface)
            .border(1.dp, if (checked) C.cyan.copy(alpha = 0.35f) else C.border.copy(alpha = 0.9f), RoundedCornerShape(999.dp))
            .clickable { onChange(!checked) }
    ) {
        Box(
            Modifier
                .offset(x = offset, y = 3.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(if (checked) C.bg else C.t2)
        )
    }
}

@Composable
fun TemplateEditor(label: String, value: String, accentColor: Color, hint: String, onChange: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).height(13.dp).clip(RoundedCornerShape(2.dp)).background(accentColor))
            Spacer(Modifier.width(7.dp))
            Text(label, color = C.t1, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = value, onValueChange = onChange, modifier = Modifier.fillMaxWidth(),
            minLines = 3, maxLines = 5,
            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, color = C.t1),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = accentColor, unfocusedBorderColor = C.border,
                focusedContainerColor = C.cardHi, unfocusedContainerColor = C.cardHi,
                cursorColor = accentColor, focusedTextColor = C.t1, unfocusedTextColor = C.t1
            ),
            shape = RoundedCornerShape(12.dp)
        )
        Spacer(Modifier.height(5.dp))
        Text(hint, color = C.t3, fontSize = 10.sp, letterSpacing = 0.3.sp)
    }
}

@Composable
fun TemplatePreview(template: String, accentColor: Color) {
    val preview = template
        .replace("{name}", "Mary")
        .replace("{offer}", "1GB Daily")
        .replace("{amount}", "50")
        .replace("{phone}", "0704XXXXXX")
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("PREVIEW", color = C.t3, fontSize = 9.sp, letterSpacing = 2.sp)
            Spacer(Modifier.width(6.dp))
            Surface(shape = RoundedCornerShape(50), color = accentColor.copy(alpha = 0.10f)) {
                Text("sample data", modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp), color = accentColor.copy(alpha = 0.65f), fontSize = 9.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(accentColor.copy(alpha = 0.05f))
                .border(1.dp, accentColor.copy(alpha = 0.14f), RoundedCornerShape(12.dp))
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 7.dp)) {
                    Box(Modifier.size(5.dp).clip(CircleShape).background(accentColor))
                    Spacer(Modifier.width(6.dp))
                    Text("SMS to 0704XXXXXX", color = accentColor.copy(alpha = 0.55f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(preview, color = C.t1.copy(alpha = 0.85f), fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
    }
}

@Composable
fun DialogField(label: String, value: String, onChange: (String) -> Unit, hint: String, kb: KeyboardType = KeyboardType.Text) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = C.t2, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            placeholder = { Text(hint, color = C.t3) },
            colors = dialogFieldColors(),
            keyboardOptions = KeyboardOptions(keyboardType = kb),
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium)
        )
        if (hint.isNotBlank()) {
            Text(hint, color = C.t3, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

private fun insertIntoTextFieldValue(value: TextFieldValue, insertion: String): TextFieldValue {
    val start = value.selection.start.coerceAtLeast(0)
    val end = value.selection.end.coerceAtLeast(0)
    val safeStart = start.coerceAtMost(value.text.length)
    val safeEnd = end.coerceAtMost(value.text.length)
    val replacementStart = minOf(safeStart, safeEnd)
    val replacementEnd = maxOf(safeStart, safeEnd)
    val updated = buildString(value.text.length + insertion.length) {
        append(value.text.substring(0, replacementStart))
        append(insertion)
        append(value.text.substring(replacementEnd))
    }
    val cursor = replacementStart + insertion.length
    return TextFieldValue(
        text = updated,
        selection = TextRange(cursor)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UssdCodeDialogField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("USSD", color = C.t2, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.1.sp)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = dialogFieldColors(),
            textStyle = LocalTextStyle.current.copy(
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace
            ),
            placeholder = { Text("e.g. *188*3*pn*1#", color = C.t3, fontFamily = FontFamily.Monospace, fontSize = 17.sp) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            maxLines = 1
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AssistChip(
                onClick = { onValueChange(insertIntoTextFieldValue(value, "pn")) },
                label = { Text("Insert pn", fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = C.amberDim,
                    labelColor = C.amber
                ),
                border = BorderStroke(1.dp, C.amber.copy(alpha = 0.45f))
            )
            AssistChip(
                onClick = { onValueChange(insertIntoTextFieldValue(value, "*")) },
                label = { Text("Insert *", fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = C.cardHi,
                    labelColor = C.t1
                ),
                border = BorderStroke(1.dp, C.border.copy(alpha = 0.6f))
            )
            AssistChip(
                onClick = { onValueChange(insertIntoTextFieldValue(value, "#")) },
                label = { Text("Insert #", fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = C.cardHi,
                    labelColor = C.t1
                ),
                border = BorderStroke(1.dp, C.border.copy(alpha = 0.6f))
            )
        }
        Text(
            "Use \"pn\" as a placeholder for customer number, then tap a chip to insert at cursor position.",
            color = C.t3,
            fontSize = 11.sp,
            lineHeight = 15.sp
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialogDropdown(
    label: String,
    value: String,
    opts: List<String>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.1.sp,
            color = C.t3
        )
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = onExpandedChange,
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                shape = RoundedCornerShape(12.dp),
                readOnly = true,
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                },
                colors = dialogFieldColors(),
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold)
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { onExpandedChange(false) },
                modifier = Modifier
                    .background(C.cardHi, RoundedCornerShape(12.dp))
                    .border(1.dp, C.border, RoundedCornerShape(12.dp))
            ) {
                opts.forEach { o ->
                    DropdownMenuItem(
                        text = { Text(o, color = C.t1) },
                        onClick = { onSelect(o) }
                    )
                }
            }
        }
    }
}

// ─── Mpesa Import Dialog ─────────────────────────────────────────────────
@Composable
fun MpesaImportDialog(onDismiss: () -> Unit, onImported: (List<SavedContact>) -> Unit) {
    var daysBack by remember { mutableIntStateOf(30) }
    var contacts by remember { mutableStateOf<List<SavedContact>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var search by remember { mutableStateOf("") }
    val ctx = LocalContext.current
    LaunchedEffect(daysBack) {
        loading = true
        contacts = withContext(Dispatchers.IO) { extractMpesaContacts(ctx, daysBack) }.sortedBy { it.name }
        loading = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import from M-PESA SMS") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Scan last")
                    Spacer(Modifier.width(8.dp))
                    Slider(value = daysBack.toFloat(), onValueChange = { daysBack = it.toInt() }, valueRange = 1f..90f, steps = 89)
                    Spacer(Modifier.width(8.dp))
                    Text("$daysBack days")
                }
                OutlinedTextField(value = search, onValueChange = { search = it }, placeholder = { Text("Filter…") }, modifier = Modifier.fillMaxWidth())
                if (loading) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    val filt = contacts.filter { search.isBlank() || it.name.contains(search, true) || it.phone.contains(search) }
                    if (filt.isEmpty()) Text("No contacts found.")
                    else LazyColumn(Modifier.heightIn(max = 260.dp)) {
                        items(filt, key = { it.phone }) { c ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(C.w04)
                                    .clickable { selected = if (c.phone in selected) selected - c.phone else selected + c.phone }
                                    .padding(10.dp)
                            ) {
                                Checkbox(checked = c.phone in selected, onCheckedChange = { s -> selected = if (s) selected + c.phone else selected - c.phone })
                                Text(c.name.ifBlank { c.phone })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onImported(contacts.filter { it.phone in selected }) }, enabled = selected.isNotEmpty()) {
                Text("Import ${selected.size}")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ─── Root App ────────────────────────────────────────────────────────────
@Composable
fun BingwaApp() {
    val ctx = LocalContext.current
    val tm = remember { TokenManager(ctx) }
    val unlimitedManager = remember { UnlimitedManager(ctx) }
    val appPrefs = remember { ctx.getSharedPreferences("app_settings", Context.MODE_PRIVATE) }
    var screenRoute by rememberSaveable { mutableStateOf(Screen.Home.route) }
    val screen = remember(screenRoute) {
        when (screenRoute) {
            Screen.Manual.route -> Screen.Manual
            Screen.Tokens.route -> Screen.Tokens
            Screen.Contacts.route -> Screen.Contacts
            Screen.Settings.route -> Screen.Settings
            else -> Screen.Home
        }
    }
    var tokenBal by remember { mutableIntStateOf(tm.getBalance()) }
    var airBal by remember { mutableStateOf(BalanceChecker.getLastKnownBalanceDisplay(ctx)) }
    var slot2PreviewBalance by remember { mutableStateOf<String?>(null) }
    var slot2PreviewNonce by remember { mutableIntStateOf(0) }
    var isRefreshing by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(appPrefs.safeGetBoolean("automation_enabled", true)) }
    var remainingMs by remember { mutableLongStateOf(unlimitedManager.remainingMs()) }
    val txns = remember { mutableStateListOf<Transaction>() }
    val relayCfg by RelayManager.configState.collectAsState()
    val mirroredPrimaryAirtime by RelayManager.mirroredPrimaryAirtime.collectAsState()
    val hasDualActiveSlots = hasTwoActiveSimSlots(ctx)
    val canPreviewSlot2 = hasDualActiveSlots && !(relayCfg.enabled && relayCfg.role == "RELAY")
    val defaultAirBal =
        if (relayCfg.enabled && relayCfg.role == "RELAY") mirroredPrimaryAirtime.ifBlank { airBal }
        else airBal
    val displayedAirBal = slot2PreviewBalance ?: defaultAirBal
    val toggleRunning = {
        running = !running
        appPrefs.edit().putBoolean("automation_enabled", running).apply()
        if (!running) ctx.stopService(Intent(ctx, BalanceChecker::class.java))
        else if (canUsePhoneAutomation(ctx)) ServiceLauncher.startBalanceChecker(ctx)
        vib(ctx, if (running) 140L else 120L)
    }

    LaunchedEffect(Unit) {
        loadTransactions(ctx, txns)
        remainingMs = unlimitedManager.remainingMs()
        runCatching { RelayManager.load(ctx) }
            .onFailure { Log.e("MainActivity", "Relay warm-up failed", it) }
        if (shouldAutoStartPhoneAutomation(ctx)) {
            ServiceLauncher.startBalanceChecker(ctx)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            remainingMs = unlimitedManager.remainingMs()
            delay(if (remainingMs > 0L) nextCountdownRefreshDelay(remainingMs) else 30_000L)
        }
    }

    LaunchedEffect(slot2PreviewBalance, slot2PreviewNonce) {
        if (slot2PreviewBalance == null) return@LaunchedEffect
        delay(4_000L)
        slot2PreviewBalance = null
    }

    DisposableEffect(Unit) {
        BalanceChecker.balanceResultListener = { result ->
            isRefreshing = false
            when {
                result.persistResult -> {
                    airBal = result.display
                    if (result.display.isBlank()) {
                        slot2PreviewBalance = null
                    }
                }
                result.selectionOverride == USSD_SIM_SELECTION_SLOT_2 -> {
                    if (result.display.isBlank()) {
                        slot2PreviewBalance = null
                        Toast.makeText(ctx, "Unable to read SIM / Slot 2 balance right now.", Toast.LENGTH_SHORT).show()
                    } else {
                        slot2PreviewBalance = result.display
                        slot2PreviewNonce++
                    }
                }
            }
        }
        TokenManager.tokenBalanceListener = { newTok ->
            tokenBal = newTok
            remainingMs = unlimitedManager.remainingMs()
        }
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val txId = intent.getIntExtra("txId", -1)
                when (intent.action) {
                    ACTION_TX_CREATED -> {
                        val tx = loadTransactionByIdFromPrefs(ctx, txId) ?: return
                        val idx = txns.indexOfFirst { it.id == txId }
                        if (idx >= 0) txns[idx] = tx else txns.add(0, tx)
                    }
                    "com.bingwa.mobile.TX_UPDATED" -> {
                        val idx = txns.indexOfFirst { it.id == txId }
                        val updatedTx = loadTransactionByIdFromPrefs(ctx, txId)
                        if (idx >= 0 && updatedTx != null) {
                            txns[idx] = updatedTx
                        } else if (updatedTx != null) {
                            txns.add(0, updatedTx)
                        }
                    }
                }
            }
        }
        val receiverRegistered = registerAppReceiver(ctx, receiver, android.content.IntentFilter().apply {
            addAction("com.bingwa.mobile.TX_UPDATED")
            addAction(ACTION_TX_CREATED)
        })
        onDispose {
            if (BalanceChecker.balanceResultListener != null) {
                BalanceChecker.balanceResultListener = null
            }
            if (receiverRegistered) {
                try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {}
            }
        }
    }

    DisposableEffect(ctx, relayCfg.enabled, relayCfg.role, relayCfg.method) {
        if (relayCfg.enabled && relayCfg.role == "PRIMARY" && relayCfg.method == "HOTSPOT") {
            runCatching { RelayManager.startHotspotMonitor(ctx) }
                .onFailure { Log.e("MainActivity", "Unable to start relay hotspot monitor", it) }
        } else {
            runCatching { RelayManager.stopHotspotMonitor() }
        }
        onDispose { runCatching { RelayManager.stopHotspotMonitor() } }
    }

    LaunchedEffect(screen, displayedAirBal.isBlank(), relayCfg.enabled, relayCfg.role, running) {
        val shouldAutoRefreshBlankBalance =
            screen == Screen.Home &&
                displayedAirBal.isBlank() &&
                !isRefreshing &&
                running &&
                !(relayCfg.enabled && relayCfg.role == "RELAY") &&
                shouldAutoStartPhoneAutomation(ctx)
        if (!shouldAutoRefreshBlankBalance) return@LaunchedEffect

        slot2PreviewBalance = null
        isRefreshing = true
        if (!requestBalanceCheckSafely(ctx, ignoreCooldown = true)) {
            isRefreshing = false
        }
    }

    BackHandler(enabled = screen != Screen.Home) { screenRoute = Screen.Home.route }

    Scaffold(
        containerColor = C.bg,
        bottomBar = {
            VolcanicNavBar(
                current = screen,
                running = running,
                onSelect = { screenRoute = it.route },
                onToggleRunning = toggleRunning
            )
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().background(C.bg).padding(pad)) {
            AnimatedContent(targetState = screen, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) }, label = "screen") { s ->
                val unlimitedLabel = unlimitedManager.getActivePlan()?.label?.takeIf { remainingMs > 0L }
                when (s) {
                    Screen.Home     -> HomeScreenVolcanic(
                        tokenBal = tokenBal,
                        airBal = displayedAirBal,
                        isRefreshing = isRefreshing,
                        canCheckSlot2 = canPreviewSlot2,
                        isShowingSlot2Preview = slot2PreviewBalance != null,
                        txns = txns,
                        running = running,
                        unlimitedLabel = unlimitedLabel,
                        unlimitedRemaining = unlimitedLabel?.let { formatRemainingTimeHome(remainingMs) },
                        onCheckSlot1 = {
                            slot2PreviewBalance = null
                            if (relayCfg.enabled && relayCfg.role == "RELAY") {
                                airBal = mirroredPrimaryAirtime.ifBlank { airBal }
                            } else if (!isRefreshing) {
                                isRefreshing = true
                                if (!requestBalanceCheckSafely(ctx, specialHandling = true)) isRefreshing = false
                            }
                        },
                        onCheckSlot2 = {
                            if (canPreviewSlot2 && !isRefreshing) {
                                slot2PreviewBalance = null
                                isRefreshing = true
                                if (!requestBalanceCheckSafely(
                                        context = ctx,
                                        selectionOverride = USSD_SIM_SELECTION_SLOT_2,
                                        persistResult = false,
                                        specialHandling = true
                                    )
                                ) {
                                    isRefreshing = false
                                }
                            }
                        },
                        onToggleRunning = toggleRunning
                    )
                    Screen.Manual   -> ManualScreen(txns)
                    Screen.Tokens   -> TokensScreen()
                    Screen.Contacts -> ContactsScreen()
                    Screen.Settings -> GroupedSettingsScreen()
                    else -> HomeScreenVolcanic(
                        tokenBal = tokenBal,
                        airBal = displayedAirBal,
                        isRefreshing = isRefreshing,
                        canCheckSlot2 = canPreviewSlot2,
                        isShowingSlot2Preview = slot2PreviewBalance != null,
                        txns = txns,
                        running = running,
                        unlimitedLabel = unlimitedLabel,
                        unlimitedRemaining = unlimitedLabel?.let { formatRemainingTimeHome(remainingMs) },
                        onCheckSlot1 = {
                            slot2PreviewBalance = null
                            if (relayCfg.enabled && relayCfg.role == "RELAY") {
                                airBal = mirroredPrimaryAirtime.ifBlank { airBal }
                            } else if (!isRefreshing) {
                                isRefreshing = true
                                if (!requestBalanceCheckSafely(ctx, specialHandling = true)) isRefreshing = false
                            }
                        },
                        onCheckSlot2 = {
                            if (canPreviewSlot2 && !isRefreshing) {
                                slot2PreviewBalance = null
                                isRefreshing = true
                                if (!requestBalanceCheckSafely(
                                        context = ctx,
                                        selectionOverride = USSD_SIM_SELECTION_SLOT_2,
                                        persistResult = false,
                                        specialHandling = true
                                    )
                                ) {
                                    isRefreshing = false
                                }
                            }
                        },
                        onToggleRunning = toggleRunning
                    )
                }
            }
        }
    }
}

// ─── Bottom Navigation Bar ───────────────────────────────────────────────
@Composable
private fun VolcanicNavBar(current: Screen, running: Boolean, onSelect: (Screen) -> Unit, onToggleRunning: () -> Unit) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 8.dp)
    ) {
        val veryCompact = maxWidth < 360.dp
        val compact = maxWidth < 420.dp
        val centerSlotWidth = when {
            veryCompact -> 72.dp
            compact -> 88.dp
            else -> 106.dp
        }
        val navHeight = when {
            veryCompact -> 72.dp
            compact -> 78.dp
            else -> 86.dp
        }
        val sideItemGap = when {
            veryCompact -> 2.dp
            compact -> 4.dp
            else -> 6.dp
        }
        val rowHorizontalPadding = when {
            veryCompact -> 4.dp
            compact -> 8.dp
            else -> 10.dp
        }
        val availableRowWidth = maxWidth - (rowHorizontalPadding * 2)
        val sideSectionWidth = (availableRowWidth - centerSlotWidth) / 2f
        val maxSideItemWidth = ((sideSectionWidth - sideItemGap) / 2f).coerceAtLeast(44.dp)
        val preferredSideItemWidth = when {
            veryCompact -> 50.dp
            compact -> 56.dp
            else -> 68.dp
        }
        val sideItemWidth = minOf(preferredSideItemWidth, maxSideItemWidth)
        Surface(
            shape = RoundedCornerShape(30.dp),
            color = C.surface.copy(alpha = 0.98f),
            border = BorderStroke(1.dp, C.border.copy(alpha = 0.86f)),
            shadowElevation = 14.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                C.cyan.copy(alpha = 0.06f),
                                Color.Transparent,
                                C.blue.copy(alpha = 0.05f)
                            )
                        )
                    )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(navHeight)
                        .padding(
                            horizontal = rowHorizontalPadding,
                            vertical = if (veryCompact) 6.dp else 8.dp
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(sideItemGap, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        NAV_ITEMS.take(2).forEach { item ->
                            NavBarItemButton(
                                item = item,
                                selected = current == item,
                                compact = compact,
                                veryCompact = veryCompact,
                                itemWidth = sideItemWidth,
                                onClick = { onSelect(item) }
                            )
                        }
                    }
                    Spacer(Modifier.width(centerSlotWidth))
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(sideItemGap, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        NAV_ITEMS.drop(2).forEach { item ->
                            NavBarItemButton(
                                item = item,
                                selected = current == item,
                                compact = compact,
                                veryCompact = veryCompact,
                                itemWidth = sideItemWidth,
                                onClick = { onSelect(item) }
                            )
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(
                            y = when {
                                veryCompact -> (-2).dp
                                compact -> (-6).dp
                                else -> (-8).dp
                            }
                        )
                        .width(centerSlotWidth),
                    contentAlignment = Alignment.Center
                ) {
                    StartNavButton(
                        running = running,
                        onClick = onToggleRunning,
                        compact = compact,
                        veryCompact = veryCompact
                    )
                }
            }
        }
    }
}

@Composable
private fun NavBarItemButton(
    item: Screen,
    selected: Boolean,
    compact: Boolean,
    veryCompact: Boolean,
    itemWidth: Dp,
    onClick: () -> Unit
) {
    val selectedTint = C.amber
    Column(
        modifier = Modifier
            .width(itemWidth)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(vertical = if (veryCompact) 2.dp else if (compact) 4.dp else 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (veryCompact) 2.dp else if (compact) 3.dp else 4.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = if (selected) C.amber.copy(alpha = 0.18f) else Color.Transparent,
            border = if (selected) BorderStroke(1.dp, C.amber.copy(alpha = 0.28f)) else BorderStroke(1.dp, Color.Transparent)
        ) {
            Box(
                modifier = Modifier.size(
                    width = when {
                        veryCompact -> 36.dp
                        compact -> 42.dp
                        else -> 46.dp
                    },
                    height = when {
                        veryCompact -> 30.dp
                        compact -> 36.dp
                        else -> 38.dp
                    }
                ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (selected) item.iconSel else item.icon,
                    null,
                    tint = if (selected) selectedTint else C.t3,
                    modifier = Modifier.size(if (veryCompact) 16.dp else if (compact) 18.dp else 20.dp)
                )
            }
        }
        Text(
            item.label,
            modifier = Modifier.fillMaxWidth(),
            fontSize = when {
                veryCompact -> 8.sp
                compact -> 9.sp
                else -> 10.sp
            },
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) selectedTint else C.t3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun StartNavButton(
    running: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    veryCompact: Boolean = false
) {
    val ctx = LocalContext.current
    val color = if (running) C.green else C.red
    val auraAnim = rememberInfiniteTransition(label = "start_nav_aura")
    val pulseScale by auraAnim.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(1800, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "start_nav_pulse_scale"
    )
    val pulseAlpha by auraAnim.animateFloat(
        initialValue = if (running) 0.24f else 0.16f,
        targetValue = if (running) 0.10f else 0.08f,
        animationSpec = infiniteRepeatable(tween(1800, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "start_nav_pulse_alpha"
    )
    val holdAction = {
        vib(ctx, 70L)
        Toast.makeText(
            ctx,
            if (running) "Stopping automation..." else "Starting automation...",
            Toast.LENGTH_SHORT
        ).show()
        onClick()
    }
    Box(
        modifier = modifier
            .size(
                when {
                    veryCompact -> 68.dp
                    compact -> 82.dp
                    else -> 92.dp
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(
                    when {
                        veryCompact -> 56.dp
                        compact -> 68.dp
                        else -> 78.dp
                    }
                )
                .graphicsLayer {
                    scaleX = pulseScale
                    scaleY = pulseScale
                }
                .clip(CircleShape)
                .background(color.copy(alpha = pulseAlpha))
        )
        Surface(
            shape = CircleShape,
            color = C.cardHi,
            border = BorderStroke(2.dp, color.copy(alpha = 0.88f)),
            shadowElevation = 18.dp,
            modifier = Modifier
                .size(
                    when {
                        veryCompact -> 48.dp
                        compact -> 60.dp
                        else -> 68.dp
                    }
                )
                .combinedClickable(
                    onClick = {
                        Toast.makeText(
                            ctx,
                            if (running) "Long press to stop automation" else "Long press to start automation",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onLongClick = holdAction
                )
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    color.copy(alpha = 0.34f),
                                    color.copy(alpha = 0.12f),
                                    Color.Transparent
                                )
                            )
                        )
                )
                Icon(
                    Icons.Outlined.PowerSettingsNew,
                    null,
                    tint = color,
                    modifier = Modifier.size(
                        when {
                            veryCompact -> 18.dp
                            compact -> 22.dp
                            else -> 26.dp
                        }
                    )
                )
            }
        }
    }
}

@Composable
private fun AutomationControlCard(running: Boolean, onToggle: () -> Unit) {
    val accent = if (running) C.red else C.green
    val anim = rememberInfiniteTransition(label = "ctrl_anim")
    val ringScale by anim.animateFloat(1f, 1.28f, infiniteRepeatable(tween(2200, easing = EaseInOutSine), RepeatMode.Reverse), label = "ringScale")
    val ringAlpha by anim.animateFloat(0.22f, 0.06f, infiniteRepeatable(tween(2200, easing = EaseInOutSine), RepeatMode.Reverse), label = "ringAlpha")
    val btnFg = if (accent.luminance() > 0.45f) Color.Black else Color.White

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = C.card,
        border = BorderStroke(1.2.dp, accent.copy(alpha = 0.38f)),
        shadowElevation = 8.dp
    ) {
        Box {
            Box(Modifier.matchParentSize().background(Brush.linearGradient(listOf(accent.copy(alpha = 0.08f), Color.Transparent))))
            Box(
                Modifier.align(Alignment.TopCenter).padding(top = 0.dp)
                    .fillMaxWidth(0.52f).height(1.dp)
                    .background(Brush.horizontalGradient(listOf(Color.Transparent, accent.copy(alpha = 0.55f), Color.Transparent)))
            )
            Row(
                Modifier.fillMaxWidth().clickable { onToggle() }.padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.fillMaxSize().scale(ringScale).clip(CircleShape)
                            .border(1.5.dp, accent.copy(alpha = ringAlpha), CircleShape)
                    )
                    Box(
                        Modifier.fillMaxSize().clip(CircleShape)
                            .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.95f), accent.copy(alpha = 0.55f))))
                    ) {
                        if (running) {
                            Box(Modifier.align(Alignment.Center).size(11.dp).clip(RoundedCornerShape(3.dp)).background(btnFg))
                        } else {
                            Canvas(Modifier.matchParentSize()) {
                                val w = size.width
                                val h = size.height
                                val p = Path().apply {
                                    moveTo(w * 0.42f, h * 0.33f)
                                    lineTo(w * 0.42f, h * 0.67f)
                                    lineTo(w * 0.70f, h * 0.50f)
                                    close()
                                }
                                drawPath(p, btnFg)
                            }
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (running) "Automation Running" else "Automation Paused",
                        color = C.t1,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 13.sp,
                        lineHeight = 15.sp
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (running) "Tap to stop background processing" else "Tap to start background processing",
                        color = C.t2,
                        fontSize = 10.sp,
                        lineHeight = 13.sp
                    )
                }
                Button(
                    onClick = onToggle,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.height(34.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = btnFg),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Icon(if (running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (running) "STOP" else "START", fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, letterSpacing = 1.0.sp)
                }
            }
        }
    }
}

// ─── Home Screen ──────────────────────────────────────────────────────────
@Composable
fun HomeScreenVolcanic(
    tokenBal: Int,
    airBal: String,
    isRefreshing: Boolean,
    canCheckSlot2: Boolean,
    isShowingSlot2Preview: Boolean,
    txns: MutableList<Transaction>,
    running: Boolean,
    unlimitedLabel: String?,
    unlimitedRemaining: String?,
    onCheckSlot1: () -> Unit,
    onCheckSlot2: () -> Unit,
    onToggleRunning: () -> Unit
) {
    val ctx = LocalContext.current
    var dayKey by remember { mutableIntStateOf(currentDayKey()) }
    LaunchedEffect(dayKey) {
        delay(millisUntilNextMidnight() + 250L)
        dayKey = currentDayKey()
    }
    val automatedTxns = txns
        .asSequence()
        .filter { it.showInRecent && transactionDayKey(it) == dayKey }
        .sortedByDescending { transactionTimestamp(it) }
        .toList()
    val activeExecutionTx = automatedTxns.firstOrNull { it.isLiveExecution() }
    val latestStatusTx = automatedTxns.firstOrNull()
    val sentCount = automatedTxns.size
    var pendingCount = 0
    var failedCount = 0
    var completedCount = 0
    var scheduledCount = 0
    automatedTxns.forEach { tx ->
        when (tx.statusEnum) {
            TransactionStatus.PROCESSING,
            TransactionStatus.PENDING,
            TransactionStatus.RETRYING -> {
                pendingCount++
                scheduledCount++
            }
            TransactionStatus.FAILED,
            TransactionStatus.CANCELLED -> failedCount++
            TransactionStatus.SUCCESS -> completedCount++
            else -> {
                if (tx.status.equals("UnderMaintenance", ignoreCase = true) ||
                    tx.statusEnum == TransactionStatus.RETRYING ||
                    DailyLimitPolicy.isDailyLimitHold(tx)
                ) {
                    scheduledCount++
                }
            }
        }
    }
    val completionRate = if (sentCount > 0) (completedCount * 100 / sentCount) else 0
    var selectedTxId by rememberSaveable { mutableIntStateOf(-1) }
    val selectedTx = automatedTxns.firstOrNull { it.id == selectedTxId }
    val chromeAnim = rememberInfiniteTransition(label = "home_chrome")
    val spin by chromeAnim.animateFloat(
        0f,
        360f,
        infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "spin"
    )
    val topTransactions = automatedTxns

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF0A0C0D),
                        Color(0xFF0A0C0D),
                        Color(0xFF08090A)
                    )
                )
            )
    ) {
        Box(
            Modifier
                .size(320.dp)
                .offset((-120).dp, (-60).dp)
                .background(
                    Brush.radialGradient(
                        listOf(Color(0xFFFFB454).copy(alpha = 0.08f), Color.Transparent)
                    ),
                    CircleShape
                )
        )
        Box(
            Modifier
                .size(280.dp)
                .align(Alignment.TopEnd)
                .offset(70.dp, (-20).dp)
                .background(
                    Brush.radialGradient(
                        listOf(Color(0xFF74E6D8).copy(alpha = 0.07f), Color.Transparent)
                    ),
                    CircleShape
                )
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 0.dp, end = 16.dp, bottom = 20.dp)
        ) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HomeHeroHeader(
                            running = running,
                            activeExecutionTx = activeExecutionTx,
                            latestStatusTx = latestStatusTx
                        )
                        HomeSplitBalanceCard(
                            airBal = airBal.ifBlank { "KSh --" },
                            tokenValue = if (unlimitedLabel != null) "Unlimited" else tokenBal.toString(),
                            tokenHint = unlimitedRemaining ?: "Never expire",
                            completedCount = completedCount,
                            pendingCount = pendingCount,
                            failedCount = failedCount,
                            scheduledCount = scheduledCount,
                            rate = completionRate,
                            isRefreshing = isRefreshing,
                            canCheckSlot2 = canCheckSlot2,
                            isShowingSlot2Preview = isShowingSlot2Preview,
                            spin = spin,
                            onCheckSlot1 = onCheckSlot1,
                            onCheckSlot2 = onCheckSlot2
                        )
                        HomeActivityHeading(automatedCount = automatedTxns.size)
                        HomeActivityPanel(
                            transactions = topTransactions,
                            onOpenTransaction = { selectedTxId = it.id },
                            onDeleteTransaction = { tx ->
                                txns.remove(tx)
                                saveTransactions(ctx, txns.toList())
                                if (selectedTxId == tx.id) selectedTxId = -1
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }

        if (selectedTx != null) {
            TransactionDetailDialog(
                tx = selectedTx,
                onDismiss = { selectedTxId = -1 },
                onDelete = {
                    txns.removeAll { it.id == selectedTx.id }
                    saveTransactions(ctx, txns.toList())
                    selectedTxId = -1
                },
                onRetry = { tx ->
                    val result = retryRecentTransaction(ctx, tx)
                    Toast.makeText(ctx, result.message, if (result.success) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
                    if (result.success) {
                        selectedTxId = if (result.newTxId >= 0) result.newTxId else -1
                    }
                }
            )
        }
    }
}

@Composable
private fun HomeHardwareStrip(
    timeLabel: String,
    batteryPercent: Int?,
    modifier: Modifier = Modifier
) {
    val stripBg = Color(0xFF15191B)
    val lineSoft = Color(0xFF262D2F)
    val textDim = Color(0xFF8A9396)
    val textDimmer = Color(0xFF5B6366)
    val amber = Color(0xFFFFB454)
    val cyan = Color(0xFF74E6D8)
    val ledAnim = rememberInfiniteTransition(label = "home_led")
    val amberAlpha by ledAnim.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(1200, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "home_led_alpha"
    )

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .width(116.dp)
                .height(21.dp)
                .clip(RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp))
                .background(Color(0xFF050605))
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.015f), stripBg)
                    )
                )
                .border(1.dp, lineSoft, RoundedCornerShape(24.dp))
                .padding(start = 22.dp, top = 15.dp, end = 22.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(cyan).shadow(4.dp, CircleShape))
                Box(
                    Modifier
                        .size(6.dp)
                        .graphicsLayer { alpha = amberAlpha }
                        .clip(CircleShape)
                        .background(amber)
                )
                Box(Modifier.size(6.dp).clip(CircleShape).background(textDimmer))
                Text(
                    "AGENT 042",
                    color = textDimmer,
                    fontSize = 11.sp,
                    letterSpacing = 0.5.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    timeLabel,
                    color = textDim,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
                HomeSignalBars(tint = textDim)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .border(1.dp, textDimmer, RoundedCornerShape(3.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        "${batteryPercent ?: 28}%",
                        color = textDim,
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeSignalBars(tint: Color) {
    Row(
        modifier = Modifier.height(14.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        listOf(7.dp, 11.dp, 15.dp, 19.dp).forEach { height ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(height)
                    .clip(RoundedCornerShape(1.dp))
                    .background(tint)
            )
        }
    }
}

@Composable
private fun HomeHeroHeader(
    running: Boolean,
    activeExecutionTx: Transaction?,
    latestStatusTx: Transaction?
) {
    val cyan = Color(0xFF74E6D8)
    val borderColor = if (running) cyan.copy(alpha = 0.35f) else Color(0xFFFFB454).copy(alpha = 0.35f)
    val pillText = if (running) "AUTOMATION LIVE" else "AUTOMATION IDLE"
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = 0.dp, start = 10.dp, end = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Bingwa Mobile",
                color = Color(0xFFEEF2F1),
                fontSize = 25.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "USSD Automation Platform",
                color = Color(0xFF8A9396),
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, borderColor)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(cyan)
                    )
                    Text(
                        pillText,
                        color = cyan,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.0.sp
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            RelayStatusPill()
            if (activeExecutionTx != null) {
                Spacer(Modifier.height(10.dp))
                HomeExecutionBanner(
                    activeExecutionTx = activeExecutionTx,
                    latestStatusTx = latestStatusTx
                )
            }
        }
    }
}

@Composable
private fun HomeExecutionBanner(
    activeExecutionTx: Transaction?,
    latestStatusTx: Transaction?,
    modifier: Modifier = Modifier
) {
    val trackedTx = activeExecutionTx ?: latestStatusTx
    val executing = activeExecutionTx != null
    val accent = trackedTx?.let(::transactionStatusColor) ?: Color(0xFF74E6D8)
    val beamAnim = rememberInfiniteTransition(label = "home_execution_banner")
    val beamProgress by beamAnim.animateFloat(
        initialValue = -0.35f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(if (executing) 1600 else 2800, easing = LinearEasing)),
        label = "home_execution_banner_beam"
    )
    val pulseScale by beamAnim.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(1500, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "home_execution_banner_pulse_scale"
    )
    val pulseAlpha by beamAnim.animateFloat(
        initialValue = 0.10f,
        targetValue = if (executing) 0.26f else 0.16f,
        animationSpec = infiniteRepeatable(tween(1500, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "home_execution_banner_pulse_alpha"
    )
    val spin by beamAnim.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "home_execution_banner_spin"
    )
    val statusLabel = when {
        executing -> "EXECUTING NOW"
        trackedTx == null -> "READY"
        else -> transactionStatusLabel(trackedTx).uppercase(Locale.getDefault())
    }
    val headline = when {
        executing -> trackedTx?.description?.takeIf { it.isNotBlank() } ?: "Execution in progress"
        trackedTx == null -> "Automation is standing by"
        trackedTx.statusEnum == TransactionStatus.SUCCESS -> "Last execution completed"
        trackedTx.statusEnum == TransactionStatus.FAILED -> "Last execution failed"
        trackedTx.statusEnum == TransactionStatus.CANCELLED -> "Last execution was cancelled"
        trackedTx.statusEnum == TransactionStatus.RETRYING -> "Execution is retrying"
        else -> "Execution update available"
    }
    val detail = when {
        executing -> buildString {
            trackedTx?.clientName?.takeIf { it.isNotBlank() }?.let { append(it) }
            trackedTx?.phoneNumber?.takeIf { it.isNotBlank() }?.let {
                if (isNotEmpty()) append(" • ")
                append(it)
            }
            if (isEmpty()) append("Please wait while the transaction finishes.")
        }
        trackedTx == null -> "Live progress and final status appear here after automation starts."
        trackedTx.statusEnum == TransactionStatus.SUCCESS -> {
            val duration = transactionExecutionDuration(trackedTx).takeIf {
                it.isNotBlank() && !it.equals("Not recorded", ignoreCase = true)
            }
            duration?.let { "Completed in $it" }
                ?: transactionCompletionTime(trackedTx).takeIf {
                    it.isNotBlank() && !it.equals("Completed time not recorded", ignoreCase = true)
                }
                ?: "Completed successfully."
        }
        trackedTx.statusEnum == TransactionStatus.FAILED -> {
            transactionReasonShort(trackedTx).takeIf { it.isNotBlank() }
                ?: "Open the transaction to view the failure details."
        }
        trackedTx.statusEnum == TransactionStatus.CANCELLED -> "Execution stopped before completion."
        trackedTx.statusEnum == TransactionStatus.RETRYING -> "The app is preparing another execution attempt."
        else -> "Open recent activity for the full execution details."
    }
    val icon = when {
        executing -> Icons.Outlined.Sync
        trackedTx == null -> Icons.Outlined.Bolt
        trackedTx.statusEnum == TransactionStatus.SUCCESS -> Icons.Rounded.CheckCircle
        trackedTx.statusEnum == TransactionStatus.FAILED -> Icons.Rounded.ErrorOutline
        trackedTx.statusEnum == TransactionStatus.CANCELLED -> Icons.Rounded.Block
        trackedTx.statusEnum == TransactionStatus.RETRYING -> Icons.Outlined.Autorenew
        else -> Icons.Outlined.Schedule
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF0D1113).copy(alpha = 0.92f),
        border = BorderStroke(1.dp, accent.copy(alpha = if (executing) 0.34f else 0.22f)),
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val beamWidth = size.width * if (executing) 0.28f else 0.18f
                val startX = (size.width * beamProgress) - beamWidth
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            accent.copy(alpha = if (executing) 0.22f else 0.10f),
                            Color.Transparent
                        ),
                        startX = startX,
                        endX = startX + beamWidth
                    ),
                    cornerRadius = CornerRadius(24f, 24f)
                )
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier.size(42.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            scaleX = pulseScale
                            scaleY = pulseScale
                            alpha = pulseAlpha
                        }
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.16f))
                )
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.18f))
                        .border(1.dp, accent.copy(alpha = 0.24f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier
                            .size(18.dp)
                            .then(if (executing) Modifier.graphicsLayer { rotationZ = spin } else Modifier)
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = accent.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, accent.copy(alpha = 0.18f))
                    ) {
                        Text(
                            text = statusLabel,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                            color = accent,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.9.sp
                        )
                    }
                    if (executing) {
                        HomeExecutionDots(accent = accent)
                    }
                }
                Text(
                    text = headline,
                    color = Color(0xFFF1F4F5),
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = detail,
                    color = Color(0xFF8A9396),
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun HomeExecutionDots(accent: Color, modifier: Modifier = Modifier) {
    val dotsAnim = rememberInfiniteTransition(label = "home_execution_dots")
    val offset by dotsAnim.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "home_execution_dots_offset"
    )

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            val anchor = when (index) {
                0 -> 0.15f
                1 -> 0.50f
                else -> 0.85f
            }
            val alpha = 0.35f + ((1f - kotlin.math.abs(offset - anchor) / 0.35f).coerceIn(0f, 1f) * 0.65f)
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = alpha))
            )
        }
    }
}

@Composable
private fun HomeSplitBalanceCard(
    airBal: String,
    tokenValue: String,
    tokenHint: String,
    completedCount: Int,
    pendingCount: Int,
    failedCount: Int,
    scheduledCount: Int,
    rate: Int,
    isRefreshing: Boolean,
    canCheckSlot2: Boolean,
    isShowingSlot2Preview: Boolean,
    spin: Float,
    onCheckSlot1: () -> Unit,
    onCheckSlot2: () -> Unit
) {
    val amber = Color(0xFFFFB454)
    val cyan = Color(0xFF74E6D8)
    val mint = Color(0xFF62E2AE)
    val coral = Color(0xFFFF8A8A)
    val completedAccent = Color(0xFFB79BFF)
    val text = Color(0xFFEEF2F1)
    val textDim = Color(0xFF8A9396)
    val textDimmer = Color(0xFF5B6366)
    val cardBg = Color(0xFF1C2123)
    val line = Color(0xFF333B3E)
    val lineSoft = Color(0xFF262D2F)
    var sim2TapCount by remember { mutableIntStateOf(0) }
    var sim2TapResets by remember { mutableIntStateOf(0) }

    LaunchedEffect(sim2TapResets, isRefreshing, isShowingSlot2Preview) {
        if (sim2TapCount > 0 && !isRefreshing && !isShowingSlot2Preview) {
            delay(5000L)
            sim2TapCount = 0
        }
    }

    val balanceHelperText = when {
        isRefreshing -> "Refreshing..."
        isShowingSlot2Preview -> "SIM 2 balance — tap to refresh SIM 1"
        canCheckSlot2 && sim2TapCount >= 2 -> "Tap 1 more time for SIM 2 balance"
        canCheckSlot2 && sim2TapCount >= 1 -> "Tap 2 more times for SIM 2 balance"
        canCheckSlot2 -> "Tap to refresh"
        else -> "Tap to refresh"
    }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        amber.copy(alpha = 0.10f),
                        cyan.copy(alpha = 0.07f),
                        cardBg
                    )
                )
            )
            .border(1.dp, lineSoft, RoundedCornerShape(18.dp))
            .padding(horizontal = 13.dp, vertical = 10.dp)
            .clickable(enabled = !isRefreshing) {
                if (canCheckSlot2 && !isShowingSlot2Preview) {
                    sim2TapCount++
                    if (sim2TapCount >= 3) {
                        sim2TapCount = 0
                        sim2TapResets++
                        onCheckSlot2()
                    } else {
                        sim2TapResets++
                        onCheckSlot1()
                    }
                } else {
                    onCheckSlot1()
                }
            }
    ) {
        val compact = maxWidth < 380.dp
        val airtimeFontSize = balanceValueFontSize(
            airBal,
            if (compact) 20.sp else 22.sp,
            if (compact) 17.sp else 19.sp,
            if (compact) 14.sp else 16.sp,
            if (compact) 12.sp else 14.sp
        )
        val tokenValueFontSize = balanceValueFontSize(
            tokenValue,
            if (compact) 22.sp else 24.sp,
            if (compact) 18.sp else 20.sp,
            if (compact) 16.sp else 18.sp,
            if (compact) 14.sp else 16.sp
        )
        val tokenHintFontSize = balanceCaptionFontSize(
            tokenHint,
            if (compact) 9.sp else 9.5.sp,
            if (compact) 8.5.sp else 9.sp,
            if (compact) 8.sp else 8.5.sp
        )
        val helperFontSize = balanceCaptionFontSize(
            balanceHelperText,
            if (compact) 9.sp else 9.5.sp,
            if (compact) 8.5.sp else 8.5.sp,
            if (compact) 7.sp else 7.5.sp
        )
        val statsSpacing = if (compact) 5.dp else 7.dp

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(Modifier.size(5.dp).clip(CircleShape).background(amber))
                        Text(
                            "AIRTIME BALANCE",
                            color = textDimmer,
                            fontSize = 9.5.sp,
                            letterSpacing = 1.2.sp,
                            maxLines = 1
                        )
                         Spacer(Modifier.weight(1f))
                     }
                     Text(
                        airBal,
                        color = text,
                        fontSize = airtimeFontSize,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                         fontFamily = FontFamily.Monospace
                     )
                      Spacer(Modifier.height(7.dp))
                      Text(
                          text = balanceHelperText,
                          color = textDimmer,
                          fontSize = helperFontSize,
                          maxLines = 1,
                          overflow = TextOverflow.Ellipsis
                      )
                      Spacer(Modifier.height(7.dp))
                }
                Box(
                    modifier = Modifier
                        .padding(horizontal = if (compact) 10.dp else 12.dp)
                        .width(1.dp)
                        .height(if (compact) 62.dp else 68.dp)
                        .background(line)
                )
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        "TOKENS",
                        color = textDimmer,
                        fontSize = 9.5.sp,
                        letterSpacing = 1.2.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                    Text(
                        tokenValue,
                        color = text,
                        fontSize = tokenValueFontSize,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        tokenHint,
                        color = textDimmer,
                        fontSize = tokenHintFontSize,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                    HomeSparkLine(compact = compact)
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(line)
            )
            HomeStatsRow(
                completed = completedCount,
                pending = pendingCount,
                failed = failedCount,
                scheduled = scheduledCount,
                rate = rate,
                compact = compact,
                spacing = statsSpacing,
                sentAccent = mint,
                pendingAccent = amber,
                failedAccent = coral,
                completedAccent = completedAccent,
                scheduledAccent = cyan
            )
        }
    }
}

@Composable
private fun HomeBalanceActionChip(
    label: String,
    tint: Color,
    enabled: Boolean,
    highlighted: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(999.dp),
        color = if (highlighted) tint.copy(alpha = 0.18f) else Color(0xFF202628),
        border = BorderStroke(1.dp, tint.copy(alpha = if (highlighted) 0.52f else 0.28f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = if (enabled) 1f else 0.55f))
            )
            if (label.isNotBlank()) {
                Text(
                    text = label,
                    color = if (enabled) Color(0xFFEEF2F1) else Color(0xFF8A9396),
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun HomeSparkLine(compact: Boolean) {
    Canvas(modifier = Modifier.size(width = if (compact) 52.dp else 56.dp, height = if (compact) 14.dp else 16.dp)) {
        val path = Path().apply {
            moveTo(size.width * 0.02f, size.height * 0.72f)
            cubicTo(
                size.width * 0.12f,
                size.height * 0.86f,
                size.width * 0.24f,
                size.height * 0.10f,
                size.width * 0.36f,
                size.height * 0.33f
            )
            cubicTo(
                size.width * 0.48f,
                size.height * 0.52f,
                size.width * 0.62f,
                size.height * 0.84f,
                size.width * 0.76f,
                size.height * 0.38f
            )
            cubicTo(
                size.width * 0.84f,
                size.height * 0.12f,
                size.width * 0.91f,
                size.height * 0.03f,
                size.width * 0.98f,
                size.height * 0.12f
            )
        }
        drawPath(
            path = path,
            color = Color(0xFF74E6D8),
            style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
        )
        drawCircle(
            color = Color(0xFF74E6D8),
            radius = 2.dp.toPx(),
            center = Offset(size.width * 0.98f, size.height * 0.12f)
        )
    }
}

@Composable
private fun HomeActivityHeading(automatedCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(22.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFFFFB454), Color(0xFF74E6D8))
                        )
                    )
            )
            Text(
                "Recent Activity",
                color = Color(0xFFEEF2F1),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = Color(0xFF1C2123),
            border = BorderStroke(1.dp, Color(0xFF333B3E))
        ) {
            Text(
                "$automatedCount automated",
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                color = Color(0xFF8A9396),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun HomeActivityPanel(
    transactions: List<Transaction>,
    onOpenTransaction: (Transaction) -> Unit,
    onDeleteTransaction: (Transaction) -> Unit,
    modifier: Modifier = Modifier
) {
    if (transactions.isEmpty()) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(26.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFF1B2225),
                            Color(0xFF141A1C)
                        )
                    )
                )
                .border(1.dp, Color(0xFF2A3235).copy(alpha = 0.92f), RoundedCornerShape(26.dp))
                .heightIn(min = 198.dp)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            HomeScanningEmptyState(Modifier.fillMaxSize())
        }
    } else {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            transactions.forEach { tx ->
                HomeDispatchRow(
                    tx = tx,
                    onOpen = { onOpenTransaction(tx) },
                    onDelete = { onDeleteTransaction(tx) }
                )
            }
        }
    }
}

@Composable
private fun HomeScanningEmptyState(modifier: Modifier = Modifier) {
    val anim = rememberInfiniteTransition(label = "scan_panel")
    val ring1 by anim.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3000, easing = LinearEasing)),
        label = "scan_ring_1"
    )
    val ring2 by anim.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(3000, easing = LinearEasing),
            initialStartOffset = StartOffset(1000)
        ),
        label = "scan_ring_2"
    )
    val ring3 by anim.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(3000, easing = LinearEasing),
            initialStartOffset = StartOffset(2000)
        ),
        label = "scan_ring_3"
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(104.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(188.dp, 104.dp),
                contentAlignment = Alignment.Center
            ) {
                listOf(
                    Triple((-84).dp, 26.dp, 5.dp),
                    Triple((-58).dp, 62.dp, 3.dp),
                    Triple((54).dp, 60.dp, 3.dp),
                    Triple((-28).dp, 92.dp, 4.dp),
                    Triple((82).dp, 18.dp, 3.dp)
                ).forEach { (x, y, s) ->
                    Box(
                        modifier = Modifier
                            .offset(x = x, y = y)
                            .size(s)
                            .clip(CircleShape)
                            .background(Color(0xFF74E6D8).copy(alpha = 0.22f))
                    )
                }

                Box(
                    modifier = Modifier
                        .offset(y = (-6).dp)
                        .size(180.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    Color(0xFF74E6D8).copy(alpha = 0.08f),
                                    Color.Transparent
                                )
                            )
                        )
                )
                HomeSonarRing(progress = ring1)
                HomeSonarRing(progress = ring2)
                HomeSonarRing(progress = ring3)
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF74E6D8))
                        .shadow(10.dp, CircleShape)
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            Color(0xFF74E6D8).copy(alpha = 0.30f),
                            Color.Transparent
                        )
                    )
                )
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Scanning for activity",
            color = Color(0xFFEEF2F1),
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.1.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(5.dp))
        Text(
            "Transactions appear here after automation starts",
            color = Color(0xFF5B6366),
            fontSize = 11.5.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.4.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun HomeSonarRing(progress: Float) {
    val minSize = 24.dp
    val maxSize = 140.dp
    val minScale = minSize.value / maxSize.value
    val scale = minScale + ((1f - minScale) * progress)
    Box(
        modifier = Modifier
            .size(maxSize)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .border(
                width = if (progress < 0.08f) 2.dp else 1.dp,
                color = Color(0xFF74E6D8).copy(alpha = (0.72f - (progress * 0.72f)).coerceAtLeast(0f)),
                shape = CircleShape
            )
    )
}

@Composable
private fun HomeDispatchRow(
    tx: Transaction,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    val liveExecution = tx.isLiveExecution()
    val statusColor = transactionStatusColor(tx)
    val titleColor = Color(0xFFF2F6F7)
    val phoneColor = Color(0xFF8B979B)
    val timeColor = Color(0xFFC9D4DB)
    val metaDotColor = Color(0xFF647279)
    val title = tx.clientName.ifBlank { tx.description.ifBlank { "Recent automation" } }
    val phone = tx.phoneNumber.ifBlank { "Phone not available" }
    val avatarLabel = recentActivityInitials(title)
    val amountLabel = recentActivityAmountLabel(tx.amount)
    val timeLabel = recentActivityTimeLabel(tx)
    val relativeLabel = recentActivityRelativeLabel(tx)
    val rowAnim = rememberInfiniteTransition(label = "home_dispatch_row")
    val liveBeam by rowAnim.animateFloat(
        initialValue = -0.30f,
        targetValue = 1.10f,
        animationSpec = infiniteRepeatable(tween(if (liveExecution) 1500 else 3000, easing = LinearEasing)),
        label = "home_dispatch_row_beam"
    )
    val liveAvatarScale by rowAnim.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(tween(1500, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "home_dispatch_row_avatar"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(5.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(999.dp))
                .background(statusColor.copy(alpha = 0.82f))
        )
        Surface(
            color = Color(0xFF090B0C),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(
                1.dp,
                if (liveExecution) statusColor.copy(alpha = 0.28f) else Color(0xFF152024).copy(alpha = 0.62f)
            ),
            modifier = Modifier
                .weight(1f)
                .drawBehind {
                    if (!liveExecution) return@drawBehind
                    val beamWidth = size.width * 0.24f
                    val startX = (size.width * liveBeam) - beamWidth
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            listOf(
                                Color.Transparent,
                                statusColor.copy(alpha = 0.18f),
                                Color.Transparent
                            ),
                            startX = startX,
                            endX = startX + beamWidth
                        ),
                        cornerRadius = CornerRadius(28f, 28f)
                    )
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpen)
                    .padding(horizontal = 16.dp, vertical = 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .graphicsLayer {
                                scaleX = if (liveExecution) liveAvatarScale else 1f
                                scaleY = if (liveExecution) liveAvatarScale else 1f
                            }
                            .clip(CircleShape)
                            .background(Color(0xFF0D1113))
                            .border(
                                1.dp,
                                statusColor.copy(alpha = if (liveExecution) 0.30f else 0.18f),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            avatarLabel,
                            color = Color(0xFFB8C0C3),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            title,
                            color = titleColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = 18.sp
                        )
                        Text(
                            phone,
                            color = phoneColor,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 15.sp
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        amountLabel,
                        color = Color(0xFFE8ECEE),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )
                    IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Outlined.Delete,
                            null,
                            tint = Color(0xFF667074),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.Schedule,
                        null,
                        tint = timeColor,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        timeLabel,
                        color = timeColor,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )
                    if (relativeLabel.isNotBlank()) {
                        Text(
                            "•",
                            color = metaDotColor,
                            fontSize = 11.sp
                        )
                        Text(
                            relativeLabel,
                            color = statusColor.copy(alpha = 0.96f),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

private fun recentActivityInitials(title: String): String {
    val parts = title.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    return when {
        parts.size >= 2 -> "${parts[0].first()}${parts[1].first()}".uppercase(Locale.getDefault())
        parts.isNotEmpty() -> parts[0].take(2).uppercase(Locale.getDefault())
        else -> "TX"
    }
}

private fun recentActivityAmountLabel(amount: String): String {
    val normalized = amount.trim().removePrefix("+").removePrefix("-").trim()
    if (normalized.isBlank()) return "KSh -"
    return when {
        normalized.startsWith("ksh", ignoreCase = true) -> "KSh " + normalized.substring(3).trim()
        normalized.startsWith("kes", ignoreCase = true) -> "KSh " + normalized.substring(3).trim()
        normalized.firstOrNull()?.isDigit() == true -> "KSh $normalized"
        else -> normalized
    }
}

private fun recentActivityTimeLabel(tx: Transaction): String {
    val timestamp = transactionTimestamp(tx)
    if (timestamp <= 0L) return tx.date.ifBlank { "--:--" }
    return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestamp)).uppercase(Locale.getDefault())
}

private fun recentActivityRelativeLabel(tx: Transaction): String {
    val timestamp = transactionTimestamp(tx)
    if (timestamp <= 0L) return ""
    val delta = (System.currentTimeMillis() - timestamp).coerceAtLeast(0L)
    val minutes = delta / 60000L
    val hours = delta / 3600000L
    return when {
        minutes < 1L -> "Just now"
        minutes < 60L -> "${minutes}m ago"
        hours < 24L -> "${hours}h ago"
        else -> ""
    }
}

private fun recentActivityServiceIcon(serviceLabel: String): ImageVector =
    if (serviceLabel.contains("sms", ignoreCase = true) || serviceLabel.contains("message", ignoreCase = true)) {
        Icons.Outlined.Sms
    } else {
        Icons.Outlined.PhoneAndroid
    }

private fun Transaction.isLiveExecution(): Boolean =
    statusEnum == TransactionStatus.PENDING ||
        statusEnum == TransactionStatus.PROCESSING ||
        statusEnum == TransactionStatus.RETRYING

private fun transactionCompletionSummary(tx: Transaction): String = when {
    DailyLimitPolicy.isDailyLimitHold(tx) -> "Scheduled to resume when execution conditions allow."
    tx.statusEnum == TransactionStatus.SUCCESS -> {
        val duration = transactionExecutionDuration(tx).takeIf {
            it.isNotBlank() && !it.equals("Not recorded", ignoreCase = true)
        }
        duration?.let { "Completed successfully in $it." } ?: "Completed successfully."
    }
    tx.statusEnum == TransactionStatus.FAILED -> {
        transactionReasonShort(tx).takeIf { it.isNotBlank() } ?: "Execution failed. Open the transaction for details."
    }
    tx.statusEnum == TransactionStatus.CANCELLED -> "Execution was cancelled before completion."
    else -> "Tap to open the transaction for the latest execution details."
}

@Composable
private fun HomeDashboardHeader(running: Boolean) {
    val statusColor = if (running) C.green else C.red
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val headerMaxWidth = maxWidth
        val compact = headerMaxWidth < 380.dp
        val titleSize = if (compact) 30.sp else 40.sp
        val titleLineHeight = if (compact) 34.sp else 42.sp
        val subtitleSize = if (compact) 14.sp else 16.sp
        val subtitleLineHeight = if (compact) 18.sp else 20.sp
        val subtitleMaxWidth = if (headerMaxWidth > 520.dp) 360.dp else headerMaxWidth
        val badgeAnim = rememberInfiniteTransition(label = "header_badge")
        val shimmerProgress by badgeAnim.animateFloat(
            initialValue = -0.3f,
            targetValue = 1.25f,
            animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing)),
            label = "header_badge_shimmer"
        )
        val breathe by badgeAnim.animateFloat(
            initialValue = 0.92f,
            targetValue = 1.08f,
            animationSpec = infiniteRepeatable(tween(2200, easing = EaseInOutSine), RepeatMode.Reverse),
            label = "header_badge_breathe"
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    "Bingwa Mobile",
                    color = C.t1,
                    fontSize = titleSize,
                    fontWeight = FontWeight.Black,
                    lineHeight = titleLineHeight,
                    textAlign = TextAlign.Center,
                    maxLines = 2
                )
                Text(
                    "USSD Automation Platform",
                    modifier = Modifier.widthIn(max = subtitleMaxWidth),
                    color = C.t2,
                    fontSize = subtitleSize,
                    lineHeight = subtitleLineHeight,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
            }

            Surface(
                shape = RoundedCornerShape(999.dp),
                color = C.surface.copy(alpha = 0.54f),
                border = BorderStroke(1.dp, statusColor.copy(alpha = 0.34f)),
                modifier = Modifier
                    .drawBehind {
                        val shimmerWidth = size.width * 0.34f
                        val startX = (size.width * shimmerProgress) - shimmerWidth
                        drawRoundRect(
                            brush = Brush.horizontalGradient(
                                listOf(
                                    Color.Transparent,
                                    Color.White.copy(alpha = 0.05f),
                                    statusColor.copy(alpha = 0.16f),
                                    Color.Transparent
                                ),
                                startX = startX,
                                endX = startX + shimmerWidth
                            ),
                            cornerRadius = CornerRadius(size.height, size.height)
                        )
                    }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .size(14.dp)
                                .graphicsLayer {
                                    scaleX = if (running) breathe else 1f
                                    scaleY = if (running) breathe else 1f
                                }
                                .clip(CircleShape)
                                .background(statusColor.copy(alpha = 0.18f))
                        )
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                    }
                    Text(
                        if (running) "AUTOMATION LIVE" else "AUTOMATION IDLE",
                        color = statusColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.1.sp
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            RelayStatusPill()
        }
    }
}

@Composable
private fun RecentActivityHeader(automatedCount: Int, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier) {
        val compact = maxWidth < 400.dp
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(5.dp)
                        .height(if (compact) 26.dp else 30.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(C.orange, C.blue.copy(alpha = 0.92f))
                            )
                        )
                )
                Text(
                    "Recent Activity",
                    modifier = Modifier.weight(1f),
                    color = C.t1,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = if (compact) 18.sp else 20.sp
                )
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = C.surface.copy(alpha = 0.90f),
                    border = BorderStroke(1.dp, C.border.copy(alpha = 0.84f))
                ) {
                    Text(
                        text = if (automatedCount == 0) "0 automated" else "$automatedCount automated",
                        color = C.t2,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }

            RecentActivityMotionRail(compact = compact)
        }
    }
}

@Composable
private fun RecentActivityMotionRail(compact: Boolean) {
    val railAnim = rememberInfiniteTransition(label = "recent_activity_rail")
    val beaconPulse by railAnim.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(tween(2400, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "recent_activity_rail_beacon_pulse"
    )
    val beaconAlpha by railAnim.animateFloat(
        initialValue = 0.12f,
        targetValue = 0.28f,
        animationSpec = infiniteRepeatable(tween(2200, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "recent_activity_rail_beacon_alpha"
    )
    val railHeight = if (compact) 8.dp else 10.dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(railHeight)
            .clip(RoundedCornerShape(999.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(
                        C.surface.copy(alpha = 0.82f),
                        C.cardHi.copy(alpha = 0.76f),
                        C.surface.copy(alpha = 0.82f)
                    )
                )
            )
            .border(1.dp, C.border.copy(alpha = 0.48f), RoundedCornerShape(999.dp))
            .drawBehind {
                val centerY = size.height / 2f
                val startX = size.width * 0.08f
                val endX = size.width * 0.92f
                val centerX = size.width / 2f

                drawLine(
                    brush = Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            C.green.copy(alpha = 0.20f),
                            C.blue.copy(alpha = 0.12f),
                            Color.Transparent
                        ),
                        startX = startX,
                        endX = endX
                    ),
                    start = Offset(startX, centerY),
                    end = Offset(endX, centerY),
                    strokeWidth = size.height * 0.28f
                )

                drawCircle(
                    color = C.green,
                    radius = (size.height * 0.24f) * beaconPulse,
                    center = Offset(centerX, centerY)
                )
                drawCircle(
                    color = C.green.copy(alpha = beaconAlpha),
                    radius = (size.height * 0.62f) * beaconPulse,
                    center = Offset(centerX, centerY)
                )
            }
    )
}

@Composable
private fun GithubOverviewCard(
    airBal: String,
    tokenBal: Int,
    unlimitedLabel: String?,
    unlimitedRemaining: String?,
    sent: Int,
    pending: Int,
    failed: Int,
    scheduled: Int = 0,
    rate: Int,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    spin: Float
) {
    Surface(
        color = C.card,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.9f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                GithubMetricTile(
                    title = "Airtime balance",
                    value = airBal.ifBlank { "KSh --" },
                    caption = "Current balance",
                    accent = C.blue,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                GithubMetricTile(
                    title = if (unlimitedLabel != null) "Unlimited plan" else "Tokens",
                    value = unlimitedLabel ?: tokenBal.toString(),
                    caption = unlimitedRemaining ?: "Available tokens",
                    accent = if (unlimitedLabel != null) C.green else C.amber,
                    modifier = Modifier.weight(1f)
                )
            }
            OutlinedButton(
                onClick = onRefresh,
                enabled = !isRefreshing,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, C.border),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = C.surface,
                    contentColor = C.t1
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier.align(Alignment.End)
            ) {
                Icon(
                    Icons.Outlined.Refresh,
                    null,
                    modifier = Modifier
                        .size(16.dp)
                        .then(if (isRefreshing) Modifier.graphicsLayer { rotationZ = spin } else Modifier)
                )
                Spacer(Modifier.width(8.dp))
                Text(if (isRefreshing) "Refreshing" else "Refresh", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Divider(color = C.w08)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GithubStatPill("Sent", sent.toString(), C.green, Modifier.weight(1f))
                GithubStatPill("Pending", pending.toString(), C.amber, Modifier.weight(1f))
                GithubStatPill("Failed", failed.toString(), C.red, Modifier.weight(1f))
                GithubStatPill("Scheduled", scheduled.toString(), C.purple, Modifier.weight(1f))
                GithubStatPill("Rate", "$rate%", if (rate >= 70) C.green else C.amber, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun GithubMetricTile(
    title: String,
    value: String,
    caption: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val valueFontSize = balanceValueFontSize(value, 19.sp, 17.sp, 15.sp, 13.sp)
    val captionFontSize = balanceCaptionFontSize(caption, 11.sp, 10.sp, 9.sp)
    Surface(
        color = C.surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.85f)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
                Text(title, color = C.t2, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
            Text(
                value,
                color = C.t1,
                fontSize = valueFontSize,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip
            )
            Text(
                caption,
                color = C.t3,
                fontSize = captionFontSize,
                lineHeight = captionFontSize * 1.1f,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip
            )
        }
    }
}

@Composable
private fun GithubStatPill(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        color = C.surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.85f)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 11.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(value, color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(label, color = C.t3, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun GithubEmptyActivityCard() {
    Surface(
        color = C.card,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.9f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(C.greenDim)
                    .border(1.dp, C.green.copy(alpha = 0.25f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.History, null, tint = C.green, modifier = Modifier.size(22.dp))
            }
            Text(
                "Scanning for activity",
                color = C.t1,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "Transactions appear here after you start automation.",
                color = C.t2,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private data class TransactionRetryResult(
    val success: Boolean,
    val message: String,
    val newTxId: Int = -1
)

private fun transactionStatusColor(tx: Transaction): Color = when {
    DailyLimitPolicy.isDailyLimitHold(tx) -> C.cyan
    tx.statusEnum == TransactionStatus.SUCCESS -> C.green
    tx.statusEnum == TransactionStatus.FAILED || tx.statusEnum == TransactionStatus.CANCELLED -> C.red
    else -> C.amber
}

private fun transactionTypeLabel(tx: Transaction): String = when (tx.source) {
    TX_SOURCE_AUTOMATED -> "Automated"
    TX_SOURCE_MANUAL -> "Manual"
    TX_SOURCE_SMS_COMMAND -> "SMS Command"
    TX_SOURCE_AIRTIME -> "Airtime"
    else -> "Activity"
}

private fun transactionTypeColor(tx: Transaction): Color = when (tx.source) {
    TX_SOURCE_AUTOMATED -> C.green
    TX_SOURCE_MANUAL -> C.purple
    TX_SOURCE_SMS_COMMAND -> C.blue
    TX_SOURCE_AIRTIME -> C.orange
    else -> C.cyan
}

private fun transactionSummaryTime(tx: Transaction): String =
    if (tx.timestamp > 0L) SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(tx.timestamp))
    else tx.date.ifBlank { "Recent" }

private fun transactionExecutionTime(tx: Transaction): String =
    if (tx.timestamp > 0L) SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.getDefault()).format(Date(tx.timestamp))
    else tx.date.ifBlank { "Not recorded" }

private fun transactionCompletionTime(tx: Transaction): String =
    if (tx.completedAt > 0L) SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.getDefault()).format(Date(tx.completedAt))
    else when (tx.statusEnum) {
        TransactionStatus.SUCCESS,
        TransactionStatus.FAILED,
        TransactionStatus.CANCELLED -> "Completed time not recorded"
        else -> "In progress"
    }

private fun transactionExecutionDuration(tx: Transaction): String {
    val durationMs = when {
        tx.executionDurationMs > 0L -> tx.executionDurationMs
        tx.completedAt > 0L && tx.timestamp > 0L -> (tx.completedAt - tx.timestamp).coerceAtLeast(0L)
        else -> 0L
    }
    if (durationMs <= 0L) {
        return when (tx.statusEnum) {
            TransactionStatus.SUCCESS,
            TransactionStatus.FAILED,
            TransactionStatus.CANCELLED -> "Not recorded"
            else -> "Still running"
        }
    }
    return formatExecutionMs(durationMs).ifBlank { "${durationMs}ms" }
}

private fun resolveOfferForRetry(context: Context, tx: Transaction): OfferItem? {
    if (tx.offerId >= 0) {
        OfferRepository.findById(context, tx.offerId)?.let { return it }
    }
    OfferRepository.findByName(context, tx.description)?.let { return it }
    if (tx.amountValue > 0.0) {
        OfferRepository.findByPrice(context, tx.amountValue.toInt())?.let { return it }
    }
    val normalizedCode = UssdHelper.normalizeUssdCode(tx.ussdCode, tx.phoneNumber)
    return OfferRepository.load(context).firstOrNull { offer ->
        offer.enabled && UssdHelper.normalizeUssdCode(offer.ussdCode, tx.phoneNumber) == normalizedCode
    }
}

private fun retryRecentTransaction(context: Context, tx: Transaction): TransactionRetryResult {
    val phone = SmsCommandHandler.normalizePhone(tx.phoneNumber).ifBlank { tx.phoneNumber.trim() }
    if (phone.isBlank()) {
        return TransactionRetryResult(false, "Phone number is missing for this transaction.")
    }

    val matchedOffer = resolveOfferForRetry(context, tx)
    val finalCode = when {
        matchedOffer != null -> UssdHelper.normalizeUssdCode(matchedOffer.ussdCode, phone)
        tx.ussdCode.isNotBlank() -> UssdHelper.normalizeUssdCode(tx.ussdCode, phone)
        else -> ""
    }
    if (finalCode.isBlank()) {
        return TransactionRetryResult(false, "USSD code is missing, so this transaction cannot be retried.")
    }

    val retryTxId = tx.id
    val restarted = TransactionStore.restartExisting(
        context = context,
        txId = retryTxId,
        description = matchedOffer?.name ?: tx.description,
        amount = tx.amount,
        phone = phone,
        ussd = finalCode,
        clientName = tx.clientName,
        status = TransactionStatus.PROCESSING.value,
        source = tx.source,
        showInRecent = true,
        offerId = matchedOffer?.id ?: tx.offerId,
        response = "Retry started. Preparing a fresh execution attempt."
    )
    if (!restarted) {
        return TransactionRetryResult(false, "Retry could not be queued. Please try again.")
    }
    broadcastTransactionUpdated(context, retryTxId)

    context.startOfferAutomation(
        offer = matchedOffer,
        phoneNumber = phone,
        txId = retryTxId,
        finalCode = finalCode,
        mode = matchedOffer?.executionMode ?: OFFER_EXECUTION_MODE_SIMPLE,
        executionPriority = USSD_EXECUTION_PRIORITY_SPECIAL
    )
    return TransactionRetryResult(
        success = true,
        message = "Retry started for ${matchedOffer?.name ?: tx.description.ifBlank { "this transaction" }}.",
        newTxId = retryTxId
    )
}

@Composable
private fun GithubActivityCard(
    tx: Transaction,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val executionCopy = remember(
        tx.id,
        tx.status,
        tx.offerId,
        tx.ussdCode,
        tx.ussdTranscript,
        tx.ussdResponse
    ) {
        transactionExecutionCopy(context, tx)
    }
    val statusColor = transactionStatusColor(tx)
    val typeColor = transactionTypeColor(tx)
    val showLiveAnimation = isTransactionActivelyExecuting(tx)
    val initials = remember(tx.clientName, tx.phoneNumber) {
        tx.clientName
            .ifBlank { tx.phoneNumber }
            .split(" ")
            .mapNotNull { it.firstOrNull()?.uppercase() }
            .take(2)
            .joinToString("")
            .ifBlank { "TX" }
    }
    val responsePreview = tx.ussdResponse
        .ifBlank { tx.ussdTranscript.lineSequence().firstOrNull().orEmpty() }
        .ifBlank {
            when {
                showLiveAnimation -> executionCopy.detailLabel
                tx.statusEnum == TransactionStatus.FAILED -> "Tap to view full failure details."
                else -> ""
            }
        }
    val cardAnim = rememberInfiniteTransition(label = "recent_activity_card")
    val shimmer by cardAnim.animateFloat(
        initialValue = -0.4f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing)),
        label = "recent_activity_shimmer"
    )
    val avatarPulse by cardAnim.animateFloat(
        initialValue = 0.985f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(
            tween(if (showLiveAnimation) 1800 else 2800, easing = EaseInOutSine),
            RepeatMode.Reverse
        ),
        label = "recent_activity_pulse"
    )

    Surface(
        color = C.card,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.22f)),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .drawBehind {
                    val shimmerWidth = size.width * 0.30f
                    val startX = (size.width * shimmer) - shimmerWidth
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            listOf(
                                Color.Transparent,
                                statusColor.copy(alpha = if (showLiveAnimation) 0.08f else 0.04f),
                                Color.Transparent
                            ),
                            startX = startX,
                            endX = startX + shimmerWidth
                        ),
                        cornerRadius = CornerRadius(30f, 30f)
                    )
                }
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            statusColor.copy(alpha = 0.05f),
                            C.card,
                            C.surface.copy(alpha = 0.78f)
                        )
                    )
                )
                .padding(horizontal = 16.dp, vertical = 15.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .graphicsLayer {
                                scaleX = avatarPulse
                                scaleY = avatarPulse
                            }
                            .clip(RoundedCornerShape(16.dp))
                            .background(statusColor.copy(alpha = 0.12f))
                            .border(1.dp, statusColor.copy(alpha = 0.24f), RoundedCornerShape(16.dp))
                    )
                    Text(initials, color = statusColor, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                }
                Spacer(Modifier.width(12.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        tx.clientName.ifBlank { "Unknown customer" },
                        color = C.t1,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 18.sp
                    )
                    Text(
                        "Bought ${tx.description.ifBlank { "Offer not captured" }}",
                        color = C.t2,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        tx.amount.ifBlank { "-" },
                        color = C.t1,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(12.dp))
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = statusColor.copy(alpha = 0.10f),
                    border = BorderStroke(1.dp, statusColor.copy(alpha = 0.28f))
                ) {
                    Text(
                        executionCopy.statusLabel,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        color = statusColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.End
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RecentSummaryChip(
                    text = tx.phoneNumber.ifBlank { "Phone unavailable" },
                    color = C.cyan,
                    modifier = Modifier.weight(1f)
                )
                RecentSummaryChip(
                    text = transactionSummaryTime(tx),
                    color = typeColor,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RecentSummaryChip(text = transactionTypeLabel(tx), color = typeColor)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (showLiveAnimation) executionCopy.supportingLabel else "Tap for full execution details",
                    color = C.t3,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f)
                )
                if (showLiveAnimation) {
                    Icon(Icons.Outlined.Sync, null, tint = statusColor, modifier = Modifier.size(18.dp))
                } else {
                    Icon(Icons.Outlined.KeyboardArrowRight, null, tint = C.t3, modifier = Modifier.size(18.dp))
                }
            }
            if (responsePreview.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = C.surface.copy(alpha = 0.80f),
                    border = BorderStroke(1.dp, C.border.copy(alpha = 0.75f))
                ) {
                    Text(
                        responsePreview,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        color = C.t2,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDelete, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                    Text("Delete", color = C.red, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun RecentSummaryChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        color = color.copy(alpha = 0.10f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.18f)),
        modifier = modifier
    ) {
        Text(
            text = text,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun RecentTransactionDetailsDialog(
    tx: Transaction,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onRetry: (Transaction) -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val statusColor = transactionStatusColor(tx)
    val sourceColor = transactionTypeColor(tx)
    val transcriptText = tx.ussdTranscript.ifBlank {
        tx.ussdResponse.ifBlank { "No USSD transcript captured for this transaction." }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.card,
        shape = RoundedCornerShape(24.dp),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    tx.clientName.ifBlank { "Transaction details" },
                    color = C.t1,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    tx.description.ifBlank { "Offer details" },
                    color = C.t2,
                    fontSize = 13.sp
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RecentSummaryChip(text = tx.status, color = statusColor)
                    RecentSummaryChip(text = transactionTypeLabel(tx), color = sourceColor)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(tx.phoneNumber))
                            Toast.makeText(context, "Phone number copied", Toast.LENGTH_SHORT).show()
                        },
                        enabled = tx.phoneNumber.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, C.border)
                    ) {
                        Icon(Icons.Outlined.ContentCopy, null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Copy Phone")
                    }
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(tx.ussdCode))
                            Toast.makeText(context, "USSD code copied", Toast.LENGTH_SHORT).show()
                        },
                        enabled = tx.ussdCode.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, C.border)
                    ) {
                        Icon(Icons.Outlined.Dialpad, null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Copy USSD")
                    }
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(transcriptText))
                            Toast.makeText(context, "USSD transcript copied", Toast.LENGTH_SHORT).show()
                        },
                        enabled = tx.ussdTranscript.isNotBlank() || tx.ussdResponse.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, C.border)
                    ) {
                        Icon(Icons.Outlined.Article, null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Copy Transcript")
                    }
                }
                if (tx.statusEnum == TransactionStatus.FAILED || tx.statusEnum == TransactionStatus.CANCELLED) {
                    Button(
                        onClick = { onRetry(tx) },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = statusColor)
                    ) {
                        Icon(Icons.Outlined.Refresh, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Retry Failed Execution")
                    }
                }
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = C.surface.copy(alpha = 0.84f),
                    border = BorderStroke(1.dp, C.border.copy(alpha = 0.8f))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("EXECUTION OVERVIEW", color = C.t3, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                        TxDetailRow("Transaction ID", "#${tx.id}")
                        TxDetailRow("Customer name", tx.clientName.ifBlank { "Not captured" })
                        TxDetailRow("Phone number", tx.phoneNumber.ifBlank { "Not captured" })
                        TxDetailRow("Offer bought", tx.description.ifBlank { "Not captured" })
                        TxDetailRow("Amount", tx.amount.ifBlank { "Not captured" })
                        TxDetailRow("Time of execution", transactionExecutionTime(tx))
                        TxDetailRow("Execution completed", transactionCompletionTime(tx))
                        TxDetailRow("Time taken to execute", transactionExecutionDuration(tx))
                        TxDetailRow("Source", transactionTypeLabel(tx))
                        TxDetailRow("USSD code", tx.ussdCode.ifBlank { "Not captured" })
                    }
                }
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = C.w04,
                    border = BorderStroke(1.dp, C.border.copy(alpha = 0.8f))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("LAST RESPONSE", color = C.t3, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                        Text(
                            tx.ussdResponse.ifBlank { "No final response captured yet." },
                            color = C.t2,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = C.w04,
                    border = BorderStroke(1.dp, C.border.copy(alpha = 0.8f))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("USSD SESSION", color = C.t3, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                        Text(
                            transcriptText,
                            color = C.t2,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = C.t1)
            }
        },
        dismissButton = {
            TextButton(onClick = onDelete) {
                Text("Delete", color = C.red)
            }
        }
    )
}

@Composable
private fun StartAutomationCTA(running: Boolean, onToggle: () -> Unit) {
    val accent = if (running) C.red else C.blue
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(if (running) C.red else C.t3))
            Spacer(Modifier.width(8.dp))
            Text(
                if (running) "Automation Running" else "Automation Paused",
                color = if (running) C.red else C.t2,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
        }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onToggle,
            modifier = Modifier.fillMaxWidth().height(58.dp),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, accent.copy(alpha = 0.75f)),
            colors = ButtonDefaults.buttonColors(containerColor = accent.copy(alpha = 0.55f))
        ) {
            Icon(Icons.Outlined.PowerSettingsNew, null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            Text(if (running) "STOP AUTOMATION" else "START AUTOMATION", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, letterSpacing = 1.1.sp)
        }
    }
}

// ─── Balance Card ─────────────────────────────────────────────────────────
@Composable
fun VolcanicBalanceCard(
    airBal: String,
    tokenBal: Int,
    unlimitedLabel: String?,
    unlimitedRemaining: String?,
    sent: Int,
    pending: Int,
    failed: Int,
    completed: Int,
    scheduled: Int = 0,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    spin: Float,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        Color(0xFF4B3729).copy(alpha = 0.94f),
                        Color(0xFF2A242B).copy(alpha = 0.98f),
                        Color(0xFF131928).copy(alpha = 0.96f)
                    ),
                    start = Offset.Zero
                )
            )
            .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(28.dp))
            .animateContentSize(animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing))
            .clickable(onClick = onRefresh)
            .padding(horizontal = 14.dp, vertical = 14.dp)
    ) {
        val compactTop = maxWidth < 380.dp
        val statSpacing = if (maxWidth < 430.dp) 6.dp else 8.dp
        val headingAccent = Color(0xFFF5AF19)
        val sentAccent = Color(0xFF1ED89A)
        val pendingAccent = Color(0xFFF5AF19)
        val failedAccent = Color(0xFFFF496A)
        val completedAccent = Color(0xFFB79BFF)
        val airtimeValue = airBal.ifBlank { "—" }
        val topTokenValue = unlimitedLabel ?: tokenBal.toString()
        val topTokenCaption = unlimitedRemaining ?: if (unlimitedLabel != null) "Unlimited plan active" else "Available Units"
        val airtimeFontSize = balanceValueFontSize(
            airtimeValue,
            if (compactTop) 34.sp else 40.sp,
            if (compactTop) 30.sp else 34.sp,
            if (compactTop) 26.sp else 30.sp,
            if (compactTop) 22.sp else 26.sp
        )
        val topTokenValueFontSize = balanceValueFontSize(
            topTokenValue,
            if (compactTop) 42.sp else 52.sp,
            if (compactTop) 34.sp else 42.sp,
            if (compactTop) 28.sp else 34.sp,
            if (compactTop) 24.sp else 28.sp
        )
        val topTokenCaptionFontSize = balanceCaptionFontSize(
            topTokenCaption,
            if (compactTop) 10.sp else 11.sp,
            if (compactTop) 9.sp else 10.sp,
            if (compactTop) 8.sp else 9.sp
        )

        Box(
            Modifier
                .matchParentSize()
                .drawBehind {
                    drawRoundRect(
                        brush = Brush.linearGradient(
                            listOf(
                                Color(0x66A46A2B),
                                Color.Transparent,
                                Color(0x2200A5A5),
                                Color(0x33152440)
                            ),
                            start = Offset(0f, 0f),
                            end = Offset(size.width, size.height)
                        ),
                        cornerRadius = CornerRadius(28.dp.toPx(), 28.dp.toPx())
                    )
                    drawCircle(
                        color = headingAccent.copy(alpha = 0.08f),
                        radius = 88.dp.toPx(),
                        center = Offset(size.width * 0.12f, size.height * 0.18f)
                    )
                    drawCircle(
                        color = headingAccent.copy(alpha = 0.06f),
                        radius = 94.dp.toPx(),
                        center = Offset(size.width * 0.76f, size.height * 0.16f)
                    )
                    drawCircle(
                        color = sentAccent,
                        radius = 5.dp.toPx(),
                        center = Offset(size.width * 0.93f, size.height * 0.29f)
                    )
                    drawLine(
                        color = sentAccent.copy(alpha = 0.24f),
                        start = Offset(size.width * 0.94f, size.height * 0.34f),
                        end = Offset(size.width * 0.985f, size.height * 0.27f),
                        strokeWidth = 5.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                    val path = Path().apply {
                        moveTo(size.width * 0.04f, size.height * 0.55f)
                        cubicTo(
                            size.width * 0.20f, size.height * 0.47f,
                            size.width * 0.31f, size.height * 0.70f,
                            size.width * 0.46f, size.height * 0.61f
                        )
                        cubicTo(
                            size.width * 0.56f, size.height * 0.47f,
                            size.width * 0.76f, size.height * 0.63f,
                            size.width * 0.96f, size.height * 0.31f
                        )
                    }
                    drawPath(
                        path = path,
                        brush = Brush.linearGradient(
                            listOf(
                                headingAccent.copy(alpha = 0.18f),
                                Color(0xB8C8A62E),
                                sentAccent.copy(alpha = 0.42f)
                            )
                        ),
                        style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
        )
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MetricHeadingLabel("AIRTIME BALANCE", accent = headingAccent)
                MetricHeadingLabel("TOKENS", accent = headingAccent, trailingDot = true)
            }

            Box(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .width(1.dp)
                        .height(if (compactTop) 122.dp else 132.dp)
                        .background(Color.White.copy(alpha = 0.74f))
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(
                                start = if (compactTop) 4.dp else 8.dp,
                                end = if (compactTop) 22.dp else 28.dp,
                                top = if (compactTop) 6.dp else 10.dp,
                                bottom = if (compactTop) 8.dp else 12.dp
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = airtimeValue,
                                color = C.t1,
                                fontSize = airtimeFontSize,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Clip
                            )
                            Icon(
                                Icons.Outlined.Autorenew,
                                null,
                                tint = C.t3,
                                modifier = Modifier
                                    .size(if (compactTop) 24.dp else 28.dp)
                                    .then(if (isRefreshing) Modifier.graphicsLayer { rotationZ = spin } else Modifier)
                            )
                        }
                        Text(
                            if (isRefreshing) "Checking balance" else "Tap to refresh",
                            color = C.t3,
                            fontSize = if (compactTop) 10.sp else 11.sp,
                            lineHeight = if (compactTop) 13.sp else 15.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(
                                start = if (compactTop) 22.dp else 28.dp,
                                end = if (compactTop) 6.dp else 10.dp,
                                top = if (compactTop) 6.dp else 10.dp,
                                bottom = if (compactTop) 8.dp else 12.dp
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AnimatedContent(
                            targetState = topTokenValue,
                            transitionSpec = {
                                (fadeIn(tween(220)) + slideInVertically(animationSpec = tween(220)) { it / 5 }) togetherWith
                                    (fadeOut(tween(180)) + slideOutVertically(animationSpec = tween(180)) { -it / 5 })
                            },
                            label = "token_balance_value"
                        ) { tokenValue ->
                            Text(
                                tokenValue,
                                color = if (unlimitedLabel != null) sentAccent else C.t1,
                                fontSize = topTokenValueFontSize,
                                fontWeight = FontWeight.ExtraBold,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Clip
                            )
                        }
                        Text(
                            topTokenCaption,
                            color = C.t2,
                            fontSize = topTokenCaptionFontSize,
                            lineHeight = topTokenCaptionFontSize * 1.1f,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.78f))
            )
            HomeStatsRow(
                completed = completed,
                pending = pending,
                failed = failed,
                scheduled = scheduled,
                rate = if (sent > 0) (completed * 100 / sent) else 0,
                compact = true,
                spacing = statSpacing,
                sentAccent = sentAccent,
                pendingAccent = pendingAccent,
                failedAccent = failedAccent,
                completedAccent = completedAccent,
                scheduledAccent = Color(0xFF74E6D8)
            )
        }
    }
}

fun balanceValueFontSize(
    value: String,
    short: TextUnit,
    medium: TextUnit,
    long: TextUnit,
    extraLong: TextUnit
): TextUnit {
    val length = value.trim().length
    return when {
        length <= 8 -> short
        length <= 12 -> medium
        length <= 16 -> long
        else -> extraLong
    }
}

fun balanceCaptionFontSize(
    value: String,
    short: TextUnit,
    medium: TextUnit,
    long: TextUnit
): TextUnit {
    val length = value.trim().length
    return when {
        length <= 20 -> short
        length <= 30 -> medium
        else -> long
    }
}

@Composable
private fun HomeStatsRow(
    completed: Int,
    pending: Int,
    failed: Int,
    scheduled: Int = 0,
    rate: Int,
    compact: Boolean,
    spacing: Dp,
    sentAccent: Color,
    pendingAccent: Color,
    failedAccent: Color,
    completedAccent: Color,
    scheduledAccent: Color = C.cyan
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HomeStatusMetricCard(
            label = "Completed",
            value = completed.toString(),
            accent = sentAccent,
            compact = compact,
            modifier = Modifier.weight(1f)
        )
        HomeStatusMetricCard(
            label = "Pending",
            value = pending.toString(),
            accent = pendingAccent,
            compact = compact,
            modifier = Modifier.weight(1f)
        )
        HomeStatusMetricCard(
            label = "Failed",
            value = failed.toString(),
            accent = failedAccent,
            compact = compact,
            modifier = Modifier.weight(1f)
        )
        HomeStatusMetricCard(
            label = "Scheduled",
            value = scheduled.toString(),
            accent = scheduledAccent,
            compact = compact,
            modifier = Modifier.weight(1f)
        )
        HomeStatusMetricCard(
            label = "Rate",
            value = "$rate%",
            accent = completedAccent,
            compact = compact,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun HomeStatusMetricCard(
    label: String,
    value: String,
    accent: Color,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = Color(0x161D2433),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.28f))
    ) {
        Column(
            modifier = Modifier
                .heightIn(min = if (compact) 82.dp else 90.dp)
                .padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                value,
                fontSize = if (compact) 18.sp else 21.sp,
                fontWeight = FontWeight.ExtraBold,
                color = accent,
                maxLines = 1,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Text(
                label,
                color = C.t2,
                fontSize = if (compact) 8.sp else 9.sp,
                lineHeight = if (compact) 10.sp else 11.sp,
                letterSpacing = 0.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun RateStatCard(rate: Int, accent: Color, compact: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = Color(0x161D2433),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.28f))
    ) {
        Column(
            modifier = Modifier
                .height(if (compact) 96.dp else 104.dp)
                .padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "$rate%",
                color = accent,
                fontSize = if (compact) 24.sp else 28.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "RATE",
                color = C.t2,
                fontSize = if (compact) 8.sp else 9.sp,
                letterSpacing = 1.1.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun MetricHeadingLabel(text: String, accent: Color, trailingDot: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(accent)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            color = C.t1,
            fontSize = if (text.length > 8) 11.sp else 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.9.sp
        )
        if (trailingDot) {
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
        }
    }
}

@Composable
private fun HomeMetricBlock(
    title: String,
    value: String,
    detail: String,
    accent: Color,
    modifier: Modifier = Modifier,
    valueColor: Color = C.t1,
    trailing: @Composable (() -> Unit)? = null
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(accent))
                Text(title, color = C.t2, fontSize = 9.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Bold)
            }
            trailing?.invoke()
        }
        Text(
            value,
            fontSize = 30.sp,
            fontWeight = FontWeight.Black,
            lineHeight = 32.sp,
            color = valueColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            detail,
            color = C.t3,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun RefreshGlassButton(
    isRefreshing: Boolean,
    spin: Float,
    onRefresh: () -> Unit
) {
    Box(
        modifier = Modifier
            .shadow(18.dp, CircleShape, clip = false)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    listOf(
                        Color.White.copy(alpha = 0.08f),
                        C.surface.copy(alpha = 0.92f)
                    )
                )
            )
            .border(1.dp, C.borderHi.copy(alpha = 0.95f), CircleShape)
            .clickable { onRefresh() }
    ) {
        Box(
            Modifier
                .size(46.dp)
                .background(
                    Brush.radialGradient(
                        listOf(
                            C.amber.copy(alpha = 0.16f),
                            Color.Transparent
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.Refresh,
                null,
                tint = if (isRefreshing) C.amber else C.t1,
                modifier = Modifier
                    .size(18.dp)
                    .then(if (isRefreshing) Modifier.graphicsLayer { rotationZ = spin } else Modifier)
            )
        }
    }
}

@Composable
fun StatCell(value: String, label: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = C.surface.copy(alpha = 0.78f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.78f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(value, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, color = color, maxLines = 1)
            Text(label, fontSize = 9.sp, color = C.t3, letterSpacing = 0.8.sp, textAlign = TextAlign.Center, lineHeight = 11.sp)
        }
    }
}

@Composable
fun DonutRing(pct: Int, color: Color, size: Dp, stroke: Dp) {
    val sweep = pct * 360f / 100f
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val st = stroke.toPx()
            val r = (size.toPx() - st) / 2f
            val center = Offset(size.toPx() / 2f, size.toPx() / 2f)
            drawCircle(C.w08, radius = r, center = center, style = Stroke(width = st))
            drawArc(color = color, startAngle = -90f, sweepAngle = sweep, useCenter = false, topLeft = Offset(center.x - r, center.y - r), size = Size(r * 2f, r * 2f), style = Stroke(width = st, cap = StrokeCap.Round))
        }
        Text("$pct%", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = color)
    }
}

@Composable
fun VolcanicTxCard(tx: Transaction, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val executionCopy = remember(
        tx.id,
        tx.status,
        tx.offerId,
        tx.ussdCode,
        tx.ussdTranscript,
        tx.ussdResponse
    ) {
        transactionExecutionCopy(context, tx)
    }
    val liveExecution = isTransactionActivelyExecuting(tx)
    val statusColor = when (tx.status) {
        TransactionStatus.SUCCESS.value -> C.green
        TransactionStatus.FAILED.value, TransactionStatus.CANCELLED.value -> C.red
        TransactionStatus.PROCESSING.value, TransactionStatus.PENDING.value, TransactionStatus.RETRYING.value -> C.amber
        else -> C.t2
    }
    val typeLabel = when (tx.source) {
        TX_SOURCE_AUTOMATED -> "Automated"
        TX_SOURCE_MANUAL -> "Manual"
        TX_SOURCE_SMS_COMMAND -> "SMS Command"
        TX_SOURCE_AIRTIME -> "Airtime"
        else -> "Activity"
    }
    val typeColor = when (typeLabel) {
        "Automated" -> C.green
        "Manual" -> C.purple
        "SMS Command" -> C.blue
        "Airtime" -> C.orange
        else -> C.blue
    }
    val processingAnim = rememberInfiniteTransition(label = "processing")
    val processingAlpha by processingAnim.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "processing_alpha"
    )
    val initials = (tx.clientName.ifBlank { tx.phoneNumber }).split(" ").mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString("")
    val clipboard = LocalClipboardManager.current
    val transcriptText = tx.ussdTranscript.ifBlank {
        tx.ussdResponse.ifBlank {
            if (isTransactionActivelyExecuting(tx)) {
                executionCopy.detailLabel
            } else {
                "Response not captured yet. This transaction is queued for ${tx.description.ifBlank { "the selected activity" }}."
            }
        }
    }
    val transcriptCards = remember(tx.ussdTranscript, transcriptText) {
        val source = tx.ussdTranscript.takeIf { it.isNotBlank() } ?: transcriptText
        source
            .split(Regex("\n\\s*\n"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(C.card)
            .border(
                1.dp,
                when {
                    liveExecution -> statusColor.copy(alpha = 0.22f + (processingAlpha * 0.22f))
                    expanded -> statusColor.copy(alpha = 0.32f)
                    else -> C.border
                },
                RoundedCornerShape(18.dp)
            )
            .clickable { expanded = !expanded }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(statusColor.copy(alpha = 0.10f))
                    .border(1.dp, statusColor.copy(alpha = 0.26f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) { Text(initials.ifBlank { "?" }, color = statusColor, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    tx.clientName.ifBlank { "Unknown Customer" },
                    color = C.t1,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 15.sp,
                    maxLines = 1
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        tx.phoneNumber.ifBlank { "Phone not available" },
                        color = C.t2,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        tx.amount,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (tx.amount.startsWith("-")) C.orange else C.t1,
                        maxLines = 1
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = statusColor.copy(alpha = if (liveExecution) 0.10f + (processingAlpha * 0.08f) else 0.10f),
                border = BorderStroke(1.dp, statusColor.copy(alpha = if (liveExecution) 0.16f + (processingAlpha * 0.20f) else 0.2f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    if (liveExecution) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(statusColor.copy(alpha = processingAlpha)))
                    }
                    Text(executionCopy.statusLabel, color = statusColor, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text(tx.date, color = C.t3, fontSize = 10.sp, textAlign = TextAlign.End)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(8.dp), color = C.cyanDim, border = BorderStroke(1.dp, C.cyan.copy(alpha = 0.16f))) {
                    Text(tx.description.ifBlank { "Transaction" }, modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp), color = C.cyan, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Surface(shape = RoundedCornerShape(8.dp), color = typeColor.copy(alpha = 0.12f), border = BorderStroke(1.dp, typeColor.copy(alpha = 0.20f))) {
                    Text(typeLabel, modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp), color = typeColor, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(C.w04)
                    .border(1.dp, C.border, RoundedCornerShape(14.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("TRANSACTION DETAILS", color = C.t3, fontSize = 9.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
                TxDetailRow("Transaction ID", "#${tx.id}")
                TxDetailRow("Customer", tx.clientName.ifBlank { "Not captured" })
                TxDetailRow("Phone", tx.phoneNumber.ifBlank { "Not captured" })
                TxDetailRow("Bundle / Activity", tx.description.ifBlank { "Not captured" })
                TxDetailRow("Amount", tx.amount.ifBlank { "Not captured" })
                TxDetailRow("Status", tx.status)
                TxDetailRow("Source", tx.source.replace('_', ' '))
                TxDetailRow("Date", tx.date.ifBlank { "Not captured" })
                TxDetailRow("USSD Code", tx.ussdCode.ifBlank { "Not captured" })
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("LAST USSD RESPONSE", color = C.t3, fontSize = 9.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.Bold)
                    Text(
                        tx.ussdResponse.ifBlank { "Response not captured yet. This transaction is queued for ${tx.description.ifBlank { "the selected activity" }}." },
                        color = C.t2,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("USSD SESSION TRANSCRIPT", color = C.t3, fontSize = 9.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.Bold)
                        TextButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(transcriptText))
                                Toast.makeText(context, "USSD transcript copied", Toast.LENGTH_SHORT).show()
                            },
                            enabled = tx.ussdTranscript.isNotBlank()
                        ) {
                            Icon(
                                Icons.Filled.ContentCopy,
                                contentDescription = "Copy transcript",
                                tint = if (tx.ussdTranscript.isNotBlank()) C.cyan else C.t3,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Copy", color = if (tx.ussdTranscript.isNotBlank()) C.cyan else C.t3, fontSize = 12.sp)
                        }
                    }
                    transcriptCards.forEachIndexed { index, cardText ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = C.surface.copy(alpha = 0.72f),
                            border = BorderStroke(1.dp, C.border)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    "POP UP ${index + 1}",
                                    color = C.cyan,
                                    fontSize = 9.sp,
                                    letterSpacing = 1.2.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    cardText,
                                    color = C.t2,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    }
                }
                TextButton(onClick = onDelete, modifier = Modifier.align(Alignment.End)) {
                    Text("Delete", color = C.red, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun TxDetailRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label.uppercase(), color = C.t3, fontSize = 9.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
        Text(value, color = C.t1, fontSize = 12.sp, lineHeight = 18.sp)
    }
}

// ─── Animated Empty State ─────────────────────────────────────────────────
@Composable
fun AnimatedEmptyState(modifier: Modifier = Modifier) {
    val anim = rememberInfiniteTransition(label = "empty_anim")
    val corePulse by anim.animateFloat(0.96f, 1.05f, infiniteRepeatable(tween(2200, easing = EaseInOutSine), RepeatMode.Reverse), label = "core_pulse")
    val ringAlpha by anim.animateFloat(0.10f, 0.22f, infiniteRepeatable(tween(2600, easing = EaseInOutSine), RepeatMode.Reverse), label = "ring_alpha")
    val dotBlink by anim.animateFloat(0.35f, 0.85f, infiniteRepeatable(tween(1800, easing = EaseInOutSine), RepeatMode.Reverse), label = "dot_blink")
    val illustrationHeight = if (LocalConfiguration.current.screenWidthDp < 400) 162.dp else 188.dp

    Box(
        modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        C.cardHi.copy(alpha = 0.98f),
                        C.card.copy(alpha = 0.96f),
                        Color(0xFF131B27).copy(alpha = 0.96f)
                    )
                )
            )
            .border(1.dp, C.border.copy(alpha = 0.90f), RoundedCornerShape(24.dp))
            .padding(horizontal = 14.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(illustrationHeight)
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                C.cardHi.copy(alpha = 0.74f),
                                Color(0xFF172233).copy(alpha = 0.88f),
                                Color(0xFF111827).copy(alpha = 0.95f)
                            )
                        )
                    ),
                contentAlignment = Alignment.TopCenter
            ) {
                Canvas(
                    Modifier
                        .matchParentSize()
                        .padding(top = 8.dp)
                ) {
                    val center = Offset(size.width * 0.50f, size.height * 0.34f)
                    val lineY = size.height * 0.68f
                    val maxRadius = size.minDimension * 0.20f

                    listOf(0.34f, 0.56f, 0.76f, 0.96f).forEachIndexed { idx, factor ->
                        drawCircle(
                            color = C.green.copy(alpha = (ringAlpha - (idx * 0.028f)).coerceAtLeast(0.04f)),
                            radius = maxRadius * factor,
                            center = center,
                            style = Stroke(width = 1.2.dp.toPx())
                        )
                    }

                    val dots = listOf(
                        Offset(size.width * 0.10f, size.height * 0.30f),
                        Offset(size.width * 0.14f, size.height * 0.56f),
                        Offset(size.width * 0.24f, size.height * 0.42f),
                        Offset(size.width * 0.28f, size.height * 0.24f)
                    )
                    dots.forEachIndexed { idx, dot ->
                        drawCircle(
                            C.green.copy(alpha = (0.16f + (dotBlink * 0.14f) - idx * 0.02f).coerceAtLeast(0.08f)),
                            radius = (2.4f + idx).dp.toPx() / 1.7f,
                            center = dot
                        )
                    }

                    drawLine(
                        brush = Brush.horizontalGradient(
                            listOf(Color.Transparent, C.green.copy(alpha = 0.30f), Color.Transparent)
                        ),
                        start = Offset(size.width * 0.14f, lineY),
                        end = Offset(size.width * 0.86f, lineY),
                        strokeWidth = 1.1.dp.toPx()
                    )
                    drawCircle(C.green.copy(alpha = 0.12f), radius = 14.dp.toPx() * corePulse, center = center)
                    drawCircle(C.green.copy(alpha = 0.88f), radius = 5.dp.toPx(), center = center)
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Scanning for activity...",
                    color = C.t1,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "Transactions appear here after automation starts",
                    color = C.t2,
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 18.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SubtleRefreshGlyph(
    isRefreshing: Boolean,
    spin: Float,
    onRefresh: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .clickable(onClick = onRefresh),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Outlined.Refresh,
            null,
            tint = if (isRefreshing) C.t2.copy(alpha = 0.54f) else C.t3.copy(alpha = 0.28f),
            modifier = Modifier
                .size(14.dp)
                .then(if (isRefreshing) Modifier.graphicsLayer { rotationZ = spin } else Modifier)
        )
    }
}

// ─── Manual Screen ───────────────────────────────────────────────────────
@Composable
fun ManualScreen(allTxns: MutableList<Transaction>) {
    val ctx = LocalContext.current
    var offers by remember { mutableStateOf(OfferRepository.load(ctx).toList()) }
    var phone by remember { mutableStateOf("") }
    var phoneErr by remember { mutableStateOf<String?>(null) }
    var selOffer by remember { mutableStateOf(offers.firstOrNull { it.enabled }) }
    var mode by remember { mutableStateOf(OFFER_EXECUTION_MODE_SIMPLE) }
    var manualTab by rememberSaveable { mutableStateOf("DISPATCH") }
    var offerExp by remember { mutableStateOf(false) }
    var bannerState by remember { mutableStateOf<String?>(null) }
    var pendingTxId by remember { mutableIntStateOf(-1) }
    var selectedHistoryTxId by rememberSaveable { mutableIntStateOf(-1) }
    var smsSearchContacts by remember { mutableStateOf<List<SavedContact>>(emptyList()) }
    var smsSearchLoading by remember { mutableStateOf(false) }
    val fallbackResolvedClientName = remember(phone, allTxns.size) { resolveClientNameByPhone(ctx, phone) }
    val enabledOffers by remember {
        derivedStateOf {
            offers.asSequence()
                .filter { it.enabled }
                .sortedByDescending { it.price }
                .toList()
        }
    }
    val manualDirectory by remember(ctx, smsSearchContacts) {
        derivedStateOf { buildManualSearchEntries(ctx, allTxns.toList(), smsSearchContacts) }
    }
    val normalizedPhone = remember(phone) { SmsCommandHandler.normalizePhone(phone) }
    val phoneMatches by remember(phone, manualDirectory) {
        derivedStateOf { autoMatchManualEntries(phone, manualDirectory) }
    }
    val exactPhoneMatch = remember(normalizedPhone, phoneMatches) {
        phoneMatches.firstOrNull { SmsCommandHandler.normalizePhone(it.phone) == normalizedPhone }
    }
    val resolvedClientName = exactPhoneMatch?.name?.takeIf { it.isNotBlank() } ?: fallbackResolvedClientName
    val dispatchReady = remember(normalizedPhone, selOffer) {
        normalizedPhone.matches(Regex("^0\\d{9}$")) && selOffer != null
    }
    val manualHistory = allTxns.filter { it.source == TX_SOURCE_MANUAL }.sortedByDescending { it.timestamp }
    val selectedHistoryTx = manualHistory.firstOrNull { it.id == selectedHistoryTxId }
    val inf = rememberInfiniteTransition(label = "console_dispatch")
    val pendingButtonScale by inf.animateFloat(
        initialValue = 1f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(1400, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "console_dispatch_scale"
    )

    DisposableEffect(Unit) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val txId = intent.getIntExtra("txId", -1)
                when (intent.action) {
                    ACTION_TX_CREATED -> {
                        val tx = loadTransactionByIdFromPrefs(ctx, txId) ?: return
                        val idx = allTxns.indexOfFirst { it.id == txId }
                        if (idx >= 0) allTxns[idx] = tx else allTxns.add(0, tx)
                    }
                    "com.bingwa.mobile.TX_UPDATED" -> {
                        val idx = allTxns.indexOfFirst { it.id == txId }
                        val updatedTx = loadTransactionByIdFromPrefs(ctx, txId)
                        if (idx >= 0 && updatedTx != null) {
                            allTxns[idx] = updatedTx
                        } else if (updatedTx != null) {
                            allTxns.add(0, updatedTx)
                        }
                        if (txId == pendingTxId && updatedTx != null) bannerState = updatedTx.status.lowercase()
                    }
                    OfferRepository.ACTION_OFFERS_UPDATED -> {
                        offers = OfferRepository.load(ctx).toList()
                    }
                }
            }
        }
        val receiverRegistered = registerAppReceiver(ctx, receiver, android.content.IntentFilter().apply {
            addAction("com.bingwa.mobile.TX_UPDATED")
            addAction(ACTION_TX_CREATED)
            addAction(OfferRepository.ACTION_OFFERS_UPDATED)
        })
        onDispose {
            if (receiverRegistered) {
                try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {}
            }
        }
    }

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            smsSearchContacts = emptyList()
            return@LaunchedEffect
        }
        smsSearchLoading = true
        smsSearchContacts = withContext(Dispatchers.IO) { extractMpesaContacts(ctx, 180) }
        smsSearchLoading = false
    }

    LaunchedEffect(offers) {
        selOffer = when {
            enabledOffers.isEmpty() -> null
            selOffer == null -> enabledOffers.first()
            else -> enabledOffers.firstOrNull { it.id == selOffer?.id } ?: enabledOffers.first()
        }
        mode = selOffer?.executionMode ?: OFFER_EXECUTION_MODE_SIMPLE
    }

    val clockLabel = remember { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()) }
    val bannerColor = when (bannerState) {
        "success" -> C.green
        "failed" -> C.red
        "pending" -> C.amber
        "relayed" -> C.blue
        "scheduled" -> C.cyan
        else -> null
    }
    val bannerMessage = when (bannerState) {
        "success" -> "Bundle dispatched successfully"
        "failed" -> "Manual run failed. Check USSD logs."
        "pending" -> "Running manually now. Waiting for USSD response."
        "relayed" -> "Forwarded to Relay phone for execution"
        "scheduled" -> "Bundle scheduled successfully"
        else -> null
    }
    val commandPhone = if (normalizedPhone.matches(Regex("^0\\d{9}$"))) normalizedPhone else "0712345678"
    val commandCode = selOffer?.ussdCode?.replace("pn", commandPhone, true) ?: "*544*1*1#"
    val executeManualRun = executeManualRun@{
        val selectedOffer = selOffer
        val finalPhone = SmsCommandHandler.normalizePhone(phone)
        phoneErr = when {
            phone.isBlank() -> "Phone number required"
            !finalPhone.matches(Regex("^0\\d{9}$")) -> "Enter a valid phone number"
            selectedOffer == null -> "Choose an offer"
            else -> null
        }
        if (phoneErr == null && selectedOffer != null) {
            if (BlacklistedContactStore.isBlacklisted(ctx, finalPhone)) {
                bannerState = "failed"
                Toast.makeText(ctx, "Blocked: this phone number is blacklisted", Toast.LENGTH_SHORT).show()
                return@executeManualRun
            }
            vib(ctx, 70L)
            phone = finalPhone
            upsertSavedContact(ctx, finalPhone, resolvedClientName)
            if (RelayManager.shouldRelayOffer(ctx, selectedOffer)) {
                bannerState = "pending"
                val finalCode = selectedOffer.ussdCode.replace("pn", finalPhone, true)
                val txId = createPendingTransaction(
                    ctx,
                    selectedOffer.name,
                    "KSh ${selectedOffer.price}",
                    finalPhone,
                    finalCode,
                    clientName = resolvedClientName,
                    status = TransactionStatus.PROCESSING.value,
                    source = TX_SOURCE_MANUAL,
                    showInRecent = false,
                    offerId = selectedOffer.id
                )
                pendingTxId = txId
                saveTransactionOutcome(ctx, txId, TransactionStatus.PROCESSING.value, "Forwarded to Relay phone for execution.")
                broadcastTransactionUpdated(ctx, txId)

                val sent = RelayManager.forwardBuyAmount(ctx, finalPhone, selectedOffer.price)
                if (sent) {
                    bannerState = "relayed"
                } else {
                    bannerState = "failed"
                    saveTransactionOutcome(ctx, txId, TransactionStatus.FAILED.value, "Failed: Relay forwarding failed.")
                    broadcastTransactionUpdated(ctx, txId)
                }
            } else {
                bannerState = "pending"
                val finalCode = selectedOffer.ussdCode.replace("pn", finalPhone, true)
                val txId = createPendingTransaction(
                    ctx,
                    selectedOffer.name,
                    "KSh ${selectedOffer.price}",
                    finalPhone,
                    finalCode,
                    clientName = resolvedClientName,
                    source = TX_SOURCE_MANUAL,
                    showInRecent = false,
                    offerId = selectedOffer.id
                )
                pendingTxId = txId
                ctx.startOfferAutomation(
                    offer = selectedOffer,
                    phoneNumber = finalPhone,
                    txId = txId,
                    finalCode = finalCode,
                    mode = mode,
                    executionPriority = USSD_EXECUTION_PRIORITY_SPECIAL,
                    returnToAppAggressively = true
                )
            }
        }
    }

    fun showSchedulePicker(onPicked: (Long) -> Unit) {
        val now = Calendar.getInstance()
        DatePickerDialog(
            ctx,
            { _, year, month, day ->
                val base = Calendar.getInstance().apply {
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, day)
                }
                TimePickerDialog(
                    ctx,
                    { _, hour, minute ->
                        val picked = base.apply {
                            set(Calendar.HOUR_OF_DAY, hour)
                            set(Calendar.MINUTE, minute)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }.timeInMillis
                        onPicked(picked)
                    },
                    now.get(Calendar.HOUR_OF_DAY),
                    now.get(Calendar.MINUTE),
                    true
                ).show()
            },
            now.get(Calendar.YEAR),
            now.get(Calendar.MONTH),
            now.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    val scheduleManualRun = schedule@{
        val selectedOffer = selOffer
        val finalPhone = SmsCommandHandler.normalizePhone(phone)
        phoneErr = when {
            phone.isBlank() -> "Phone number required"
            !finalPhone.matches(Regex("^0\\d{9}$")) -> "Enter a valid phone number"
            selectedOffer == null -> "Choose an offer"
            else -> null
        }
        if (phoneErr != null || selectedOffer == null) return@schedule

        vib(ctx, 70L)
        phone = finalPhone
        upsertSavedContact(ctx, finalPhone, resolvedClientName)

        showSchedulePicker { triggerAtMillis ->
            val now = System.currentTimeMillis()
            if (triggerAtMillis <= now + 5_000L) {
                bannerState = "failed"
                Toast.makeText(ctx, "Choose a future time", Toast.LENGTH_SHORT).show()
                return@showSchedulePicker
            }
            val finalCode = selectedOffer.ussdCode.replace("pn", finalPhone, true)
            val txId = createPendingTransaction(
                ctx,
                selectedOffer.name,
                "KSh ${selectedOffer.price}",
                finalPhone,
                finalCode,
                clientName = resolvedClientName,
                status = TransactionStatus.PENDING.value,
                source = TX_SOURCE_AUTOMATED,
                showInRecent = false,
                offerId = selectedOffer.id
            )
            if (txId < 0) {
                bannerState = "failed"
                Toast.makeText(ctx, "Could not create schedule", Toast.LENGTH_SHORT).show()
                return@showSchedulePicker
            }
            val dispatch = ScheduledOfferDispatchStore.ScheduledDispatch(
                txId = txId,
                triggerAtMillis = triggerAtMillis,
                mode = mode,
                code = finalCode,
                phoneNumber = finalPhone,
                offerId = selectedOffer.id,
                offerName = selectedOffer.name,
                simSelection = selectedOffer.simSelection,
                signatureEnabled = selectedOffer.signatureDetectionEnabled,
                signatureMode = selectedOffer.signatureAction,
                executionPriority = USSD_EXECUTION_PRIORITY_SPECIAL,
                returnToAppAggressively = false
            )
            val ok = ScheduledOfferDispatchStore.schedule(ctx, dispatch)
            val whenLabel = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(triggerAtMillis))
            if (ok) {
                bannerState = "scheduled"
                saveTransactionOutcome(ctx, txId, TransactionStatus.PENDING.value, "Scheduled for $whenLabel.")
                broadcastTransactionUpdated(ctx, txId)
                Toast.makeText(ctx, "Scheduled for $whenLabel", Toast.LENGTH_SHORT).show()
            } else {
                bannerState = "failed"
                saveTransactionOutcome(ctx, txId, TransactionStatus.FAILED.value, "Failed to schedule for $whenLabel.")
                broadcastTransactionUpdated(ctx, txId)
                Toast.makeText(ctx, "Failed to schedule", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Box(Modifier.fillMaxSize().background(C.bg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.statusBarsPadding())
            Spacer(Modifier.height(10.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 430.dp)
                    .padding(bottom = 20.dp),
                shape = RoundedCornerShape(34.dp),
                color = Color(0xFF121618),
                border = BorderStroke(1.dp, Color(0xFF2A302F)),
                shadowElevation = 22.dp
            ) {
                Column(
                    modifier = Modifier
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color(0xFF171C1E),
                                    Color(0xFF101314)
                                )
                            )
                        )
                        .padding(bottom = 18.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(12.dp),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Box(
                            modifier = Modifier
                                .width(112.dp)
                                .height(18.dp)
                                .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                                .background(Color(0xFF050605))
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ManualStatusLed(color = C.cyan, glowing = true)
                            ManualStatusLed(color = C.amber, glowing = true, blinking = true)
                            ManualStatusLed(color = C.t3, glowing = false)
                            Text(
                                "AGENT 042",
                                color = C.t3,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 0.5.sp
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                clockLabel,
                                color = C.t2,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color.Transparent,
                                border = BorderStroke(1.dp, C.t3.copy(alpha = 0.6f))
                            ) {
                                Text(
                                    if (dispatchReady) "READY" else "IDLE",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                    color = if (dispatchReady) C.cyan else C.t3,
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier.padding(horizontal = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "MANUAL TERMINAL",
                            color = C.amber,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.6.sp
                        )
                        Text(
                            "Manual",
                            color = C.t1,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = (-0.4).sp
                        )
                        Text(
                            "Manual execution & history",
                            color = C.t2,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        ManualTerminalTabButton(
                            text = "Manual",
                            active = manualTab == "DISPATCH",
                            onClick = { manualTab = "DISPATCH" },
                            modifier = Modifier.weight(1f)
                        )
                        ManualTerminalTabButton(
                            text = "History",
                            active = manualTab == "HISTORY",
                            onClick = { manualTab = "HISTORY" },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        shape = RoundedCornerShape(24.dp),
                        color = Color(0xFF1B2022),
                        border = BorderStroke(1.dp, Color(0xFF2A3032))
                    ) {
                        if (manualTab == "DISPATCH") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 18.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                if (bannerMessage != null && bannerColor != null) {
                                    ManualTerminalBanner(
                                        message = bannerMessage,
                                        color = bannerColor
                                    )
                                }

                                Text(
                                    "TARGET NUMBER",
                                    color = C.t3,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.4.sp
                                )

                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = Color(0xFF121617),
                                    border = BorderStroke(
                                        1.dp,
                                        when {
                                            phoneErr != null -> C.red.copy(alpha = 0.46f)
                                            phone.isNotBlank() -> C.cyan.copy(alpha = 0.28f)
                                            else -> Color(0xFF394144)
                                        }
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 13.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(
                                            Icons.Outlined.Phone,
                                            null,
                                            tint = C.t2,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        BasicTextField(
                                            value = phone,
                                            onValueChange = {
                                                val digitsOnly = buildString {
                                                    it.forEachIndexed { index, ch ->
                                                        if (ch.isDigit() || (ch == '+' && index == 0)) append(ch)
                                                    }
                                                }.take(13)
                                                val normalizedInput = SmsCommandHandler.normalizePhone(digitsOnly)
                                                phone = when {
                                                    normalizedInput.matches(Regex("^0\\d{9}$")) -> normalizedInput
                                                    digitsOnly.startsWith("+") -> "+${digitsOnly.drop(1).filter(Char::isDigit).take(12)}"
                                                    else -> digitsOnly.filter(Char::isDigit).take(12)
                                                }
                                                bannerState = null
                                                phoneErr = when {
                                                    digitsOnly.isBlank() -> null
                                                    normalizedInput.matches(Regex("^0\\d{9}$")) -> null
                                                    normalizedInput.isNotBlank() && normalizedInput.length > 10 -> "Enter a valid phone number"
                                                    digitsOnly.filter(Char::isDigit).length < 9 -> "Enter the phone number"
                                                    else -> null
                                                }
                                            },
                                            modifier = Modifier.weight(1f),
                                            singleLine = true,
                                            textStyle = TextStyle(
                                                color = C.t1,
                                                fontSize = 18.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                fontFamily = FontFamily.Monospace,
                                                letterSpacing = 0.4.sp
                                            ),
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                            cursorBrush = SolidColor(C.cyan),
                                            decorationBox = { innerTextField ->
                                                if (phone.isBlank()) {
                                                    Text(
                                                        "0712345678",
                                                        color = C.t3,
                                                        fontSize = 18.sp,
                                                        fontFamily = FontFamily.Monospace
                                                    )
                                                }
                                                innerTextField()
                                            }
                                        )
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                                        ) {
                                            ManualStatusLed(color = C.cyan, glowing = true, blinking = true)
                                            Text(
                                                "LIVE",
                                                color = C.cyan,
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                }

                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        ManualTerminalReadout("${enabledOffers.size} OFFERS")
                                        ManualTerminalReadout(
                                            selOffer?.let { "KES ${it.price}" } ?: "NO OFFER",
                                            accent = C.cyan
                                        )
                                    }
                                    ManualTerminalReadout(mode, accent = C.amber, filled = true)
                                }

                                when {
                                    phoneErr != null -> Text(phoneErr ?: "", color = C.red, fontSize = 12.sp)
                                    exactPhoneMatch != null -> Text("Matched from ${exactPhoneMatch.source}", color = C.green, fontSize = 12.sp)
                                    smsSearchLoading -> Text("Refreshing saved contacts and M-PESA matches...", color = C.t2, fontSize = 12.sp)
                                }

                                AnimatedVisibility(visible = resolvedClientName.isNotBlank()) {
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = C.green.copy(alpha = 0.10f),
                                        border = BorderStroke(1.dp, C.green.copy(alpha = 0.24f))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                                        ) {
                                            Icon(Icons.Outlined.Badge, null, tint = C.green, modifier = Modifier.size(14.dp))
                                            Text(
                                                resolvedClientName,
                                                color = C.t1,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                }

                                Text(
                                    "BUNDLE",
                                    color = C.t3,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.4.sp
                                )

                                Box {
                                    ManualPaperSlipCard(
                                        name = selOffer?.name ?: "Choose an offer",
                                        meta = selOffer?.let { "KES ${it.price} · ${it.executionMode}" } ?: "Select an enabled offer",
                                        ticketNo = "No.${selOffer?.id?.toString()?.padStart(4, '0') ?: "----"}",
                                        enabled = enabledOffers.isNotEmpty(),
                                        onClick = { offerExp = true }
                                    )
                                    DropdownMenu(
                                        expanded = offerExp,
                                        onDismissRequest = { offerExp = false },
                                        modifier = Modifier
                                            .background(C.cardHi, RoundedCornerShape(14.dp))
                                            .border(1.dp, C.border, RoundedCornerShape(14.dp))
                                    ) {
                                        enabledOffers.forEachIndexed { index, o ->
                                            DropdownMenuItem(
                                                text = {
                                                    Column {
                                                        Text("${index + 1}. ${o.name}", color = C.t1)
                                                        Text(
                                                            "KES ${o.price} · ${o.executionMode}",
                                                            color = C.amber,
                                                            fontSize = 12.sp
                                                        )
                                                    }
                                                },
                                                onClick = {
                                                    selOffer = o
                                                    mode = o.executionMode
                                                    offerExp = false
                                                }
                                            )
                                        }
                                    }
                                }

                                Text(
                                    "MODE",
                                    color = C.t3,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.4.sp
                                )

                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    ManualModeToggleButton(
                                        label = "SIMPLE",
                                        icon = Icons.Filled.FlashOn,
                                        active = mode == "SIMPLE",
                                        onClick = { mode = "SIMPLE" },
                                        modifier = Modifier.weight(1f)
                                    )
                                    ManualModeToggleButton(
                                        label = "ADVANCED",
                                        icon = Icons.Outlined.Tune,
                                        active = mode == "ADVANCED",
                                        onClick = { mode = "ADVANCED" },
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Icon(Icons.Outlined.Info, null, tint = C.t2, modifier = Modifier.size(14.dp))
                                    Text(
                                        if (mode == "ADVANCED") {
                                            "Auto-navigates the USSD popup. Requires Accessibility access."
                                        } else {
                                            "Uses the simpler manual flow for straightforward requests."
                                        },
                                        color = C.t2,
                                        fontSize = 11.sp,
                                        lineHeight = 16.sp
                                    )
                                }

                                ManualTransmitLine(
                                    code = commandCode,
                                    phone = commandPhone
                                )

                                Button(
                                    onClick = executeManualRun,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(56.dp)
                                        .graphicsLayer {
                                            val scale = if (bannerState == "pending") pendingButtonScale else 1f
                                            scaleX = scale
                                            scaleY = scale
                                        },
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = C.amber,
                                        contentColor = C.bg
                                    ),
                                    enabled = dispatchReady && bannerState != "pending"
                                ) {
                                    Icon(Icons.Filled.Send, null, tint = C.bg, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        if (bannerState == "pending") "EXECUTING..." else "EXECUTE",
                                        color = C.bg,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 15.sp,
                                        letterSpacing = 0.6.sp
                                    )
                                }

                                Spacer(Modifier.height(10.dp))

                                OutlinedButton(
                                    onClick = scheduleManualRun,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(52.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = C.cyan
                                    ),
                                    border = BorderStroke(1.dp, C.cyan.copy(alpha = 0.55f)),
                                    enabled = dispatchReady && bannerState != "pending"
                                ) {
                                    Icon(Icons.Outlined.Schedule, null, tint = C.cyan, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        "SCHEDULE",
                                        color = C.cyan,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 14.sp,
                                        letterSpacing = 0.8.sp
                                    )
                                }
                            }
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 18.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            "HISTORY",
                                            color = C.amber,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.4.sp
                                        )
                                        Text(
                                            "Recent manual dispatches",
                                            color = C.t1,
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    ManualTerminalReadout("${manualHistory.size} TOTAL")
                                }

                                if (manualHistory.isEmpty()) {
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(18.dp),
                                        color = Color(0xFF121617),
                                        border = BorderStroke(1.dp, Color(0xFF333B3E))
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 18.dp, vertical = 22.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(Icons.Outlined.History, null, tint = C.t2, modifier = Modifier.size(22.dp))
                                            Text(
                                                "No console history yet",
                                                color = C.t1,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                "Executed manual dispatches will appear here.",
                                                color = C.t2,
                                                fontSize = 12.sp,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        manualHistory.forEach { tx ->
                                            ManualTerminalHistoryRow(
                                                tx = tx,
                                                onClick = { selectedHistoryTxId = tx.id }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (selectedHistoryTx != null) {
            RecentTransactionDetailsDialog(
                tx = selectedHistoryTx,
                onDismiss = { selectedHistoryTxId = -1 },
                onDelete = {
                    allTxns.removeAll { it.id == selectedHistoryTx.id }
                    saveTransactions(ctx, allTxns.toList())
                    selectedHistoryTxId = -1
                },
                onRetry = { tx ->
                    val result = retryRecentTransaction(ctx, tx)
                    Toast.makeText(ctx, result.message, if (result.success) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
                    if (result.success) {
                        selectedHistoryTxId = if (result.newTxId >= 0) result.newTxId else -1
                        manualTab = "HISTORY"
                    }
                }
            )
        }
    }
}

@Composable
private fun ManualStatusLed(
    color: Color,
    glowing: Boolean,
    blinking: Boolean = false
) {
    val alpha by if (blinking) {
        rememberInfiniteTransition(label = "console_led_blink").animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(1200, easing = EaseInOutSine), RepeatMode.Reverse),
            label = "console_led_alpha"
        )
    } else {
        rememberUpdatedState(1f)
    }
    Box(
        modifier = Modifier
            .size(6.dp)
            .graphicsLayer { this.alpha = alpha }
            .clip(CircleShape)
            .background(color)
            .then(
                if (glowing) {
                    Modifier.border(1.dp, color.copy(alpha = 0.12f), CircleShape)
                } else Modifier
            )
    )
}

@Composable
private fun ManualTerminalTabButton(
    text: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 6.dp, bottomEnd = 6.dp),
        color = if (active) Color(0xFF252B2E) else Color(0xFF1A1F21),
        border = BorderStroke(1.dp, if (active) C.amber.copy(alpha = 0.26f) else Color(0xFF2B3234))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text.uppercase(Locale.getDefault()),
                color = if (active) C.t1 else C.t2,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.4.sp
            )
            if (active) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(C.amber)
                )
            }
        }
    }
}

@Composable
private fun ManualTerminalBanner(message: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.28f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Outlined.Info, null, tint = color, modifier = Modifier.size(14.dp))
            Text(message, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ManualTerminalReadout(
    text: String,
    accent: Color = C.t2,
    filled: Boolean = false
) {
    Surface(
        shape = RoundedCornerShape(9.dp),
        color = if (filled) accent else Color.Transparent,
        border = BorderStroke(1.dp, if (filled) accent else Color(0xFF394144))
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            color = if (filled) C.bg else accent,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ManualPaperSlipCard(
    name: String,
    meta: String,
    ticketNo: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .shadow(8.dp, RoundedCornerShape(6.dp)),
        shape = RoundedCornerShape(6.dp),
        color = Color(0xFFE8E0CD)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    val holeRadius = 3.5.dp.toPx()
                    val spacing = 14.dp.toPx()
                    var x = 14.dp.toPx()
                    while (x < size.width - 10.dp.toPx()) {
                        drawCircle(Color(0xFF1B2022), holeRadius, Offset(x, 0f))
                        drawCircle(Color(0xFF1B2022), holeRadius, Offset(x, size.height))
                        x += spacing
                    }
                }
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            Text(
                ticketNo,
                modifier = Modifier.align(Alignment.TopEnd),
                color = Color(0xFFB8AB8C),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    C.amber,
                                    Color(0xFFCF8B2E)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.FlashOn, null, tint = Color(0xFF241804), modifier = Modifier.size(20.dp))
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        name,
                        color = Color(0xFF2A2420),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        meta,
                        color = Color(0xFFB06E16),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Icon(Icons.Filled.KeyboardArrowDown, null, tint = Color(0xFFB8AB8C))
            }
        }
    }
}

@Composable
private fun ManualModeToggleButton(
    label: String,
    icon: ImageVector,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp, bottomStart = 5.dp, bottomEnd = 5.dp),
        color = if (active) C.amber else Color(0xFF121617),
        border = BorderStroke(1.dp, if (active) Color(0xFF8A5D18) else Color(0xFF394144))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(icon, null, tint = if (active) Color(0xFF241804) else C.t2, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(7.dp))
            Text(
                label,
                color = if (active) Color(0xFF241804) else C.t2,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.3.sp
            )
        }
    }
}

@Composable
private fun ManualTransmitLine(
    code: String,
    phone: String
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF0D1011),
        border = BorderStroke(1.dp, Color(0xFF394144))
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(">", color = C.amber, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Text("DIAL", color = C.cyan, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            Text(code, color = C.cyan, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            Text("TO", color = C.t3, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            Text(phone, color = C.cyan, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            Box(
                modifier = Modifier
                    .width(7.dp)
                    .height(14.dp)
                    .background(C.cyan)
            )
        }
    }
}

@Composable
private fun ManualTerminalHistoryRow(
    tx: Transaction,
    onClick: () -> Unit
) {
    val statusColor = transactionStatusColor(tx)
    val liveExecution = tx.isLiveExecution()
    val title = tx.clientName.ifBlank { tx.description.ifBlank { "Manual dispatch" } }
    val phone = tx.phoneNumber.ifBlank { "Phone not available" }
    val serviceLabel = tx.description.ifBlank { "Manual dispatch" }
    val avatarLabel = recentActivityInitials(title)
    val amountLabel = recentActivityAmountLabel(tx.amount)
    val timeLabel = recentActivityTimeLabel(tx)
    val relativeLabel = recentActivityRelativeLabel(tx)
    val serviceIcon = recentActivityServiceIcon(serviceLabel)
    val summary = transactionCompletionSummary(tx)
    val rowAnim = rememberInfiniteTransition(label = "manual_history_row")
    val liveBeam by rowAnim.animateFloat(
        initialValue = -0.30f,
        targetValue = 1.10f,
        animationSpec = infiniteRepeatable(tween(if (liveExecution) 1500 else 3000, easing = LinearEasing)),
        label = "manual_history_row_beam"
    )
    val liveDotAlpha by rowAnim.animateFloat(
        initialValue = 0.38f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "manual_history_row_dot"
    )
    val liveAvatarScale by rowAnim.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(tween(1500, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "manual_history_row_avatar"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(5.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(999.dp))
                .background(statusColor.copy(alpha = 0.82f))
        )
        Surface(
            color = Color(0xFF090B0C),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(
                1.dp,
                if (liveExecution) statusColor.copy(alpha = 0.28f) else Color(0xFF152024).copy(alpha = 0.62f)
            ),
            modifier = Modifier
                .weight(1f)
                .drawBehind {
                    if (!liveExecution) return@drawBehind
                    val beamWidth = size.width * 0.24f
                    val startX = (size.width * liveBeam) - beamWidth
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            listOf(
                                Color.Transparent,
                                statusColor.copy(alpha = 0.18f),
                                Color.Transparent
                            ),
                            startX = startX,
                            endX = startX + beamWidth
                        ),
                        cornerRadius = CornerRadius(28f, 28f)
                    )
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick)
                    .padding(horizontal = 16.dp, vertical = 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .graphicsLayer {
                                scaleX = if (liveExecution) liveAvatarScale else 1f
                                scaleY = if (liveExecution) liveAvatarScale else 1f
                            }
                            .clip(CircleShape)
                            .background(Color(0xFF0D1113))
                            .border(
                                1.dp,
                                statusColor.copy(alpha = if (liveExecution) 0.30f else 0.18f),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            avatarLabel,
                            color = Color(0xFFB8C0C3),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            title,
                            color = Color(0xFFF2F6F7),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = 18.sp
                        )
                        Text(
                            phone,
                            color = Color(0xFF8B979B),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 15.sp
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        amountLabel,
                        color = Color(0xFFE8ECEE),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = statusColor.copy(alpha = if (liveExecution) 0.14f else 0.10f),
                        border = BorderStroke(1.dp, statusColor.copy(alpha = if (liveExecution) 0.28f else 0.18f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (liveExecution) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(statusColor.copy(alpha = liveDotAlpha))
                                )
                            }
                            Text(
                                transactionStatusLabel(tx),
                                color = statusColor.copy(alpha = 0.96f),
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color(0xFF11181B))
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                serviceIcon,
                                null,
                                tint = Color(0xFF9FD8FF),
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                serviceLabel,
                                color = Color(0xFF9FD8FF),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                lineHeight = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Schedule,
                                null,
                                tint = Color(0xFFC9D4DB),
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                timeLabel,
                                color = Color(0xFFC9D4DB),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1
                            )
                            if (relativeLabel.isNotBlank()) {
                                Text(
                                    "•",
                                    color = Color(0xFF647279),
                                    fontSize = 11.sp
                                )
                                Text(
                                    relativeLabel,
                                    color = statusColor.copy(alpha = 0.96f),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (liveExecution) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HomeExecutionDots(accent = statusColor)
                        Text(
                            "Processing transaction. Status updates appear automatically.",
                            color = statusColor.copy(alpha = 0.92f),
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    Text(
                        summary,
                        color = statusColor.copy(alpha = 0.92f),
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }
        }
    }
}

// ─── Tokens Screen ──────────────────────────────────────────────────────
@Composable
fun TokensScreen() {
    val ctx = LocalContext.current
    val tm = remember { TokenManager(ctx) }
    var bal by remember { mutableIntStateOf(tm.getBalance()) }
    var confirm by remember { mutableStateOf<Int?>(null) }
    val packs = remember {
        listOf(
            TokenTopUp(tokens = 105, ksh = 15),
            TokenTopUp(tokens = 255, ksh = 25, popular = true),
            TokenTopUp(tokens = 605, ksh = 55),
            TokenTopUp(tokens = 1200, ksh = 100)
        )
    }

    val um = remember { UnlimitedManager(ctx) }
    var remMs by remember { mutableLongStateOf(um.remainingMs()) }
    val activePlan = um.getActivePlan()?.takeIf { remMs > 0L }

    LaunchedEffect(Unit) {
        while (true) {
            remMs = um.remainingMs()
            delay(nextCountdownRefreshDelay(remMs))
        }
    }
    DisposableEffect(Unit) {
        TokenManager.tokenBalanceListener = {
            bal = it
            remMs = um.remainingMs()
        }
        onDispose { TokenManager.tokenBalanceListener = null }
    }

    Box(Modifier.fillMaxSize().background(C.bg)) {
        Box(
            Modifier
                .size(300.dp)
                .offset((-110).dp, 10.dp)
                .background(Brush.radialGradient(listOf(C.amber.copy(alpha = 0.08f), Color.Transparent)), CircleShape)
        )
        Box(
            Modifier
                .size(280.dp)
                .align(Alignment.TopEnd)
                .offset(90.dp, 90.dp)
                .background(Brush.radialGradient(listOf(C.amber.copy(alpha = 0.07f), Color.Transparent)), CircleShape)
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(top = 18.dp, start = 16.dp, end = 16.dp, bottom = 16.dp)
                    .widthIn(max = 430.dp)
                    .align(Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                TokensHeroCard(balance = bal, activePlan = activePlan, remainingMs = remMs)

                SectionHeader(
                    title = "TOP UP",
                    subtitle = "",
                    accent = C.amber
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    packs.forEach { p ->
                        TokenTopUpCard(p) { confirm = p.ksh }
                    }
                }

                SectionHeader(
                    title = "UNLIMITED",
                    subtitle = "",
                    accent = C.amber
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    UnlimitedManager.PLANS.forEach { plan ->
                        UnlimitedPlanCard(plan) { confirm = plan.ksh }
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }

    confirm?.let { amount ->
        val plan = UnlimitedManager.planForAmount(amount)
        val tokensToAdd = if (plan == null) TokenManager.convertAmountToTokens(amount) else 0
        val confirmAccent = C.amber
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Confirm Purchase", color = C.t1) },
            text = {
                Text(
                    if (plan != null) "Use KSh $amount airtime to activate unlimited ${plan.label.lowercase()} access?"
                    else "Use KSh $amount airtime to receive $tokensToAdd tokens?",
                    color = C.t2
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = null
                        MpesaReceiver.buyTokensWithAirtime(ctx, amount) { ok, info ->
                            if (ok) {
                                remMs = um.remainingMs()
                                if (plan != null) Toast.makeText(ctx, "Unlimited ${plan.label} activated. ${formatRemainingTimeDetailed(remMs)}.", Toast.LENGTH_SHORT).show()
                                else Toast.makeText(ctx, "Airtime used successfully. $tokensToAdd tokens were added.", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(ctx, info ?: "Token purchase failed.", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                ) {
                    Text("Confirm", color = confirmAccent)
                }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel", color = C.t2) } }
        )
    }
}

private data class TokenTopUp(val tokens: Int, val ksh: Int, val popular: Boolean = false)

@Composable
private fun TokenTopUpCard(p: TokenTopUp, onBuy: () -> Unit) {
    val accent = C.amber
    Surface(
        color = Color(0xFF14181A),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.30f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color(0xFF21211C),
                            Color(0xFF14181A),
                            Color(0xFF0F1418)
                        )
                    )
                )
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                if (p.popular) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = accent.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, accent.copy(alpha = 0.22f))
                    ) {
                        Text(
                            "POPULAR",
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 2.dp),
                            color = accent,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.6.sp
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        p.tokens.toString(),
                        color = C.t1,
                        fontSize = 29.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "tokens",
                        color = C.t2,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 3.dp),
                        maxLines = 1
                    )
                }
                Text("Ksh ${p.ksh}", color = C.t2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }

            OutlinedButton(
                onClick = onBuy,
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.55f)),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Transparent, contentColor = accent),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                modifier = Modifier.height(46.dp)
            ) {
                Icon(Icons.Outlined.Add, null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(8.dp))
                Text("Buy", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun UnlimitedPlanCard(plan: UnlimitedManager.Plan, onBuy: () -> Unit) {
    val accent = C.amber
    Surface(
        color = Color(0xFF14181A),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.26f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color(0xFF21211C),
                            Color(0xFF14181A),
                            Color(0xFF0F1418)
                        )
                    )
                )
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Unlimited", color = C.t1, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = accent.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, accent.copy(alpha = 0.22f))
                    ) {
                        Text(
                            plan.label.uppercase(),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            color = accent,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        )
                    }
                }
                Text("KSh ${plan.ksh}", color = accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }

            Button(
                onClick = onBuy,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Transparent, contentColor = accent),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.55f)),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                modifier = Modifier.height(46.dp)
            ) {
                Icon(Icons.Outlined.Shield, null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(8.dp))
                Text("Buy", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
    }
}

// ─── Contacts Screen ─────────────────────────────────────────────────────
@Composable
fun ContactsScreen(onBack: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    var contacts by remember { mutableStateOf(SavedContactStore.load(ctx)) }
    var query by remember { mutableStateOf("") }
    var showImport by remember { mutableStateOf(false) }
    var showAddDlg by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newPhone by remember { mutableStateOf("") }
    val filtered = contacts.filter { query.isBlank() || it.name.contains(query, true) || it.phone.contains(query) }

    DisposableEffect(ctx) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == SavedContactStore.ACTION_CONTACTS_UPDATED) {
                    contacts = SavedContactStore.load(ctx)
                }
            }
        }
        val registered = registerAppReceiver(
            ctx,
            receiver,
            android.content.IntentFilter(SavedContactStore.ACTION_CONTACTS_UPDATED)
        )
        onDispose {
            if (registered) {
                try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {}
            }
        }
    }

    Column(Modifier.fillMaxSize().background(C.bg)) {
        if (onBack != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = C.cardHi.copy(alpha = 0.96f),
                    border = BorderStroke(1.dp, C.border.copy(alpha = 0.9f))
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, null, tint = C.t1)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text("Contacts", color = C.t1, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("${contacts.size} customers saved", color = C.t2, fontSize = 12.sp)
                }
                ContactHeaderAction(Icons.Filled.CloudDownload, C.cyan) { showImport = true }
                ContactHeaderAction(Icons.Filled.PersonAdd, C.purple) {
                    showAddDlg = true
                    newName = ""
                    newPhone = ""
                }
            }
        } else {
            PageHeader("Contacts", "${contacts.size} customers saved")
            Row(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ActionButton(Modifier.weight(1f), "Import M-PESA", Icons.Filled.CloudDownload, C.cyan) { showImport = true }
                ActionButton(Modifier.weight(1f), "Add Manually", Icons.Filled.PersonAdd, C.purple) {
                    showAddDlg = true
                    newName = ""
                    newPhone = ""
                }
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            color = C.cardHi.copy(alpha = 0.94f),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, C.border.copy(alpha = 0.88f))
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Directory", color = C.t1, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (filtered.isEmpty()) "No matches" else "${filtered.size} visible",
                        color = C.t3,
                        fontSize = 11.sp
                    )
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search name or number…", color = C.t3, fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Outlined.Search, null, tint = C.t2, modifier = Modifier.size(17.dp)) },
                    trailingIcon = if (query.isNotEmpty()) {
                        {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Filled.Clear, null, tint = C.t2, modifier = Modifier.size(15.dp))
                            }
                        }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = fieldColors(),
                    singleLine = true
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = C.card,
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, C.border.copy(alpha = 0.86f))
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(C.cyanDim),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Outlined.People, null, tint = C.cyan, modifier = Modifier.size(28.dp))
                        }
                        Text(
                            if (query.isBlank()) "No contacts saved yet" else "No matching contacts",
                            color = C.t1,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (query.isBlank()) "Import M-PESA contacts or add a number manually to build your customer directory." else "Try another name or phone number.",
                            color = C.t2,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filtered, key = { it.phone }) { c ->
                    ContactCard(
                        c = c,
                        onDelete = {
                            contacts = SavedContactStore.delete(ctx, c.phone)
                        },
                        onCall = {
                            runCatching {
                                ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${c.phone}")))
                            }
                        },
                        onMessage = {
                            runCatching {
                                ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${c.phone}")))
                            }
                        }
                    )
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
        if (showImport) {
            MpesaImportDialog(onDismiss = { showImport = false }) { imported ->
                contacts = SavedContactStore.merge(ctx, imported)
                vib(ctx)
                Toast.makeText(ctx, "${imported.size} contacts imported", Toast.LENGTH_LONG).show()
                showImport = false
            }
        }
        if (showAddDlg) {
            AlertDialog(
                containerColor = C.card, shape = RoundedCornerShape(18.dp), onDismissRequest = { showAddDlg = false },
                title = { Text("Add Contact", color = C.t1, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("Name (optional)") }, singleLine = true, colors = dialogFieldColors(), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = newPhone, onValueChange = { newPhone = it }, label = { Text("Phone number") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), colors = dialogFieldColors(), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        if (newPhone.isNotBlank()) {
                            contacts = SavedContactStore.upsert(ctx, newPhone.trim(), newName.trim())
                            showAddDlg = false
                            vib(ctx)
                        }
                    }, colors = ButtonDefaults.buttonColors(containerColor = C.cyan)) {
                        Text("Save", color = C.bg, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = { TextButton(onClick = { showAddDlg = false }) { Text("Cancel", color = C.t2) } }
            )
        }
    }
}

@Composable
private fun ContactHeaderAction(icon: ImageVector, accent: Color, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = accent.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.22f))
    ) {
        IconButton(onClick = onClick) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
fun ContactCard(c: SavedContact, onDelete: () -> Unit, onCall: () -> Unit, onMessage: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val accent = remember(c.phone, c.name) {
        listOf(C.cyan, C.green, C.blue, C.orange, C.purple)[kotlin.math.abs((c.phone + c.name).hashCode()) % 5]
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = C.card,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.86f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(accent.copy(alpha = 0.08f), Color.Transparent)
                    )
                )
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.16f))
                        .border(1.dp, accent.copy(alpha = 0.24f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        (c.name.ifBlank { c.phone }).take(2).uppercase(),
                        color = accent,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 14.sp
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (c.name.isNotBlank()) {
                        Text(c.name, color = C.t1, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Outlined.Phone, null, tint = C.t3, modifier = Modifier.size(12.dp))
                        Text(
                            c.phone,
                            color = if (c.name.isBlank()) C.t1 else C.t2,
                            fontSize = if (c.name.isBlank()) 14.sp else 12.sp
                        )
                    }
                }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.MoreVert, null, tint = C.t3, modifier = Modifier.size(16.dp))
                    }
                    DropdownMenu(
                        expanded = menu,
                        onDismissRequest = { menu = false },
                        modifier = Modifier
                            .background(C.cardHi)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, C.border, RoundedCornerShape(12.dp))
                    ) {
                        DropdownMenuItem(
                            text = { Text("Delete", color = C.red, fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = C.red, modifier = Modifier.size(16.dp)) },
                            onClick = { onDelete(); menu = false }
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(
                    modifier = Modifier.weight(1f),
                    label = "Call",
                    icon = Icons.Outlined.Call,
                    color = C.green,
                    onClick = onCall
                )
                ActionButton(
                    modifier = Modifier.weight(1f),
                    label = "SMS",
                    icon = Icons.Outlined.Sms,
                    color = C.blue,
                    onClick = onMessage
                )
            }
        }
    }
}

@Composable
fun ActionButton(modifier: Modifier, label: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f)),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = color.copy(alpha = 0.07f))
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ─── Blacklist Screen ─────────────────────────────────────────────────────
@Composable
fun BlacklistScreen(onBack: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    var blacklist by remember { mutableStateOf(BlacklistedContactStore.load(ctx)) }
    var showAddDlg by remember { mutableStateOf(false) }
    var newPhone by remember { mutableStateOf("") }
    val sorted = remember(blacklist) { blacklist.sorted() }

    DisposableEffect(ctx) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == BlacklistedContactStore.ACTION_BLACKLIST_UPDATED) {
                    blacklist = BlacklistedContactStore.load(ctx)
                }
            }
        }
        val registered = registerAppReceiver(
            ctx,
            receiver,
            android.content.IntentFilter(BlacklistedContactStore.ACTION_BLACKLIST_UPDATED)
        )
        onDispose {
            if (registered) {
                try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {}
            }
        }
    }

    Column(Modifier.fillMaxSize().background(C.bg)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (onBack != null) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = C.cardHi.copy(alpha = 0.96f),
                    border = BorderStroke(1.dp, C.border.copy(alpha = 0.9f))
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, null, tint = C.t1)
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Text("Blacklist", color = C.t1, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("${sorted.size} blocked numbers", color = C.t2, fontSize = 12.sp)
            }
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = C.red.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, C.red.copy(alpha = 0.28f))
            ) {
                IconButton(onClick = { showAddDlg = true; newPhone = "" }) {
                    Icon(Icons.Filled.Add, null, tint = C.red, modifier = Modifier.size(18.dp))
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        if (sorted.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = C.card,
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, C.border.copy(alpha = 0.86f))
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(C.red.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Block, null, tint = C.red, modifier = Modifier.size(28.dp))
                        }
                        Text("No blacklisted numbers", color = C.t1, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text("Tap + to block a phone number from receiving automated bundles.", color = C.t2, fontSize = 12.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
                    }
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(sorted, key = { it }) { phone ->
                    BlacklistCard(
                        phone = phone,
                        onUnblock = {
                            blacklist = BlacklistedContactStore.remove(ctx, phone)
                            vib(ctx, 80L)
                        }
                    )
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }

    if (showAddDlg) {
        AlertDialog(
            containerColor = C.card,
            shape = RoundedCornerShape(18.dp),
            onDismissRequest = { showAddDlg = false },
            title = { Text("Block Number", color = C.t1, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = newPhone,
                        onValueChange = { newPhone = it },
                        label = { Text("Phone number (07xxxxxxxx)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        colors = dialogFieldColors(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (newPhone.isNotBlank()) {
                        blacklist = BlacklistedContactStore.add(ctx, newPhone.trim())
                        showAddDlg = false
                        vib(ctx)
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = C.red)) {
                    Text("Block", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDlg = false }) { Text("Cancel", color = C.t2) }
            }
        )
    }
}

@Composable
private fun BlacklistCard(phone: String, onUnblock: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = C.card,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.86f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(C.red.copy(alpha = 0.14f))
                    .border(1.dp, C.red.copy(alpha = 0.24f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Block, null, tint = C.red, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(phone, color = C.t1, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text("Blocked from automated bundles", color = C.t2, fontSize = 11.sp)
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.MoreVert, null, tint = C.t3, modifier = Modifier.size(16.dp))
                }
                DropdownMenu(
                    expanded = menu,
                    onDismissRequest = { menu = false },
                    modifier = Modifier
                        .background(C.cardHi)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, C.border, RoundedCornerShape(12.dp))
                ) {
                    DropdownMenuItem(
                        text = { Text("Unblock", color = C.green, fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Outlined.CheckCircle, null, tint = C.green, modifier = Modifier.size(16.dp)) },
                        onClick = { onUnblock(); menu = false }
                    )
                }
            }
        }
    }
}

// ─── Settings Screen ──────────────────────────────────────────────────────
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    var showOffers by remember { mutableStateOf(false) }

    if (showOffers) { OffersScreen(onBack = { showOffers = false }); return }

    var autoClear by remember { mutableStateOf(prefs.safeGetString("auto_clear", "Never") ?: "Never") }
    var clearExpanded by remember { mutableStateOf(false) }
    val clearOptions = listOf("Daily", "Weekly", "Monthly", "Yearly", "Never")
    var overviewData by remember { mutableStateOf(calculateOverview(ctx)) }
    var autoEnabled by remember { mutableStateOf(prefs.safeGetBoolean("automation_enabled", true)) }
    var autoRetry by remember { mutableStateOf(prefs.safeGetBoolean("auto_retry", false)) }
    var autoContacts by remember { mutableStateOf(prefs.safeGetBoolean("auto_save_contacts", true)) }
    val sims = getAvailableSims(ctx)
    var simId by remember { mutableIntStateOf(currentUssdSimSelection(ctx)) }
    var notifySuccess by remember { mutableStateOf(prefs.safeGetBoolean("notify_success", true)) }
    var notifyFailed by remember { mutableStateOf(prefs.safeGetBoolean("notify_failed", true)) }
    var notifyScheduled by remember { mutableStateOf(prefs.safeGetBoolean("notify_scheduled", true)) }
    var notifySimId by remember { mutableIntStateOf(prefs.safeGetInt("notify_sim_id", -1)) }
    var tplSuccess by remember { mutableStateOf(prefs.safeGetString("sms_tpl_success", DEFAULT_TPL_SUCCESS) ?: DEFAULT_TPL_SUCCESS) }
    var tplFailed by remember { mutableStateOf(prefs.safeGetString("sms_tpl_failed", DEFAULT_TPL_FAILED) ?: DEFAULT_TPL_FAILED) }
    var tplPending by remember { mutableStateOf(prefs.safeGetString("sms_tpl_pending", DEFAULT_TPL_PENDING) ?: DEFAULT_TPL_PENDING) }
    var tplScheduled by remember { mutableStateOf(prefs.safeGetString("sms_tpl_scheduled", DEFAULT_TPL_SCHEDULED) ?: DEFAULT_TPL_SCHEDULED) }
    var vibToggle by remember { mutableStateOf(prefs.safeGetBoolean("vibration_on_toggle", true)) }
    var vibExecute by remember { mutableStateOf(prefs.safeGetBoolean("vibration_on_execute", true)) }
    var remoteEnabled by remember { mutableStateOf(prefs.safeGetBoolean("remote_enabled", false)) }
    var adminPhone by remember { mutableStateOf(prefs.safeGetString("admin_phone", "") ?: "") }
    var adminPrefix by remember { mutableStateOf(prefs.safeGetString("sms_prefix", "BINGWA") ?: "BINGWA") }
    var adminPin by remember { mutableStateOf(prefs.safeGetString("sms_pin", "") ?: "") }
    var pinVisible by remember { mutableStateOf(false) }
    var adminSmsSimId by remember { mutableIntStateOf(prefs.safeGetInt("admin_sms_sim_id", -1)) }
    var alertLowBalance by remember { mutableStateOf(prefs.safeGetBoolean("alert_low_balance", false)) }
    var alertLowTokens by remember { mutableStateOf(prefs.safeGetBoolean("alert_low_tokens", false)) }
    var alertFailedTx by remember { mutableStateOf(prefs.safeGetBoolean("alert_failed_tx", false)) }
    var lowBalanceLimit by remember { mutableIntStateOf(prefs.safeGetInt("low_balance_limit", 50)) }
    var lowTokenLimit by remember { mutableIntStateOf(prefs.safeGetInt("low_token_limit", 5)) }
    var alertLowBattery by remember { mutableStateOf(prefs.safeGetBoolean("alert_low_battery", false)) }
    var lowBatteryLimit by remember { mutableIntStateOf(prefs.safeGetInt("low_battery_limit", 20)) }
    var twoPhoneEnabled by remember { mutableStateOf(prefs.safeGetBoolean("two_phone_enabled", false)) }
    var twoPhoneRole by remember { mutableStateOf(prefs.safeGetString("two_phone_role", "PRIMARY") ?: "PRIMARY") }
    var relayMethod by remember { mutableStateOf(prefs.safeGetString("relay_method", "SMS") ?: "SMS") }
    var pairedPhone by remember { mutableStateOf(prefs.safeGetString("paired_phone", "") ?: "") }
    var relayIp by remember { mutableStateOf(prefs.safeGetString("relay_ip", "") ?: "") }
    var relayIpAuto by remember { mutableStateOf(prefs.safeGetBoolean("relay_ip_auto", false)) }
    var relayPrefix by remember { mutableStateOf(prefs.safeGetString("relay_prefix", prefs.safeGetString("sms_prefix", "BINGWA")) ?: (prefs.safeGetString("sms_prefix", "BINGWA") ?: "BINGWA")) }
    var relayPin by remember { mutableStateOf(prefs.safeGetString("relay_pin", prefs.safeGetString("sms_pin", "")) ?: (prefs.safeGetString("sms_pin", "") ?: "")) }
    var relaySendResults by remember { mutableStateOf(prefs.safeGetBoolean("relay_send_results", true)) }
    var roleExp by remember { mutableStateOf(false) }
    var methodExp by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(C.bg).verticalScroll(rememberScrollState())) {
        PageHeader("Settings", "App preferences & configuration")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsOverviewCard(
                autoEnabled = autoEnabled,
                remoteEnabled = remoteEnabled,
                twoPhoneEnabled = twoPhoneEnabled
            )

            SettingsGroup("Automation") {
                ToggleRow(Icons.Outlined.Bolt, "Enable Automation", "Auto-run bundles on payment", autoEnabled) { autoEnabled = it; prefs.edit().putBoolean("automation_enabled", it).apply() }
                GroupDivider()
                ToggleRow(Icons.Rounded.Autorenew, "Auto-Retry on Failure", "Retry failed USSD up to 3 times", autoRetry) { autoRetry = it; prefs.edit().putBoolean("auto_retry", it).apply() }
                GroupDivider()
                ToggleRow(Icons.Rounded.PersonAdd, "Auto-Save Contacts", "Save payer numbers from M-PESA SMS", autoContacts) { autoContacts = it; prefs.edit().putBoolean("auto_save_contacts", it).apply() }
                GroupDivider()
                UssdSimPickerRow("USSD SIM Card", sims, simId) {
                    simId = it
                    prefs.edit().putInt("selected_sim_id", it).apply()
                }
            }

            SettingsGroup("Bundle Offers") {
                LinkRow(Icons.Outlined.Code, "Manage Offers & USSD Codes", "Add, edit, remove bundles", C.cyan) { showOffers = true }
            }

            SettingsGroup("Two-Phone Mode") {
                ToggleRow(Icons.Rounded.Devices, "Enable Two‑Phone Mode", "Forward selected offers to the Relay phone", twoPhoneEnabled) {
                    twoPhoneEnabled = it
                    prefs.edit().putBoolean("two_phone_enabled", it).apply()
                    if (!it) RelayManager.stopRelayHotspotService(ctx)
                    val cfg = RelayManager.load(ctx)
                    if (cfg.enabled && cfg.role == "RELAY" && cfg.method == "HOTSPOT") RelayManager.startRelayHotspotService(ctx)
                }
                AnimatedVisibility(visible = twoPhoneEnabled) {
                    Column {
                        GroupDivider()
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                            SettingsRowIcon(Icons.Rounded.Devices)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("This Phone Role", color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text("PRIMARY receives M‑PESA; RELAY executes selected offers", color = C.t2, fontSize = 11.sp)
                            }
                            Box {
                                TextButton(onClick = { roleExp = true }) { Text(twoPhoneRole.uppercase(), color = C.cyan, fontSize = 12.sp) }
                                DropdownMenu(expanded = roleExp, onDismissRequest = { roleExp = false }, modifier = Modifier.background(C.cardHi, RoundedCornerShape(12.dp)).border(1.dp, C.border, RoundedCornerShape(12.dp))) {
                                    listOf("PRIMARY", "RELAY").forEach { opt ->
                                        DropdownMenuItem(
                                            text = { Text(opt, color = if (opt == twoPhoneRole.uppercase()) C.cyan else C.t1) },
                                            onClick = { twoPhoneRole = opt; prefs.edit().putString("two_phone_role", opt).apply(); roleExp = false }
                                        )
                                    }
                                }
                            }
                        }
                        GroupDivider()
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                            SettingsRowIcon(Icons.Rounded.Router)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Relay Method", color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text("SMS works anywhere; Hotspot works offline on local network", color = C.t2, fontSize = 11.sp)
                            }
                            Box {
                                TextButton(onClick = { methodExp = true }) { Text(relayMethod.uppercase(), color = C.cyan, fontSize = 12.sp) }
                                DropdownMenu(expanded = methodExp, onDismissRequest = { methodExp = false }, modifier = Modifier.background(C.cardHi, RoundedCornerShape(12.dp)).border(1.dp, C.border, RoundedCornerShape(12.dp))) {
                                    listOf("SMS", "HOTSPOT").forEach { opt ->
                                        DropdownMenuItem(
                                            text = { Text(opt, color = if (opt == relayMethod.uppercase()) C.cyan else C.t1) },
                                            onClick = { relayMethod = opt; prefs.edit().putString("relay_method", opt).apply(); methodExp = false }
                                        )
                                    }
                                }
                            }
                        }
                        GroupDivider()
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SettingsRowIcon(Icons.Rounded.Phone); Spacer(Modifier.width(12.dp))
                                Column {
                                    Text("Paired Phone Number", color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    Text("PRIMARY → RELAY number, and RELAY → PRIMARY number", color = C.t2, fontSize = 11.sp)
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = pairedPhone,
                                onValueChange = { pairedPhone = it.trim() },
                                placeholder = { Text("e.g. 0712345678", color = C.t3) },
                                leadingIcon = { Icon(Icons.Rounded.Phone, null, tint = C.t2) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = fieldColors(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                            )
                        }
                        AnimatedVisibility(visible = relayMethod.uppercase() == "HOTSPOT") {
                            Column {
                                GroupDivider()
                                ToggleRow(Icons.Rounded.AutoFixHigh, "Auto‑Detect Relay IP", "Connect using current Wi‑Fi gateway (hotspot)", relayIpAuto) {
                                    relayIpAuto = it
                                    prefs.edit().putBoolean("relay_ip_auto", it).apply()
                                }
                                GroupDivider()
                                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        SettingsRowIcon(Icons.Rounded.Wifi); Spacer(Modifier.width(12.dp))
                                        Column {
                                            Text("Relay Hotspot IP", color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                            Text("IP address of the RELAY phone on the hotspot network", color = C.t2, fontSize = 11.sp)
                                        }
                                    }
                                    Spacer(Modifier.height(10.dp))
                                    OutlinedTextField(
                                        value = relayIp,
                                        onValueChange = { relayIp = it.trim() },
                                        placeholder = { Text(if (relayIpAuto) "Auto-detected" else "e.g. 192.168.43.1", color = C.t3) },
                                        leadingIcon = { Icon(Icons.Rounded.Router, null, tint = C.t2) },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = fieldColors(),
                                        enabled = !relayIpAuto,
                                        singleLine = true
                                    )
                                }
                            }
                        }
                        GroupDivider()
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SettingsRowIcon(Icons.Rounded.Tag); Spacer(Modifier.width(12.dp))
                                Column { Text("Relay Prefix", color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.Medium); Text("Prefix used when PRIMARY forwards via SMS", color = C.t2, fontSize = 11.sp) }
                            }
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(value = relayPrefix, onValueChange = { relayPrefix = it.trim().uppercase() }, placeholder = { Text("BINGWA", color = C.t3) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = fieldColors(), singleLine = true)
                        }
                        GroupDivider()
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SettingsRowIcon(Icons.Rounded.Lock); Spacer(Modifier.width(12.dp))
                                Column { Text("Relay PIN (optional)", color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.Medium); Text("Must match on both phones (recommended)", color = C.t2, fontSize = 11.sp) }
                            }
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = relayPin,
                                onValueChange = { relayPin = it.filter { ch -> ch.isDigit() }.take(6) },
                                placeholder = { Text("4–6 digits", color = C.t3) },
                                leadingIcon = { Icon(Icons.Rounded.Lock, null, tint = C.t2) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = fieldColors(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                            )
                        }
                        GroupDivider()
                        ToggleRow(Icons.Rounded.Sms, "Send Relay Results by SMS", "RELAY sends execution result back to PRIMARY", relaySendResults) {
                            relaySendResults = it
                            prefs.edit().putBoolean("relay_send_results", it).apply()
                        }
                        GroupDivider()
                        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                            Button(
                                onClick = {
                                    prefs.edit()
                                        .putString("two_phone_role", twoPhoneRole.trim().uppercase())
                                        .putString("relay_method", relayMethod.trim().uppercase())
                                        .putString("paired_phone", pairedPhone.trim())
                                        .putString("relay_ip", relayIp.trim())
                                        .putBoolean("relay_ip_auto", relayIpAuto)
                                        .putString("relay_prefix", relayPrefix.trim().uppercase())
                                        .putString("relay_pin", relayPin.trim())
                                        .apply()
                                    RelayManager.stopRelayHotspotService(ctx)
                                    val cfg = RelayManager.load(ctx)
                                    if (cfg.enabled && cfg.role == "RELAY" && cfg.method == "HOTSPOT") RelayManager.startRelayHotspotService(ctx)
                                    vib(ctx)
                                    Toast.makeText(ctx, "Two‑Phone settings saved", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = C.cyan)
                            ) {
                                Icon(Icons.Filled.Save, null, tint = C.bg, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp))
                                Text("Save Two‑Phone Settings", color = C.bg, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            SettingsGroup("Remote Admin") {
                ToggleRow(Icons.Rounded.PhoneAndroid, "Enable Remote Control", "Allow SMS commands from admin phone", remoteEnabled) { remoteEnabled = it; prefs.edit().putBoolean("remote_enabled", it).apply() }
                GroupDivider()
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    val pinHint = if (adminPin.trim().isNotEmpty()) "*".repeat((adminPin.trim().length - 2).coerceAtLeast(0)) + adminPin.trim().takeLast(2) else ""
                    Surface(shape = RoundedCornerShape(14.dp), color = C.w04, border = BorderStroke(1.dp, C.border)) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(C.cyanDim)
                                    .border(1.dp, C.cyan.copy(alpha = 0.20f), RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) { Icon(Icons.Rounded.Info, null, tint = C.cyan, modifier = Modifier.size(18.dp)) }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Command Format", color = C.t1, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(2.dp))
                                Text("${adminPrefix.trim().uppercase()} ${if (pinHint.isNotEmpty()) "$pinHint " else ""}STATUS", color = C.t2, fontSize = 11.sp)
                            }
                        }
                    }
                }
                GroupDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SettingsRowIcon(Icons.Rounded.Phone); Spacer(Modifier.width(12.dp))
                        Column { Text("Admin Phone", color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.Medium); Text("Number that can send remote commands", color = C.t2, fontSize = 11.sp) }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = adminPhone, onValueChange = { adminPhone = it.trim() }, placeholder = { Text("e.g. 0712345678", color = C.t3) }, leadingIcon = { Icon(Icons.Rounded.Phone, null, tint = C.t2) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = fieldColors(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                }
                GroupDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SettingsRowIcon(Icons.Rounded.Tag); Spacer(Modifier.width(12.dp))
                        Column { Text("Command Prefix", color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.Medium); Text("Word that must start every SMS command", color = C.t2, fontSize = 11.sp) }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = adminPrefix, onValueChange = { adminPrefix = it.trim().uppercase() }, placeholder = { Text("BINGWA", color = C.t3) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = fieldColors(), singleLine = true)
                }
                GroupDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SettingsRowIcon(Icons.Rounded.Lock); Spacer(Modifier.width(12.dp))
                        Column { Text("Security PIN", color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.Medium); Text("4–6 digit PIN to authorise commands (optional)", color = C.t2, fontSize = 11.sp) }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = adminPin,
                        onValueChange = { adminPin = it.filter { ch -> ch.isDigit() }.take(6) },
                        placeholder = { Text("4–6 digits", color = C.t3) },
                        leadingIcon = { Icon(Icons.Rounded.Lock, null, tint = C.t2) },
                        trailingIcon = {
                            IconButton(onClick = { pinVisible = !pinVisible }) {
                                Icon(if (pinVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, null, tint = C.t2)
                            }
                        },
                        visualTransformation = if (pinVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = fieldColors(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                }
                GroupDivider()
                SimPickerRow("Admin SMS SIM", "SIM used for admin notifications", sims, adminSmsSimId) { adminSmsSimId = it; prefs.edit().putInt("admin_sms_sim_id", it).apply() }
                GroupDivider()
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Button(
                        onClick = { prefs.edit().putString("admin_phone", adminPhone.trim()).putString("sms_prefix", adminPrefix.trim().uppercase()).putString("sms_pin", adminPin.trim()).apply(); vib(ctx); Toast.makeText(ctx, "Admin settings saved", Toast.LENGTH_SHORT).show() },
                        modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = C.cyan)
                    ) {
                        Icon(Icons.Filled.Save, null, tint = C.bg, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp))
                        Text("Save Admin Settings", color = C.bg, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                    }
                }
            }

            SettingsGroup("Admin Alerts") {
                ToggleRow(Icons.Rounded.Warning, "Low Airtime Alert", "SMS when balance drops below limit", alertLowBalance) { alertLowBalance = it; prefs.edit().putBoolean("alert_low_balance", it).apply() }
                AnimatedVisibility(visible = alertLowBalance) {
                    Column {
                        GroupDivider()
                        LimitRow("Limit (KES)", lowBalanceLimit) { v -> lowBalanceLimit = v; prefs.edit().putInt("low_balance_limit", v).apply() }
                    }
                }
                GroupDivider()
                ToggleRow(Icons.Rounded.Circle, "Low Tokens Alert", "SMS when tokens fall below limit", alertLowTokens) { alertLowTokens = it; prefs.edit().putBoolean("alert_low_tokens", it).apply() }
                AnimatedVisibility(visible = alertLowTokens) {
                    Column {
                        GroupDivider()
                        LimitRow("Limit", lowTokenLimit) { v -> lowTokenLimit = v; prefs.edit().putInt("low_token_limit", v).apply() }
                    }
                }
                GroupDivider()
                ToggleRow(Icons.Rounded.Error, "Failed Transaction Alert", "SMS when a transaction fails", alertFailedTx) { alertFailedTx = it; prefs.edit().putBoolean("alert_failed_tx", it).apply() }
                GroupDivider()
                ToggleRow(Icons.Rounded.BatteryAlert, "Low Battery Alert", "SMS when battery drops below limit", alertLowBattery) { alertLowBattery = it; prefs.edit().putBoolean("alert_low_battery", it).apply() }
                AnimatedVisibility(visible = alertLowBattery) {
                    Column {
                        GroupDivider()
                        LimitRow("Limit (%)", lowBatteryLimit) { v ->
                            val vv = v.coerceIn(1, 100)
                            lowBatteryLimit = vv
                            prefs.edit().putInt("low_battery_limit", vv).apply()
                        }
                    }
                }
            }

            SettingsGroup("Success Notification") {
                ToggleRow(Icons.Rounded.CheckCircle, "Send on Success", "SMS customer when bundle is delivered", notifySuccess) { notifySuccess = it; prefs.edit().putBoolean("notify_success", it).apply() }
                AnimatedVisibility(visible = notifySuccess) {
                    Column {
                        GroupDivider()
                        SimPickerRow("Send via SIM", "SIM used to send this SMS", sims, notifySimId) { notifySimId = it; prefs.edit().putInt("notify_sim_id", it).apply() }
                        GroupDivider()
                        TemplateEditor("Success Message Template", tplSuccess, C.green, SMS_TAGS) { tplSuccess = it; prefs.edit().putString("sms_tpl_success", it).apply() }
                        Spacer(Modifier.height(6.dp))
                        TemplatePreview(tplSuccess, C.green)
                    }
                }
            }

            SettingsGroup("Daily Limit / Pending Notification") {
                var notifyPending by remember { mutableStateOf(prefs.safeGetBoolean("notify_pending", true)) }
                ToggleRow(Icons.Rounded.Schedule, "Send on Daily Limit", "SMS customer when offer already used today", notifyPending) { notifyPending = it; prefs.edit().putBoolean("notify_pending", it).apply() }
                AnimatedVisibility(visible = notifyPending) {
                    Column {
                        GroupDivider()
                        TemplateEditor("Pending / Daily Limit Template", tplPending, C.amber, SMS_TAGS) { tplPending = it; prefs.edit().putString("sms_tpl_pending", it).apply() }
                        Spacer(Modifier.height(6.dp))
                        TemplatePreview(tplPending, C.amber)
                    }
                }
            }

            SettingsGroup("Failure Notification") {
                ToggleRow(Icons.Rounded.Warning, "Send on Failure", "SMS customer when bundle fails", notifyFailed) { notifyFailed = it; prefs.edit().putBoolean("notify_failed", it).apply() }
                AnimatedVisibility(visible = notifyFailed) {
                    Column {
                        GroupDivider()
                        TemplateEditor("Failure Message Template", tplFailed, C.red, SMS_TAGS) { tplFailed = it; prefs.edit().putString("sms_tpl_failed", it).apply() }
                        Spacer(Modifier.height(6.dp))
                        TemplatePreview(tplFailed, C.red)
                    }
                }
            }

            SettingsGroup("Scheduled Dispatch Notification") {
                ToggleRow(Icons.Rounded.Schedule, "Send on Scheduled Dispatch", "SMS customer when a scheduled bundle is finally sent", notifyScheduled) { notifyScheduled = it; prefs.edit().putBoolean("notify_scheduled", it).apply() }
                AnimatedVisibility(visible = notifyScheduled) {
                    Column {
                        GroupDivider()
                        SimPickerRow("Send via SIM", "SIM used to send this SMS", sims, notifySimId) { notifySimId = it; prefs.edit().putInt("notify_sim_id", it).apply() }
                        GroupDivider()
                        TemplateEditor("Scheduled Dispatch Template", tplScheduled, C.purple, SMS_TAGS) { tplScheduled = it; prefs.edit().putString("sms_tpl_scheduled", it).apply() }
                        Spacer(Modifier.height(6.dp))
                        TemplatePreview(tplScheduled, C.purple)
                    }
                }
            }

            SettingsGroup("Transactions") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    SettingsRowIcon(Icons.Rounded.AutoDelete); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Auto-Clear Transactions", color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text("Automatically delete old records", color = C.t2, fontSize = 11.sp)
                    }
                    Box {
                        TextButton(onClick = { clearExpanded = true }) { Text(autoClear, color = C.cyan, fontSize = 12.sp) }
                        DropdownMenu(expanded = clearExpanded, onDismissRequest = { clearExpanded = false }, modifier = Modifier.background(C.cardHi, RoundedCornerShape(12.dp)).border(1.dp, C.border, RoundedCornerShape(12.dp))) {
                            clearOptions.forEach { opt -> DropdownMenuItem(text = { Text(opt, color = if (opt == autoClear) C.cyan else C.t1) }, onClick = { autoClear = opt; prefs.edit().putString("auto_clear", opt).apply(); clearExpanded = false }) }
                        }
                    }
                }
                GroupDivider()
                LinkRow(Icons.Rounded.DeleteSweep, "Clear All Transactions", "Wipe entire transaction history", C.red) {
                    TransactionStore.clear(ctx)
                    Toast.makeText(ctx, "Transactions cleared", Toast.LENGTH_SHORT).show()
                }
            }

            SettingsGroup("Bundle Overview") {
                if (overviewData.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { Text("No transactions yet", color = C.t3, fontSize = 13.sp) }
                } else {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        overviewData.take(5).forEachIndexed { idx, row ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("${idx + 1}. ${row.first}", color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                Text("${row.second} sold", color = C.cyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                            if (idx < overviewData.size - 1) Divider(color = C.border.copy(.5f), thickness = 0.5.dp)
                        }
                    }
                    GroupDivider()
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TextButton(onClick = { TransactionStore.clear(ctx); overviewData = emptyList() }) {
                            Text("Clear Overview Data", color = C.amber, fontSize = 12.sp)
                        }
                    }
                }
            }

            SettingsGroup("Haptics") {
                ToggleRow(Icons.Rounded.Vibration, "Vibrate on Toggle", "Haptic feedback on start/stop", vibToggle) { vibToggle = it; prefs.edit().putBoolean("vibration_on_toggle", it).apply() }
                GroupDivider()
                ToggleRow(Icons.Rounded.PlayArrow, "Vibrate on Manual", "Haptic feedback on manual send", vibExecute) { vibExecute = it; prefs.edit().putBoolean("vibration_on_execute", it).apply() }
            }

            SettingsGroup("System Permissions") {
                LinkRow(Icons.Rounded.Accessibility, "Accessibility Service", "Required for ADVANCED popup navigation", C.purple) { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                GroupDivider()
                LinkRow(Icons.Rounded.Sms, "Default SMS App", "Required to read M-PESA messages", C.blue) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ctx.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }

            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.w04).border(1.dp, C.border, RoundedCornerShape(16.dp)).padding(20.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(C.cyanDim).border(1.dp, C.cyan.copy(alpha = 0.2f), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                        Text("B", color = C.cyan, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Bingwa Mobile", color = C.t1, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("Version ${BuildConfig.VERSION_NAME} · by Victor Ngetich", color = C.t2, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ─── Offers Screen ───────────────────────────────────────────────────────
@Composable
fun OffersScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var offers by remember {
        mutableStateOf(OfferRepository.load(ctx).toList())
    }
    var showDlg by remember { mutableStateOf(false) }
    var editItem by remember { mutableStateOf<OfferItem?>(null) }
    var showRepairDialog by remember { mutableStateOf(false) }
    var pendingApprovalOfferId by remember { mutableStateOf<Int?>(null) }

    fun persist(list: List<OfferItem>) {
        offers = list
        OfferRepository.save(ctx, list)
    }

    fun launchSignatureLearning(offer: OfferItem) {
        val cleanOffer = offer.clearPendingSignatureReview()
        val list = offers.toMutableList()
        val index = list.indexOfFirst { it.id == cleanOffer.id }
        if (index >= 0) list[index] = cleanOffer else list.add(cleanOffer)
        persist(list)
        Toast.makeText(ctx, "Learning USSD signature for ${cleanOffer.name}", Toast.LENGTH_SHORT).show()
        val learnPhone = "0700000000"
        val learnCode = cleanOffer.ussdCode.replace("pn", learnPhone, ignoreCase = true)
        ctx.startOfferAutomation(
            offer = cleanOffer,
            phoneNumber = learnPhone,
            txId = -1,
            finalCode = learnCode,
            mode = cleanOffer.executionMode,
            signatureLearning = true,
            executionPriority = USSD_EXECUTION_PRIORITY_SPECIAL
        )
    }

    DisposableEffect(Unit) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                offers = OfferRepository.load(ctx).toList()
                if (intent.action == "com.bingwa.mobile.OFFER_SIGNATURE_LEARNED") {
                    val offerId = intent.getIntExtra("offerId", -1)
                    val pendingOffer = offers.firstOrNull { it.id == offerId }
                    if (offerId >= 0 && pendingOffer != null &&
                        (pendingOffer.pendingLearnedSignature.isNotEmpty() || pendingOffer.pendingSignatureLearningCaptures.isNotEmpty())
                    ) {
                        pendingApprovalOfferId = offerId
                    }
                }
            }
        }
        val receiverRegistered = registerAppReceiver(ctx, receiver, android.content.IntentFilter().apply {
            addAction("com.bingwa.mobile.OFFER_SIGNATURE_LEARNED")
            addAction(OfferRepository.ACTION_OFFERS_UPDATED)
        })
        onDispose {
            if (receiverRegistered) {
                runCatching { ctx.unregisterReceiver(receiver) }
            }
        }
    }

    Scaffold(
        containerColor = C.bg,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Bundle Offers", color = C.t1) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.Filled.ArrowBack, null, tint = C.t1) } },
                actions = {
                    IconButton({ showRepairDialog = true }) { Icon(Icons.Outlined.Refresh, null, tint = C.green) }
                    IconButton({ editItem = null; showDlg = true }) { Icon(Icons.Filled.Add, null, tint = C.cyan) }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent)
            )
        }
    ) { pad ->
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, top = pad.calculateTopPadding() + 10.dp, end = 16.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (offers.isEmpty()) item { AnimatedEmptyState() }
            else itemsIndexed(offers) { idx, o ->
                OfferCard(
                    number = idx + 1,
                    o = o,
                    onEdit = { editItem = o; showDlg = true },
                    onToggle = { persist(offers.toMutableList().also { it[idx] = o.copy(enabled = !o.enabled) }) },
                    onDelete = { persist(offers.toMutableList().also { it.removeAt(idx) }) }
                )
            }
        }
        if (showDlg) {
            OfferDialog(
                existing = editItem,
                onDismiss = { showDlg = false },
                onSave = { updated ->
                    val list = offers.toMutableList()
                    val i = list.indexOfFirst { it.id == updated.id }
                    if (i >= 0) list[i] = updated else list.add(updated)
                    persist(list)
                    showDlg = false
                },
                onSaveAndLearn = { updated ->
                    showDlg = false
                    launchSignatureLearning(updated)
                },
                onApprovePending = { offer ->
                    OfferRepository.approveStagedSignature(ctx, offer.id)
                    offers = OfferRepository.load(ctx).toList()
                    pendingApprovalOfferId = null
                    Toast.makeText(ctx, "Approved learned signature for ${offer.name}", Toast.LENGTH_SHORT).show()
                    showDlg = false
                },
                onRelearnSignature = { offer ->
                    pendingApprovalOfferId = null
                    showDlg = false
                    launchSignatureLearning(offer)
                }
            )
        }
        if (showRepairDialog) {
            AlertDialog(
                containerColor = C.card,
                shape = RoundedCornerShape(20.dp),
                onDismissRequest = { showRepairDialog = false },
                title = { Text("Repair Bundle Catalog", color = C.t1, fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        "Refresh built-in bundle offers, restore any missing default bundles, remove broken entries, and keep your custom offers plus any USSD codes you edited manually.",
                        color = C.t2,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val result = OfferRepository.repair(ctx)
                        offers = result.offers
                        showRepairDialog = false
                        Toast.makeText(
                            ctx,
                            "Catalog repaired. Restored ${result.restoredDefaultOffers} default offers and removed ${result.removedBrokenOffers} broken entries.",
                            Toast.LENGTH_LONG
                        ).show()
                    }) {
                        Text("Repair Now", color = C.cyan, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRepairDialog = false }) {
                        Text("Cancel", color = C.t2)
                    }
                }
            )
        }
    }

    offers.firstOrNull { it.id == pendingApprovalOfferId }?.let { offer ->
        SignatureApprovalDialog(
            offer = offer,
            onApprove = {
                OfferRepository.approveStagedSignature(ctx, offer.id)
                offers = OfferRepository.load(ctx).toList()
                pendingApprovalOfferId = null
                Toast.makeText(ctx, "Approved learned signature for ${offer.name}", Toast.LENGTH_SHORT).show()
            },
            onRelearn = {
                pendingApprovalOfferId = null
                launchSignatureLearning(offer)
            },
            onDismiss = { pendingApprovalOfferId = null }
        )
    }
}

@Composable
fun OfferCard(number: Int, o: OfferItem, onEdit: () -> Unit, onToggle: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val hasPendingSignature = o.pendingLearnedSignature.isNotEmpty() || o.pendingSignatureLearningCaptures.isNotEmpty()
    val protectionLabel = when {
        o.signatureDetectionEnabled && o.signatureAction == "ADJUST" -> "Guard + Adjust"
        o.signatureDetectionEnabled -> "Guard + Stop"
        else -> "Off"
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = C.cardHi.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, if (o.enabled) C.borderHi.copy(alpha = 0.85f) else C.border.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (o.enabled) C.cyan.copy(alpha = 0.16f) else C.w08,
                    border = BorderStroke(1.dp, if (o.enabled) C.cyan.copy(alpha = 0.28f) else C.border.copy(alpha = 0.45f))
                ) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Wifi,
                            null,
                            tint = if (o.enabled) C.cyan else C.t3,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            o.name,
                            color = if (o.enabled) C.t1 else C.t2,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            lineHeight = 22.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = if (o.enabled) C.green.copy(alpha = 0.14f) else C.red.copy(alpha = 0.12f)
                        ) {
                            Text(
                                if (o.enabled) "LIVE" else "PAUSED",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                color = if (o.enabled) C.green else C.red,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        MiniTag(o.category.uppercase(), C.orange)
                        MiniTag(o.targetDevice.uppercase(), C.blue)
                        MiniTag(offerSimSelectionLabel(o.simSelection).uppercase(), C.purple)
                    }
                }
                Box(
                    modifier = Modifier,
                    contentAlignment = Alignment.TopEnd
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = C.w04,
                        border = BorderStroke(1.dp, C.border.copy(alpha = 0.75f))
                    ) {
                        IconButton({ menu = true }, Modifier.size(36.dp)) {
                            Icon(Icons.Filled.MoreVert, null, tint = C.t2, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                DropdownMenu(
                    expanded = menu,
                    onDismissRequest = { menu = false },
                    modifier = Modifier
                        .background(C.cardHi, RoundedCornerShape(18.dp))
                        .border(1.dp, C.border.copy(alpha = 0.75f), RoundedCornerShape(18.dp)),
                    containerColor = C.cardHi,
                    shape = RoundedCornerShape(18.dp),
                    tonalElevation = 0.dp,
                    shadowElevation = 10.dp
                ) {
                    Column(modifier = Modifier.padding(vertical = 6.dp)) {
                        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(text = "Offer Actions", color = C.cyan, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, letterSpacing = 1.sp)
                        }
                        Divider(color = C.border.copy(alpha = 0.45f), thickness = 0.5.dp)
                        MenuActionItem(
                            icon = Icons.Outlined.Edit,
                            iconTint = C.cyan,
                            iconBg = C.cyan.copy(alpha = 0.14f),
                            title = "Edit Offer",
                            subtitle = "USSD, mode, device, SIM",
                            onClick = { menu = false; onEdit() }
                        )
                        Divider(color = C.border.copy(alpha = 0.3f), thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 14.dp))
                        MenuActionItem(
                            icon = if (o.enabled) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            iconTint = if (o.enabled) C.amber else C.green,
                            iconBg = if (o.enabled) C.amber.copy(alpha = 0.14f) else C.green.copy(alpha = 0.14f),
                            title = if (o.enabled) "Disable Offer" else "Enable Offer",
                            subtitle = if (o.enabled) "Temporarily stop" else "Resume this offer",
                            onClick = { menu = false; onToggle() }
                        )
                        Divider(color = C.border.copy(alpha = 0.3f), thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 14.dp))
                        MenuActionItem(
                            icon = Icons.Outlined.Delete,
                            iconTint = C.red,
                            iconBg = C.red.copy(alpha = 0.10f),
                            title = "Delete Offer",
                            subtitle = "Remove permanently",
                            titleColor = C.red,
                            onClick = { menu = false; onDelete() }
                        )
                    }
                }
            }
            Divider(color = C.border.copy(alpha = 0.7f), thickness = 0.5.dp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OfferInfoTile("Price", "KES ${o.price}", C.cyan, Icons.Outlined.Badge, Modifier.weight(1f))
                OfferInfoTile("Mode", o.executionMode, C.purple, Icons.Outlined.Tune, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OfferInfoTile("Device", o.targetDevice, C.blue, Icons.Outlined.PhoneAndroid, Modifier.weight(1f))
                OfferInfoTile("Protection", protectionLabel, if (o.signatureDetectionEnabled) C.green else C.t3, if (o.signatureDetectionEnabled) Icons.Outlined.Shield else Icons.Outlined.Security, Modifier.weight(1f))
            }
            OfferCodeBlock("USSD Code", o.ussdCode)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (o.signatureDetectionEnabled) MiniTag("GUARD ON", C.green)
                if (o.learnedSignature.isNotEmpty()) MiniTag("STEPS ${o.learnedSignature.size}", C.green)
                if (o.signatureLearningCaptures.isNotEmpty()) MiniTag("POPUPS ${o.signatureLearningCaptures.size}", C.amber)
                if (hasPendingSignature) MiniTag("PENDING REVIEW", C.orange)
            }
        }
    }
}

@Composable
private fun SignatureApprovalDialog(
    offer: OfferItem,
    onApprove: () -> Unit,
    onRelearn: () -> Unit,
    onDismiss: () -> Unit
) {
    var showDetails by remember { mutableStateOf(false) }
    val pendingSteps = offer.pendingLearnedSignature
    val pendingCaptures = offer.pendingSignatureLearningCaptures
    val preview = pendingCaptures.lastOrNull()?.popupText
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.take(180)
        .orEmpty()

    AlertDialog(
        containerColor = C.card,
        shape = RoundedCornerShape(20.dp),
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Verify Learned Signature", color = C.t1, fontWeight = FontWeight.Bold)
                Text(
                    offer.name,
                    color = C.t2,
                    fontSize = 11.sp
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "The app learned ${pendingSteps.size} menu step(s) and captured ${pendingCaptures.size} popup(s). Verify it if every step and selection is correct, or relearn it if anything looks wrong. Each saved step keeps the recorded text and the option that was chosen.",
                    color = C.t2,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
                Text(
                    "Learned on ${formatSignatureLearnedAt(offer.pendingSignatureLearnedAt)}",
                    color = C.t3,
                    fontSize = 11.sp
                )
                if (preview.isNotBlank()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = C.w04,
                        border = BorderStroke(1.dp, C.border)
                    ) {
                        Text(
                            "Last popup: $preview",
                            modifier = Modifier.padding(10.dp),
                            color = C.t1,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
                if (pendingSteps.isNotEmpty() || pendingCaptures.isNotEmpty()) {
                    TextButton(
                        onClick = { showDetails = true },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("Review Captured Steps", color = C.cyan, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onRelearn,
                    border = BorderStroke(1.dp, C.amber.copy(alpha = 0.5f))
                ) {
                    Text("Relearn", color = C.amber, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = onApprove,
                    colors = ButtonDefaults.buttonColors(containerColor = C.green)
                ) {
                    Text("Verify", color = C.bg, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Later", color = C.t2) }
        }
    )

    if (showDetails) {
        SignatureLearningDetailsDialog(
            learnedSteps = pendingSteps,
            learningCaptures = pendingCaptures,
            learnedAt = offer.pendingSignatureLearnedAt,
            onDismiss = { showDetails = false }
        )
    }
}

@Composable
private fun OfferInfoTile(
    label: String,
    value: String,
    accent: Color,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = accent.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.20f))
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 68.dp)
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(accent.copy(alpha = 0.13f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, tint = accent, modifier = Modifier.size(13.dp))
                }
                Text(label.uppercase(), color = C.t3, fontSize = 9.sp, letterSpacing = 0.8.sp)
            }
            Text(
                value,
                color = C.t1,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 17.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun OfferCodeBlock(label: String, value: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = C.w04,
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.9f))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(C.cyan.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Outlined.Terminal, null, tint = C.cyan, modifier = Modifier.size(16.dp))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(label.uppercase(), color = C.t3, fontSize = 10.sp, letterSpacing = 1.sp)
                        Text("Careful: keep `pn` placeholder", color = C.t2, fontSize = 10.sp)
                    }
                }
                MiniTag("READY", C.cyan)
            }
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = C.surface.copy(alpha = 0.42f),
                border = BorderStroke(1.dp, C.border.copy(alpha = 0.65f))
            ) {
                Text(
                    value,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                    color = C.t1,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Info, null, tint = C.t3, modifier = Modifier.size(14.dp))
                Text("Use `pn` as the recipient number placeholder.", color = C.t3, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun SignatureLearningDetailsDialog(
    learnedSteps: List<UssdSignatureStep>,
    learningCaptures: List<UssdLearningCapture>,
    learnedAt: Long,
    onDismiss: () -> Unit
) {
    val details = remember(learnedSteps, learningCaptures) {
        buildLearnedStepDetails(learnedSteps, learningCaptures)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = C.bg
        ) {
            Scaffold(
                containerColor = C.bg,
                topBar = {
                    Surface(
                        color = C.surface.copy(alpha = 0.96f),
                        border = BorderStroke(1.dp, C.border.copy(alpha = 0.6f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text("USSD Learning Record", color = C.t1, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                Text(
                                    "Saved on ${formatSignatureLearnedAt(learnedAt)}",
                                    color = C.t2,
                                    fontSize = 12.sp
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = C.w04,
                                border = BorderStroke(1.dp, C.border.copy(alpha = 0.85f))
                            ) {
                                IconButton(onClick = onDismiss) {
                                    Icon(Icons.Outlined.Close, null, tint = C.t2)
                                }
                            }
                        }
                    }
                }
            ) { pad ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = pad.calculateTopPadding() + 16.dp,
                        end = 16.dp,
                        bottom = 28.dp + pad.calculateBottomPadding()
                    ),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        OfferDialogSection(
                            title = "Learning Summary",
                            subtitle = "Review the captured steps, the selected option for each step, and the recorded popup text."
                        ) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                MiniTag("STEPS ${learnedSteps.size}", C.green)
                                MiniTag("POPUPS ${learningCaptures.size}", C.amber)
                                MiniTag(if (details.isEmpty()) "NO RECORD" else "RECORD READY", if (details.isEmpty()) C.red else C.cyan)
                            }
                        }
                    }
                    if (details.isEmpty()) {
                        item {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(18.dp),
                                color = C.cardHi,
                                border = BorderStroke(1.dp, C.border)
                            ) {
                                Text(
                                    "No learned steps are available yet.",
                                    modifier = Modifier.padding(16.dp),
                                    color = C.t2,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    } else {
                        items(details, key = { it.stepIndex }) { detail ->
                            SignatureLearningRecordCard(detail = detail)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SignatureLearningRecordCard(
    detail: LearnedStepDetail,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = C.cardHi,
        border = BorderStroke(1.dp, C.border)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        if (detail.stepIndex >= 0) "Step ${detail.stepIndex + 1}" else "Final Popup",
                        color = C.cyan,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                    if (detail.menuTitle.isNotBlank()) {
                        Text(
                            detail.menuTitle,
                            color = C.t2,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    }
                }
                MiniTag(
                    if (detail.selectedOptionLabel.isBlank()) "NO OPTION" else "OPTION SAVED",
                    if (detail.selectedOptionLabel.isBlank()) C.red else C.green
                )
            }

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = C.surface.copy(alpha = 0.45f),
                border = BorderStroke(1.dp, C.border.copy(alpha = 0.75f))
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Selected option: ${detail.selectedOptionLabel.ifBlank { "Not captured" }}",
                        color = C.t1,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                    Text(
                        "Sent input: ${detail.enteredInput.ifBlank { "Not captured" }}",
                        color = C.t2,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                    if (detail.menuOptionsSnapshot.isNotEmpty()) {
                        Text(
                            "Visible options: ${detail.menuOptionsSnapshot.joinToString(" | ")}",
                            color = C.t3,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Record",
                    color = C.t1,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
                if (detail.recordedTexts.isEmpty()) {
                    Text(
                        "No recorded text was saved for this step.",
                        color = C.t3,
                        fontSize = 11.sp
                    )
                } else {
                    detail.recordedTexts.forEachIndexed { index, popupText ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            color = C.w04,
                            border = BorderStroke(1.dp, C.border.copy(alpha = 0.7f))
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    if (index == 0) "Recorded text" else "Recorded text ${index + 1}",
                                    color = C.amber,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.8.sp
                                )
                                Text(
                                    popupText,
                                    color = C.t1,
                                    fontSize = 11.sp,
                                    lineHeight = 17.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SignatureLearningRecordSection(
    title: String,
    subtitle: String,
    details: List<LearnedStepDetail>,
    learnedAt: Long,
    learnedStepsCount: Int,
    popupCount: Int,
    emptyMessage: String,
    accent: Color,
    actions: @Composable (() -> Unit)? = null
) {
    OfferDialogSection(title = title, subtitle = subtitle) {
        Text(
            "Saved on ${formatSignatureLearnedAt(learnedAt)}",
            color = C.t3,
            fontSize = 11.sp
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            MiniTag("STEPS $learnedStepsCount", accent)
            MiniTag("POPUPS $popupCount", C.amber)
            MiniTag(if (details.isEmpty()) "NO RECORD" else "RECORD READY", if (details.isEmpty()) C.red else C.cyan)
        }
        if (details.isEmpty()) {
            Text(
                emptyMessage,
                color = C.t2,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                details.forEach { detail ->
                    SignatureLearningRecordCard(detail = detail)
                }
            }
        }
        actions?.invoke()
    }
}

@Composable
private fun CompactDialogSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    checkedTrackColor: Color,
    uncheckedThumbColor: Color = C.red
) {
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 16.dp else 2.dp,
        animationSpec = tween(180),
        label = "compact_dialog_switch"
    )
    val trackColor by animateColorAsState(
        targetValue = if (checked) checkedTrackColor.copy(alpha = 0.92f) else C.surface,
        label = "compact_dialog_switch_track"
    )
    val borderColor by animateColorAsState(
        targetValue = if (checked) checkedTrackColor.copy(alpha = 0.34f) else uncheckedThumbColor.copy(alpha = 0.62f),
        label = "compact_dialog_switch_border"
    )
    val thumbColor by animateColorAsState(
        targetValue = if (checked) C.bg else uncheckedThumbColor,
        label = "compact_dialog_switch_thumb"
    )
    Box(
        modifier = Modifier
            .width(40.dp)
            .height(24.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(trackColor)
            .border(1.dp, borderColor, RoundedCornerShape(999.dp))
            .clickable { onCheckedChange(!checked) }
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset, y = 3.dp)
                .size(18.dp)
                .clip(CircleShape)
                .background(thumbColor)
        )
    }
}

@Composable
private fun CompactDialogToggleCard(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector,
    checkedAccent: Color,
    uncheckedAccent: Color = C.red,
    badgeText: String? = null
) {
    val accent = if (checked) checkedAccent else uncheckedAccent
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (checked) checkedAccent.copy(alpha = 0.12f) else uncheckedAccent.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.24f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(14.dp))
            }
            Column(
                modifier = Modifier
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(title, color = C.t1, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                badgeText?.let {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = accent.copy(alpha = 0.14f)
                    ) {
                        Text(
                            text = it.uppercase(Locale.getDefault()),
                            color = accent,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                }
                Text(description, color = C.t2, fontSize = 10.sp, lineHeight = 14.sp)
            }
            CompactDialogSwitch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                checkedTrackColor = checkedAccent,
                uncheckedThumbColor = uncheckedAccent
            )
        }
    }
}

@Composable
private fun ExecutionPathProtectionToggle(
    signatureEnabled: Boolean,
    onProtectionChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "Protection",
                color = C.t1,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Verify signature before dispatch",
                color = C.t3,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }
        CompactDialogSwitch(
            checked = signatureEnabled,
            onCheckedChange = onProtectionChange,
            checkedTrackColor = C.cyan
        )
    }
}

@Composable
private fun StatusBanner(enabled: Boolean, price: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (enabled) C.green.copy(alpha = 0.08f) else C.red.copy(alpha = 0.06f),
        border = BorderStroke(1.dp, if (enabled) C.green.copy(alpha = 0.25f) else C.red.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (enabled) C.green.copy(alpha = 0.12f) else C.red.copy(alpha = 0.1f)
                ) {
                    Icon(
                        if (enabled) Icons.Rounded.CheckCircle else Icons.Rounded.PauseCircle,
                        contentDescription = null,
                        tint = if (enabled) C.green else C.red,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        if (enabled) "Live" else "Paused",
                        color = C.t1,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                    Text(
                        if (enabled) "Visible in console" else "Hidden from matching",
                        color = C.t3,
                        fontSize = 11.sp
                    )
                }
            }
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = C.amberDim,
                border = BorderStroke(1.dp, C.amber.copy(alpha = 0.25f))
            ) {
                Text(
                    "KES ${price.ifBlank { "0" }}",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    color = C.amber,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = C.cardHi.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.75f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = C.amberDim,
                    border = BorderStroke(1.dp, C.amber.copy(alpha = 0.25f))
                ) {
                    Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = null, tint = C.amber, modifier = Modifier.size(12.dp))
                    }
                }
                Text(title, color = C.t1, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
            content()
        }
    }
}

@Composable
private fun ProtectionRow(
    signatureEnabled: Boolean,
    onProtectionChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Protection", color = C.t1, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text("Verify signature before dispatch", color = C.t3, fontSize = 10.5.sp, lineHeight = 14.sp)
        }
        CompactDialogSwitch(
            checked = signatureEnabled,
            onCheckedChange = onProtectionChange,
            checkedTrackColor = C.cyan
        )
    }
}

@Composable
private fun offerExecutionModeOptions(): List<String> = listOf(OFFER_EXECUTION_MODE_SIMPLE, OFFER_EXECUTION_MODE_ADVANCED)

@Composable
private fun OfferDialogSection(
    title: String,
    subtitle: String? = null,
    barColor: Color = C.cyan,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = C.cardHi.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.9f))
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    title,
                    color = C.t1,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    fontFamily = FontFamily.SansSerif
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        color = C.t2,
                        fontSize = 12.sp,
                        lineHeight = 1.5.sp,
                        fontFamily = FontFamily.SansSerif
                    )
                }
            }
            content()
        }
    }
}

@Composable
private fun OfferStatusCard(enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val badgeText = if (enabled) "Live" else "Paused"
    val description = if (enabled) {
        "Visible in console and available for matching."
    } else {
        "Hidden from matching until you turn it back on."
    }
    CompactDialogToggleCard(
        title = "Bundle status",
        description = description,
        checked = enabled,
        onCheckedChange = onCheckedChange,
        icon = if (enabled) Icons.Rounded.CheckCircle else Icons.Rounded.PauseCircle,
        checkedAccent = C.green,
        uncheckedAccent = C.red,
        badgeText = badgeText
    )
}

@Composable
private fun OfferEditorOverviewCard(
    existing: OfferItem?,
    category: String,
    mode: String,
    device: String,
    simSelection: Int,
    signatureEnabled: Boolean,
    hasLearnedSignature: Boolean,
    hasPendingSignature: Boolean
) {
    OfferDialogSection(
        title = if (existing != null) "Offer Configuration" else "New Offer Configuration",
        subtitle = "Review the key settings before editing the detailed fields below.",
        barColor = C.amber
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = C.amber.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, C.amber.copy(alpha = 0.35f)),
                modifier = Modifier.size(56.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        if (existing != null) Icons.Outlined.Edit else Icons.Outlined.Add,
                        null,
                        tint = C.amber,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (existing != null) "Editing ${existing.name.ifBlank { existing.category }}" else "Creating New Offer",
                    color = C.t1,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    lineHeight = 22.sp
                )
                Text(
                    if (existing != null) "Modify USSD, SIM, device, and protection settings" else "Set up USSD, SIM, device, and protection",
                    color = C.t3,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }
        }

        Divider(color = C.border.copy(alpha = 0.7f), thickness = 0.5.dp)

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    color = C.cardHi.copy(alpha = 0.75f),
                    border = BorderStroke(1.dp, C.border.copy(alpha = 0.7f))
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("CATEGORY", color = C.t3, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
                        Text(category.ifBlank { "—" }, color = C.t1, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                }
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    color = C.cardHi.copy(alpha = 0.75f),
                    border = BorderStroke(1.dp, C.border.copy(alpha = 0.7f))
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("EXECUTION", color = C.t3, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
                        Text(mode.ifBlank { "—" }, color = C.t1, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    color = C.cardHi.copy(alpha = 0.75f),
                    border = BorderStroke(1.dp, C.border.copy(alpha = 0.7f))
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("DEVICE", color = C.t3, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
                        Text(device.ifBlank { "—" }, color = C.t1, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                }
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    color = C.cardHi.copy(alpha = 0.75f),
                    border = BorderStroke(1.dp, C.border.copy(alpha = 0.7f))
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("SIM", color = C.t3, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
                        Text(offerSimSelectionLabel(simSelection).ifBlank { "—" }, color = C.t1, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                }
            }
        }

        Divider(color = C.border.copy(alpha = 0.7f), thickness = 0.5.dp)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (signatureEnabled) C.green.copy(alpha = 0.12f) else C.w04,
                border = BorderStroke(1.dp, if (signatureEnabled) C.green.copy(alpha = 0.4f) else C.border.copy(alpha = 0.7f)),
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (signatureEnabled) Icons.Rounded.Shield else Icons.Rounded.Security,
                        null,
                        tint = if (signatureEnabled) C.green else C.t3,
                        modifier = Modifier.size(15.dp)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            if (signatureEnabled) "Protection On" else "Protection Off",
                            color = if (signatureEnabled) C.green else C.t3,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                        Text(
                            if (signatureEnabled) "Signature verification active" else "No signature verification",
                            color = C.t3,
                            fontSize = 10.sp
                        )
                    }
                }
            }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (hasLearnedSignature) C.green.copy(alpha = 0.12f) else C.w04,
                border = BorderStroke(1.dp, if (hasLearnedSignature) C.green.copy(alpha = 0.4f) else C.border.copy(alpha = 0.7f)),
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.VerifiedUser,
                        null,
                        tint = if (hasLearnedSignature) C.green else C.t3,
                        modifier = Modifier.size(15.dp)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            if (hasLearnedSignature) "Learned" else "Not Learned",
                            color = if (hasLearnedSignature) C.green else C.t3,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                        Text(
                            if (hasLearnedSignature) "Signature captured" else "No signature data",
                            color = C.t3,
                            fontSize = 10.sp
                        )
                    }
                }
            }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (hasPendingSignature) C.amber.copy(alpha = 0.12f) else C.w04,
                border = BorderStroke(1.dp, if (hasPendingSignature) C.amber.copy(alpha = 0.4f) else C.border.copy(alpha = 0.7f)),
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.AutoFixHigh,
                        null,
                        tint = if (hasPendingSignature) C.amber else C.t3,
                        modifier = Modifier.size(15.dp)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            if (hasPendingSignature) "Review Needed" else "No Pending",
                            color = if (hasPendingSignature) C.amber else C.t3,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                        Text(
                            if (hasPendingSignature) "Awaiting approval" else "All clear",
                            color = C.t3,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OfferDialogToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = C.cardHi.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.9f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val ledAlpha by rememberInfiniteTransition(label = "toggle_led").animateFloat(
                        initialValue = 1f,
                        targetValue = 0.35f,
                        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Reverse)
                    )
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(C.cyan.copy(alpha = ledAlpha), CircleShape)
                    )
                    Text(title, color = C.t1, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
                Text(description, color = C.t2, fontSize = 11.sp, lineHeight = 16.sp)
            }
            CompactDialogSwitch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                checkedTrackColor = C.cyan
            )
        }
    }
}

@Composable
fun OfferDialog(
    existing: OfferItem?,
    onDismiss: () -> Unit,
    onSave: (OfferItem) -> Unit,
    onSaveAndLearn: (OfferItem) -> Unit,
    onApprovePending: (OfferItem) -> Unit,
    onRelearnSignature: (OfferItem) -> Unit
) {
    val ctx = LocalContext.current
    var name by remember(existing?.id, existing?.name) { mutableStateOf(existing?.name ?: "") }
    var codeField by rememberSaveable(existing?.id, existing?.ussdCode, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(existing?.ussdCode ?: ""))
    }
    val code = codeField.text
    var price by remember(existing?.id, existing?.price) { mutableStateOf(existing?.price?.toString() ?: "") }
    val initialCategory = normalizeOfferCategory(existing?.category)
    val initialMode = normalizeOfferExecutionMode(existing?.executionMode, initialCategory)
    var mode by remember(existing?.id, initialMode) { mutableStateOf(initialMode) }
    var cat by remember(existing?.id, initialCategory) { mutableStateOf(initialCategory) }
    var device by remember(existing?.id, existing?.targetDevice) { mutableStateOf(existing?.targetDevice ?: "PRIMARY") }
    var simSelection by remember(existing?.id, existing?.simSelection) {
        mutableIntStateOf(normalizeOfferSimSelection(existing?.simSelection ?: OFFER_SIM_USE_GENERAL))
    }
    var enabled by remember(existing?.id, existing?.enabled) { mutableStateOf(existing?.enabled ?: true) }
    var signatureEnabled by remember(existing?.id, existing?.signatureDetectionEnabled) { mutableStateOf(existing?.signatureDetectionEnabled ?: false) }
    var signatureAction by remember(existing?.id, existing?.signatureAction) { mutableStateOf(existing?.signatureAction ?: "STOP") }
    var modeExp by remember { mutableStateOf(false) }
    var catExp by remember { mutableStateOf(false) }
    var devExp by remember { mutableStateOf(false) }
    var simExp by remember { mutableStateOf(false) }
    var signatureExp by remember { mutableStateOf(false) }
    var modeTouched by remember(existing?.id, initialMode, initialCategory) {
        mutableStateOf(existing != null && initialMode != defaultExecutionModeForCategory(initialCategory))
    }
    val learnedSteps = existing?.learnedSignature.orEmpty()
    val learningCaptures = existing?.signatureLearningCaptures.orEmpty()
    val learnedAt = existing?.signatureLearnedAt ?: 0L
    val pendingLearnedSteps = existing?.pendingLearnedSignature.orEmpty()
    val pendingLearningCaptures = existing?.pendingSignatureLearningCaptures.orEmpty()
    val pendingLearnedAt = existing?.pendingSignatureLearnedAt ?: 0L
    val hasLearnedSignature = learnedSteps.isNotEmpty() || learningCaptures.isNotEmpty()
    val hasPendingSignature = pendingLearnedSteps.isNotEmpty() || pendingLearningCaptures.isNotEmpty()
    val learnedDetails = remember(learnedSteps, learningCaptures) {
        buildLearnedStepDetails(learnedSteps, learningCaptures)
    }
    val pendingLearnedDetails = remember(pendingLearnedSteps, pendingLearningCaptures) {
        buildLearnedStepDetails(pendingLearnedSteps, pendingLearningCaptures)
    }
    val canSave = remember(name, price, code) {
        name.isNotBlank() && code.isNotBlank() && (price.toIntOrNull() ?: 0) > 0
    }

    fun buildOffer(): OfferItem? {
        val p = price.toIntOrNull() ?: 0
        if (name.isBlank() || p <= 0 || code.isBlank()) return null
        val codeChanged = existing?.ussdCode?.trim()?.equals(code.trim(), ignoreCase = true) == false
        val normalizedCategory = normalizeOfferCategory(cat)
        val normalizedMode = normalizeOfferExecutionMode(mode, normalizedCategory)
        return OfferItem(
            id = existing?.id ?: (System.currentTimeMillis() % 100000).toInt(),
            catalogKey = existing?.catalogKey.orEmpty(),
            name = name.trim(),
            price = p,
            ussdCode = code.trim(),
            catalogDefaultUssdCode = existing?.catalogDefaultUssdCode.orEmpty(),
            enabled = enabled,
            executionMode = normalizedMode,
            category = normalizedCategory,
            targetDevice = device,
            simSelection = simSelection,
            signatureDetectionEnabled = signatureEnabled,
            signatureAction = signatureAction,
            learnedSignature = if (codeChanged) emptyList() else existing?.learnedSignature.orEmpty(),
            signatureLearnedAt = if (codeChanged) 0L else (existing?.signatureLearnedAt ?: 0L),
            signatureLearningCaptures = if (codeChanged) emptyList() else existing?.signatureLearningCaptures.orEmpty(),
            pendingLearnedSignature = if (codeChanged) emptyList() else existing?.pendingLearnedSignature.orEmpty(),
            pendingSignatureLearnedAt = if (codeChanged) 0L else (existing?.pendingSignatureLearnedAt ?: 0L),
            pendingSignatureLearningCaptures = if (codeChanged) emptyList() else existing?.pendingSignatureLearningCaptures.orEmpty()
        )
    }

    fun updateCategory(nextCategory: String) {
        val normalizedCategory = normalizeOfferCategory(nextCategory)
        val currentDefaultMode = defaultExecutionModeForCategory(cat)
        val shouldFollowCategoryDefault = !modeTouched || mode == currentDefaultMode
        cat = normalizedCategory
        if (shouldFollowCategoryDefault) {
            mode = defaultExecutionModeForCategory(normalizedCategory)
            modeTouched = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = C.bg
        ) {
            val showSaveAndLearn = signatureEnabled
            val showProtectionRecords = signatureEnabled || hasLearnedSignature || hasPendingSignature
            Scaffold(
                containerColor = C.bg,
                topBar = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    brush = Brush.verticalGradient(
                                        colors = listOf(C.amber.copy(alpha = 0.08f), Color.Transparent)
                                    )
                                )
                                .statusBarsPadding()
                                .padding(horizontal = 20.dp, vertical = 16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Top
                            ) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = C.amberDim,
                                            border = BorderStroke(1.dp, C.amber.copy(alpha = 0.3f))
                                        ) {
                                            Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                                                Icon(
                                                    Icons.Outlined.Tag,
                                                    contentDescription = null,
                                                    tint = C.amber,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                            Text(
                                                "Bundle Settings",
                                                color = C.t3,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium,
                                                letterSpacing = 0.3.sp
                                            )
                                            Text(
                                                if (existing != null) "Edit Bundle" else "New Bundle",
                                                color = C.t1,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 20.sp,
                                                fontFamily = FontFamily.SansSerif,
                                                lineHeight = 26.sp
                                            )
                                        }
                                    }
                                    Text(
                                        existing?.let { it.name.ifBlank { it.category } } ?: "Configure a new offer",
                                        color = C.t2,
                                        fontSize = 12.sp,
                                        lineHeight = 18.sp,
                                        modifier = Modifier.padding(start = 42.dp)
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = C.cardHi.copy(alpha = 0.92f),
                                    border = BorderStroke(1.dp, C.border.copy(alpha = 0.85f))
                                ) {
                                    IconButton(onClick = onDismiss) {
                                        Icon(Icons.Outlined.Close, null, tint = C.t2, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                        Divider(color = C.border.copy(alpha = 0.5f))
                    }
                },
                bottomBar = {
                    Surface(
                        color = C.bg,
                        border = BorderStroke(1.dp, C.border.copy(alpha = 0.6f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = onDismiss,
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                modifier = Modifier.height(40.dp)
                            ) {
                                Text("Cancel", color = C.t3, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                            }
                            if (showSaveAndLearn) {
                                OutlinedButton(
                                    onClick = { buildOffer()?.let(onSaveAndLearn) },
                                    enabled = canSave,
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, C.cyan.copy(alpha = 0.5f)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = C.cyan),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                    modifier = Modifier.wrapContentWidth().height(40.dp)
                                ) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Outlined.AutoFixHigh, null, tint = C.cyan, modifier = Modifier.size(13.dp))
                                        Text("Save & Learn", color = C.cyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    }
                                }
                            }
                            Button(
                                onClick = { buildOffer()?.let(onSave) },
                                enabled = canSave,
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = C.amber),
                                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                                modifier = Modifier.weight(1f).height(40.dp)
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.Check, contentDescription = null, tint = Color(0xFF1A1305), modifier = Modifier.size(13.dp))
                                    Text("Save", color = Color(0xFF1A1305), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            ) { pad ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = pad.calculateTopPadding() + 12.dp,
                        end = 16.dp,
                        bottom = pad.calculateBottomPadding() + 20.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        StatusBanner(enabled = enabled, price = price)
                    }

                    item {
                        OfferEditorOverviewCard(
                            existing = existing,
                            category = cat,
                            mode = mode,
                            device = device,
                            simSelection = simSelection,
                            signatureEnabled = signatureEnabled,
                            hasLearnedSignature = hasLearnedSignature,
                            hasPendingSignature = hasPendingSignature
                        )
                    }

                    item {
                        SettingsCard("Offer Details", Icons.Outlined.Tag) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Column(modifier = Modifier.weight(1f)) {
                                    FieldLabel("Category")
                                    DialogDropdown("Category", cat, offerCategoryOptions(), catExp, { catExp = it }) {
                                        updateCategory(it)
                                        catExp = false
                                    }
                                }
                                Column(modifier = Modifier.weight(1.2f)) {
                                    FieldLabel("Plan Name")
                                    OutlinedTextField(
                                        value = name,
                                        onValueChange = { name = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(10.dp),
                                        placeholder = { Text("e.g. 20 SMS Daily", color = C.t3) },
                                        colors = dialogFieldColors(),
                                        singleLine = true,
                                        textStyle = LocalTextStyle.current.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    FieldLabel("Selling Price (KES)")
                                    OutlinedTextField(
                                        value = price,
                                        onValueChange = { price = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(10.dp),
                                        placeholder = { Text("e.g. 5", color = C.t3) },
                                        colors = dialogFieldColors(),
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        singleLine = true,
                                        textStyle = LocalTextStyle.current.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    FieldLabel("Status", uppercase = true, fontSize = 9.sp)
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (enabled) C.green.copy(alpha = 0.10f) else C.red.copy(alpha = 0.08f),
                                        border = BorderStroke(1.dp, if (enabled) C.green.copy(alpha = 0.25f) else C.red.copy(alpha = 0.18f))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(Modifier.size(7.dp).background(if (enabled) C.green else C.red, CircleShape))
                                            Text(if (enabled) "Live" else "Paused", color = if (enabled) C.green else C.red, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item {
                        SettingsCard("USSD Configuration", Icons.Outlined.Terminal) {
                            FieldLabel("USSD Code")
                            OutlinedTextField(
                                value = codeField,
                                onValueChange = { codeField = it },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = dialogFieldColors(),
                                textStyle = LocalTextStyle.current.copy(
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Monospace
                                ),
                                placeholder = { Text("Enter USSD code", color = C.t3, fontFamily = FontFamily.Monospace) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    modifier = Modifier
                                        .height(34.dp)
                                        .clickable { codeField = TextFieldValue(codeField.text + "pn") },
                                    shape = RoundedCornerShape(8.dp),
                                    color = C.cyanDim,
                                    border = BorderStroke(1.dp, C.cyan.copy(alpha = 0.45f))
                                ) {
                                    Box(modifier = Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                                        Text("Insert pn", color = C.cyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                                Surface(
                                    modifier = Modifier
                                        .height(34.dp)
                                        .clickable { codeField = TextFieldValue(codeField.text + "*") },
                                    shape = RoundedCornerShape(8.dp),
                                    color = C.w04,
                                    border = BorderStroke(1.dp, C.border.copy(alpha = 0.55f))
                                ) {
                                    Box(modifier = Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                                        Text("Insert *", color = C.t3, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                                Surface(
                                    modifier = Modifier
                                        .height(34.dp)
                                        .clickable { codeField = TextFieldValue(codeField.text + "#") },
                                    shape = RoundedCornerShape(8.dp),
                                    color = C.w04,
                                    border = BorderStroke(1.dp, C.border.copy(alpha = 0.55f))
                                ) {
                                    Box(modifier = Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                                        Text("Insert #", color = C.t3, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                                Column(modifier = Modifier.width(150.dp)) {
                                    FieldLabel("USSD Type")
                                    DialogDropdown(
                                        "USSD Type",
                                        mode,
                                        offerExecutionModeOptions(),
                                        modeExp,
                                        { modeExp = it }
                                    ) {
                                        mode = it
                                        modeTouched = it != defaultExecutionModeForCategory(cat)
                                        modeExp = false
                                    }
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    FieldLabel("Mode Guide", uppercase = true, fontSize = 9.sp)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        if (signatureEnabled)
                                            "Protection auto-enables guided USSD flow for stronger verification across devices."
                                        else
                                            "Data bundles use SIMPLE. Calls and SMS use ADVANCED by default.",
                                        color = C.t3,
                                        fontSize = 10.5.sp,
                                        lineHeight = 15.sp
                                    )
                                }
                            }
                        }
                    }

                    item {
                        SettingsCard("Execution Path", Icons.Outlined.Route) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Column(modifier = Modifier.weight(1f)) {
                                    FieldLabel("SIM To Use")
                                    DialogDropdown(
                                        "SIM To Use",
                                        offerSimSelectionLabel(simSelection),
                                        listOf("General SIM", "Slot 1", "Slot 2"),
                                        simExp,
                                        { simExp = it }
                                    ) {
                                        simSelection = when (it) {
                                            "Slot 1" -> USSD_SIM_SELECTION_SLOT_1
                                            "Slot 2" -> USSD_SIM_SELECTION_SLOT_2
                                            else -> OFFER_SIM_USE_GENERAL
                                        }
                                        simExp = false
                                    }
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    FieldLabel("Execute On")
                                    DialogDropdown("Execute On", device, listOf("PRIMARY", "RELAY"), devExp, { devExp = it }) {
                                        device = it
                                        devExp = false
                                    }
                                }
                            }
                        }
                    }

                    item {
                        SettingsCard("Protection", Icons.Outlined.Security) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("Verify signature before dispatch", color = C.t1, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text(
                                        if (signatureEnabled) "Menu verification is active" else "No signature verification",
                                        color = C.t3,
                                        fontSize = 10.5.sp,
                                        lineHeight = 14.sp
                                    )
                                }
                                CompactDialogSwitch(
                                    checked = signatureEnabled,
                                    onCheckedChange = { signatureEnabled = it },
                                    checkedTrackColor = C.cyan
                                )
                            }
                            if (signatureEnabled) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Divider(color = C.border.copy(alpha = 0.4f), thickness = 0.5.dp)
                                Spacer(modifier = Modifier.height(12.dp))
                                FieldLabel("When codes change")
                                DialogDropdown(
                                    "When codes change",
                                    if (signatureAction == "ADJUST") "ADJUST" else "STOP",
                                    listOf("STOP", "ADJUST"),
                                    signatureExp,
                                    { signatureExp = it }
                                ) {
                                    signatureAction = it
                                    signatureExp = false
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    if (signatureAction == "ADJUST")
                                        "ADJUST only auto-fixes exact same-label moves. If the network changes wording, the app stops instead of guessing."
                                    else
                                        "STOP is the recommended production setting. It prevents the app from choosing the wrong bundle when the menu looks different.",
                                    color = C.t2,
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp
                                )
                                if (mode != OFFER_EXECUTION_MODE_ADVANCED) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        "This offer is saved as $mode, but Bingwa will switch to the guided USSD path automatically whenever protection or learning is enabled.",
                                        color = C.t3,
                                        fontSize = 11.sp,
                                        lineHeight = 16.sp
                                    )
                                }
                            } else if (!hasLearnedSignature && !hasPendingSignature) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "No signature learned yet. Turn protection on, save the offer, then use Save & Learn to scan the live USSD menus.",
                                    color = C.t2,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    }

                    if (showProtectionRecords) {
                        if (hasPendingSignature) {
                            item {
                                Spacer(modifier = Modifier.height(4.dp))
                                SignatureLearningRecordSection(
                                    title = "Pending Review",
                                    subtitle = "A new learning run is waiting for approval. Review each step, the selected option, and the recorded USSD text before replacing the saved record.",
                                    details = pendingLearnedDetails,
                                    learnedAt = pendingLearnedAt,
                                    learnedStepsCount = pendingLearnedSteps.size,
                                    popupCount = pendingLearningCaptures.size,
                                    emptyMessage = "This pending learning run has no saved record yet.",
                                    accent = C.orange,
                                    actions = {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(
                                                onClick = { existing?.let(onRelearnSignature) },
                                                shape = RoundedCornerShape(14.dp),
                                                border = BorderStroke(1.dp, C.amber.copy(alpha = 0.5f))
                                            ) {
                                                Text("Relearn", color = C.amber, fontWeight = FontWeight.Bold)
                                            }
                                            Button(
                                                onClick = { existing?.let(onApprovePending) },
                                                shape = RoundedCornerShape(14.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = C.green)
                                            ) {
                                                Text("Approve", color = C.bg, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                )
                            }
                        }
                        item {
                            Spacer(modifier = Modifier.height(4.dp))
                            SignatureLearningRecordSection(
                                title = "USSD Learning Record",
                                subtitle = "This record shows the learned menu steps, selected options, and the popup text captured during learning.",
                                details = learnedDetails,
                                learnedAt = learnedAt,
                                learnedStepsCount = learnedSteps.size,
                                popupCount = learningCaptures.size,
                                emptyMessage = "No signature learned yet. Save this offer, then use Save & Learn to scan the live USSD menus with test number 0700000000.",
                                accent = C.green
                            )
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun MenuActionItem(
    icon: ImageVector,
    iconTint: Color,
    iconBg: Color,
    title: String,
    subtitle: String,
    titleColor: Color = C.t1,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = Color.Transparent,
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                modifier = Modifier.size(36.dp),
                shape = RoundedCornerShape(10.dp),
                color = iconBg,
                border = BorderStroke(1.dp, iconTint.copy(alpha = 0.25f))
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(icon, null, tint = iconTint, modifier = Modifier.size(18.dp))
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, color = titleColor, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(text = subtitle, color = C.t3, fontSize = 11.sp)
            }
            Icon(Icons.Filled.ChevronRight, null, tint = C.t3.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
fun PatternSettingsScreen(onBack: () -> Unit) {
    Scaffold(
        containerColor = C.bg,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("USSD Response Patterns") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.Filled.ArrowBack, null) } }
            )
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Pattern Settings", color = C.t1)
        }
    }
}
