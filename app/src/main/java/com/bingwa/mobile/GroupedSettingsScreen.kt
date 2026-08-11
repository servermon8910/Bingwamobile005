package com.bingwa.mobile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.view.ViewGroup
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlin.math.absoluteValue

private sealed class SettingsDest {
    data object Home : SettingsDest()
    data object Sim : SettingsDest()
    data object ScratchCards : SettingsDest()
    data object Relay : SettingsDest()
    data object Remote : SettingsDest()
    data object Notifications : SettingsDest()
    data object Automation : SettingsDest()
    data object Contacts : SettingsDest()
    data object Blacklist : SettingsDest()
    data object Offers : SettingsDest()
    data object Alerts : SettingsDest()
    data object Transactions : SettingsDest()
    data object Diagnostics : SettingsDest()
}

private data class FallbackRuleOption(
    val mode: String,
    val title: String,
    val description: String,
    val icon: ImageVector
)

@Composable
fun GroupedSettingsScreen() {
    var dest by remember { mutableStateOf<SettingsDest>(SettingsDest.Home) }
    BackHandler(enabled = dest != SettingsDest.Home) { dest = SettingsDest.Home }

    when (dest) {
        SettingsDest.Home -> SettingsHome(
            onOpenSim = { dest = SettingsDest.Sim },
            onOpenScratchCards = { dest = SettingsDest.ScratchCards },
            onOpenRelay = { dest = SettingsDest.Relay },
            onOpenRemote = { dest = SettingsDest.Remote },
            onOpenNotifications = { dest = SettingsDest.Notifications },
            onOpenAutomation = { dest = SettingsDest.Automation },
            onOpenContacts = { dest = SettingsDest.Contacts },
            onOpenBlacklist = { dest = SettingsDest.Blacklist },
            onOpenOffers = { dest = SettingsDest.Offers },
            onOpenAlerts = { dest = SettingsDest.Alerts },
            onOpenTransactions = { dest = SettingsDest.Transactions },
            onOpenDiagnostics = { dest = SettingsDest.Diagnostics }
        )
        SettingsDest.Sim -> SimSettings(onBack = { dest = SettingsDest.Home })
        SettingsDest.ScratchCards -> ScratchCardRechargeScreen(onBack = { dest = SettingsDest.Home })
        SettingsDest.Relay -> RelaySettings(onBack = { dest = SettingsDest.Home })
        SettingsDest.Remote -> RemoteControlSettings(onBack = { dest = SettingsDest.Home })
        SettingsDest.Notifications -> CustomerNotificationSettings(onBack = { dest = SettingsDest.Home })
        SettingsDest.Automation -> AutomationSettings(onBack = { dest = SettingsDest.Home })
        SettingsDest.Contacts -> ContactsScreen(onBack = { dest = SettingsDest.Home })
        SettingsDest.Blacklist -> BlacklistScreen(onBack = { dest = SettingsDest.Home })
        SettingsDest.Offers -> OffersScreen(onBack = { dest = SettingsDest.Home })
        SettingsDest.Alerts -> AdminAlertsSettings(onBack = { dest = SettingsDest.Home })
        SettingsDest.Transactions -> TransactionSettings(onBack = { dest = SettingsDest.Home })
        SettingsDest.Diagnostics -> DeviceDiagnosticsScreen(onBack = { dest = SettingsDest.Home })
    }
}

@Composable
private fun SettingsHome(
    onOpenSim: () -> Unit,
    onOpenScratchCards: () -> Unit,
    onOpenRelay: () -> Unit,
    onOpenRemote: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenAutomation: () -> Unit,
    onOpenContacts: () -> Unit,
    onOpenBlacklist: () -> Unit,
    onOpenOffers: () -> Unit,
    onOpenAlerts: () -> Unit,
    onOpenTransactions: () -> Unit,
    onOpenDiagnostics: () -> Unit
) {
    val ctx = LocalContext.current
    Box(Modifier.fillMaxSize().background(C.bg)) {
        Box(
            Modifier
                .size(320.dp)
                .offset((-120).dp, 30.dp)
                .background(Brush.radialGradient(listOf(C.amber.copy(alpha = 0.08f), Color.Transparent)), CircleShape)
        )
        Box(
            Modifier
                .size(320.dp)
                .align(Alignment.TopEnd)
                .offset(110.dp, 130.dp)
                .background(Brush.radialGradient(listOf(C.amber.copy(alpha = 0.06f), Color.Transparent)), CircleShape)
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(
                Modifier
                    .statusBarsPadding()
                    .padding(top = 18.dp, start = 16.dp, end = 16.dp, bottom = 24.dp)
                    .widthIn(max = 430.dp)
                    .align(Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SettingsGroup("History & Offers", accent = C.amber) {
                LinkRow(
                    Icons.Rounded.Schedule,
                    "Transaction History",
                    "Review payments, statuses, summaries, and cleanup controls",
                    C.amber,
                    onOpenTransactions
                )
                GroupDivider(C.amber)
                LinkRow(Icons.Rounded.Tag, "Offers & USSD Codes", "Manage available bundles and execution codes", C.amber, onOpenOffers)
                GroupDivider(C.amber)
                LinkRow(Icons.Rounded.Contacts, "Contacts", "Keep saved customer names clean and easy to search", C.amber, onOpenContacts)
            }

            SettingsGroup("Execution Setup", accent = C.amber) {
                LinkRow(Icons.Rounded.SimCard, "SIM Settings", "Choose SIMs for USSD, customer notifications, and admin replies", C.amber, onOpenSim)
                GroupDivider(C.amber)
                LinkRow(Icons.Rounded.Search, "Scratch Card Recharge", "Scan one image, detect many 16-digit PINs, and recharge them one by one", C.amber, onOpenScratchCards)
                GroupDivider(C.amber)
                LinkRow(Icons.Rounded.Devices, "Relay (Two‑Phone Mode)", "Configure SMS or hotspot relay between two phones", C.amber, onOpenRelay)
                GroupDivider(C.amber)
                LinkRow(Icons.Rounded.PhoneAndroid, "Remote Control", "Manage admin phone, prefix, PIN, and remote commands", C.amber, onOpenRemote)
            }

            SettingsGroup("Automation & Alerts", accent = C.amber) {
                LinkRow(Icons.Rounded.Autorenew, "Automation Settings", "Control automation, retries, and auto-save behavior", C.amber, onOpenAutomation)
                GroupDivider(C.amber)
                LinkRow(Icons.Rounded.Sms, "Customer Notifications", "Edit success, pending, and failure templates", C.amber, onOpenNotifications)
                GroupDivider(C.amber)
                LinkRow(Icons.Rounded.Warning, "Admin Alerts", "Low airtime, low tokens, and low battery alerts", C.amber, onOpenAlerts)
            }

            SettingsGroup("Support", accent = C.amber) {
                LinkRow(Icons.Rounded.Info, "Setup Doctor", "Check permissions, compatibility, battery rules, and alarms", C.amber, onOpenDiagnostics)
                GroupDivider(C.amber)
                LinkRow(Icons.Rounded.Accessibility, "Accessibility", "Open your phone Accessibility settings", C.amber) {
                    runCatching {
                        ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
                GroupDivider(C.amber)
                LinkRow(Icons.Filled.Block, "Blacklist", "Block phone numbers from receiving automated bundles", C.red, onOpenBlacklist)
            }
            }
            Spacer(Modifier.height(22.dp))
        }
    }
}

@Composable
fun SettingsTopBar(title: String, subtitle: String, onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = UiDimens.ScreenPaddingHorizontal, vertical = 12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = C.cardHi.copy(alpha = 0.94f),
                border = BorderStroke(1.dp, C.border.copy(alpha = 0.9f))
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(42.dp)) {
                    Icon(Icons.Rounded.ArrowBack, null, tint = C.t1)
                }
            }
            Column(Modifier.padding(start = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val titleFontSize = when {
                    title.length >= 28 -> 16.sp
                    title.length >= 24 -> 18.sp
                    title.length >= 20 -> 20.sp
                    else -> 22.sp
                }
                Text(
                    title,
                    color = C.t1,
                    fontSize = titleFontSize,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip
                )
                if (subtitle.isNotBlank()) {
                    Text(subtitle, color = C.t2, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
        }
    }
}

@Composable
private fun SimSettings(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    val sims = getAvailableSims(ctx)
    var ussdSimId by remember { mutableIntStateOf(currentUssdSimSelection(ctx)) }
    var notifySimId by remember { mutableIntStateOf(prefs.safeGetInt("notify_sim_id", -1)) }
    var adminSmsSimId by remember { mutableIntStateOf(prefs.safeGetInt("admin_sms_sim_id", -1)) }

    Column(Modifier.fillMaxSize().background(C.bg).verticalScroll(rememberScrollState())) {
        SettingsTopBar("SIM Settings", "Choose SIMs for USSD, customer notifications, and admin replies", onBack)
        Column(
            Modifier.padding(horizontal = UiDimens.ScreenPaddingHorizontal),
            verticalArrangement = Arrangement.spacedBy(UiDimens.SpacingLg)
        ) {
            SettingsGroup("SIM Settings") {
                UssdSimPickerRow("USSD Execution SIM", sims, ussdSimId) { v ->
                    ussdSimId = v
                    prefs.edit().putInt("selected_sim_id", v).apply()
                }
                GroupDivider()
                SimPickerRow("Customer Notification SIM", "SIM used to send customer notifications", sims, notifySimId) { v ->
                    notifySimId = v
                    prefs.edit().putInt("notify_sim_id", v).apply()
                }
                GroupDivider()
                SimPickerRow("Admin Reply SIM", "SIM used to reply to admin SMS commands", sims, adminSmsSimId) { v ->
                    adminSmsSimId = v
                    prefs.edit().putInt("admin_sms_sim_id", v).apply()
                }
            }
        }
        Spacer(Modifier.height(UiDimens.Spacing2xl))
    }
}

@Composable
private fun RelaySettings(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
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
    var showConnectQr by remember { mutableStateOf(false) }
    var showHotspotScanner by remember { mutableStateOf(false) }
    var connectQrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var connectQrSummary by remember { mutableStateOf("") }

    fun persistRelaySettings() {
        prefs.edit()
            .putBoolean("two_phone_enabled", twoPhoneEnabled)
            .putString("two_phone_role", twoPhoneRole.trim().uppercase())
            .putString("relay_method", relayMethod.trim().uppercase())
            .putString("paired_phone", pairedPhone.trim())
            .putString("relay_ip", relayIp.trim())
            .putBoolean("relay_ip_auto", relayIpAuto)
            .putString("relay_prefix", relayPrefix.trim().uppercase())
            .putString("relay_pin", relayPin.trim())
            .putBoolean("relay_send_results", relaySendResults)
            .apply()
        RelayManager.stopRelayHotspotService(ctx)
        val cfg = RelayManager.load(ctx)
        if (cfg.enabled && cfg.role == "RELAY" && cfg.method == "HOTSPOT") {
            RelayManager.startRelayHotspotService(ctx)
        } else {
            RelayManager.startHotspotMonitor(ctx)
        }
    }

    fun importRelayHotspotQr(rawValue: String): Boolean {
        val raw = rawValue.trim()
        if (raw.isBlank()) return false
        val payload = RelayQrCodec.decode(raw)
        if (payload == null) {
            return false
        }

        twoPhoneEnabled = true
        twoPhoneRole = "PRIMARY"
        relayMethod = "HOTSPOT"
        pairedPhone = payload.relayPhone
        relayIp = payload.relayIp
        relayIpAuto = payload.relayIp.isBlank()
        relayPrefix = payload.relayPrefix
        relayPin = payload.relayPin
        relaySendResults = payload.sendResultsSms
        persistRelaySettings()
        vib(ctx)
        Toast.makeText(ctx, "Relay hotspot settings imported from QR", Toast.LENGTH_SHORT).show()
        return true
    }

    val openScanner = launch@{
        if (!ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            Toast.makeText(ctx, "This phone does not have a usable camera for QR scanning", Toast.LENGTH_SHORT).show()
            return@launch
        }
        showHotspotScanner = true
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) openScanner()
        else Toast.makeText(ctx, "Camera permission is needed to scan the relay QR", Toast.LENGTH_SHORT).show()
    }

    val qrBitmap = connectQrBitmap
    if (showConnectQr && qrBitmap != null) {
        AlertDialog(
            onDismissRequest = { showConnectQr = false },
            confirmButton = {
                TextButton(onClick = { showConnectQr = false }) { Text("Close", color = C.cyan) }
            },
            title = { Text("Scan To Connect", color = C.t1) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("On the PRIMARY phone, open Relay settings and tap Scan Connect QR.", color = C.t2, fontSize = 12.sp)
                    Spacer(Modifier.height(14.dp))
                    Surface(shape = RoundedCornerShape(18.dp), color = androidx.compose.ui.graphics.Color.White) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "Relay connect QR",
                            modifier = Modifier.padding(14.dp).size(240.dp)
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(connectQrSummary, color = C.t2, fontSize = 11.sp)
                }
            },
            containerColor = C.cardHi,
            properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true)
        )
    }

    if (showHotspotScanner) {
        RelayHotspotScannerDialog(
            onDismiss = { showHotspotScanner = false },
            onQrDetected = { raw ->
                val imported = importRelayHotspotQr(raw)
                if (imported) {
                    showHotspotScanner = false
                } else {
                    Toast.makeText(ctx, "That QR code is not a relay hotspot code", Toast.LENGTH_SHORT).show()
                }
                imported
            }
        )
    }

    Column(Modifier.fillMaxSize().background(C.bg).verticalScroll(rememberScrollState())) {
        SettingsTopBar("Relay (Two‑Phone Mode)", "SMS or hotspot relay between phones", onBack)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsGroup("Two‑Phone Mode") {
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
                                GroupDivider()
                                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        SettingsRowIcon(Icons.Rounded.Router); Spacer(Modifier.width(12.dp))
                                        Column {
                                            Text("Quick Connect QR", color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                            Text(
                                                if (twoPhoneRole.uppercase() == "RELAY") "Show a QR that the PRIMARY phone can scan to connect quickly"
                                                else "Scan the RELAY phone QR to fill the hotspot settings automatically",
                                                color = C.t2,
                                                fontSize = 11.sp
                                            )
                                        }
                                    }
                                    Button(
                                        onClick = {
                                            if (twoPhoneRole.uppercase() == "RELAY") {
                                                val payload = RelayQrCodec.createPayload(
                                                    pairedPhone = pairedPhone,
                                                    relayPrefix = relayPrefix,
                                                    relayPin = relayPin,
                                                    relayIp = relayIp,
                                                    sendResultsSms = relaySendResults
                                                )
                                                if (payload.relayIp.isBlank()) {
                                                    Toast.makeText(ctx, "Turn on the relay hotspot or set the relay IP first", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    connectQrBitmap = RelayQrCodec.createBitmap(RelayQrCodec.encode(payload))
                                                    connectQrSummary = "Relay IP ${payload.relayIp}${if (payload.relayPhone.isNotBlank()) " • Phone ${payload.relayPhone}" else ""}"
                                                    showConnectQr = true
                                                }
                                            } else {
                                                val hasCameraPermission = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                                                if (hasCameraPermission) openScanner() else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth().height(48.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = C.blue)
                                    ) {
                                        Icon(
                                            if (twoPhoneRole.uppercase() == "RELAY") Icons.Rounded.Router else Icons.Rounded.Devices,
                                            null,
                                            tint = C.bg,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            if (twoPhoneRole.uppercase() == "RELAY") "Show Connect QR" else "Scan Connect QR",
                                            color = C.bg,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 13.sp
                                        )
                                    }
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
                                    persistRelaySettings()
                                    vib(ctx)
                                    Toast.makeText(ctx, "Two‑Phone settings saved", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = C.cyan)
                            ) {
                                Icon(Icons.Filled.Save, null, tint = C.bg, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Save Two‑Phone Settings", color = C.bg, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(22.dp))
    }
}

@Composable
private fun RelayHotspotScannerDialog(
    onDismiss: () -> Unit,
    onQrDetected: (String) -> Boolean
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        RelayHotspotScannerScreen(
            onDismiss = onDismiss,
            onQrDetected = onQrDetected
        )
    }
}

@Composable
private fun RelayHotspotScannerScreen(
    onDismiss: () -> Unit,
    onQrDetected: (String) -> Boolean
) {
    var torchEnabled by remember { mutableStateOf(false) }
    var waitingDots by remember { mutableIntStateOf(0) }
    val onQrDetectedState by rememberUpdatedState(onQrDetected)
    val terminalBg = Color(0xFF14181D)
    val panel = Color(0xFF1B2027)
    val panelAlt = Color(0xFF21272F)
    val line = Color.White.copy(alpha = 0.07f)
    val amber = Color(0xFFFFB454)
    val amberDim = Color(0xFF8A6A3D)
    val cyan = Color(0xFF74E6D8)
    val ink = Color(0xFFE9E7E2)
    val inkDim = Color(0xFF7D848C)

    BackHandler(onBack = onDismiss)

    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            waitingDots = (waitingDots + 1) % 4
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF0A0C0F)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 28.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .widthIn(max = 400.dp)
                    .fillMaxHeight(0.94f)
                    .clip(RoundedCornerShape(34.dp))
                    .background(terminalBg)
                    .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(34.dp))
                    .padding(horizontal = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(top = 20.dp, bottom = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(panel)
                            .border(1.dp, line, RoundedCornerShape(10.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ArrowBack,
                            contentDescription = "Go back",
                            tint = ink,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Text(
                        "LINK TERMINAL",
                        color = ink,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(panel)
                            .border(1.dp, line, RoundedCornerShape(20.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(cyan)
                        )
                        Text("AGT-042", color = inkDim, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    RelayScannerViewfinder(
                        modifier = Modifier
                            .fillMaxWidth(0.64f)
                            .aspectRatio(1f),
                        torchEnabled = torchEnabled,
                        onQrDetected = onQrDetectedState
                    )

                    Spacer(Modifier.height(26.dp))
                    Text(
                        "Scan QR to link relay",
                        color = ink,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "AWAITING_SIGNAL${".".repeat(waitingDots)}",
                        color = cyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(Modifier.height(34.dp))
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(
                            if (torchEnabled) {
                                Brush.radialGradient(listOf(amber, amberDim))
                            } else {
                                Brush.linearGradient(listOf(panelAlt, panel))
                            }
                        )
                        .border(1.dp, if (torchEnabled) amber.copy(alpha = 0.5f) else line, CircleShape)
                        .clickable { torchEnabled = !torchEnabled },
                    contentAlignment = Alignment.Center
                ) {
                    if (torchEnabled) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .border(1.dp, amber.copy(alpha = 0.35f), CircleShape)
                        )
                    }
                    Icon(
                        imageVector = Icons.Filled.FlashOn,
                        contentDescription = "Toggle flashlight",
                        tint = if (torchEnabled) Color(0xFF1A1306) else inkDim,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(Modifier.height(18.dp))
                Text(
                    ">> QR::LINK_REQUEST · STATUS:AWAITING_ACK_",
                    color = inkDim,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(22.dp))
                Row(
                    modifier = Modifier.padding(bottom = 28.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    RelayScannerChip(
                        label = "SECURE_LINK",
                        dotColor = cyan,
                        background = panel,
                        borderColor = line,
                        textColor = inkDim
                    )
                    RelayScannerChip(
                        label = "HIGH_SPEED_MODE",
                        dotColor = amber,
                        background = panel,
                        borderColor = line,
                        textColor = inkDim
                    )
                }
                Spacer(Modifier.height(12.dp).navigationBarsPadding())
            }
        }
    }
}

@Composable
private fun RelayScannerViewfinder(
    modifier: Modifier = Modifier,
    torchEnabled: Boolean,
    onQrDetected: (String) -> Boolean
) {
    val transition = rememberInfiniteTransition(label = "relay_scanner")
    val scanProgress by transition.animateFloat(
        initialValue = 0.06f,
        targetValue = 0.78f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = LinearEasing)
        ),
        label = "relay_scan_beam"
    )
    val ringA by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2800, easing = LinearEasing)
        ),
        label = "relay_ring_a"
    )
    val ringB by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2800, easing = LinearEasing),
            initialStartOffset = StartOffset(950)
        ),
        label = "relay_ring_b"
    )
    val ringC by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2800, easing = LinearEasing),
            initialStartOffset = StartOffset(1900)
        ),
        label = "relay_ring_c"
    )
    val latestOnQrDetected by rememberUpdatedState(onQrDetected)
    var barcodeView by remember { mutableStateOf<BarcodeView?>(null) }
    var acceptedScan by remember { mutableStateOf(false) }
    var lastRejectedScanAt by remember { mutableStateOf(0L) }

    DisposableEffect(barcodeView) {
        barcodeView?.resume()
        onDispose {
            barcodeView?.pause()
        }
    }

    DisposableEffect(barcodeView, torchEnabled) {
        barcodeView?.setTorch(torchEnabled)
        onDispose { }
    }

    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF0C0F12))
    ) {
        val cornerSize = maxWidth * 0.12f
        AndroidView(
            factory = { context ->
                BarcodeView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
                    decodeContinuous(object : BarcodeCallback {
                        override fun barcodeResult(result: BarcodeResult) {
                            val raw = result.text?.trim().orEmpty()
                            if (raw.isBlank() || acceptedScan) return
                            val now = System.currentTimeMillis()
                            if (now - lastRejectedScanAt < 1200L) return
                            val accepted = latestOnQrDetected(raw)
                            if (accepted) {
                                acceptedScan = true
                                pause()
                            } else {
                                lastRejectedScanAt = now
                            }
                        }

                        override fun possibleResultPoints(resultPoints: List<ResultPoint>) = Unit
                    })
                    resume()
                }.also { barcodeView = it }
            },
            update = { view ->
                if (!acceptedScan) view.resume()
                view.setTorch(torchEnabled)
                barcodeView = view
            },
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFF11151A).copy(alpha = 0.22f),
                            Color(0xFF0C0F12).copy(alpha = 0.06f)
                        )
                    )
                )
        )

        RelayScannerRing(progress = ringA)
        RelayScannerRing(progress = ringB)
        RelayScannerRing(progress = ringC)

        Box(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .height(34.dp)
                .align(Alignment.TopCenter)
                .offset(y = maxHeight * scanProgress)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Transparent,
                            Color(0xFF74E6D8).copy(alpha = 0.45f),
                            Color.Transparent
                        )
                    )
                )
        )

        RelayScannerCorner(
            modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
            size = cornerSize,
            color = Color(0xFFFFB454),
            top = true,
            start = true
        )
        RelayScannerCorner(
            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
            size = cornerSize,
            color = Color(0xFFFFB454),
            top = true,
            start = false
        )
        RelayScannerCorner(
            modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
            size = cornerSize,
            color = Color(0xFFFFB454),
            top = false,
            start = true
        )
        RelayScannerCorner(
            modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
            size = cornerSize,
            color = Color(0xFFFFB454),
            top = false,
            start = false
        )
    }
}

