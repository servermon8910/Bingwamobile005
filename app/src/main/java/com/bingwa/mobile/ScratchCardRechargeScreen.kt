package com.bingwa.mobile

import android.content.Context
import android.net.Uri
import android.telephony.SubscriptionInfo
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.statusBarsPadding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.LinkedHashSet
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.delay

private const val PREFS_SCRATCH_CARD = "scratch_card_prefs"
private const val KEY_RECENT_PINS = "recent_pins"
private const val KEY_RECHARGE_HISTORY = "recharge_history"
private const val KEY_USED_PIN_RECORDS = "used_pin_records"
private const val MAX_RECENT_PINS = 5
private const val FREE_RECHARGE_WINDOW_LIMIT = 10
private const val FREE_RECHARGE_WINDOW_MS = 24 * 60 * 60 * 1000L
private const val USED_PIN_RECORD_RETENTION_MS = 60 * 60 * 1000L
private const val SCRATCH_BALANCE_REFRESH_POLL_MS = 250L
private const val SCRATCH_BATCH_GAP_MS = 650L
private const val SCRATCH_POST_RECHARGE_REFRESH_DELAY_MS = 4_000L
private const val SCRATCH_DEFAULT_SCAN_MESSAGE =
    "Pick one image to scan for 16-digit scratch card PINs."

private val ScratchBg0 = Color(0xFF15181D)
private val ScratchBg1 = Color(0xFF1B1F26)
private val ScratchBg2 = Color(0xFF20252C)
private val ScratchLine = Color(0xFF2C323C)
private val ScratchLineSoft = Color(0xFF252A32)
private val ScratchAmber = Color(0xFFFFB454)
private val ScratchAmberDim = Color(0xFF8A6530)
private val ScratchCyan = Color(0xFF74E6D8)
private val ScratchCyanDim = Color(0xFF3D6B66)
private val ScratchText0 = Color(0xFFEEF1F5)
private val ScratchText1 = Color(0xFF9AA4B2)
private val ScratchText2 = Color(0xFF5F6773)
private val ScratchDanger = Color(0xFFFF6B6B)
private val ScratchSectionGap = 24.dp

private sealed interface ScratchCardRechargeResult {
    data class Completed(val response: String) : ScratchCardRechargeResult
    data class Failed(val message: String) : ScratchCardRechargeResult
}

private data class ScratchRecentPinRecord(
    val pin: String,
    val finalResponse: String,
    val timestamp: Long
)

private data class ScratchUsedPinRecord(
    val pin: String,
    val simSelection: Int,
    val timestamp: Long,
    val finalResponse: String
)

private enum class ScratchQueueVisualState {
    EMPTY,
    FREE,
    PAID,
    RUNNING,
    DONE,
    FAILED
}

private fun scratchCoolAccent(): Color = ScratchCyan

private fun scratchCoolDim(alpha: Float = 0.12f): Color = ScratchCyanDim.copy(alpha = alpha)

private fun scratchPanelColor(): Color = ScratchBg1

private fun scratchPanelHighColor(): Color = ScratchBg2

private fun scratchTickerColor(): Color = Color(0xFF100C08).copy(alpha = 0.96f)