@Composable
private fun RelayScannerRing(progress: Float) {
    val scale = 0.4f + (progress * 2.7f)
    val alpha = (0.65f - (progress * 0.65f)).coerceIn(0f, 0.65f)
    Box(
        modifier = Modifier
            .fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.3f * scale)
                .aspectRatio(1f)
                .clip(CircleShape)
                .border(1.dp, Color(0xFF74E6D8).copy(alpha = alpha), CircleShape)
        )
    }
}

@Composable
private fun RelayScannerCorner(
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp,
    color: Color,
    top: Boolean,
    start: Boolean
) {
    Box(
        modifier = modifier
            .size(size)
            .border(
                width = 3.dp,
                color = color,
                shape = RoundedCornerShape(
                    topStart = if (top && start) 6.dp else 0.dp,
                    topEnd = if (top && !start) 6.dp else 0.dp,
                    bottomStart = if (!top && start) 6.dp else 0.dp,
                    bottomEnd = if (!top && !start) 6.dp else 0.dp
                )
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent)
        )
        val coverWidth = if (start) Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .width(6.dp) else Modifier
            .align(Alignment.CenterStart)
            .fillMaxHeight()
            .width(6.dp)
        Box(modifier = coverWidth.background(Color(0xFF0C0F12)))
        val coverHeight = if (top) Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(6.dp) else Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .height(6.dp)
        Box(modifier = coverHeight.background(Color(0xFF0C0F12)))
    }
}