@Composable
fun ScratchCardRechargeScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sims = remember { getAvailableSims(ctx) }
    val defaultRechargeSim = remember { currentUssdSimSelection(ctx) }

    var pin by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    var isScanningImage by remember { mutableStateOf(false) }
    var isRefreshingBalance by remember { mutableStateOf(false) }
    var selectedRechargeSim by remember { mutableIntStateOf(defaultRechargeSim) }
    var recentPins by remember { mutableStateOf(loadRecentPins(ctx)) }
    var recentRechargeCount by remember { mutableIntStateOf(loadRecentRechargeCount(ctx)) }
    var usedPinRecords by remember { mutableStateOf(loadUsedPinRecords(ctx)) }
    var showRecentRecharges by remember { mutableStateOf(false) }
    var detectedPins by remember { mutableStateOf<List<String>>(emptyList()) }
    var finalResponse by remember { mutableStateOf("") }
    var responseMessage by remember { mutableStateOf("No final USSD response captured yet.") }
    var responseIsError by remember { mutableStateOf(false) }
    var balanceDisplay by remember {
        mutableStateOf(BalanceChecker.currentBalanceStr.ifBlank { "KSh --" })
    }
    var lastBalanceCheckedAt by remember {
        mutableLongStateOf(if (BalanceChecker.currentBalanceStr.isBlank()) 0L else System.currentTimeMillis())
    }
    var scanMessage by remember {
        mutableStateOf(SCRATCH_DEFAULT_SCAN_MESSAGE)
    }
    var queueFreeSnapshot by remember { mutableIntStateOf(FREE_RECHARGE_WINDOW_LIMIT) }
    var activeQueuePin by remember { mutableStateOf<String?>(null) }
    var completedQueuePins by remember { mutableStateOf<Set<String>>(emptySet()) }
    var failedQueuePins by remember { mutableStateOf<Set<String>>(emptySet()) }
    var queueResponses by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var showSimMenu by remember { mutableStateOf(false) }

    val selectedSimLabel = remember(selectedRechargeSim, sims) {
        describeUssdSimSelection(selectedRechargeSim, sims)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }
    val queueTargetPins = when {
        detectedPins.isNotEmpty() -> detectedPins.size
        pin.length == 16 -> 1
        else -> 0
    }
    val queuePinsForAction = when {
        detectedPins.isNotEmpty() -> detectedPins
        pin.length == 16 -> listOf(pin)
        else -> emptyList()
    }
    val runNowCount = queueTargetPins
    val freeLeftCount = (FREE_RECHARGE_WINDOW_LIMIT - recentRechargeCount).coerceAtLeast(0)
    val balanceCurrency = splitScratchBalance(balanceDisplay).first
    val balanceAmount = splitScratchBalance(balanceDisplay).second
    val processedCount = completedQueuePins.size + failedQueuePins.size
    val progressPercent = if (queueTargetPins == 0) 0 else ((processedCount * 100f) / queueTargetPins).toInt().coerceIn(0, 100)
    val queueStatusLabel = when {
        isScanningImage -> "Scanning"
        isProcessing -> "Running"
        queueTargetPins > 0 -> "Ready"
        else -> "Idle"
    }
    val queueStatusTint = when {
        isScanningImage -> ScratchCyan
        isProcessing -> ScratchAmber
        queueTargetPins > 0 -> ScratchCyan
        else -> ScratchText2
    }
    val batchSubtitle = when {
        isScanningImage -> "Scanning single image for 16-digit Airtel recharge PINs."
        isProcessing && activeQueuePin != null -> "Running ${processedCount + 1} of $queueTargetPins with a 2-second gap."
        detectedPins.isEmpty() -> "Idle - waiting for a scan to build the queue."
        else -> "Queue ready - ${detectedPins.size} card${if (detectedPins.size == 1) "" else "s"} detected."
    }
    val consoleMessage = when {
        isScanningImage -> "Reading image for 16-digit scratch card PINs..."
        isProcessing && activeQueuePin != null -> "Running *141*PIN# silently for ${formatPinForDisplay(activeQueuePin.orEmpty())} on $selectedSimLabel."
        finalResponse.isNotBlank() -> responseMessage
        else -> scanMessage
    }
    val tickerText = buildString {
        append("*141*")
        append(
            when {
                activeQueuePin != null -> formatPinForDisplay(activeQueuePin.orEmpty())
                pin.length == 16 -> formatPinForDisplay(pin)
                else -> "0000 0000 0000 0000"
            }
        )
        append("#  ->  ")
        append(
            when {
                isScanningImage -> "SCANNING"
                isProcessing -> "BATCH RUNNING"
                queueTargetPins > 0 -> "QUEUE READY"
                else -> "AWAITING SCAN"
            }
        )
        append("  ·  ")
        append(selectedSimLabel.uppercase(Locale.getDefault()))
        append("  ·  ")
        append(if (queueTargetPins == 0) "BATCH IDLE" else "$processedCount OF $queueTargetPins RUN")
        append("  ·  ")
    }
    val savedResponsesByPin = remember(recentPins, usedPinRecords) {
        buildMap {
            recentPins.forEach { put(it.pin, it.finalResponse) }
            usedPinRecords.forEach { put(it.pin, it.finalResponse) }
        }
    }
    fun resetQueueUi(clearPin: Boolean = false) {
        detectedPins = emptyList()
        completedQueuePins = emptySet()
        failedQueuePins = emptySet()
        activeQueuePin = null
        queueResponses = emptyMap()
        queueFreeSnapshot = freeLeftCount
        scanMessage = SCRATCH_DEFAULT_SCAN_MESSAGE
        if (clearPin) pin = ""
        if (!isProcessing) {
            finalResponse = ""
            responseMessage = "No final USSD response captured yet."
            responseIsError = false
        }
    }

    fun applyLiveBalance(display: String, checkedAt: Long = System.currentTimeMillis()) {
        if (display.isBlank()) return
        balanceDisplay = display
        lastBalanceCheckedAt = checkedAt
    }

    fun publishSharedBalance(display: String, simSelection: Int = selectedRechargeSim) {
        if (display.isBlank()) return
        val parsedAmount = BalanceChecker.parseBalanceInt(display).toDouble()
        val currency = splitScratchBalance(display).first
        BalanceChecker.balanceResultListener?.invoke(
            BalanceChecker.BalanceResult(
                display = display,
                rawAmount = parsedAmount,
                currency = currency,
                rawText = display,
                timestamp = System.currentTimeMillis(),
                sourceSimSlot = simSelection,
                success = true,
                selectionOverride = simSelection,
                persistResult = true
            )
        )
    }

    fun syncBalanceFromRechargeResponse(rawResponse: String): Boolean {
        val parsedBalance = BalanceChecker.parseBalanceDisplay(rawResponse)
        if (parsedBalance.isBlank()) return false

        applyLiveBalance(parsedBalance)
        BalanceChecker.persistLastKnownBalance(ctx, parsedBalance)
        val parsedAmount = BalanceChecker.parseBalanceInt(rawResponse)
        if (parsedAmount >= 0) {
            BalanceChecker.currentBalance = parsedAmount
        }
        RelayManager.syncPrimaryAirtimeBalance(ctx, parsedBalance)
        publishSharedBalance(parsedBalance)
        return true
    }

    fun requestBalanceRefresh() {
        if (isRefreshingBalance) return
        if (sims.isEmpty()) {
            Toast.makeText(ctx, "No SIM available for airtime balance check.", Toast.LENGTH_SHORT).show()
            return
        }

        isRefreshingBalance = true
        val balanceSimSelection = selectedRechargeSim
        val balanceSimLabel = selectedSimLabel

        scope.launch {
            when (val result = startScratchBalanceCheck(ctx, balanceSimSelection)) {
                is ScratchCardRechargeResult.Completed -> {
                    applyLiveBalance(result.response)
                    responseMessage = "Airtime balance updated for $balanceSimLabel."
                    responseIsError = false
                }
                is ScratchCardRechargeResult.Failed -> {
                    responseMessage = result.message
                    responseIsError = true
                    Toast.makeText(ctx, result.message, Toast.LENGTH_SHORT).show()
                }
            }
            isRefreshingBalance = false
        }
    }

    fun runRechargeBatch(targetPins: List<String>) {
        val sanitizedPins = targetPins.filter { it.length == 16 }.distinct()
        if (sanitizedPins.isEmpty()) {
            Toast.makeText(ctx, "Please enter a complete 16-digit PIN", Toast.LENGTH_SHORT).show()
            return
        }
        if (isProcessing || isScanningImage) return
        if (sims.isEmpty()) {
            Toast.makeText(ctx, "No SIM available for scratch recharge.", Toast.LENGTH_SHORT).show()
            return
        }
        val rechargeSimForRun = selectedRechargeSim
        val rechargeSimLabelForRun = selectedSimLabel
        queueFreeSnapshot = freeLeftCount
        completedQueuePins = emptySet()
        failedQueuePins = emptySet()
        queueResponses = emptyMap()
        activeQueuePin = null
        finalResponse = ""
        responseMessage = if (sanitizedPins.size > 1) {
            "Preparing ${sanitizedPins.size} cards for silent recharge on $rechargeSimLabelForRun."
        } else {
            "Waiting for final USSD response..."
        }
        responseIsError = false
        scope.launch {
            isProcessing = true
            var successCount = 0
            sanitizedPins.forEachIndexed { index, targetPin ->
                pin = targetPin
                activeQueuePin = targetPin
                responseMessage = if (sanitizedPins.size > 1) {
                    "Running card ${index + 1} of ${sanitizedPins.size} on $rechargeSimLabelForRun."
                } else {
                    "Running recharge on $rechargeSimLabelForRun."
                }
                when (val result = startScratchCardRecharge(ctx, targetPin, rechargeSimForRun)) {
                    is ScratchCardRechargeResult.Completed -> {
                        val normalizedResponse = normalizeScratchResponse(result.response)
                        saveRecentPin(ctx, targetPin, normalizedResponse)
                        saveRechargeTimestamp(ctx)
                        saveUsedPinRecord(ctx, targetPin, rechargeSimForRun, normalizedResponse)
                        recentPins = loadRecentPins(ctx)
                        recentRechargeCount = loadRecentRechargeCount(ctx)
                        usedPinRecords = loadUsedPinRecords(ctx)
                        completedQueuePins = completedQueuePins + targetPin
                        queueResponses = queueResponses + (targetPin to normalizedResponse)
                        finalResponse = normalizedResponse
                        syncBalanceFromRechargeResponse(normalizedResponse)
                        responseMessage = if (sanitizedPins.size > 1) {
                            "Card ${index + 1} of ${sanitizedPins.size} completed on $rechargeSimLabelForRun."
                        } else {
                            "Recharge completed on $rechargeSimLabelForRun."
                        }
                        responseIsError = false
                        successCount += 1
                    }
                    is ScratchCardRechargeResult.Failed -> {
                        failedQueuePins = failedQueuePins + targetPin
                        queueResponses = queueResponses + (targetPin to result.message)
                        responseIsError = true
                        finalResponse = result.message
                        responseMessage = if (sanitizedPins.size > 1) {
                            "Card ${index + 1} of ${sanitizedPins.size} failed."
                        } else {
                            "Recharge failed."
                        }
                    }
                }

                if (index < sanitizedPins.lastIndex) {
                    delay(SCRATCH_BATCH_GAP_MS)
                }
            }
            activeQueuePin = null
            isProcessing = false

            val summary = if (sanitizedPins.size > 1) {
                "Batch finished: $successCount of ${sanitizedPins.size} cards completed on $rechargeSimLabelForRun."
            } else if (successCount == 1) {
                "Recharge completed on $rechargeSimLabelForRun."
            } else {
                responseMessage
            }
            responseMessage = summary
            Toast.makeText(ctx, summary, Toast.LENGTH_SHORT).show()
            if (successCount > 0) {
                delay(SCRATCH_POST_RECHARGE_REFRESH_DELAY_MS)
                requestBalanceRefresh()
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            recentRechargeCount = loadRecentRechargeCount(ctx)
            usedPinRecords = loadUsedPinRecords(ctx)
        }
    }

    LaunchedEffect(Unit) {
        if (BalanceChecker.currentBalanceStr.isBlank()) {
            requestBalanceRefresh()
        } else {
            applyLiveBalance(BalanceChecker.currentBalanceStr, lastBalanceCheckedAt)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            val latestBalance = BalanceChecker.currentBalanceStr
            if (latestBalance.isNotBlank() && latestBalance != balanceDisplay) {
                applyLiveBalance(latestBalance)
            }
            delay(SCRATCH_BALANCE_REFRESH_POLL_MS)
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isScanningImage = true
            resetQueueUi(clearPin = true)
            scanMessage = "Scanning image for scratch-card PIN..."
            val extractedPins = runCatching { scanScratchCardPins(ctx, uri) }
                .getOrElse {
                    emptyList()
                }
            isScanningImage = false

            if (extractedPins.isEmpty()) {
                pin = ""
                scanMessage = "No 16-digit scratch-card PIN was found in that image."
                Toast.makeText(ctx, "No 16-digit PIN found in the selected image.", Toast.LENGTH_LONG).show()
            } else {
                queueFreeSnapshot = freeLeftCount
                detectedPins = extractedPins
                pin = extractedPins.first()
                scanMessage = if (extractedPins.size == 1) {
                    "Detected 1 scratch card PIN. Ready to recharge."
                } else {
                    "Detected ${extractedPins.size} scratch card PINs. Queue ready to run."
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ScratchBg0)
    ) {
        Box(
            modifier = Modifier
                .size(320.dp)
                .align(Alignment.TopStart)
                .padding(top = 24.dp)
                .background(
                    Brush.radialGradient(
                        listOf(ScratchAmber.copy(alpha = 0.12f), Color.Transparent)
                    ),
                    CircleShape
                )
        )
        Box(
            modifier = Modifier
                .size(360.dp)
                .align(Alignment.TopEnd)
                .padding(top = 72.dp)
                .background(
                    Brush.radialGradient(
                        listOf(ScratchCyan.copy(alpha = 0.10f), Color.Transparent)
                    ),
                    CircleShape
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            ScratchLedStrip(simLabel = selectedSimLabel)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                Spacer(Modifier.height(20.dp))

                ScratchScreenHeader(onBack = onBack)

                Spacer(Modifier.height(16.dp))

                ScratchTicker(text = tickerText)

                Spacer(Modifier.height(ScratchSectionGap))

                ScratchBalanceCard(
                    currency = balanceCurrency,
                    amount = balanceAmount,
                    simLabel = selectedSimLabel,
                    checkedLabel = formatScratchCheckedLabel(lastBalanceCheckedAt, isRefreshingBalance),
                    isRefreshing = isRefreshingBalance,
                    onRefresh = ::requestBalanceRefresh
                )

                Spacer(Modifier.height(ScratchSectionGap))

                ScratchBatchReadoutCard(
                    subtitle = batchSubtitle,
                    freeLeftCount = freeLeftCount,
                    runNowCount = runNowCount,
                    processedCount = processedCount,
                    progressPercent = progressPercent
                )

                Spacer(Modifier.height(ScratchSectionGap))

                ScratchSectionLabel(text = "Batch Recharge")

                Spacer(Modifier.height(12.dp))

                ScratchProcessRail()

                Spacer(Modifier.height(ScratchSectionGap))

                ScratchActionsCard(
                    primaryLabel = when {
                        isScanningImage -> "Scanning..."
                        isProcessing -> if (detectedPins.isNotEmpty()) "Running Batch..." else "Running..."
                        queuePinsForAction.isNotEmpty() -> if (detectedPins.isNotEmpty()) "Run Batch" else "Run Recharge"
                        else -> "Pick Image"
                    },
                    selectedSimLabel = selectedSimLabel,
                    isBusy = isProcessing || isScanningImage,
                    onPrimaryAction = {
                        if (queuePinsForAction.isNotEmpty()) {
                            runRechargeBatch(queuePinsForAction)
                        } else {
                            imagePickerLauncher.launch("image/*")
                        }
                    },
                    onChooseSim = { showSimMenu = true },
                    onClear = { if (!isProcessing && !isScanningImage) resetQueueUi(clearPin = true) }
                )

                Box(modifier = Modifier.fillMaxWidth()) {
                    DropdownMenu(
                        expanded = showSimMenu,
                        onDismissRequest = { showSimMenu = false },
                        modifier = Modifier
                            .background(ScratchBg2, RoundedCornerShape(16.dp))
                            .border(1.dp, ScratchLine, RoundedCornerShape(16.dp))
                    ) {
                        DropdownMenuItem(
                            text = { Text("Slot 1 (Default)", color = ScratchText0) },
                            onClick = {
                                selectedRechargeSim = USSD_SIM_SELECTION_SLOT_1
                                showSimMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Slot 2", color = ScratchText0) },
                            onClick = {
                                selectedRechargeSim = USSD_SIM_SELECTION_SLOT_2
                                showSimMenu = false
                            }
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                ScratchConsoleCard(
                    message = consoleMessage,
                    isError = responseIsError && !isProcessing,
                    statusLabel = queueStatusLabel,
                    statusTint = queueStatusTint
                )

                if (isScanningImage || detectedPins.isNotEmpty() || processedCount > 0) {
                    Spacer(Modifier.height(ScratchSectionGap))
                    ScratchQueueSection(
                        isScanningImage = isScanningImage,
                        detectedPins = detectedPins,
                        queueFreeSnapshot = queueFreeSnapshot,
                        selectedPin = pin,
                        activeQueuePin = activeQueuePin,
                        completedQueuePins = completedQueuePins,
                        failedQueuePins = failedQueuePins,
                        responsesByPin = savedResponsesByPin + queueResponses,
                        onPinClick = { pin = it },
                        hasRecentRecharges = usedPinRecords.isNotEmpty(),
                        onShowRecentRecharges = { showRecentRecharges = true }
                    )
                }

                Spacer(Modifier.height(28.dp))
            }
        }
    }

    if (showRecentRecharges) {
        ScratchRecentRechargesDialog(
            records = usedPinRecords,
            sims = sims,
            onDismiss = { showRecentRecharges = false }
        )
    }
}

@Composable
private fun ScratchLedStrip(simLabel: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(
                    color = ScratchLineSoft,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx()
                )
            }
            .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(scratchCoolAccent())
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "LINKED",
            color = ScratchText2,
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.2.sp
        )
        Spacer(Modifier.weight(1f))
        Text(
            simLabel.uppercase(Locale.getDefault()),
            color = ScratchText2,
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ScratchScreenHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            modifier = Modifier.clickable(onClick = onBack),
            color = ScratchBg2,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, ScratchLine)
        ) {
            Box(
                modifier = Modifier.size(38.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = ScratchText1,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Scratch Card Recharge",
                color = ScratchText0,
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Scan one image, detect every 16-digit PIN, then recharge each card one by one.",
                color = ScratchText1,
                fontSize = 12.5.sp,
                lineHeight = 19.sp
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScratchTicker(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = scratchTickerColor(),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, ScratchAmberDim.copy(alpha = 0.42f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            ScratchAmber.copy(alpha = 0.10f),
                            Color.Transparent,
                            ScratchCyan.copy(alpha = 0.06f)
                        )
                    )
                )
                .padding(vertical = 8.dp)
        ) {
            Text(
                text = text,
                color = ScratchAmber,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp,
                maxLines = 1,
                modifier = Modifier
                    .basicMarquee(iterations = Int.MAX_VALUE)
                    .padding(horizontal = 14.dp)
            )
        }
    }
}

@Composable
private fun ScratchBalanceCard(
    currency: String,
    amount: String,
    simLabel: String,
    checkedLabel: String,
    isRefreshing: Boolean,
    onRefresh: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF0E1310),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Color(0xFF263029))
    ) {
        Box(
            modifier = Modifier
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFF0E1310),
                            Color(0xFF101814),
                            Color(0xFF111714)
                        )
                    )
                )
                .padding(horizontal = 22.dp, vertical = 20.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color.Transparent,
                                scratchCoolAccent().copy(alpha = 0.5f),
                                Color.Transparent
                            )
                        ),
                        RoundedCornerShape(999.dp)
                    )
            )
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(scratchCoolAccent())
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Airtime Balance",
                            color = Color(0xFF5C8F83),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.6.sp
                        )
                    }
                    Surface(
                        modifier = Modifier.clickable(enabled = !isRefreshing, onClick = onRefresh),
                        color = scratchCoolDim(0.10f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Color(0xFF263029))
                    ) {
                        Box(
                            modifier = Modifier.size(28.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.Autorenew,
                                contentDescription = "Refresh balance",
                                tint = Color(0xFF5C8F83),
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        currency,
                        color = ScratchCyanDim,
                        fontSize = 16.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        amount,
                        color = scratchCoolAccent(),
                        fontSize = 42.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        simLabel,
                        color = Color(0xFF5C8F83),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        checkedLabel,
                        color = Color(0xFF4C5A54),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun ScratchBatchReadoutCard(
    subtitle: String,
    freeLeftCount: Int,
    runNowCount: Int,
    processedCount: Int,
    progressPercent: Int
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = ScratchBg2,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, ScratchLine)
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.verticalGradient(
                        listOf(
                            ScratchBg2,
                            ScratchBg1,
                            ScratchBg1
                        )
                    )
                )
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Batch status",
                        color = ScratchText0,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        subtitle,
                        color = ScratchText1,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
                Spacer(Modifier.width(12.dp))
                ScratchSonarIcon()
            }

            Spacer(Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ScratchReadoutStat(
                    label = "Free left",
                    value = String.format(Locale.getDefault(), "%02d", freeLeftCount),
                    tint = scratchCoolAccent(),
                    modifier = Modifier.weight(1f)
                )
                ScratchReadoutStat(
                    label = "Run now",
                    value = String.format(Locale.getDefault(), "%02d", runNowCount),
                    tint = C.orange,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(16.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = C.surface.copy(alpha = 0.78f),
                shape = RoundedCornerShape(999.dp),
                border = BorderStroke(1.dp, ScratchLineSoft)
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth((progressPercent.coerceIn(0, 100) / 100f))
                            .height(6.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(scratchCoolAccent().copy(alpha = 0.35f), scratchCoolAccent())
                                )
                            )
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    when {
                        runNowCount == 0 -> "No cards queued yet"
                        processedCount == 0 -> "$runNowCount of 10 slots queued"
                        else -> "$processedCount of $runNowCount cards run"
                    },
                    color = ScratchText2,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    "$progressPercent%",
                    color = ScratchText2,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun ScratchReadoutStat(
    label: String,
    value: String,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = ScratchBg0,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, ScratchLineSoft)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label.uppercase(Locale.getDefault()),
                color = ScratchText2,
                fontSize = 9.5.sp,
                letterSpacing = 1.sp
            )
            Text(
                value,
                color = tint,
                fontSize = 20.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.ExtraBold
            )
        }
    }
}

@Composable
private fun ScratchSonarIcon() {
    Box(
        modifier = Modifier.size(46.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.size(46.dp),
            color = ScratchBg0,
            shape = CircleShape,
            border = BorderStroke(1.dp, ScratchLine)
        ) {}
        Surface(
            modifier = Modifier.size(38.dp),
            color = Color.Transparent,
            shape = CircleShape,
            border = BorderStroke(1.dp, ScratchCyanDim.copy(alpha = 0.45f))
        ) {}
        Surface(
            modifier = Modifier.size(30.dp),
            color = Color.Transparent,
            shape = CircleShape,
            border = BorderStroke(1.dp, ScratchCyanDim.copy(alpha = 0.28f))
        ) {}
        Icon(
            Icons.Rounded.Search,
            contentDescription = null,
            tint = scratchCoolAccent(),
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun ScratchSectionLabel(
    text: String,
    trailingContent: (@Composable RowScope.() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text.uppercase(Locale.getDefault()),
                color = ScratchAmber,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.8.sp
            )
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(ScratchLine, Color.Transparent)
                        )
                    )
            )
        }
        if (trailingContent != null) {
            Spacer(Modifier.width(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                content = trailingContent
            )
        }
    }
}

@Composable
private fun ScratchProcessRail() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = ScratchBg1,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, ScratchLine)
    ) {
        Column {
            ScratchRailStep(
                icon = Icons.Rounded.Search,
                title = "Scan a single image",
                body = "Pick one photo from your phone. The app extracts every 16-digit scratch PIN it can find.",
                active = true
            )
            ScratchRailStep(
                icon = Icons.Rounded.Bolt,
                title = "Recharge automatically",
                body = "The app recharges each card in turn, capturing the result before moving to the next one."
            )
            ScratchRailStep(
                icon = Icons.Rounded.Shield,
                title = "Free, then paid",
                body = "The first 10 cards are free in each 24-hour window. Every extra block of up to 10 costs 30 tokens."
            )
            ScratchRailStep(
                symbol = "!",
                title = "Before you start",
                body = "Make sure the selected SIM has enough time on network to complete the whole batch without interruption.",
                warn = true,
                last = true
            )
        }
    }
}