@Composable
private fun RelayScannerChip(
    label: String,
    dotColor: Color,
    background: Color,
    borderColor: Color,
    textColor: Color
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(background)
            .border(1.dp, borderColor, RoundedCornerShape(20.dp))
            .padding(horizontal = 13.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(label, color = textColor, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun RemoteControlSettings(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    val sims = getAvailableSims(ctx)
    var remoteEnabled by remember { mutableStateOf(prefs.safeGetBoolean("remote_enabled", false)) }
    var adminPhone by remember { mutableStateOf(prefs.safeGetString("admin_phone", "") ?: "") }
    var adminPrefix by remember { mutableStateOf(prefs.safeGetString("sms_prefix", "BINGWA") ?: "BINGWA") }
    var adminPin by remember { mutableStateOf(prefs.safeGetString("sms_pin", "") ?: "") }
    var pinVisible by remember { mutableStateOf(false) }
    val normalizedAdminPhone = remember(adminPhone) {
        adminPhone.trim().takeIf { it.isNotEmpty() }?.let(SmsCommandHandler::normalizePhone).orEmpty()
    }
    val normalizedPrefix = remember(adminPrefix) {
        normalizeAdminCommandPrefix(adminPrefix)
    }
    val savedPrefix = normalizedPrefix.ifBlank { "BINGWA" }
    val pinHint = remember(adminPin) { adminPin.trim().takeIf { it.isNotEmpty() }?.let(::maskAdminPin).orEmpty() }
    val phoneError = remoteEnabled && normalizedAdminPhone.isBlank()
    val phoneFormatError = normalizedAdminPhone.isNotBlank() && !isRemoteAdminPhoneValid(normalizedAdminPhone)
    val pinError = adminPin.isNotBlank() && adminPin.length !in 4..6
    val canSave = !phoneError && !phoneFormatError && !pinError
    val previewMessage = buildString {
        append(savedPrefix)
        append(' ')
        if (pinHint.isNotEmpty()) {
            append(pinHint)
            append(' ')
        }
        append("STATUS")
    }
    val adminReplySimLabel = remember(sims, prefs) {
        val subId = prefs.safeGetInt("admin_sms_sim_id", -1)
        if (subId == -1) {
            "Default SIM"
        } else {
            sims.find { it.subscriptionId == subId }?.let { "${it.displayName} · Slot ${it.simSlotIndex + 1}" } ?: "SIM $subId"
        }
    }

    Column(Modifier.fillMaxSize().background(C.bg).verticalScroll(rememberScrollState())) {
        SettingsTopBar("Remote Control", "Admin SMS commands and security", onBack)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsGroup("Remote Admin") {
                ToggleRow(Icons.Rounded.PhoneAndroid, "Enable Remote Control", "Allow SMS commands from admin phone", remoteEnabled) {
                    remoteEnabled = it
                    prefs.edit().putBoolean("remote_enabled", it).apply()
                }
                GroupDivider()
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
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
                                Text(previewMessage, color = C.t2, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Replies use $adminReplySimLabel. Change it in SIM Settings if admin SMS replies need a different line.",
                                    color = C.t3,
                                    fontSize = 10.sp,
                                    lineHeight = 14.sp
                                )
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
                    OutlinedTextField(
                        value = adminPhone,
                        onValueChange = { adminPhone = it.trim() },
                        placeholder = { Text("e.g. 0712345678", color = C.t3) },
                        leadingIcon = { Icon(Icons.Rounded.Phone, null, tint = C.t2) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = fieldColors(),
                        isError = phoneError || phoneFormatError,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when {
                            phoneError -> "Add the admin phone number before enabling Remote Control."
                            phoneFormatError -> "Use a valid Kenyan mobile number such as 0712345678 or +254712345678."
                            normalizedAdminPhone.isNotBlank() -> "Commands will be accepted only from $normalizedAdminPhone."
                            else -> "Only one trusted number can send admin commands."
                        },
                        color = if (phoneError || phoneFormatError) C.red else C.t3,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                }
                GroupDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SettingsRowIcon(Icons.Rounded.Tag); Spacer(Modifier.width(12.dp))
                        Column { Text("Command Prefix", color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.Medium); Text("Word that must start every SMS command", color = C.t2, fontSize = 11.sp) }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = adminPrefix,
                        onValueChange = { adminPrefix = normalizeAdminCommandPrefix(it) },
                        placeholder = { Text("BINGWA", color = C.t3) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = fieldColors(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Saved as $savedPrefix. Letters and numbers only so the prefix is easy to type and match.",
                        color = C.t3,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
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
                        isError = pinError,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (pinError) "PIN must contain 4 to 6 digits." else "Leave blank to allow commands without a PIN. When set, send the PIN as its own word before the command.",
                        color = if (pinError) C.red else C.t3,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                }
                GroupDivider()
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Button(
                        enabled = canSave,
                        onClick = {
                            prefs.edit()
                                .putString("admin_phone", normalizedAdminPhone)
                                .putString("sms_prefix", savedPrefix)
                                .putString("sms_pin", adminPin.trim())
                                .apply()
                            vib(ctx)
                            Toast.makeText(ctx, "Admin settings saved", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = C.cyan,
                            contentColor = C.bg,
                            disabledContainerColor = C.border,
                            disabledContentColor = C.t3
                        )
                    ) {
                        Icon(Icons.Filled.Save, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Save Admin Settings", fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                    }
                }
            }
            SettingsGroup("Quick Start") {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Surface(shape = RoundedCornerShape(16.dp), color = C.cyanDim, border = BorderStroke(1.dp, C.cyan.copy(alpha = 0.18f))) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Use one of these formats from the saved Admin Phone number.", color = C.t1, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            RemoteCommandExamples(
                                examples = listOf(
                                    previewMessage,
                                    buildString {
                                        append(savedPrefix)
                                        append(' ')
                                        if (pinHint.isNotEmpty()) {
                                            append(pinHint)
                                            append(' ')
                                        }
                                        append("OFFERS")
                                    },
                                    buildString {
                                        append(savedPrefix)
                                        append(' ')
                                        if (pinHint.isNotEmpty()) {
                                            append(pinHint)
                                            append(' ')
                                        }
                                        append("BUY 0712345678 1")
                                    }
                                )
                            )
                            Text(
                                "Tip: `OFFERS` shows the offer numbers you can use with `ON`, `OFF`, and `BUY`.",
                                color = C.t2,
                                fontSize = 11.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }
            SettingsGroup("Command Reference") {
                RemoteCommandSection(
                    title = "Checks",
                    items = listOf(
                        RemoteCommandItem("STATUS", "View sent, pending, failed, usage, and battery summary."),
                        RemoteCommandItem("PING", "Get a quick app health snapshot."),
                        RemoteCommandItem("BALANCE", "Check airtime balance and token balance."),
                        RemoteCommandItem("TOKENS", "Check token or unlimited status."),
                        RemoteCommandItem("BATTERY", "Check the current phone battery status.")
                    )
                )
                GroupDivider()
                RemoteCommandSection(
                    title = "Offers",
                    items = listOf(
                        RemoteCommandItem("OFFERS", "List offers with the numbers and IDs you can use remotely."),
                        RemoteCommandItem("ON <offer>", "Enable an offer using the number or ID from OFFERS."),
                        RemoteCommandItem("OFF <offer>", "Disable an offer using the number or ID from OFFERS."),
                        RemoteCommandItem("BUY <phone> <offer>", "Dispatch a bundle to a phone using an offer number or ID."),
                        RemoteCommandItem("BUYAMT <phone> <amount>", "Dispatch by amount when you know the KSh value.")
                    )
                )
                GroupDivider()
                RemoteCommandSection(
                    title = "Transactions",
                    items = listOf(
                        RemoteCommandItem("P", "Show the latest pending transactions."),
                        RemoteCommandItem("F", "Show the latest failed transactions."),
                        RemoteCommandItem("RETRY <tx-id>", "Retry a failed transaction by its ID.")
                    )
                )
                GroupDivider()
                RemoteCommandSection(
                    title = "Wallet",
                    items = listOf(
                        RemoteCommandItem("BT <amount>", "Buy tokens using airtime."),
                        RemoteCommandItem("HELP", "Send the full SMS help message back to the admin phone.")
                    )
                )
            }
        }
        Spacer(Modifier.height(22.dp))
    }
}

private data class RemoteCommandItem(
    val command: String,
    val description: String
)

private fun normalizeAdminCommandPrefix(value: String): String =
    value.uppercase().filter { it.isLetterOrDigit() }.take(12)

private fun maskAdminPin(pin: String): String {
    val trimmed = pin.trim()
    if (trimmed.isEmpty()) return ""
    if (trimmed.length <= 2) return "*".repeat(trimmed.length)
    return "*".repeat(trimmed.length - 2) + trimmed.takeLast(2)
}

private fun isRemoteAdminPhoneValid(phone: String): Boolean =
    phone.matches(Regex("^0\\d{9}$"))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RemoteCommandExamples(examples: List<String>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        examples.distinct().forEach { example ->
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = C.cardHi,
                border = BorderStroke(1.dp, C.border)
            ) {
                Text(
                    text = example,
                    color = C.t1,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun RemoteCommandSection(title: String, items: List<RemoteCommandItem>) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(title, color = C.cyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        items.forEach { item ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = C.w04,
                border = BorderStroke(1.dp, C.border.copy(alpha = 0.85f))
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(item.command, color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(item.description, color = C.t2, fontSize = 11.sp, lineHeight = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun CustomerNotificationSettings(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    var notifySuccess by remember { mutableStateOf(prefs.safeGetBoolean("notify_success", true)) }
    var notifyFailed by remember { mutableStateOf(prefs.safeGetBoolean("notify_failed", true)) }
    var notifyScheduled by remember { mutableStateOf(prefs.safeGetBoolean("notify_scheduled", true)) }
    var tplSuccess by remember { mutableStateOf(prefs.safeGetString("sms_tpl_success", DEFAULT_TPL_SUCCESS) ?: DEFAULT_TPL_SUCCESS) }
    var tplFailed by remember { mutableStateOf(prefs.safeGetString("sms_tpl_failed", DEFAULT_TPL_FAILED) ?: DEFAULT_TPL_FAILED) }
    var tplPending by remember { mutableStateOf(prefs.safeGetString("sms_tpl_pending", DEFAULT_TPL_PENDING) ?: DEFAULT_TPL_PENDING) }
    var tplLimitNotice by remember { mutableStateOf(prefs.safeGetString("sms_tpl_limit_notice", DEFAULT_TPL_LIMIT_NOTICE) ?: DEFAULT_TPL_LIMIT_NOTICE) }
    var tplScheduled by remember { mutableStateOf(prefs.safeGetString("sms_tpl_scheduled", DEFAULT_TPL_SCHEDULED) ?: DEFAULT_TPL_SCHEDULED) }
    var notifyPending by remember { mutableStateOf(prefs.safeGetBoolean("notify_pending", true)) }
    var notifyLimitNotice by remember { mutableStateOf(prefs.safeGetBoolean("notify_limit_notice", true)) }

    Column(Modifier.fillMaxSize().background(C.bg).verticalScroll(rememberScrollState())) {
        SettingsTopBar("Customer Notifications", "SMS to customers for each outcome", onBack)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsGroup("Success") {
                ToggleRow(Icons.Rounded.CheckCircle, "Send on Success", "SMS customer when bundle is delivered", notifySuccess) {
                    notifySuccess = it
                    prefs.edit().putBoolean("notify_success", it).apply()
                }
                AnimatedVisibility(visible = notifySuccess) {
                    Column {
                        GroupDivider()
                        TemplateEditor("Success Message Template", tplSuccess, C.green, SMS_TAGS) { v ->
                            tplSuccess = v
                            prefs.edit().putString("sms_tpl_success", v).apply()
                        }
                        Spacer(Modifier.height(6.dp))
                        TemplatePreview(tplSuccess, C.green)
                    }
                }
            }

            SettingsGroup("Daily Limit / Pending") {
                ToggleRow(Icons.Rounded.Schedule, "Send on Daily Limit", "SMS customer when offer already used today", notifyPending) {
                    notifyPending = it
                    prefs.edit().putBoolean("notify_pending", it).apply()
                }
                AnimatedVisibility(visible = notifyPending) {
                    Column {
                        GroupDivider()
                        TemplateEditor("Pending / Daily Limit Template", tplPending, C.amber, SMS_TAGS) { v ->
                            tplPending = v
                            prefs.edit().putString("sms_tpl_pending", v).apply()
                        }
                        Spacer(Modifier.height(6.dp))
                        TemplatePreview(tplPending, C.amber)
                    }
                }
            }

            SettingsGroup("Daily Limit / Notice") {
                ToggleRow(Icons.Rounded.Info, "Send Alternative Notice", "SMS customer when Notice mode is used instead of auto-queue", notifyLimitNotice) {
                    notifyLimitNotice = it
                    prefs.edit().putBoolean("notify_limit_notice", it).apply()
                }
                AnimatedVisibility(visible = notifyLimitNotice) {
                    Column {
                        GroupDivider()
                        TemplateEditor("Alternative Number / Try Tomorrow Template", tplLimitNotice, C.blue, SMS_TAGS) { v ->
                            tplLimitNotice = v
                            prefs.edit().putString("sms_tpl_limit_notice", v).apply()
                        }
                        Spacer(Modifier.height(6.dp))
                        TemplatePreview(tplLimitNotice, C.blue)
                    }
                }
            }

            SettingsGroup("Failure") {
                ToggleRow(Icons.Rounded.Warning, "Send on Failure", "SMS customer when bundle fails", notifyFailed) {
                    notifyFailed = it
                    prefs.edit().putBoolean("notify_failed", it).apply()
                }
                AnimatedVisibility(visible = notifyFailed) {
                    Column {
                        GroupDivider()
                        TemplateEditor("Failure Message Template", tplFailed, C.red, SMS_TAGS) { v ->
                            tplFailed = v
                            prefs.edit().putString("sms_tpl_failed", v).apply()
                        }
                        Spacer(Modifier.height(6.dp))
                        TemplatePreview(tplFailed, C.red)
                    }
                }
            }

            SettingsGroup("Scheduled Dispatch") {
                ToggleRow(Icons.Rounded.Schedule, "Send on Scheduled Dispatch", "SMS customer when a scheduled bundle is finally sent", notifyScheduled) {
                    notifyScheduled = it
                    prefs.edit().putBoolean("notify_scheduled", it).apply()
                }
                AnimatedVisibility(visible = notifyScheduled) {
                    Column {
                        GroupDivider()
                        TemplateEditor("Scheduled Dispatch Template", tplScheduled, C.purple, SMS_TAGS) { v ->
                            tplScheduled = v
                            prefs.edit().putString("sms_tpl_scheduled", v).apply()
                        }
                        Spacer(Modifier.height(6.dp))
                        TemplatePreview(tplScheduled, C.purple)
                    }
                }
            }
        }
        Spacer(Modifier.height(22.dp))
    }
}

@Composable
private fun FallbackRuleSelectorCard(
    option: FallbackRuleOption,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(6.dp),
        color = if (selected) C.amber.copy(alpha = 0.08f) else C.bg.copy(alpha = 0.58f),
        border = BorderStroke(1.dp, if (selected) C.amber.copy(alpha = 0.34f) else C.border.copy(alpha = 0.72f))
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    if (selected) {
                        Brush.horizontalGradient(
                            listOf(
                                C.amber.copy(alpha = 0.12f),
                                Color.Transparent
                            )
                        )
                    } else {
                        Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                    }
                )
                .padding(horizontal = 14.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = if (selected) C.amber.copy(alpha = 0.14f) else C.cardHi.copy(alpha = 0.82f),
                border = BorderStroke(1.dp, if (selected) C.amber.copy(alpha = 0.34f) else C.border.copy(alpha = 0.72f))
            ) {
                Box(
                    modifier = Modifier
                        .width(34.dp)
                        .height(18.dp)
                        .padding(horizontal = 1.dp),
                    contentAlignment = if (selected) Alignment.CenterEnd else Alignment.CenterStart
                ) {
                    Box(
                        Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(if (selected) C.amber else C.t3)
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        option.title,
                        color = if (selected) C.amber else C.t1,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (selected) {
                        Surface(
                            shape = RoundedCornerShape(3.dp),
                            color = C.amber,
                            border = BorderStroke(1.dp, C.amber.copy(alpha = 0.28f))
                        ) {
                            Text(
                                "SET",
                                color = C.bg,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Text(
                    option.description,
                    color = C.t2,
                    fontSize = 11.sp,
                    lineHeight = 17.sp
                )
            }
        }
    }
}

@Composable
private fun FallbackMilepost(
    number: Int,
    title: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            number.toString(),
            color = C.border.copy(alpha = 0.95f),
            fontSize = 30.sp,
            fontWeight = FontWeight.ExtraBold
        )
        Text(
            title.uppercase(),
            color = C.t2,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .weight(1f)
                .padding(bottom = 6.dp)
        )
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(C.border.copy(alpha = 0.55f))
                .padding(bottom = 6.dp)
        )
    }
}

@Composable
private fun FallbackRouteStatBox(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = C.bg.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.72f))
    ) {
        Row(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(56.dp)
                    .background(accent.copy(alpha = 0.65f))
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 11.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(label.uppercase(), color = C.t3, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Text(value.uppercase(), color = accent, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

@Composable
private fun FallbackStatusChip(
    icon: ImageVector,
    label: String,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = tint.copy(alpha = 0.13f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.24f))
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(13.dp))
            Text(label.uppercase(), color = tint, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun FallbackSummaryTile(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = accent.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.20f))
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(label.uppercase(), color = accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text(value, color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun FallbackOverviewMetric(
    label: String,
    value: String,
    supporting: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = accent.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.22f))
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(label.uppercase(), color = accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text(value, color = C.t1, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(supporting, color = C.t2, fontSize = 10.sp, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun FallbackJourneyStep(
    index: Int,
    title: String,
    subtitle: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = accent.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.18f))
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = accent.copy(alpha = 0.16f),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.22f))
            ) {
                Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                    Text(index.toString(), color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text(title, color = C.t1, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = C.t2, fontSize = 10.sp, lineHeight = 14.sp)
        }
    }
}

@Composable
private fun FallbackInfoSurface(
    title: String,
    subtitle: String,
    accent: Color,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit = {}
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = C.surface.copy(alpha = 0.70f),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.82f))
    ) {
        Row(
            Modifier
                .fillMaxWidth()
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .heightIn(min = 120.dp)
                    .background(accent)
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = {
                Text(
                    title.uppercase(),
                    color = accent,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(subtitle, color = C.t2, fontSize = 11.sp, lineHeight = 17.sp)
                content()
            }
        )
        }
    }
}

@Composable
private fun FallbackChevronRowCard(
    title: String,
    subtitle: String,
    detail: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(16.dp),
        color = C.surface.copy(alpha = 0.66f),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.82f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, color = C.t2, fontSize = 11.sp, lineHeight = 16.sp)
                }
                Icon(
                    Icons.Filled.ChevronRight,
                    null,
                    tint = C.t2,
                    modifier = Modifier
                        .size(18.dp)
                        .rotate(if (expanded) 90f else 0f)
                )
            }
            if (expanded) {
                Text(detail, color = C.t2, fontSize = 11.sp, lineHeight = 17.sp)
            }
        }
    }
}

@Composable
private fun FallbackSectionHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                title.uppercase(),
                color = C.t3,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            Box(
                Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(C.border.copy(alpha = 0.55f))
            )
            if (action != null) action()
        }
        if (subtitle.isNotBlank()) {
            Text(subtitle, color = C.t2, fontSize = 11.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun FallbackInlineActionButton(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = C.amber.copy(alpha = 0.18f),
            contentColor = if (enabled) C.t1 else C.t3,
            disabledContainerColor = C.cardHi.copy(alpha = 0.50f),
            disabledContentColor = C.t3
        ),
        border = BorderStroke(1.dp, if (enabled) C.amber.copy(alpha = 0.26f) else C.border.copy(alpha = 0.62f)),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Icon(Icons.Rounded.Add, null, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun FallbackScreenContainer(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(30.dp),
        color = C.surface.copy(alpha = 0.95f),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.86f))
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            C.cardHi.copy(alpha = 0.98f),
                            C.surface.copy(alpha = 0.97f),
                            C.card.copy(alpha = 0.94f),
                            C.bg.copy(alpha = 0.99f)
                        )
                    )
                ),
            content = content
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FallbackOverviewCard(
    fallbackEnabled: Boolean,
    onToggleChange: (Boolean) -> Unit
) {
    SettingsGroup("Fallback Plans") {
        ToggleRow(
            Icons.Rounded.Autorenew,
            "Enable Fallback Plans",
            "Link backup plans when the main plan is blocked or missing",
            fallbackEnabled
        ) { onToggleChange(it) }
    }
}

@Composable
private fun FallbackOverviewSwitch(
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    val thumbOffset = if (checked) 29.dp else 4.dp
    Box(
        Modifier
            .width(68.dp)
            .height(36.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (checked) C.amber.copy(alpha = 0.94f) else C.surface.copy(alpha = 0.55f))
            .border(
                width = 1.dp,
                color = if (checked) C.amber.copy(alpha = 0.38f) else C.amber.copy(alpha = 0.22f),
                shape = RoundedCornerShape(999.dp)
            )
            .clickable { onChange(!checked) }
    ) {
        Box(
            Modifier
                .offset(x = thumbOffset, y = 4.dp)
                .size(28.dp)
                .clip(CircleShape)
                .background(if (checked) C.bg else Color(0xFFE4E7EC))
        )
    }
}

@Composable
private fun FallbackMappingCard(
    number: Int,
    primaryOffer: OfferItem,
    fallbackOffers: List<OfferItem>,
    fallbackRuleSummary: String,
    fallbackUpdatedLabel: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val firstFallback = fallbackOffers.firstOrNull()
    val fallbackPlanLabel = if (fallbackOffers.size == 1) {
        "1 backup plan"
    } else {
        "${fallbackOffers.size} backup plans"
    }
    val queueSummary = if (fallbackOffers.size == 1) {
        "One backup is ready to run after the primary plan."
    } else {
        "${fallbackOffers.size} backup plans will run in the saved queue order."
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 148.dp),
        shape = RoundedCornerShape(22.dp),
        color = C.surface.copy(alpha = 0.74f),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.82f))
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, C.border.copy(alpha = 0.78f))
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    C.cyan.copy(alpha = 0.12f),
                                    C.cardHi.copy(alpha = 0.94f),
                                    C.surface.copy(alpha = 0.96f)
                                )
                            )
                        )
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = C.cardHi.copy(alpha = 0.74f),
                            border = BorderStroke(1.dp, C.border.copy(alpha = 0.85f))
                        ) {
                            Text(
                                number.toString(),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                color = C.amber,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Fallback route", color = C.t3, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(
                                primaryOffer.name,
                                color = C.t1,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text("${primaryOffer.category} • KES ${primaryOffer.price}", color = C.t2, fontSize = 11.sp)
                        }
                        FallbackStatusChip(
                            icon = Icons.Rounded.Autorenew,
                            label = fallbackPlanLabel,
                            tint = C.amber
                        )
                    }

                    Text(
                        queueSummary,
                        color = C.t2,
                        fontSize = 11.sp,
                        lineHeight = 17.sp
                    )

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FallbackRouteNode(
                            title = "Primary",
                            offer = primaryOffer,
                            accent = C.cyan,
                            modifier = Modifier.weight(1f)
                        )
                        FallbackRouteNode(
                            title = if (fallbackOffers.size > 1) "First backup" else "Backup",
                            offer = firstFallback,
                            accent = C.amber,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FallbackSummaryTile(
                    label = "Trigger",
                    value = fallbackRuleSummary,
                    accent = C.cyan,
                    modifier = Modifier.weight(1f)
                )
                FallbackSummaryTile(
                    label = "Updated",
                    value = fallbackUpdatedLabel,
                    accent = C.border,
                    modifier = Modifier.weight(1f)
                )
            }
            GroupDivider()
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Linked backup queue", color = C.t3, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "${fallbackOffers.size} plans",
                        color = C.t2,
                        fontSize = 10.sp
                    )
                }
                fallbackOffers.forEachIndexed { index, offer ->
                    FallbackQueueStep(
                        index = index + 1,
                        offer = offer,
                        isFirst = index == 0
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onEdit,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = C.cyan.copy(alpha = 0.16f),
                        contentColor = C.cyan
                    ),
                    contentPadding = PaddingValues(vertical = 10.dp)
                ) {
                    Icon(Icons.Rounded.AutoFixHigh, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Edit route", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = onDelete,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = C.red.copy(alpha = 0.14f),
                        contentColor = C.red
                    ),
                    contentPadding = PaddingValues(vertical = 10.dp)
                ) {
                    Icon(Icons.Rounded.DeleteSweep, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Delete route", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FallbackRouteEditorCard(
    primaryOffer: OfferItem,
    selectedFallbackOffers: List<OfferItem>,
    editingExistingRoute: Boolean,
    fallbackRuleOptions: List<FallbackRuleOption>,
    selectedFallbackRuleMode: String,
    onChangePrimary: () -> Unit,
    onSelectFallbackRule: (String) -> Unit,
    onMoveFallbackUp: (Int) -> Unit,
    onMoveFallbackDown: (Int) -> Unit,
    onRemoveFallback: (Int) -> Unit
) {
    val routeModeLabel = if (editingExistingRoute) "Editing" else "New"
    val queueCount = selectedFallbackOffers.size
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = C.surface.copy(alpha = 0.82f),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.86f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "ROUTE BUILDER",
                    color = C.green,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Fallback Plans",
                    color = C.t1,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    lineHeight = 28.sp
                )
                Text(
                    "Set a primary plan, then couple backup plans in the order they should run if it fails.",
                    color = C.t2,
                    fontSize = 11.sp,
                    lineHeight = 17.sp
                )
            }

            FallbackMilepost(number = 1, title = "Primary Plan")
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = C.cardHi.copy(alpha = 0.58f),
                border = BorderStroke(1.dp, C.amber.copy(alpha = 0.30f))
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = C.amber.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, C.amber.copy(alpha = 0.24f))
                        ) {
                            Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Router, null, tint = C.amber, modifier = Modifier.size(18.dp))
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("Selected primary plan", color = C.t3, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            Text(
                                primaryOffer.name,
                                color = C.t1,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text("${primaryOffer.category} · KES ${primaryOffer.price}", color = C.t2, fontSize = 11.sp)
                        }
                        Surface(
                            modifier = Modifier.clickable(onClick = onChangePrimary),
                            shape = RoundedCornerShape(3.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, C.amber.copy(alpha = 0.28f))
                        ) {
                            Text(
                                "CHANGE",
                                color = C.amber,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FallbackRouteStatBox(
                            label = "Route mode",
                            value = routeModeLabel,
                            accent = C.amber,
                            modifier = Modifier.weight(1f)
                        )
                        FallbackRouteStatBox(
                            label = "Queue",
                            value = queueCount.toString(),
                            accent = C.green,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            FallbackMilepost(number = 2, title = "Backup Queue")
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = C.cardHi.copy(alpha = 0.58f),
                border = BorderStroke(1.dp, C.border.copy(alpha = 0.78f))
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Run Order", color = C.t1, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                        Surface(
                            shape = RoundedCornerShape(3.dp),
                            color = C.green,
                            border = BorderStroke(1.dp, C.green.copy(alpha = 0.28f))
                        ) {
                            Text(
                                "$queueCount PLAN${if (queueCount == 1) "" else "S"}",
                                color = C.bg,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                    if (selectedFallbackOffers.isEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, C.border.copy(alpha = 0.72f))
                        ) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Filled.ChevronRight, null, tint = C.amber.copy(alpha = 0.45f), modifier = Modifier.size(20.dp))
                                Text(
                                    "No backup plans coupled yet.\nAdd plans below and they will couple here in order.",
                                    color = C.t3,
                                    fontSize = 11.sp,
                                    lineHeight = 17.sp
                                )
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            selectedFallbackOffers.forEachIndexed { index, offer ->
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = C.bg.copy(alpha = 0.92f),
                                    border = BorderStroke(
                                        1.dp,
                                        if (index == 0) C.green.copy(alpha = 0.34f) else C.border.copy(alpha = 0.72f)
                                    )
                                ) {
                                    Column(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Icon(
                                                Icons.Rounded.MoreVert,
                                                null,
                                                tint = C.t3,
                                                modifier = Modifier
                                                    .size(15.dp)
                                                    .rotate(90f)
                                            )
                                            Surface(
                                                shape = RoundedCornerShape(3.dp),
                                                color = C.green.copy(alpha = 0.12f),
                                                border = BorderStroke(1.dp, C.green.copy(alpha = 0.26f))
                                            ) {
                                                Box(
                                                    Modifier.size(24.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text("${index + 1}", color = C.green, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                                                }
                                            }
                                            Column(
                                                modifier = Modifier.weight(1f),
                                                verticalArrangement = Arrangement.spacedBy(2.dp)
                                            ) {
                                                Text(offer.name, color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                                                Text("${offer.category} · KES ${offer.price}", color = C.t3, fontSize = 10.sp)
                                            }
                                            Surface(
                                                modifier = Modifier.clickable { onRemoveFallback(index) },
                                                shape = RoundedCornerShape(3.dp),
                                                color = Color.Transparent,
                                                border = BorderStroke(1.dp, C.red.copy(alpha = 0.22f))
                                            ) {
                                                Text(
                                                    "X",
                                                    color = C.red,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                )
                                            }
                                        }
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Surface(
                                                modifier = Modifier.clickable(enabled = index > 0) { onMoveFallbackUp(index) },
                                                shape = RoundedCornerShape(3.dp),
                                                color = if (index > 0) C.cardHi.copy(alpha = 0.72f) else C.w04,
                                                border = BorderStroke(1.dp, C.border.copy(alpha = 0.70f))
                                            ) {
                                                Text(
                                                    "UP",
                                                    color = if (index > 0) C.t1 else C.t3,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                                )
                                            }
                                            Surface(
                                                modifier = Modifier.clickable(enabled = index < selectedFallbackOffers.lastIndex) {
                                                    onMoveFallbackDown(index)
                                                },
                                                shape = RoundedCornerShape(3.dp),
                                                color = if (index < selectedFallbackOffers.lastIndex) C.cardHi.copy(alpha = 0.72f) else C.w04,
                                                border = BorderStroke(1.dp, C.border.copy(alpha = 0.70f))
                                            ) {
                                                Text(
                                                    "DOWN",
                                                    color = if (index < selectedFallbackOffers.lastIndex) C.t1 else C.t3,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
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

            FallbackMilepost(number = 3, title = "Trigger Rules")
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = C.cardHi.copy(alpha = 0.58f),
                border = BorderStroke(1.dp, C.border.copy(alpha = 0.78f))
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    fallbackRuleOptions.forEach { option ->
                        FallbackRuleSelectorCard(
                            option = option,
                            selected = selectedFallbackRuleMode == option.mode,
                            onClick = { onSelectFallbackRule(option.mode) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FallbackEditorSummaryRow(
    title: String,
    value: String,
    supporting: String,
    tint: Color,
    actionLabel: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = C.cardHi.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.78f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = tint.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, tint.copy(alpha = 0.24f))
            ) {
                Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Devices, null, tint = tint, modifier = Modifier.size(18.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = C.t3, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                Text(value, color = C.t1, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(supporting, color = C.t2, fontSize = 11.sp)
            }
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = tint.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, tint.copy(alpha = 0.24f))
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(actionLabel, color = tint, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    Icon(Icons.Rounded.ArrowDropDown, null, tint = tint, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun FallbackFlowChip(
    label: String,
    tint: Color
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = tint.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.24f))
    ) {
        Text(
            label,
            color = if (tint == C.border) C.t2 else tint,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun FallbackEditorChip(
    label: String,
    tint: Color
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = tint.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.24f))
    ) {
        Text(
            label,
            color = if (tint == C.border) C.t3 else tint,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FallbackSelectedPlanCard(
    primaryOffer: OfferItem,
    index: Int,
    offer: OfferItem,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean
) {
    val amountDelta = offer.price - primaryOffer.price
    val deltaLabel = when {
        amountDelta == 0 -> "Same price as primary"
        amountDelta < 0 -> "KES ${-amountDelta} lower than primary"
        else -> "KES $amountDelta higher than primary"
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = C.surface.copy(alpha = 0.56f),
        border = BorderStroke(1.dp, if (index == 0) C.amber.copy(alpha = 0.30f) else C.border.copy(alpha = 0.82f))
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = if (index == 0) C.amber.copy(alpha = 0.16f) else C.cardHi,
                    border = BorderStroke(1.dp, if (index == 0) C.amber.copy(alpha = 0.24f) else C.border.copy(alpha = 0.72f))
                ) {
                    Box(
                        Modifier.size(28.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("${index + 1}", color = if (index == 0) C.amber else C.t1, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = if (index == 0) C.amber.copy(alpha = 0.14f) else C.cardHi,
                        border = BorderStroke(1.dp, if (index == 0) C.amber.copy(alpha = 0.22f) else C.border.copy(alpha = 0.65f))
                    ) {
                        Text(
                            if (index == 0) "Runs first" else "Queue position ${index + 1}",
                            color = if (index == 0) C.amber else C.t3,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Text(offer.name, color = C.t1, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("${offer.category} • KES ${offer.price}", color = C.t2, fontSize = 11.sp)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FallbackEditorChip(
                            label = if (index == 0) "First backup after primary" else "Runs after backup $index",
                            tint = if (index == 0) C.amber else C.border
                        )
                        FallbackEditorChip(label = deltaLabel, tint = C.border)
                    }
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (canMoveUp) C.cardHi else C.w04,
                    border = BorderStroke(1.dp, C.border.copy(alpha = 0.72f)),
                    modifier = Modifier.clickable(enabled = canMoveUp, onClick = onMoveUp)
                ) {
                    Text(
                        "Move up",
                        color = if (canMoveUp) C.t1 else C.t3,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (canMoveDown) C.cardHi else C.w04,
                    border = BorderStroke(1.dp, C.border.copy(alpha = 0.72f)),
                    modifier = Modifier.clickable(enabled = canMoveDown, onClick = onMoveDown)
                ) {
                    Text(
                        "Move down",
                        color = if (canMoveDown) C.t1 else C.t3,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = C.red.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, C.red.copy(alpha = 0.22f)),
                    modifier = Modifier.clickable(onClick = onRemove)
                ) {
                    Text(
                        "REMOVE",
                        color = C.red,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FallbackAvailablePlanCard(
    primaryOffer: OfferItem,
    offer: OfferItem,
    recommendation: String?,
    onAdd: () -> Unit
) {
    val sameCategory = offer.category.equals(primaryOffer.category, ignoreCase = true)
    val amountDelta = offer.price - primaryOffer.price
    val deltaLabel = when {
        amountDelta == 0 -> "Matches the primary amount"
        amountDelta < 0 -> "KES ${-amountDelta} cheaper"
        else -> "KES $amountDelta above primary"
    }
    Surface(
        shape = RoundedCornerShape(5.dp),
        color = C.surface.copy(alpha = 0.56f),
        border = BorderStroke(
            1.dp,
            if (!recommendation.isNullOrBlank()) C.green.copy(alpha = 0.26f) else C.border.copy(alpha = 0.82f)
        )
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(offer.name, color = C.t1, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                    Text("${offer.category} · KES ${offer.price} · $deltaLabel", color = C.t2, fontSize = 10.sp)
                }
                Surface(
                    modifier = Modifier.clickable(onClick = onAdd),
                    shape = RoundedCornerShape(3.dp),
                    color = C.bg.copy(alpha = 0.92f),
                    border = BorderStroke(1.dp, C.border.copy(alpha = 0.72f))
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("+", color = C.t1, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("ADD", color = C.t1, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FallbackEditorChip(label = "Available backup", tint = C.border)
                FallbackEditorChip(label = if (sameCategory) "Same category" else offer.category, tint = if (sameCategory) C.amber else C.border)
                if (!recommendation.isNullOrBlank()) {
                    FallbackEditorChip(label = recommendation, tint = C.green)
                }
            }
        }
    }
}

@Composable
private fun FallbackEmptyState(message: String) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = C.cardHi.copy(alpha = 0.46f),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.72f))
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = C.amber.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, C.amber.copy(alpha = 0.20f))
            ) {
                Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Info, null, tint = C.amber, modifier = Modifier.size(16.dp))
                }
            }
            Text(
                message,
                color = C.t2,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun FallbackRouteNode(
    title: String,
    offer: OfferItem?,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = accent.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.20f))
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(title.uppercase(), color = accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text(offer?.name ?: "Not selected", color = C.t1, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(
                offer?.let { "${it.category} • KES ${it.price}" } ?: "Pick a plan",
                color = C.t2,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun FallbackQueueStep(
    index: Int,
    offer: OfferItem,
    isFirst: Boolean
) {
    val accent = if (isFirst) C.amber else C.border
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isFirst) C.amber.copy(alpha = 0.09f) else C.cardHi.copy(alpha = 0.60f),
        border = BorderStroke(
            1.dp,
            if (isFirst) C.amber.copy(alpha = 0.26f) else C.border.copy(alpha = 0.72f)
        )
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = accent.copy(alpha = if (isFirst) 0.16f else 0.12f),
                    border = BorderStroke(1.dp, accent.copy(alpha = if (isFirst) 0.28f else 0.20f))
                ) {
                    Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                        Text(
                            index.toString(),
                            color = if (isFirst) C.amber else C.t1,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                if (!isFirst) {
                    Box(
                        Modifier
                            .width(1.dp)
                            .height(22.dp)
                            .background(C.border.copy(alpha = 0.45f))
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        offer.name,
                        color = C.t1,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    if (isFirst) {
                        Text("Runs first", color = C.amber, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Text("${offer.category} • KES ${offer.price}", color = C.t2, fontSize = 11.sp)
                Text(
                    if (isFirst) "This backup runs immediately after the primary plan fails." else "This backup runs if the earlier backup cannot continue.",
                    color = C.t3,
                    fontSize = 10.sp,
                    lineHeight = 15.sp
                )
            }
        }
    }
}

@Composable
private fun FallbackPrimarySelectorCard(
    offer: OfferItem,
    selected: Boolean,
    mapped: Boolean,
    planNumber: Int?,
    onClick: () -> Unit
) {
    val accent = when {
        selected -> C.amber
        mapped -> C.cyan
        else -> C.border
    }
    val statusLabel = when {
        selected -> "Selected"
        mapped -> "Mapped"
        else -> "New route"
    }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = accent.copy(alpha = if (selected) 0.14f else 0.10f),
        border = BorderStroke(1.dp, accent.copy(alpha = if (selected) 0.30f else 0.20f)),
        modifier = Modifier
            .width(220.dp)
            .clickable(onClick = onClick)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = accent.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.24f))
                ) {
                    Text(
                        statusLabel,
                        color = if (accent == C.border) C.t2 else accent,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
                if (selected) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = C.amber, modifier = Modifier.size(16.dp))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (planNumber != null) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = C.surface.copy(alpha = 0.92f),
                            border = BorderStroke(1.dp, C.border.copy(alpha = 0.85f))
                        ) {
                            Text(
                                planNumber.toString(),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                color = accent,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    Text(
                        offer.name,
                        color = C.t1,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text("${offer.category} • KES ${offer.price}", color = C.t2, fontSize = 11.sp)
            }
            Text(
                if (mapped) "Open this primary plan to review or reorder its linked backup queue." else "Start a new backup route for this primary plan.",
                color = C.t3,
                fontSize = 10.sp,
                lineHeight = 15.sp
            )
        }
    }
}

@Composable
private fun AutomationSettings(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    var autoEnabled by remember { mutableStateOf(prefs.safeGetBoolean("automation_enabled", true)) }
    var autoRetry by remember { mutableStateOf(prefs.safeGetBoolean("auto_retry", false)) }
    var autoContacts by remember { mutableStateOf(prefs.safeGetBoolean("auto_save_contacts", true)) }
    val enabledOffers = remember { OfferRepository.load(ctx).filter { it.enabled } }
    val dailyLimitConfig = remember { DailyLimitPolicy.load(ctx) }
    val enabledOfferIds = remember(enabledOffers) { enabledOffers.map { it.id }.toSet() }
    var dailyLimitMode by remember { mutableStateOf(prefs.safeGetString("daily_limit_mode", DailyLimitPolicy.MODE_QUEUE_TOMORROW) ?: DailyLimitPolicy.MODE_QUEUE_TOMORROW) }
    var dailyLimitModeExp by remember { mutableStateOf(false) }
    var repeatNoticeEnabled by remember { mutableStateOf(prefs.safeGetBoolean("daily_limit_repeat_notice_enabled", false)) }
    var fallbackEnabled by remember { mutableStateOf(prefs.safeGetBoolean("daily_limit_fallback_enabled", false)) }
    val initialFallbackTriggerOfferNotFound = remember { prefs.safeGetBoolean("fallback_trigger_offer_not_found", prefs.safeGetBoolean("fallback_trigger_failed", true)) }
    val initialFallbackTriggerDailyLimit = remember { prefs.safeGetBoolean("fallback_trigger_daily_limit", true) }
    val initialFallbackRuleMode = remember {
        DailyLimitPolicy.normalizeFallbackRule(
            rawMode = prefs.safeGetString("daily_limit_fallback_rule_mode", null),
            legacyOfferNotFound = initialFallbackTriggerOfferNotFound,
            legacyDailyLimit = initialFallbackTriggerDailyLimit
        )
    }
    var fallbackRuleModeSaved by remember { mutableStateOf(initialFallbackRuleMode) }
    var fallbackRuleMode by remember { mutableStateOf(initialFallbackRuleMode) }
    val fallbackRuleOptions = remember {
        listOf(
            FallbackRuleOption(
                mode = DailyLimitPolicy.FALLBACK_RULE_ALREADY_RECOMMENDED,
                title = "Already Recommended",
                description = "Use fallback only when the number has already received today's plan.",
                icon = Icons.Rounded.CheckCircle
            ),
            FallbackRuleOption(
                mode = DailyLimitPolicy.FALLBACK_RULE_OFFER_NOT_FOUND,
                title = "Offer Not Found",
                description = "Use fallback only when the saved USSD flow or signature is no longer available.",
                icon = Icons.Rounded.Error
            ),
            FallbackRuleOption(
                mode = DailyLimitPolicy.FALLBACK_RULE_BOTH,
                title = "Both Rules",
                description = "Use fallback for either daily-limit responses or offer-not-found failures.",
                icon = Icons.Rounded.Autorenew
            )
        )
    }
    val fallbackRuleSummary = when (fallbackRuleMode) {
        DailyLimitPolicy.FALLBACK_RULE_ALREADY_RECOMMENDED -> "Already recommended only"
        DailyLimitPolicy.FALLBACK_RULE_OFFER_NOT_FOUND -> "Offer not found only"
        else -> "Already recommended and offer not found"
    }
    val initialFallbackMappings = remember(enabledOffers) {
        fun normalizeMappings(raw: List<DailyLimitFallbackMapping>): List<DailyLimitFallbackMapping> {
            return raw.mapNotNull { mapping ->
                val primaryOfferId = mapping.primaryOfferId.takeIf { it in enabledOfferIds } ?: return@mapNotNull null
                val fallbackIds = mapping.fallbackOfferIds
                    .filter { it in enabledOfferIds && it != primaryOfferId }
                    .distinct()
                if (fallbackIds.isEmpty()) null else DailyLimitFallbackMapping(primaryOfferId, fallbackIds)
            }
        }

        val configuredMappings = normalizeMappings(dailyLimitConfig.fallbackMappings)
        if (configuredMappings.isNotEmpty()) {
            configuredMappings
        } else {
            val legacyFallbackId = dailyLimitConfig.legacyFallbackOfferId
            if (legacyFallbackId in enabledOfferIds) {
                enabledOffers
                    .filter { it.id != legacyFallbackId }
                    .map { DailyLimitFallbackMapping(primaryOfferId = it.id, fallbackOfferIds = listOf(legacyFallbackId)) }
            } else {
                emptyList()
            }
        }
    }
    var fallbackMappings by remember { mutableStateOf(initialFallbackMappings) }
    var fallbackPrimaryOfferId by remember {
        mutableIntStateOf(initialFallbackMappings.firstOrNull()?.primaryOfferId ?: enabledOffers.firstOrNull()?.id ?: -1)
    }
    var fallbackPrimaryExp by remember { mutableStateOf(false) }
    var fallbackAddPrimaryExp by remember { mutableStateOf(false) }
    var fallbackDeleteConfirmOfferId by remember { mutableIntStateOf(-1) }
    var fallbackUpdatedAt by remember { mutableStateOf(prefs.safeGetLong("daily_limit_fallback_updated_at", 0L)) }
    val selectedPrimaryOffer = enabledOffers.firstOrNull { it.id == fallbackPrimaryOfferId }
    val selectedPrimaryFallbackIds = fallbackMappings
        .firstOrNull { it.primaryOfferId == fallbackPrimaryOfferId }
        ?.fallbackOfferIds
        .orEmpty()
    var editorFallbackIds by remember { mutableStateOf(selectedPrimaryFallbackIds) }
    var editorDirty by remember { mutableStateOf(false) }
    LaunchedEffect(fallbackPrimaryOfferId, fallbackMappings) {
        if (!editorDirty) {
            editorFallbackIds = selectedPrimaryFallbackIds
        }
    }
    val selectedPrimaryFallbackOffers = editorFallbackIds.mapNotNull { fallbackId ->
        enabledOffers.firstOrNull { it.id == fallbackId }
    }
    val availableFallbackOffers = enabledOffers
        .filter { offer -> offer.id != fallbackPrimaryOfferId && offer.id !in editorFallbackIds }
        .sortedWith(
            compareBy<OfferItem>(
                { offer ->
                    val primaryCategory = selectedPrimaryOffer?.category?.trim().orEmpty()
                    if (primaryCategory.isNotBlank() && offer.category.equals(primaryCategory, ignoreCase = true)) 0 else 1
                },
                { offer ->
                    selectedPrimaryOffer?.let { primary ->
                        if (offer.price <= primary.price) 0 else 1
                    } ?: 0
                },
                { offer ->
                    selectedPrimaryOffer?.let { primary -> (offer.price - primary.price).absoluteValue } ?: Int.MAX_VALUE
                },
                { offer -> offer.name.lowercase() }
            )
        )
    val suggestedFallbackOffers = availableFallbackOffers.filter { offer ->
        selectedPrimaryOffer?.let { primary ->
            val sameCategory = offer.category.equals(primary.category, ignoreCase = true)
            sameCategory || offer.price <= primary.price
        } ?: false
    }
    val secondaryFallbackOffers = availableFallbackOffers.filterNot { offer ->
        suggestedFallbackOffers.any { it.id == offer.id }
    }
    val configuredFallbackPlans = fallbackMappings.mapNotNull { mapping ->
        val primary = enabledOffers.firstOrNull { it.id == mapping.primaryOfferId } ?: return@mapNotNull null
        val fallbacks = mapping.fallbackOfferIds.mapNotNull { fallbackId ->
            enabledOffers.firstOrNull { it.id == fallbackId }
        }
        if (fallbacks.isEmpty()) null else primary to fallbacks
    }.sortedBy { it.first.name.lowercase() }
    val mappedPrimaryOfferIds = remember(fallbackMappings) { fallbackMappings.map { it.primaryOfferId }.toSet() }
    val unmappedPrimaryOffers = enabledOffers.filter { it.id !in mappedPrimaryOfferIds }
    val editingExistingRoute = fallbackPrimaryOfferId in mappedPrimaryOfferIds
    val fallbackRouteCount = fallbackMappings.sumOf { it.fallbackOfferIds.size }
    val fallbackPrimaryCount = configuredFallbackPlans.size
    val fallbackUpdatedLabel = if (fallbackUpdatedAt > 0L) {
        "Last updated: ${SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(fallbackUpdatedAt))}"
    } else {
        "Tap to review this route"
    }
    val ruleDirty = fallbackRuleMode != fallbackRuleModeSaved
    val hasUnsavedFallbackChanges = ruleDirty || editorDirty

    fun saveDailyLimitMode(mode: String) {
        dailyLimitMode = mode
        val edit = prefs.edit().putString("daily_limit_mode", mode)
        if (mode == DailyLimitPolicy.MODE_QUEUE_TOMORROW && repeatNoticeEnabled) {
            repeatNoticeEnabled = false
            edit.putBoolean("daily_limit_repeat_notice_enabled", false)
        }
        edit.apply()
    }

    fun saveRepeatNotice(enabled: Boolean) {
        repeatNoticeEnabled = enabled
        val edit = prefs.edit().putBoolean("daily_limit_repeat_notice_enabled", enabled)
        if (enabled) {
            dailyLimitMode = DailyLimitPolicy.MODE_NOTICE_ONLY
            edit.putString("daily_limit_mode", DailyLimitPolicy.MODE_NOTICE_ONLY)
        }
        edit.apply()
    }

    fun persistFallbackMappings(updated: List<DailyLimitFallbackMapping>) {
        val normalized = updated.mapNotNull { mapping ->
            val primaryOfferId = mapping.primaryOfferId.takeIf { it in enabledOfferIds } ?: return@mapNotNull null
            val fallbackIds = mapping.fallbackOfferIds
                .filter { it in enabledOfferIds && it != primaryOfferId }
                .distinct()
            if (fallbackIds.isEmpty()) null else DailyLimitFallbackMapping(primaryOfferId, fallbackIds)
        }
        fallbackMappings = normalized
        DailyLimitPolicy.saveFallbackMappings(ctx, normalized)
        fallbackUpdatedAt = System.currentTimeMillis()
        prefs.edit().putLong("daily_limit_fallback_updated_at", fallbackUpdatedAt).apply()
        if (fallbackPrimaryOfferId !in enabledOfferIds) {
            fallbackPrimaryOfferId = normalized.firstOrNull()?.primaryOfferId ?: enabledOffers.firstOrNull()?.id ?: -1
        }
    }

    fun updateFallbacksForPrimary(primaryOfferId: Int, transform: (List<Int>) -> List<Int>) {
        if (primaryOfferId !in enabledOfferIds) return
        val current = fallbackMappings.firstOrNull { it.primaryOfferId == primaryOfferId }?.fallbackOfferIds.orEmpty()
        val updatedIds = transform(current)
            .filter { it in enabledOfferIds && it != primaryOfferId }
            .distinct()
        val nextMappings = fallbackMappings
            .filterNot { it.primaryOfferId == primaryOfferId }
            .toMutableList()
        if (updatedIds.isNotEmpty()) {
            nextMappings += DailyLimitFallbackMapping(primaryOfferId = primaryOfferId, fallbackOfferIds = updatedIds)
        }
        persistFallbackMappings(nextMappings)
    }

    fun deleteFallbackRoute(primaryOfferId: Int) {
        val nextMappings = fallbackMappings.filterNot { it.primaryOfferId == primaryOfferId }
        persistFallbackMappings(nextMappings)
        if (fallbackPrimaryOfferId == primaryOfferId) {
            fallbackPrimaryOfferId = nextMappings.firstOrNull()?.primaryOfferId
                ?: unmappedPrimaryOffers.firstOrNull()?.id
                ?: enabledOffers.firstOrNull()?.id
                ?: -1
        }
        editorDirty = false
    }

    fun beginEditingPrimary(primaryOfferId: Int) {
        editorDirty = false
        fallbackPrimaryOfferId = primaryOfferId
    }

    val fallbackDeleteTarget = enabledOffers.firstOrNull { it.id == fallbackDeleteConfirmOfferId }

    Column(Modifier.fillMaxSize().background(C.bg).verticalScroll(rememberScrollState())) {
        if (fallbackDeleteTarget != null) {
            AlertDialog(
                onDismissRequest = { fallbackDeleteConfirmOfferId = -1 },
                containerColor = C.cardHi,
                title = { Text("Delete fallback route?", color = C.t1) },
                text = {
                    Text(
                        "Remove the saved fallback route for ${fallbackDeleteTarget.name}? You can create it again later.",
                        color = C.t2
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            deleteFallbackRoute(fallbackDeleteTarget.id)
                            fallbackDeleteConfirmOfferId = -1
                        }
                    ) { Text("Delete", color = C.red) }
                },
                dismissButton = {
                    TextButton(onClick = { fallbackDeleteConfirmOfferId = -1 }) {
                        Text("Cancel", color = C.t2)
                    }
                }
            )
        }

        SettingsTopBar("Fallback Plans", "Set backup plans that fit your whole automation flow", onBack)

        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = UiDimens.ScreenPaddingHorizontal),
            verticalArrangement = Arrangement.spacedBy(UiDimens.SpacingLg)
        ) {
            FallbackOverviewCard(
                fallbackEnabled = fallbackEnabled,
                onToggleChange = {
                    fallbackEnabled = it
                    prefs.edit().putBoolean("daily_limit_fallback_enabled", it).apply()
                }
            )

            AnimatedVisibility(visible = fallbackEnabled) {
                FallbackScreenContainer {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        FallbackSectionHeader(
                            title = "Saved routes",
                            subtitle = if (fallbackPrimaryCount == 0) {
                                "No primary plans are mapped yet."
                            } else {
                                "$fallbackPrimaryCount primary plans already have backup routes."
                            }
                        )

                        if (configuredFallbackPlans.isEmpty()) {
                            FallbackEmptyState("No fallback routes are saved yet. Pick a primary plan below, add backup plans, then save the route.")
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                configuredFallbackPlans.forEachIndexed { index, (primaryOffer, fallbackOffers) ->
                                    FallbackMappingCard(
                                        number = index + 1,
                                        primaryOffer = primaryOffer,
                                        fallbackOffers = fallbackOffers,
                                        fallbackRuleSummary = fallbackRuleSummary,
                                        fallbackUpdatedLabel = fallbackUpdatedLabel,
                                        onEdit = { beginEditingPrimary(primaryOffer.id) },
                                        onDelete = { fallbackDeleteConfirmOfferId = primaryOffer.id }
                                    )
                                }
                            }
                        }

                        if (unmappedPrimaryOffers.isEmpty() && configuredFallbackPlans.isNotEmpty()) {
                            FallbackEmptyState("All enabled primary plans already have routes. Tap any route to edit its backup order.")
                        }

                        if (selectedPrimaryOffer != null) {
                            FallbackSectionHeader(
                                title = "Route builder",
                                subtitle = if (editingExistingRoute) {
                                    "Editing ${selectedPrimaryOffer.name}. Update the backup queue, then save."
                                } else {
                                    "Choose a primary plan, add one or more backup plans, then save."
                                },
                                action = {
                                    Box {
                                        FallbackInlineActionButton(
                                            label = "New route",
                                            enabled = enabledOffers.isNotEmpty(),
                                            onClick = {
                                                when {
                                                    unmappedPrimaryOffers.size == 1 -> beginEditingPrimary(unmappedPrimaryOffers.first().id)
                                                    unmappedPrimaryOffers.isNotEmpty() -> fallbackAddPrimaryExp = true
                                                    enabledOffers.isNotEmpty() -> beginEditingPrimary(
                                                        fallbackPrimaryOfferId.takeIf { it in enabledOfferIds } ?: enabledOffers.first().id
                                                    )
                                                }
                                            }
                                        )
                                        DropdownMenu(
                                            expanded = fallbackAddPrimaryExp,
                                            onDismissRequest = { fallbackAddPrimaryExp = false },
                                            modifier = Modifier
                                                .background(C.cardHi, RoundedCornerShape(12.dp))
                                                .border(1.dp, C.border, RoundedCornerShape(12.dp))
                                        ) {
                                            unmappedPrimaryOffers.forEach { offer ->
                                                DropdownMenuItem(
                                                    text = { Text("${offer.name} • KES ${offer.price}", color = C.t1) },
                                                    onClick = {
                                                        beginEditingPrimary(offer.id)
                                                        fallbackAddPrimaryExp = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            )

                            Box {
                                FallbackRouteEditorCard(
                                    primaryOffer = selectedPrimaryOffer,
                                    selectedFallbackOffers = selectedPrimaryFallbackOffers,
                                    editingExistingRoute = editingExistingRoute,
                                    fallbackRuleOptions = fallbackRuleOptions,
                                    selectedFallbackRuleMode = fallbackRuleMode,
                                    onChangePrimary = { fallbackPrimaryExp = true },
                                    onSelectFallbackRule = { mode ->
                                        fallbackRuleMode = mode
                                    },
                                    onMoveFallbackUp = { index ->
                                        if (index > 0) {
                                            editorFallbackIds = editorFallbackIds.toMutableList().apply {
                                                add(index - 1, removeAt(index))
                                            }
                                            editorDirty = true
                                        }
                                    },
                                    onMoveFallbackDown = { index ->
                                        if (index < editorFallbackIds.lastIndex) {
                                            editorFallbackIds = editorFallbackIds.toMutableList().apply {
                                                add(index + 1, removeAt(index))
                                            }
                                            editorDirty = true
                                        }
                                    },
                                    onRemoveFallback = { index ->
                                        editorFallbackIds = editorFallbackIds.toMutableList().apply {
                                            if (index in indices) removeAt(index)
                                        }
                                        editorDirty = true
                                    }
                                )
                                DropdownMenu(
                                    expanded = fallbackPrimaryExp,
                                    onDismissRequest = { fallbackPrimaryExp = false },
                                    modifier = Modifier
                                        .background(C.cardHi, RoundedCornerShape(12.dp))
                                        .border(1.dp, C.border, RoundedCornerShape(12.dp))
                                ) {
                                    enabledOffers.forEach { offer ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    "${offer.name} • KES ${offer.price}",
                                                    color = if (offer.id == fallbackPrimaryOfferId) C.amber else C.t1
                                                )
                                            },
                                            onClick = {
                                                beginEditingPrimary(offer.id)
                                                fallbackPrimaryExp = false
                                            }
                                        )
                                    }
                                }
                            }

                            if (availableFallbackOffers.isNotEmpty()) {
                                FallbackSectionHeader(
                                    title = "Available plans",
                                    subtitle = "Suggested matches show first so you can build routes faster."
                                )

                                if (suggestedFallbackOffers.isNotEmpty()) {
                                    Text("Suggested for ${selectedPrimaryOffer.name}", color = C.t3, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        suggestedFallbackOffers.forEach { offer ->
                                            val recommendation = when {
                                                offer.category.equals(selectedPrimaryOffer.category, ignoreCase = true) &&
                                                    offer.price <= selectedPrimaryOffer.price ->
                                                    "Same category and lower amount"
                                                offer.category.equals(selectedPrimaryOffer.category, ignoreCase = true) ->
                                                    "Same category"
                                                offer.price <= selectedPrimaryOffer.price ->
                                                    "Lower amount"
                                                else -> null
                                            }
                                            FallbackAvailablePlanCard(
                                                primaryOffer = selectedPrimaryOffer,
                                                offer = offer,
                                                recommendation = recommendation,
                                                onAdd = {
                                                    editorFallbackIds = editorFallbackIds + offer.id
                                                    editorDirty = true
                                                }
                                            )
                                        }
                                    }
                                }

                                if (secondaryFallbackOffers.isNotEmpty()) {
                                    if (suggestedFallbackOffers.isNotEmpty()) {
                                        Text("More options", color = C.t3, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        secondaryFallbackOffers.forEach { offer ->
                                            FallbackAvailablePlanCard(
                                                primaryOffer = selectedPrimaryOffer,
                                                offer = offer,
                                                recommendation = null,
                                                onAdd = {
                                                    editorFallbackIds = editorFallbackIds + offer.id
                                                    editorDirty = true
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            if (editingExistingRoute) {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = C.red.copy(alpha = 0.12f),
                                    border = BorderStroke(1.dp, C.red.copy(alpha = 0.22f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { fallbackDeleteConfirmOfferId = selectedPrimaryOffer.id }
                                ) {
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(Icons.Rounded.DeleteSweep, null, tint = C.red, modifier = Modifier.size(16.dp))
                                        Text("Delete this route", color = C.red, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    }
                                }
                            }

                            AnimatedVisibility(visible = hasUnsavedFallbackChanges) {
                                Button(
                                    onClick = {
                                        fallbackRuleModeSaved = fallbackRuleMode
                                        prefs.edit()
                                            .putString("daily_limit_fallback_rule_mode", fallbackRuleMode)
                                            .putBoolean(
                                                "fallback_trigger_offer_not_found",
                                                DailyLimitPolicy.ruleIncludesOfferNotFound(fallbackRuleMode)
                                            )
                                            .putBoolean(
                                                "fallback_trigger_daily_limit",
                                                DailyLimitPolicy.ruleIncludesAlreadyRecommended(fallbackRuleMode)
                                            )
                                            .remove("daily_limit_fallback_min_price")
                                            .apply()
                                        updateFallbacksForPrimary(fallbackPrimaryOfferId) { editorFallbackIds }
                                        editorDirty = false
                                    },
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = C.amber,
                                        contentColor = C.bg
                                    ),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                                ) {
                                    Icon(Icons.Filled.Save, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (editingExistingRoute) {
                                            "Save ${selectedPrimaryOffer.name}"
                                        } else {
                                            "Save route"
                                        },
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            SettingsGroup("Automation") {
                ToggleRow(Icons.Rounded.Autorenew, "Enable Automation", "Auto-run bundles on payment", autoEnabled) {
                    autoEnabled = it
                    prefs.edit().putBoolean("automation_enabled", it).apply()
                    if (!it) ctx.stopService(android.content.Intent(ctx, BalanceChecker::class.java))
                    else ServiceLauncher.startBalanceChecker(ctx)
                }
                GroupDivider()
                ToggleRow(Icons.Rounded.Autorenew, "Auto-Retry on Failure", "Retry failed USSD up to 3 times", autoRetry) {
                    autoRetry = it
                    prefs.edit().putBoolean("auto_retry", it).apply()
                }
                GroupDivider()
                ToggleRow(Icons.Rounded.PersonAdd, "Auto-Save Contacts", "Save payer numbers from M-PESA SMS", autoContacts) {
                    autoContacts = it
                    prefs.edit().putBoolean("auto_save_contacts", it).apply()
                }
            }

            SettingsGroup("Daily Limit Handling") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    SettingsRowIcon(Icons.Rounded.Schedule)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("If number already got today's plan", color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text("Choose one mode. Turning on repeat notice disables auto-queue tomorrow.", color = C.t2, fontSize = 11.sp)
                    }
                    Box {
                        val label = if (dailyLimitMode == DailyLimitPolicy.MODE_NOTICE_ONLY) "NOTICE" else "QUEUE"
                        TextButton(onClick = { dailyLimitModeExp = true }) { Text(label, color = C.cyan, fontSize = 12.sp) }
                        DropdownMenu(
                            expanded = dailyLimitModeExp,
                            onDismissRequest = { dailyLimitModeExp = false },
                            modifier = Modifier.background(C.cardHi, RoundedCornerShape(12.dp)).border(1.dp, C.border, RoundedCornerShape(12.dp))
                        ) {
                            DropdownMenuItem(
                                text = { Text("QUEUE TOMORROW", color = if (dailyLimitMode == DailyLimitPolicy.MODE_QUEUE_TOMORROW) C.cyan else C.t1) },
                                onClick = {
                                    saveDailyLimitMode(DailyLimitPolicy.MODE_QUEUE_TOMORROW)
                                    dailyLimitModeExp = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("NOTICE ONLY", color = if (dailyLimitMode == DailyLimitPolicy.MODE_NOTICE_ONLY) C.cyan else C.t1) },
                                onClick = {
                                    saveDailyLimitMode(DailyLimitPolicy.MODE_NOTICE_ONLY)
                                    dailyLimitModeExp = false
                                }
                            )
                        }
                    }
                }
                GroupDivider()
                ToggleRow(
                    Icons.Rounded.Info,
                    "Repeat Same-Number Notice",
                    "If the same blocked number pays again today, send a notice instead of starting another dispatch",
                    repeatNoticeEnabled
                ) {
                    saveRepeatNotice(it)
                }
                AnimatedVisibility(visible = repeatNoticeEnabled) {
                    Column {
                        GroupDivider()
                        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Surface(shape = RoundedCornerShape(14.dp), color = C.w04, border = BorderStroke(1.dp, C.border)) {
                                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                    Text("Repeat Notice Active", color = C.t1, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "Same-number repeat payments are held and the customer is told to provide another number or confirm tomorrow morning dispatch. Auto-queue tomorrow is switched off while this stays enabled.",
                                        color = C.t2,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(22.dp))
    }
}

@Composable
private fun AdminAlertsSettings(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    var alertLowBalance by remember { mutableStateOf(prefs.safeGetBoolean("alert_low_balance", false)) }
    var alertLowTokens by remember { mutableStateOf(prefs.safeGetBoolean("alert_low_tokens", false)) }
    var alertFailedTx by remember { mutableStateOf(prefs.safeGetBoolean("alert_failed_tx", false)) }
    var lowBalanceLimit by remember { mutableIntStateOf(prefs.safeGetInt("low_balance_limit", 50)) }
    var lowTokenLimit by remember { mutableIntStateOf(prefs.safeGetInt("low_token_limit", 5)) }
    var alertLowBattery by remember { mutableStateOf(prefs.safeGetBoolean("alert_low_battery", false)) }
    var lowBatteryLimit by remember { mutableIntStateOf(prefs.safeGetInt("low_battery_limit", 20)) }

    Column(Modifier.fillMaxSize().background(C.bg).verticalScroll(rememberScrollState())) {
        SettingsTopBar("Admin Alerts", "Notifications sent to admin", onBack)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsGroup("Admin Alerts") {
                ToggleRow(Icons.Rounded.Warning, "Low Airtime Alert", "SMS when balance drops below limit", alertLowBalance) {
                    alertLowBalance = it
                    prefs.edit().putBoolean("alert_low_balance", it).apply()
                }
                AnimatedVisibility(visible = alertLowBalance) {
                    Column {
                        GroupDivider()
                        LimitRow("Limit (KES)", lowBalanceLimit) { v -> lowBalanceLimit = v; prefs.edit().putInt("low_balance_limit", v).apply() }
                    }
                }
                GroupDivider()
                ToggleRow(Icons.Rounded.Circle, "Low Tokens Alert", "SMS when tokens fall below limit", alertLowTokens) {
                    alertLowTokens = it
                    prefs.edit().putBoolean("alert_low_tokens", it).apply()
                }
                AnimatedVisibility(visible = alertLowTokens) {
                    Column {
                        GroupDivider()
                        LimitRow("Limit", lowTokenLimit) { v -> lowTokenLimit = v; prefs.edit().putInt("low_token_limit", v).apply() }
                    }
                }
                GroupDivider()
                ToggleRow(Icons.Rounded.Error, "Failed Transaction Alert", "SMS when a transaction fails", alertFailedTx) {
                    alertFailedTx = it
                    prefs.edit().putBoolean("alert_failed_tx", it).apply()
                }
                GroupDivider()
                ToggleRow(Icons.Rounded.BatteryAlert, "Low Battery Alert", "SMS when battery drops below limit", alertLowBattery) {
                    alertLowBattery = it
                    prefs.edit().putBoolean("alert_low_battery", it).apply()
                }
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
        }
        Spacer(Modifier.height(22.dp))
    }
}

@Composable
private fun TransactionSettings(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    var autoClear by remember { mutableStateOf(prefs.safeGetString("auto_clear", "Never") ?: "Never") }
    var confirmClear by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var activeTab by remember { mutableStateOf(TransactionHistoryFilter.ALL) }
    var searchQuery by remember { mutableStateOf("") }
    var summaryPeriod by remember {
        mutableStateOf(
            TransactionHistorySummaryPeriod.fromValue(
                prefs.safeGetString("transaction_summary_period", TransactionHistorySummaryPeriod.WEEKLY.value)
                    ?: TransactionHistorySummaryPeriod.WEEKLY.value
            )
        )
    }
    var history by remember {
        mutableStateOf(historyVisibleTransactions(TransactionStore.load(ctx)))
    }
    var selectedTx by remember { mutableStateOf<Transaction?>(null) }
    val offers = remember { OfferRepository.load(ctx).filter { it.enabled } }
    val clearOptions = remember { listOf("Daily", "Weekly", "Monthly", "Yearly", "Never") }
    val normalizedQuery = searchQuery.trim()
    val historyEntries = remember(history, offers) {
        history.map { tx ->
            TransactionHistoryEntry(
                tx = tx,
                classification = transactionHistoryFilterFor(tx, offers),
                dateLabel = transactionHistoryDateLabel(tx)
            )
        }
    }
    val tabs = remember(historyEntries) {
        TransactionHistoryFilter.entries.map { filter ->
            TransactionHistoryTab(
                filter = filter,
                label = filter.label,
                count = if (filter == TransactionHistoryFilter.ALL) {
                    historyEntries.size
                } else {
                    historyEntries.count { it.classification == filter }
                }
            )
        }
    }
    val filteredHistory = remember(historyEntries, activeTab, normalizedQuery) {
        historyEntries.filter { entry ->
            val tx = entry.tx
            val matchesTab = activeTab == TransactionHistoryFilter.ALL ||
                entry.classification == activeTab
            val matchesQuery = normalizedQuery.isBlank() ||
                tx.clientName.contains(normalizedQuery, ignoreCase = true) ||
                tx.description.contains(normalizedQuery, ignoreCase = true) ||
                tx.phoneNumber.contains(normalizedQuery, ignoreCase = true)
            matchesTab && matchesQuery
        }
    }
    val groupedHistory = remember(filteredHistory) {
        filteredHistory.groupBy { it.dateLabel }
    }
    val summaryCompleted = remember(historyEntries, summaryPeriod) {
        historyEntries.filter {
            it.classification == TransactionHistoryFilter.COMPLETED &&
                transactionMatchesSummaryPeriod(it.tx, summaryPeriod)
        }
    }
    val totalCollected = remember(summaryCompleted) {
        summaryCompleted.sumOf { transactionHistoryAmountValue(it.tx) }
    }
    val unmatchedCount = remember(historyEntries) {
        historyEntries.count { it.classification == TransactionHistoryFilter.UNMATCHED }
    }

    DisposableEffect(Unit) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val txId = intent.getIntExtra("txId", -1)
                if (txId < 0) return
                when (intent.action) {
                    ACTION_TX_CREATED,
                    "com.bingwa.mobile.TX_UPDATED" -> {
                        val tx = loadTransactionByIdFromPrefs(ctx, txId) ?: return
                        val idx = history.indexOfFirst { it.id == txId }
                        val updated = if (idx >= 0) {
                            history.toMutableList().apply {
                                if (shouldShowInHistory(tx)) {
                                    this[idx] = tx
                                } else {
                                    removeAt(idx)
                                }
                            }
                        } else if (shouldShowInHistory(tx)) {
                            mutableListOf<Transaction>().apply {
                                add(tx)
                                addAll(history)
                            }
                        } else {
                            history
                        }
                        history = historyVisibleTransactions(updated)
                    }
                }
            }
        }
        val receiverRegistered = registerAppReceiver(ctx, receiver, android.content.IntentFilter().apply {
            addAction("com.bingwa.mobile.TX_UPDATED")
            addAction(ACTION_TX_CREATED)
        })
        onDispose {
            if (receiverRegistered) {
                try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {}
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            confirmButton = {
                TextButton(onClick = {
                    TransactionStore.clear(ctx)
                    history = emptyList()
                    Toast.makeText(ctx, "Transactions cleared", Toast.LENGTH_SHORT).show()
                    confirmClear = false
                }) { Text("Clear", color = C.red) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel", color = C.t2) } },
            title = { Text("Clear all transactions?", color = C.t1) },
            text = { Text("This will wipe the entire transaction history.", color = C.t2) },
            containerColor = C.cardHi,
            properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true)
        )
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF0A0C0D),
                        Color(0xFF111517),
                        C.bg
                    )
                )
            ),
        contentPadding = PaddingValues(bottom = 22.dp)
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(44.dp)
                            .background(C.surface.copy(alpha = 0.92f), RoundedCornerShape(14.dp))
                    ) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = C.t1)
                    }
                    Text(
                        "Transactions",
                        color = C.t1,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Box {
                        IconButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier
                                .size(44.dp)
                                .background(C.surface.copy(alpha = 0.92f), RoundedCornerShape(14.dp))
                        ) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "More", tint = C.t1)
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                            modifier = Modifier
                                .width(272.dp)
                                .background(Color(0xFF262C2F), RoundedCornerShape(16.dp))
                                .border(1.dp, C.border.copy(alpha = 0.74f), RoundedCornerShape(16.dp))
                        ) {
                            TransactionMenuSectionTitle("Total Received Window")
                            TransactionHistorySummaryPeriod.entries.forEach { period ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            period.menuLabel,
                                            color = if (period == summaryPeriod) C.amber else C.t1,
                                            fontSize = 13.sp,
                                            fontWeight = if (period == summaryPeriod) FontWeight.Bold else FontWeight.Medium
                                        )
                                    },
                                    trailingIcon = {
                                        if (period == summaryPeriod) {
                                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = C.amber)
                                        }
                                    },
                                    onClick = {
                                        summaryPeriod = period
                                        prefs.edit().putString("transaction_summary_period", period.value).apply()
                                        menuExpanded = false
                                    }
                                )
                            }
                            TransactionMenuDivider()
                            TransactionMenuSectionTitle("Retention & Cleanup")
                            clearOptions.forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "Auto-Clear: $option",
                                            color = if (option == autoClear) C.cyan else C.t1,
                                            fontSize = 13.sp,
                                            fontWeight = if (option == autoClear) FontWeight.Bold else FontWeight.Medium
                                        )
                                    },
                                    trailingIcon = {
                                        if (option == autoClear) {
                                            Icon(Icons.Rounded.Autorenew, contentDescription = null, tint = C.cyan)
                                        }
                                    },
                                    onClick = {
                                        autoClear = option
                                        prefs.edit().putString("auto_clear", option).apply()
                                        menuExpanded = false
                                    }
                                )
                            }
                            TransactionMenuDivider()
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text("Clear All Transactions", color = C.red, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                        Text("Wipe entire transaction history", color = C.t3, fontSize = 11.sp)
                                    }
                                },
                                trailingIcon = {
                                    Icon(Icons.Rounded.DeleteSweep, contentDescription = null, tint = C.red)
                                },
                                onClick = {
                                    confirmClear = true
                                    menuExpanded = false
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                TransactionHistoryHeroCard(
                    summaryPeriod = summaryPeriod,
                    totalCollected = totalCollected,
                    completedCount = summaryCompleted.size,
                    unmatchedCount = unmatchedCount
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Search by name or number...", color = C.t3) },
                    leadingIcon = {
                        Icon(Icons.Rounded.Search, contentDescription = null, tint = C.t3)
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF15191B),
                        unfocusedContainerColor = Color(0xFF15191B),
                        focusedBorderColor = Color(0xFF333B3E),
                        unfocusedBorderColor = Color(0xFF333B3E),
                        focusedTextColor = C.t1,
                        unfocusedTextColor = C.t1,
                        cursorColor = C.cyan,
                        focusedLeadingIconColor = C.t3,
                        unfocusedLeadingIconColor = C.t3,
                        focusedPlaceholderColor = C.t3,
                        unfocusedPlaceholderColor = C.t3
                    )
                )
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    tabs.forEach { tab ->
                        TransactionHistoryTabChip(
                            tab = tab,
                            isActive = activeTab == tab.filter,
                            onClick = { activeTab = tab.filter }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        if (filteredHistory.isEmpty()) {
            item {
                TransactionHistoryEmptyState(
                    hasTransactions = history.isNotEmpty(),
                    filterLabel = activeTab.label,
                    hasSearch = normalizedQuery.isNotBlank()
                )
            }
        } else {
            groupedHistory.forEach { (dateLabel, itemsForDate) ->
                item {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            dateLabel.uppercase(Locale.getDefault()),
                            color = C.t3,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                items(
                    items = itemsForDate,
                    key = { it.tx.id }
                ) { entry ->
                    TransactionHistoryRow(
                        tx = entry.tx,
                        offers = offers,
                        onClick = { selectedTx = entry.tx },
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
                item {
                    Spacer(Modifier.height(14.dp))
                }
            }
        }
    }

    selectedTx?.let { tx ->
        TransactionDetailDialog(
            tx = tx,
            onDismiss = { selectedTx = null },
            onDelete = {
                val updated = history.filterNot { it.id == tx.id }
                TransactionStore.save(ctx, updated)
                history = historyVisibleTransactions(updated)
                Toast.makeText(ctx, "Transaction deleted", Toast.LENGTH_SHORT).show()
                selectedTx = null
            }
        )
    }
}

private enum class TransactionHistorySummaryPeriod(
    val value: String,
    val menuLabel: String,
    val heroLabel: String
) {
    DAILY("daily", "Daily", "TODAY'S RECEIVED"),
    WEEKLY("weekly", "Weekly", "THIS WEEK'S RECEIVED"),
    MONTHLY("monthly", "Monthly", "THIS MONTH'S RECEIVED");

    companion object {
        fun fromValue(value: String): TransactionHistorySummaryPeriod =
            entries.find { it.value.equals(value, ignoreCase = true) } ?: DAILY
    }
}

private enum class TransactionHistoryFilter(val label: String) {
    ALL("All"),
    COMPLETED("Completed"),
    PENDING("Pending"),
    FAILED("Failed"),
    SCHEDULED("Scheduled"),
    UNMATCHED("Unmatched")
}

private data class TransactionHistoryTab(
    val filter: TransactionHistoryFilter,
    val label: String,
    val count: Int
)

private data class TransactionHistoryEntry(
    val tx: Transaction,
    val classification: TransactionHistoryFilter,
    val dateLabel: String
)

@Composable
private fun TransactionHistoryHeroCard(
    summaryPeriod: TransactionHistorySummaryPeriod,
    totalCollected: Double,
    completedCount: Int,
    unmatchedCount: Int
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                brush = Brush.linearGradient(
                    listOf(
                        Color(0x22FFB454),
                        Color(0x1674E6D8),
                        Color(0xFF1C2123)
                    )
                ),
                shape = RoundedCornerShape(22.dp)
            )
            .border(1.dp, Color(0xFF262D2F), RoundedCornerShape(22.dp))
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            listOf(10.dp, 18.dp, 8.dp, 24.dp, 14.dp).forEach { barHeight ->
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(barHeight)
                        .clip(RoundedCornerShape(999.dp))
                        .background(C.cyan.copy(alpha = 0.35f))
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                summaryPeriod.heroLabel,
                color = C.t2,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                transactionHistoryCurrency(totalCollected),
                color = C.amber,
                fontSize = 30.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = C.cyan, modifier = Modifier.size(14.dp))
                Text(
                    if (unmatchedCount > 0) {
                        "$completedCount successful transactions • $unmatchedCount unmatched"
                    } else {
                        "$completedCount successful transactions"
                    },
                    color = C.t2,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun TransactionHistoryTabChip(
    tab: TransactionHistoryTab,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(
                if (isActive) {
                    Brush.horizontalGradient(
                        listOf(
                            C.amber.copy(alpha = 0.96f),
                            C.cyan.copy(alpha = 0.92f)
                        )
                    )
                } else {
                    Brush.horizontalGradient(
                        listOf(
                            Color(0xFF1C2123),
                            Color(0xFF1C2123)
                        )
                    )
                },
                shape
            )
            .border(
                width = if (isActive) 0.dp else 1.dp,
                color = if (isActive) Color.Transparent else Color(0xFF262D2F),
                shape = shape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            tab.label,
            color = if (isActive) Color(0xFF15191B) else C.t2,
            fontSize = 13.sp,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(
                    if (isActive) Color(0x3315191B) else Color(0xFF2E3437),
                    RoundedCornerShape(999.dp)
                )
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                tab.count.toString(),
                color = if (isActive) Color(0xFF15191B) else C.t3,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip
            )
        }
    }
}

@Composable
private fun TransactionHistoryEmptyState(
    hasTransactions: Boolean,
    filterLabel: String,
    hasSearch: Boolean
) {
    Surface(
        color = Color(0xFF1C2123),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Color(0xFF262D2F)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(Color(0xFF2E3437)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.DeleteSweep, contentDescription = null, tint = C.t3, modifier = Modifier.size(20.dp))
            }
            Text(
                if (hasTransactions) "No matching transactions" else "No transactions recorded yet",
                color = C.t1,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                when {
                    !hasTransactions ->
                        "New collections and dispatch results will appear here automatically."
                    hasSearch ->
                        "Try a different name, phone number, or switch back to the ${filterLabel.lowercase(Locale.getDefault())} tab."
                    else ->
                        "There are no ${filterLabel.lowercase(Locale.getDefault())} transactions right now."
                },
                color = C.t2,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun TransactionHistoryRow(
    tx: Transaction,
    offers: List<OfferItem>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val offerMode = remember(tx.offerId, offers) {
        offers.firstOrNull { it.id == tx.offerId }?.executionMode
    }
    val executionCopy = remember(
        tx.id,
        tx.status,
        tx.offerId,
        tx.ussdCode,
        tx.ussdTranscript,
        tx.ussdResponse,
        offerMode
    ) {
        transactionExecutionCopy(tx, offerMode)
    }
    val liveExecution = isTransactionActivelyExecuting(tx)
    val classification = transactionHistoryFilterFor(tx, offers)
    val accent = transactionHistoryAccent(classification)
    val avatarColor = transactionAvatarColor(tx)
    val title = tx.clientName.ifBlank {
        tx.description.ifBlank { "Transaction #${tx.id}" }
    }
    val primaryDetail = tx.phoneNumber
        .takeIf { it.isNotBlank() }
        ?.let(::maskTransactionPhone)
        ?: transactionSourceLabel(tx.source)
    val note = when {
        classification == TransactionHistoryFilter.SCHEDULED -> {
            transactionReasonShort(tx).takeIf { it.isNotBlank() }
                ?: "Queued for dispatch tomorrow because the daily sending limit has been reached."
        }
        classification == TransactionHistoryFilter.UNMATCHED -> {
            val received = transactionHistoryCurrency(transactionHistoryAmountValue(tx))
            "Received $received but this transaction is not configured in the Offers section."
        }
        classification == TransactionHistoryFilter.FAILED -> {
            transactionReasonShort(tx).takeIf { it.isNotBlank() }
                ?: tx.description.takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) }
                ?: transactionSourceLabel(tx.source)
        }
        liveExecution -> executionCopy.detailLabel
        else -> {
            if (DailyLimitPolicy.isDailyLimitHold(tx)) {
                transactionReasonShort(tx).takeIf { it.isNotBlank() }
                    ?: "Daily limit reached. Use an alternative number or dispatch tomorrow."
            } else {
                tx.description
                    .takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) }
                    ?: transactionSourceLabel(tx.source)
            }
        }
    }
    val amountPrefix = transactionHistoryAmountPrefix(tx)

    Surface(
        color = Color(0xFF1C2123),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Color(0xFF262D2F)),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(avatarColor.copy(alpha = 0.18f))
                    .border(1.dp, avatarColor.copy(alpha = 0.34f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    transactionAvatarText(tx),
                    color = avatarColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    title,
                    color = C.t1,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 18.sp
                )
                Text(
                    primaryDetail,
                    color = C.t3,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
                TransactionStatusBadge(classification = classification, accent = accent)
                note.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        color = if (classification == TransactionHistoryFilter.UNMATCHED) accent else C.t2,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    amountPrefix + transactionHistoryCurrency(transactionHistoryAmountValue(tx)),
                    color = accent,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    executionCopy.statusLabel,
                    color = accent.copy(alpha = 0.92f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    transactionHistoryTimeLabel(tx),
                    color = C.t3,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun TransactionStatusBadge(
    classification: TransactionHistoryFilter,
    accent: Color
) {
    Surface(
        color = accent.copy(alpha = 0.12f),
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.24f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Text(
                classification.label,
                color = accent,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun TransactionMenuSectionTitle(text: String) {
    Text(
        text = text.uppercase(Locale.getDefault()),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        color = C.t3,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun TransactionMenuDivider() {
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color(0xFF333B3E))
    )
}

private fun transactionHistoryFilterFor(
    tx: Transaction,
    offers: List<OfferItem>
): TransactionHistoryFilter {
    if (DailyLimitPolicy.isDailyLimitHold(tx)) return TransactionHistoryFilter.SCHEDULED
    if (isTransactionUnmatched(tx, offers)) return TransactionHistoryFilter.UNMATCHED
    return when (tx.statusEnum) {
        TransactionStatus.SUCCESS -> TransactionHistoryFilter.COMPLETED
        TransactionStatus.FAILED, TransactionStatus.CANCELLED -> TransactionHistoryFilter.FAILED
        else -> TransactionHistoryFilter.PENDING
    }
}

private fun isTransactionUnmatched(
    tx: Transaction,
    offers: List<OfferItem>
): Boolean {
    if (tx.statusEnum == TransactionStatus.FAILED || tx.statusEnum == TransactionStatus.CANCELLED) return false
    val amount = transactionHistoryAmountValue(tx)
    if (amount <= 0.0 || tx.source == TX_SOURCE_AIRTIME) return false
    return resolveOfferForHistory(tx, offers) == null
}

private fun resolveOfferForHistory(
    tx: Transaction,
    offers: List<OfferItem>
): OfferItem? {
    tx.offerId.takeIf { it >= 0 }?.let { offerId ->
        offers.firstOrNull { it.id == offerId }?.let { return it }
    }
    offers.firstOrNull { it.name.trim().equals(tx.description.trim(), ignoreCase = true) }?.let { return it }
    if (tx.amountValue > 0.0) {
        offers.firstOrNull { it.price == tx.amountValue.toInt() }?.let { return it }
    }
    val normalizedCode = UssdHelper.normalizeUssdCode(tx.ussdCode, tx.phoneNumber)
    return offers.firstOrNull {
        UssdHelper.normalizeUssdCode(it.ussdCode, tx.phoneNumber) == normalizedCode
    }
}

private fun transactionHistoryAccent(classification: TransactionHistoryFilter): Color =
    when (classification) {
        TransactionHistoryFilter.COMPLETED -> C.green
        TransactionHistoryFilter.FAILED -> C.red
        TransactionHistoryFilter.SCHEDULED -> C.cyan
        TransactionHistoryFilter.UNMATCHED -> C.orange
        TransactionHistoryFilter.PENDING -> Color(0xFFF7B731)
        TransactionHistoryFilter.ALL -> C.cyan
    }

private fun transactionMatchesSummaryPeriod(
    tx: Transaction,
    period: TransactionHistorySummaryPeriod
): Boolean {
    val timestamp = transactionHistoryTimestamp(tx)
    if (timestamp <= 0L) return false
    val now = Calendar.getInstance()
    val item = Calendar.getInstance().apply { timeInMillis = timestamp }
    return when (period) {
        TransactionHistorySummaryPeriod.DAILY -> sameDay(item, now)
        TransactionHistorySummaryPeriod.WEEKLY ->
            item.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
                item.get(Calendar.WEEK_OF_YEAR) == now.get(Calendar.WEEK_OF_YEAR)
        TransactionHistorySummaryPeriod.MONTHLY ->
            item.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
                item.get(Calendar.MONTH) == now.get(Calendar.MONTH)
    }
}

private fun transactionAvatarColor(tx: Transaction): Color {
    val palette = listOf(
        Color(0xFF6C63FF),
        Color(0xFF00C9A7),
        Color(0xFF4ECDC4),
        Color(0xFFFD79A8),
        Color(0xFFF7B731)
    )
    return palette[tx.id.absoluteValue % palette.size]
}

private fun transactionAvatarText(tx: Transaction): String {
    val source = tx.clientName
        .ifBlank { tx.description }
        .ifBlank { tx.phoneNumber }
        .ifBlank { "TX" }
    val parts = source
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
    return when {
        parts.size >= 2 -> "${parts[0].first()}${parts[1].first()}".uppercase(Locale.getDefault())
        parts.isNotEmpty() -> parts.first().take(2).uppercase(Locale.getDefault())
        else -> "TX"
    }
}

private fun transactionSourceLabel(source: String): String =
    when (source) {
        TX_SOURCE_AUTOMATED -> "Automated"
        TX_SOURCE_MANUAL -> "Manual"
        TX_SOURCE_SMS_COMMAND -> "SMS Command"
        TX_SOURCE_AIRTIME -> "Airtime"
        else -> "System"
    }

private fun transactionHistoryAmountValue(tx: Transaction): Double =
    tx.amountValue.takeIf { it > 0.0 }
        ?: Regex("""\d+(?:\.\d+)?""").find(tx.amount)?.value?.toDoubleOrNull()
        ?: 0.0

private fun transactionHistoryAmountPrefix(tx: Transaction): String =
    if (tx.source == TX_SOURCE_AIRTIME || tx.amount.trim().startsWith("-")) "-" else "+"

private fun shouldShowInHistory(tx: Transaction): Boolean = tx.source != TX_SOURCE_AIRTIME

private fun historyVisibleTransactions(transactions: List<Transaction>): List<Transaction> =
    transactions
        .filter(::shouldShowInHistory)
        .sortedByDescending { transactionHistoryTimestamp(it) }

private fun transactionHistoryCurrency(amount: Double): String {
    val rounded = amount.toLong().toDouble()
    val body = if (amount == rounded) {
        rounded.toLong().toString()
    } else {
        String.format(Locale.getDefault(), "%.2f", amount)
    }
    return "KES $body"
}

private fun transactionHistoryTimestamp(tx: Transaction): Long {
    if (tx.timestamp > 0L) return tx.timestamp
    return runCatching {
        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).parse(tx.date)?.time ?: 0L
    }.getOrDefault(0L)
}

private fun transactionHistoryDateLabel(tx: Transaction): String {
    val timestamp = transactionHistoryTimestamp(tx)
    if (timestamp <= 0L) return tx.date.substringBefore(" ").ifBlank { "Earlier" }
    val now = Calendar.getInstance()
    val item = Calendar.getInstance().apply { timeInMillis = timestamp }
    val yesterday = (now.clone() as? Calendar ?: Calendar.getInstance().apply { timeInMillis = now.timeInMillis })
        .apply { add(Calendar.DAY_OF_YEAR, -1) }
    return when {
        sameDay(item, now) -> "Today"
        sameDay(item, yesterday) -> "Yesterday · ${SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))}"
        else -> SimpleDateFormat("EEE · MMM d", Locale.getDefault()).format(Date(timestamp))
    }
}

private fun transactionHistoryTimeLabel(tx: Transaction): String {
    val timestamp = transactionHistoryTimestamp(tx)
    if (timestamp <= 0L) return tx.date.ifBlank { "Just now" }
    return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestamp)).uppercase(Locale.getDefault())
}

private fun isTransactionToday(tx: Transaction): Boolean {
    val timestamp = transactionHistoryTimestamp(tx)
    if (timestamp <= 0L) return false
    return sameDay(
        Calendar.getInstance().apply { timeInMillis = timestamp },
        Calendar.getInstance()
    )
}

private fun sameDay(first: Calendar, second: Calendar): Boolean =
    first.get(Calendar.YEAR) == second.get(Calendar.YEAR) &&
        first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR)

private fun maskTransactionPhone(phone: String): String {
    val digits = phone.filter(Char::isDigit)
    if (digits.length < 7) return phone
    return digits.take(4) + "..." + digits.takeLast(2)
}