@Composable
private fun ScratchRailStep(
    title: String,
    body: String,
    icon: ImageVector? = null,
    symbol: String? = null,
    active: Boolean = false,
    warn: Boolean = false,
    last: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                color = when {
                    warn -> ScratchDanger.copy(alpha = 0.08f)
                    active -> ScratchAmber.copy(alpha = 0.08f)
                    else -> scratchCoolDim(0.08f)
                },
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(
                    1.dp,
                    when {
                        warn -> ScratchDanger.copy(alpha = 0.65f)
                        active -> ScratchAmber.copy(alpha = 0.65f)
                        else -> scratchCoolAccent().copy(alpha = 0.32f)
                    }
                )
            ) {
                Box(
                    modifier = Modifier.size(30.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (icon != null) {
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = when {
                                warn -> ScratchDanger
                                active -> ScratchAmber
                                else -> scratchCoolAccent()
                            },
                            modifier = Modifier.size(15.dp)
                        )
                    } else {
                        Text(
                            symbol.orEmpty(),
                            color = when {
                                warn -> ScratchDanger
                                active -> ScratchAmber
                                else -> ScratchText1
                            },
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            if (!last) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(38.dp)
                        .background(ScratchLineSoft)
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = ScratchText0, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(body, color = ScratchText1, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun ScratchActionsCard(
    primaryLabel: String,
    selectedSimLabel: String,
    isBusy: Boolean,
    onPrimaryAction: () -> Unit,
    onChooseSim: () -> Unit,
    onClear: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = onPrimaryAction,
            enabled = !isBusy,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = ScratchAmber,
                contentColor = Color(0xFF241505),
                disabledContainerColor = ScratchAmber.copy(alpha = 0.35f),
                disabledContentColor = Color(0xFF241505).copy(alpha = 0.6f)
            )
        ) {
            Icon(
                if (primaryLabel.contains("Run", ignoreCase = true)) Icons.Rounded.Bolt else Icons.Rounded.Search,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(primaryLabel, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }

        OutlinedButton(
            onClick = onChooseSim,
            enabled = !isBusy,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, ScratchLine),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = ScratchBg1,
                contentColor = ScratchText0,
                disabledContainerColor = ScratchBg2,
                disabledContentColor = ScratchText2
            )
        ) {
            Icon(Icons.Rounded.SimCard, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "Choose SIM · ${if (selectedSimLabel.length > 14) selectedSimLabel.take(14) + "..." else selectedSimLabel}",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        TextButton(
            onClick = onClear,
            enabled = !isBusy,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Icon(Icons.Rounded.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                "Clear scan",
                color = ScratchText2,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun ScratchConsoleCard(
    message: String,
    isError: Boolean,
    statusLabel: String,
    statusTint: Color
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = ScratchBg1,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, ScratchLineSoft)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 5.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (isError) ScratchDanger else statusTint)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                message,
                color = ScratchText1,
                fontSize = 11.5.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun ScratchQueueSection(
    isScanningImage: Boolean,
    detectedPins: List<String>,
    queueFreeSnapshot: Int,
    selectedPin: String,
    activeQueuePin: String?,
    completedQueuePins: Set<String>,
    failedQueuePins: Set<String>,
    responsesByPin: Map<String, String>,
    onPinClick: (String) -> Unit,
    hasRecentRecharges: Boolean,
    onShowRecentRecharges: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScratchSectionLabel(
            text = "Card Queue",
            trailingContent = {
                Text(
                    if (isScanningImage) "Scanning" else "${detectedPins.size}",
                    color = ScratchAmber,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    " of 10 detected",
                    color = ScratchText2,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        )

        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ScratchLegendItem(label = "Free", tint = scratchCoolAccent())
            ScratchLegendItem(label = "Paid overflow", tint = ScratchAmber)
            ScratchLegendItem(label = "Empty", tint = ScratchText2.copy(alpha = 0.6f))
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = ScratchBg1,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, ScratchLine)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                val totalRows = maxOf(10, detectedPins.size)
                for (index in 0 until totalRows) {
                    val queuePin = detectedPins.getOrNull(index)
                    val state = when {
                        isScanningImage -> ScratchQueueVisualState.RUNNING
                        queuePin == null -> ScratchQueueVisualState.EMPTY
                        queuePin in failedQueuePins -> ScratchQueueVisualState.FAILED
                        queuePin == activeQueuePin -> ScratchQueueVisualState.RUNNING
                        queuePin in completedQueuePins -> ScratchQueueVisualState.DONE
                        index < queueFreeSnapshot -> ScratchQueueVisualState.FREE
                        else -> ScratchQueueVisualState.PAID
                    }
                    ScratchQueueStub(
                        index = index + 1,
                        pin = queuePin,
                        state = state,
                        selected = queuePin != null && queuePin == selectedPin,
                        response = queuePin?.let { responsesByPin[it].orEmpty() }.orEmpty(),
                        showDivider = index != totalRows - 1,
                        onClick = { queuePin?.let(onPinClick) }
                    )
                }
            }
        }

        if (hasRecentRecharges) {
            TextButton(
                onClick = onShowRecentRecharges,
                modifier = Modifier.align(Alignment.End)
            ) {
                Icon(Icons.Rounded.History, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Recent recharges", color = ScratchText2, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ScratchLegendItem(label: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(tint)
        )
        Spacer(Modifier.width(6.dp))
        Text(label, color = ScratchText2, fontSize = 10.sp)
    }
}

@Composable
private fun ScratchQueueStub(
    index: Int,
    pin: String?,
    state: ScratchQueueVisualState,
    selected: Boolean,
    response: String,
    showDivider: Boolean,
    onClick: () -> Unit
) {
    val tint = when (state) {
        ScratchQueueVisualState.EMPTY -> ScratchText2
        ScratchQueueVisualState.FREE, ScratchQueueVisualState.DONE -> scratchCoolAccent()
        ScratchQueueVisualState.PAID, ScratchQueueVisualState.RUNNING -> ScratchAmber
        ScratchQueueVisualState.FAILED -> ScratchDanger
    }
    val label = when (state) {
        ScratchQueueVisualState.EMPTY -> "EMPTY"
        ScratchQueueVisualState.FREE -> "FREE"
        ScratchQueueVisualState.PAID -> "PAID"
        ScratchQueueVisualState.RUNNING -> "RUNNING"
        ScratchQueueVisualState.DONE -> "DONE"
        ScratchQueueVisualState.FAILED -> "FAILED"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = pin != null, onClick = onClick)
            .drawBehind {
                if (showDivider) {
                    drawLine(
                        color = ScratchLine,
                        start = Offset(0f, size.height),
                        end = Offset(size.width, size.height),
                        strokeWidth = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx()))
                    )
                }
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                String.format(Locale.getDefault(), "#%02d", index),
                color = ScratchText2,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.width(24.dp)
            )
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(if (pin == null) ScratchBg0 else tint.copy(alpha = 0.10f))
                    .border(1.dp, tint.copy(alpha = if (pin == null) 0.20f else 0.44f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (state == ScratchQueueVisualState.DONE) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(12.dp)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(tint.copy(alpha = if (pin == null) 0.45f else 1f))
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = pin?.let(::formatPinForDisplay) ?: "•••• •••• •••• ••••",
                color = if (pin == null) ScratchText2 else tint,
                fontSize = 12.5.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(8.dp))
            ScratchQueueStatusPill(text = label, tint = tint)
        }
    }
}

@Composable
private fun ScratchQueueStatusPill(text: String, tint: Color) {
    Surface(
        color = ScratchBg0,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, tint.copy(alpha = if (tint == ScratchText2) 0.18f else 0.34f))
    ) {
        Text(
            text = text,
            color = tint,
            fontSize = 8.5.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            maxLines = 1
        )
    }
}

@Composable
private fun ScratchRecentRechargesDialog(
    records: List<ScratchUsedPinRecord>,
    sims: List<SubscriptionInfo>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Recent recharges") },
        text = {
            if (records.isEmpty()) {
                Text("No recent recharges in the last hour.")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    itemsIndexed(records) { index, record ->
                        val simLabel = describeUssdSimSelection(record.simSelection, sims)
                        UsedPinRecordItem(
                            index = index + 1,
                            record = record,
                            simLabel = simLabel,
                            onClick = {}
                        )
                        if (index != records.lastIndex) {
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}


@Composable
private fun UsedPinRecordItem(
    index: Int,
    record: ScratchUsedPinRecord,
    simLabel: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = C.surface.copy(alpha = 0.58f),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.34f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                ScratchCardLabel(index = index, tint = C.orange)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = formatPinForDisplay(record.pin),
                    color = C.t1,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "${simLabel.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }}  ·  ${formatUsedPinAgeLabel(record.timestamp)}",
                    color = C.t3,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(10.dp))
                ScratchResponsePreview(
                    response = record.finalResponse,
                    tint = C.orange,
                    emptyLabel = "No final USSD response saved for this card yet."
                )
            }
            Spacer(Modifier.width(12.dp))
            ScratchPill(text = "Reuse", tint = C.orange)
        }
    }
}

@Composable
private fun ScratchMetricHeading(text: String, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(accent)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            color = C.t2,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp
        )
    }
}

@Composable
private fun RecentPinItem(
    index: Int,
    record: ScratchRecentPinRecord,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = C.surface.copy(alpha = 0.58f),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.34f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                ScratchCardLabel(index = index, tint = C.cyan)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = formatPinForDisplay(record.pin),
                    color = C.t1,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "${formatRecentPinAgeLabel(record.timestamp)}  ·  Tap to move this PIN back into the recharge field.",
                    color = C.t3,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(10.dp))
                ScratchResponsePreview(
                    response = record.finalResponse,
                    tint = C.cyan,
                    emptyLabel = "No final USSD response saved for this card yet."
                )
            }
            Spacer(Modifier.width(12.dp))
            ScratchPill(text = "Use", tint = C.cyan)
        }
    }
}

@Composable
private fun ScratchHeroStat(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = C.surface.copy(alpha = 0.62f),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.20f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp)) {
            Text(
                label.uppercase(Locale.getDefault()),
                color = C.t3,
                fontSize = 11.sp,
                letterSpacing = 1.2.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                value,
                color = C.t1,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ScratchPill(
    text: String,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = tint.copy(alpha = if (tint == ScratchText2) 0.08f else 0.12f),
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, tint.copy(alpha = if (tint == ScratchText2) 0.18f else 0.34f))
    ) {
        Text(
            text = text,
            color = tint,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ScratchCardLabel(index: Int, tint: Color) {
    Surface(
        color = tint.copy(alpha = 0.12f),
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.36f))
    ) {
        Text(
            text = "Scratch card $index",
            color = tint,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun ScratchResponsePreview(
    response: String,
    tint: Color,
    emptyLabel: String
) {
    val normalizedResponse = response.ifBlank { emptyLabel }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = tint.copy(alpha = 0.09f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.18f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = "Final USSD response",
                color = tint,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = normalizedResponse,
                color = C.t2,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ScratchCardHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            color = C.surface.copy(alpha = 0.62f),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, C.border.copy(alpha = 0.30f))
        ) {
            Box(
                modifier = Modifier.size(38.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = C.t2, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, color = C.t1, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = C.t3, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun ScratchEmptyState(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = C.surface.copy(alpha = 0.42f),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.24f))
    ) {
        Text(
            message,
            color = C.t3,
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
        )
    }
}

@Composable
private fun ScratchDetectedPinItem(
    index: Int,
    pin: String,
    finalResponse: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) C.greenDim.copy(alpha = 0.28f) else C.surface.copy(alpha = 0.58f),
        border = BorderStroke(
            1.dp,
            if (selected) C.green.copy(alpha = 0.48f) else C.border.copy(alpha = 0.34f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                ScratchCardLabel(index = index, tint = if (selected) C.green else C.blue)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = formatPinForDisplay(pin),
                    color = C.t1,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (selected) "Currently selected for recharge." else "Tap to use this OCR result.",
                    color = if (selected) C.green else C.t3,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(10.dp))
                ScratchResponsePreview(
                    response = finalResponse,
                    tint = if (selected) C.green else C.blue,
                    emptyLabel = "No final USSD response saved for this card yet."
                )
            }
            Spacer(Modifier.width(12.dp))
            ScratchPill(
                text = if (selected) "Selected" else "Use",
                tint = if (selected) C.green else C.cyan
            )
        }
    }
}

private fun formatPinForDisplay(pin: String): String {
    if (pin.length != 16) return pin
    return pin.chunked(4).joinToString(" ")
}

private fun loadRecentPins(context: Context, now: Long = System.currentTimeMillis()): List<ScratchRecentPinRecord> {
    val prefs = context.getSharedPreferences(PREFS_SCRATCH_CARD, Context.MODE_PRIVATE)
    val stored = prefs.getString(KEY_RECENT_PINS, "").orEmpty()
    val cleaned = when {
        stored.isBlank() -> emptyList()
        "|" !in stored && ";" !in stored -> stored.split(",")
            .mapIndexedNotNull { index, rawPin ->
                val pin = rawPin.takeIf { it.length == 16 } ?: return@mapIndexedNotNull null
                ScratchRecentPinRecord(
                    pin = pin,
                    finalResponse = "",
                    timestamp = now - index
                )
            }
        else -> stored.split(";")
            .mapNotNull { raw ->
                val parts = raw.split("|")
                if (parts.size < 2) return@mapNotNull null
                val pin = parts[0].takeIf { it.length == 16 } ?: return@mapNotNull null
                val response = Uri.decode(parts[1]).orEmpty()
                val timestamp = parts.getOrNull(2)?.toLongOrNull() ?: now
                ScratchRecentPinRecord(
                    pin = pin,
                    finalResponse = response,
                    timestamp = timestamp
                )
            }
    }
        .distinctBy { it.pin }
        .sortedByDescending { it.timestamp }
        .take(MAX_RECENT_PINS)
    prefs.edit().putString(KEY_RECENT_PINS, encodeRecentPins(cleaned)).apply()
    return cleaned
}

private fun saveRecentPin(
    context: Context,
    pin: String,
    finalResponse: String,
    timestamp: Long = System.currentTimeMillis()
) {
    if (pin.length != 16) return
    val prefs = context.getSharedPreferences(PREFS_SCRATCH_CARD, Context.MODE_PRIVATE)
    val currentPins = loadRecentPins(context, timestamp)
        .filterNot { it.pin == pin }
        .toMutableList()
    currentPins.add(
        0,
        ScratchRecentPinRecord(
            pin = pin,
            finalResponse = normalizeScratchResponse(finalResponse),
            timestamp = timestamp
        )
    )
    prefs.edit().putString(KEY_RECENT_PINS, encodeRecentPins(currentPins.take(MAX_RECENT_PINS))).apply()
}

private fun loadRecentRechargeCount(context: Context, now: Long = System.currentTimeMillis()): Int {
    val prefs = context.getSharedPreferences(PREFS_SCRATCH_CARD, Context.MODE_PRIVATE)
    val cleaned = prefs.getString(KEY_RECHARGE_HISTORY, "").orEmpty()
        .split(",")
        .mapNotNull { it.toLongOrNull() }
        .filter { now - it in 0..FREE_RECHARGE_WINDOW_MS }
    prefs.edit().putString(KEY_RECHARGE_HISTORY, cleaned.joinToString(",")).apply()
    return cleaned.size
}

private fun saveRechargeTimestamp(context: Context, timestamp: Long = System.currentTimeMillis()) {
    val prefs = context.getSharedPreferences(PREFS_SCRATCH_CARD, Context.MODE_PRIVATE)
    val values = prefs.getString(KEY_RECHARGE_HISTORY, "").orEmpty()
        .split(",")
        .mapNotNull { it.toLongOrNull() }
        .filter { timestamp - it in 0..FREE_RECHARGE_WINDOW_MS }
        .toMutableList()
    values += timestamp
    prefs.edit().putString(KEY_RECHARGE_HISTORY, values.joinToString(",")).apply()
}

private fun loadUsedPinRecords(context: Context, now: Long = System.currentTimeMillis()): List<ScratchUsedPinRecord> {
    val prefs = context.getSharedPreferences(PREFS_SCRATCH_CARD, Context.MODE_PRIVATE)
    val cleaned = prefs.getString(KEY_USED_PIN_RECORDS, "").orEmpty()
        .split(";")
        .mapNotNull { raw ->
            val parts = raw.split("|")
            if (parts.size !in 3..4) return@mapNotNull null
            val pin = parts[0].takeIf { it.length == 16 } ?: return@mapNotNull null
            val simSelection = parts[1].toIntOrNull() ?: return@mapNotNull null
            val timestamp = parts[2].toLongOrNull() ?: return@mapNotNull null
            if (now - timestamp !in 0..USED_PIN_RECORD_RETENTION_MS) return@mapNotNull null
            ScratchUsedPinRecord(
                pin = pin,
                simSelection = simSelection,
                timestamp = timestamp,
                finalResponse = Uri.decode(parts.getOrNull(3)).orEmpty()
            )
        }
        .sortedByDescending { it.timestamp }
    prefs.edit().putString(KEY_USED_PIN_RECORDS, encodeUsedPinRecords(cleaned)).apply()
    return cleaned
}

private fun saveUsedPinRecord(
    context: Context,
    pin: String,
    simSelection: Int,
    finalResponse: String,
    timestamp: Long = System.currentTimeMillis()
) {
    val current = loadUsedPinRecords(context, timestamp)
        .filterNot { it.pin == pin }
        .toMutableList()
    current.add(
        0,
        ScratchUsedPinRecord(
            pin = pin,
            simSelection = simSelection,
            timestamp = timestamp,
            finalResponse = normalizeScratchResponse(finalResponse)
        )
    )
    val prefs = context.getSharedPreferences(PREFS_SCRATCH_CARD, Context.MODE_PRIVATE)
    prefs.edit().putString(KEY_USED_PIN_RECORDS, encodeUsedPinRecords(current)).apply()
}

private fun clearUsedPinRecords(context: Context) {
    context.getSharedPreferences(PREFS_SCRATCH_CARD, Context.MODE_PRIVATE)
        .edit()
        .remove(KEY_USED_PIN_RECORDS)
        .apply()
}

private fun splitScratchBalance(value: String): Pair<String, String> {
    val normalized = value.trim().ifBlank { "KSh --" }
    val parts = normalized.split(Regex("\\s+"), limit = 2)
    return if (parts.size == 2 && parts[0].any { it.isLetter() }) {
        parts[0].uppercase(Locale.getDefault()) to parts[1]
    } else {
        "KSH" to normalized
    }
}

private fun formatScratchCheckedLabel(timestamp: Long, isRefreshing: Boolean): String {
    if (isRefreshing) return "Checking now"
    if (timestamp <= 0L) return "Tap refresh to check"
    val minutes = ((System.currentTimeMillis() - timestamp).coerceAtLeast(0L)) / 60_000L
    return when {
        minutes < 1L -> "Checked just now"
        minutes == 1L -> "Checked 1 min ago"
        minutes < 60L -> "Checked ${minutes} min ago"
        else -> "Checked ${minutes / 60L}h ago"
    }
}

private fun formatUsedPinAgeLabel(timestamp: Long): String {
    val deltaMinutes = ((System.currentTimeMillis() - timestamp).coerceAtLeast(0L)) / 60_000L
    return when {
        deltaMinutes < 1L -> "Used just now"
        deltaMinutes == 1L -> "Used 1 min ago"
        deltaMinutes < 60L -> "Used ${deltaMinutes} min ago"
        else -> "Used ${deltaMinutes / 60L}h ago"
    }
}

private fun formatRecentPinAgeLabel(timestamp: Long): String {
    val deltaMinutes = ((System.currentTimeMillis() - timestamp).coerceAtLeast(0L)) / 60_000L
    return when {
        deltaMinutes < 1L -> "Saved just now"
        deltaMinutes == 1L -> "Saved 1 min ago"
        deltaMinutes < 60L -> "Saved ${deltaMinutes} min ago"
        else -> "Saved ${deltaMinutes / 60L}h ago"
    }
}

private fun encodeRecentPins(records: List<ScratchRecentPinRecord>): String =
    records.joinToString(";") { "${it.pin}|${Uri.encode(it.finalResponse)}|${it.timestamp}" }

private fun encodeUsedPinRecords(records: List<ScratchUsedPinRecord>): String =
    records.joinToString(";") { "${it.pin}|${it.simSelection}|${it.timestamp}|${Uri.encode(it.finalResponse)}" }

private fun normalizeScratchResponse(response: String): String =
    response.trim().ifBlank { "USSD completed but returned an empty response." }

private suspend fun scanScratchCardPins(context: Context, imageUri: Uri): List<String> {
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    return try {
        val image = InputImage.fromFilePath(context, imageUri)
        val text = recognizer.awaitRecognition(image)
        extractScratchPins(text)
    } finally {
        recognizer.close()
    }
}

private suspend fun TextRecognizer.awaitRecognition(image: InputImage): String =
    suspendCancellableCoroutine { cont ->
        process(image)
            .addOnSuccessListener { result ->
                if (cont.isActive) cont.resumeWith(Result.success(result.text))
            }
            .addOnFailureListener { error ->
                if (cont.isActive) cont.resumeWith(Result.failure(error))
            }
    }

private fun extractScratchPins(recognizedText: String): List<String> {
    if (recognizedText.isBlank()) return emptyList()

    val results = LinkedHashSet<String>()
    Regex("""(?<![A-Za-z0-9])(?:[0-9OoQqDdIiLlSsBbZzGg][\s-]*){16}(?![A-Za-z0-9])""")
        .findAll(recognizedText)
        .forEach { match ->
            val normalized = buildString {
                match.value.forEach { ch ->
                    when {
                        ch.isWhitespace() || ch == '-' -> Unit
                        else -> normalizeOcrDigit(ch)?.let(::append)
                    }
                }
            }
            if (normalized.length == 16) {
                results += normalized
            }
        }

    if (results.isNotEmpty()) return results.toList()

    val flattened = buildString {
        recognizedText.forEach { ch ->
            normalizeOcrDigit(ch)?.let(::append)
        }
    }
    if (flattened.length < 16) return emptyList()

    for (index in 0..flattened.length - 16) {
        results += flattened.substring(index, index + 16)
    }
    return results.toList()
}

private fun normalizeOcrDigit(ch: Char): Char? = when (ch) {
    in '0'..'9' -> ch
    'O', 'o', 'Q', 'q', 'D' -> '0'
    'I', 'l', 'L' -> '1'
    'S', 's' -> '5'
    'B' -> '8'
    'Z', 'z' -> '2'
    'G', 'g' -> '6'
    else -> null
}

private suspend fun startScratchCardRecharge(
    context: Context,
    pin: String,
    simSelection: Int
): ScratchCardRechargeResult {
    val ussdCode = "*141*$pin#"
    return try {
        suspendCancellableCoroutine { cont ->
            val executed = UssdHelper.dialUssd(
                context = context,
                ussdCode = ussdCode,
                silentOnly = true,
                subIdOverride = simSelection,
                onSuccess = { response ->
                    if (cont.isActive) {
                        cont.resumeWith(Result.success(ScratchCardRechargeResult.Completed(response.trim())))
                    }
                },
                onFailure = { error ->
                    if (cont.isActive) {
                        cont.resumeWith(Result.success(ScratchCardRechargeResult.Failed(error)))
                    }
                }
            )

            if (!executed && cont.isActive) {
                cont.resumeWith(
                    Result.success(
                        ScratchCardRechargeResult.Failed("Unable to start silent scratch-card recharge on this phone.")
                    )
                )
            }
        }
    } catch (_: Exception) {
        ScratchCardRechargeResult.Failed("Unable to start recharge on this phone.")
    }
}

private suspend fun startScratchBalanceCheck(
    context: Context,
    simSelection: Int
): ScratchCardRechargeResult {
    return try {
        suspendCancellableCoroutine { cont ->
            val executed = UssdHelper.dialUssd(
                context = context,
                ussdCode = BalanceChecker.resolveBalanceUssdCode(context, simSelection),
                silentOnly = true,
                subIdOverride = simSelection,
                onSuccess = { response ->
                    val display = BalanceChecker.parseBalanceDisplay(response)
                    if (display.isBlank()) {
                        if (cont.isActive) {
                            cont.resumeWith(
                                Result.success(
                                    ScratchCardRechargeResult.Failed("Balance check completed, but no airtime balance was found in the USSD response.")
                                )
                            )
                        }
                        return@dialUssd
                    }

                    BalanceChecker.persistLastKnownBalance(context, display)
                    val parsedAmount = BalanceChecker.parseBalanceInt(response)
                    if (parsedAmount >= 0) {
                        BalanceChecker.currentBalance = parsedAmount
                    }
                    RelayManager.syncPrimaryAirtimeBalance(context, display)
                    BalanceChecker.balanceResultListener?.invoke(
                        BalanceChecker.BalanceResult(
                            display = display,
                            rawAmount = parsedAmount.toDouble(),
                            currency = splitScratchBalance(display).first,
                            rawText = response,
                            timestamp = System.currentTimeMillis(),
                            sourceSimSlot = simSelection,
                            success = true,
                            selectionOverride = simSelection,
                            persistResult = true
                        )
                    )

                    if (cont.isActive) {
                        cont.resumeWith(Result.success(ScratchCardRechargeResult.Completed(display)))
                    }
                },
                onFailure = { error ->
                    if (cont.isActive) {
                        cont.resumeWith(
                            Result.success(
                                ScratchCardRechargeResult.Failed(
                                    error.ifBlank { "Unable to complete airtime balance check on this phone." }
                                )
                            )
                        )
                    }
                }
            )

            if (!executed && cont.isActive) {
                cont.resumeWith(
                    Result.success(
                        ScratchCardRechargeResult.Failed("Unable to start airtime balance check on this phone.")
                    )
                )
            }
        }
    } catch (_: Exception) {
        ScratchCardRechargeResult.Failed("Unable to start airtime balance check on this phone.")
    }
}
