package com.bingwa.mobile

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dialpad
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

@Composable
internal fun TransactionDetailDialog(
    tx: Transaction,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onRetry: ((Transaction) -> Unit)? = null
) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val isFailed = tx.statusEnum == TransactionStatus.FAILED || tx.statusEnum == TransactionStatus.CANCELLED
    val isDailyLimitHold = DailyLimitPolicy.isDailyLimitHold(tx)
    val executionCopy = remember(
        tx.id,
        tx.status,
        tx.offerId,
        tx.ussdCode,
        tx.ussdTranscript,
        tx.ussdResponse
    ) {
        transactionExecutionCopy(ctx, tx)
    }
    val liveExecution = isTransactionActivelyExecuting(tx)
    val statusColor = when {
        isDailyLimitHold -> C.cyan
        tx.statusEnum == TransactionStatus.SUCCESS -> C.green
        tx.statusEnum == TransactionStatus.FAILED || tx.statusEnum == TransactionStatus.CANCELLED -> C.red
        else -> C.amber
    }
    val statusLabel = executionCopy.statusLabel

    var retrying by remember { mutableStateOf(false) }
    var altPhone by remember { mutableStateOf("") }
    var altDispatching by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true, dismissOnClickOutside = true)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(C.orange.copy(alpha = 0.18f), Color.Transparent),
                        center = Offset.Unspecified,
                        radius = 720f
                    )
                )
                .padding(horizontal = 16.dp, vertical = 28.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 420.dp),
                shape = RoundedCornerShape(26.dp),
                color = C.card,
                border = BorderStroke(1.dp, C.border.copy(alpha = 0.65f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    TransactionDetailHeader(
                        tx = tx,
                        statusColor = statusColor,
                        statusLabel = statusLabel,
                        onClose = onDismiss
                    )

                    TransactionDetailQuickActions(
                        tx = tx,
                        onCopyPhone = {
                            if (tx.phoneNumber.isBlank()) return@TransactionDetailQuickActions
                            clipboard.setText(AnnotatedString(tx.phoneNumber))
                            Toast.makeText(ctx, "Phone number copied", Toast.LENGTH_SHORT).show()
                        },
                        onCopyUssd = {
                            if (tx.ussdCode.isBlank()) return@TransactionDetailQuickActions
                            clipboard.setText(AnnotatedString(tx.ussdCode))
                            Toast.makeText(ctx, "USSD code copied", Toast.LENGTH_SHORT).show()
                        }
                    )

                    AnimatedVisibility(visible = isFailed && onRetry != null) {
                        Column(modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp)) {
                            Button(
                                onClick = {
                                    if (retrying) return@Button
                                    retrying = true
                                    onRetry?.invoke(tx)
                                },
                                modifier = Modifier.fillMaxWidth().height(54.dp),
                                shape = RoundedCornerShape(18.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = C.red)
                            ) {
                                Icon(Icons.Outlined.Refresh, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    if (retrying) "Retrying…" else "Retry Failed Execution",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }

                    LaunchedEffect(retrying) {
                        if (!retrying) return@LaunchedEffect
                        delay(1400)
                        retrying = false
                    }

                    AnimatedVisibility(visible = isDailyLimitHold) {
                        Column(modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp)) {
                            Text(
                                "Alternative Number",
                                color = C.t3,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp
                            )
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = altPhone,
                                onValueChange = { altPhone = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                placeholder = { Text("0712345678", color = C.t3) },
                                shape = RoundedCornerShape(16.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = Color(0xFF15191B),
                                    unfocusedContainerColor = Color(0xFF15191B),
                                    focusedBorderColor = Color(0xFF333B3E),
                                    unfocusedBorderColor = Color(0xFF333B3E),
                                    focusedTextColor = C.t1,
                                    unfocusedTextColor = C.t1,
                                    cursorColor = C.cyan,
                                    focusedPlaceholderColor = C.t3,
                                    unfocusedPlaceholderColor = C.t3
                                )
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    if (altDispatching) return@Button
                                    val normalized = SmsCommandHandler.normalizePhone(altPhone)
                                    val currentPhone = SmsCommandHandler.normalizePhone(tx.phoneNumber)
                                    if (!normalized.matches(Regex("^0\\d{9}$"))) {
                                        Toast.makeText(ctx, "Enter a valid alternative number (0712345678).", Toast.LENGTH_SHORT).show()
                                        return@Button
                                    }
                                    if (normalized == currentPhone) {
                                        Toast.makeText(ctx, "Alternative number must be different from the original number.", Toast.LENGTH_SHORT).show()
                                        return@Button
                                    }
                                    altDispatching = true
                                    val result = DailyLimitPolicy.replaceDailyLimitWithAlternativeNumber(ctx, tx, normalized)
                                    if (result.success) {
                                        broadcastTransactionUpdated(ctx, tx.id)
                                        Toast.makeText(ctx, result.message, Toast.LENGTH_LONG).show()
                                        onDismiss()
                                    } else {
                                        Toast.makeText(ctx, result.message.ifBlank { "Could not start the alternative dispatch." }, Toast.LENGTH_LONG).show()
                                    }
                                    altDispatching = false
                                },
                                modifier = Modifier.fillMaxWidth().height(54.dp),
                                shape = RoundedCornerShape(18.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = C.cyan)
                            ) {
                                Text(
                                    if (altDispatching) "Starting…" else "Buy Using Alternative Number",
                                    color = Color(0xFF15121B),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }

                    TransactionDetailSection(
                        title = "Transaction",
                        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp)
                    ) {
                        TransactionDetailInfoCard {
                            TransactionDetailInfoRow("Transaction ID", "#${tx.id}", mono = true)
                            TransactionDetailInfoRow("Customer", tx.clientName.ifBlank { "Not captured" })
                            TransactionDetailInfoRow(
                                "Phone Number",
                                tx.phoneNumber.takeIf { it.isNotBlank() }?.let(::maskPhone) ?: "Not captured",
                                mono = true
                            )
                            TransactionDetailInfoRow("Offer Bought", tx.description.ifBlank { "Not captured" })
                            TransactionDetailInfoRow("Amount", tx.amount.ifBlank { "Not captured" }, accent = C.orange)
                        }
                    }

                    TransactionDetailSection(
                        title = "Execution",
                        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp)
                    ) {
                        TransactionDetailInfoCard {
                            TransactionDetailInfoRow("Time of Execution", formatTimestamp(tx.timestamp), mono = true)
                            TransactionDetailInfoRow("Completed", formatCompletedAt(tx), mono = true)
                            TransactionDetailInfoRow("Duration", formatDuration(tx), muted = tx.executionDurationMs <= 0L)
                            TransactionDetailInfoRow("Mode", executionCopy.modeLabel)
                            if (liveExecution) {
                                TransactionDetailInfoRow("Live Status", executionCopy.detailLabel)
                            }
                            TransactionDetailInfoRow(
                                "Source",
                                transactionSource(tx),
                                trailingChip = true
                            )
                        }
                    }

                    TransactionDetailSection(
                        title = "USSD Session",
                        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp)
                    ) {
                        UssdTerminal(
                            ussdCode = tx.ussdCode.ifBlank { "Not captured" },
                            response = tx.ussdResponse.ifBlank { "" },
                            onCopy = {
                                if (tx.ussdCode.isBlank()) return@UssdTerminal
                                clipboard.setText(AnnotatedString(tx.ussdCode))
                                Toast.makeText(ctx, "USSD code copied", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }

                    TransactionDetailSection(
                        title = "Last Response",
                        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp)
                    ) {
                        LastResponsePanel(
                            text = tx.ussdResponse,
                            emptyMessage = if (liveExecution) executionCopy.detailLabel else "No final response captured yet."
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 22.dp, vertical = 18.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (onDelete != null) {
                            Button(
                                onClick = onDelete,
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp)
                            ) {
                                Icon(Icons.Outlined.Delete, contentDescription = null, tint = C.red, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Delete", color = C.red, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        } else {
                            Spacer(Modifier.width(1.dp))
                        }
                        Button(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(999.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = C.t1, contentColor = Color(0xFF15121B)),
                            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp)
                        ) {
                            Text("Close", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TransactionDetailHeader(
    tx: Transaction,
    statusColor: Color,
    statusLabel: String,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 18.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.linearGradient(listOf(C.orange, C.red)))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                avatarInitials(tx),
                color = Color(0xFF1A0C06),
                fontWeight = FontWeight.ExtraBold,
                fontSize = 16.sp
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                tx.clientName.ifBlank { "Transaction Detail" },
                color = C.t1,
                fontSize = 17.sp,
                fontWeight = FontWeight.ExtraBold,
                lineHeight = 22.sp
            )
            Text(
                tx.description.ifBlank { tx.phoneNumber.ifBlank { "Offer details" } },
                color = C.t2,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 18.sp
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onClose, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Outlined.Close, contentDescription = null, tint = C.t3)
            }
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = statusColor.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, statusColor.copy(alpha = 0.32f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Icon(
                        if (statusColor == C.green) Icons.Outlined.CheckCircle else Icons.Outlined.ReportProblem,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        statusLabel.uppercase(Locale.getDefault()),
                        color = statusColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.9.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun TransactionDetailQuickActions(
    tx: Transaction,
    onCopyPhone: () -> Unit,
    onCopyUssd: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 10.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        QuickActionButton(
            label = "Copy Phone",
            icon = Icons.Outlined.ContentCopy,
            enabled = tx.phoneNumber.isNotBlank(),
            onClick = onCopyPhone
        )
        QuickActionButton(
            label = "Copy USSD",
            icon = Icons.Outlined.Dialpad,
            enabled = tx.ussdCode.isNotBlank(),
            onClick = onCopyUssd
        )
    }
}

@Composable
private fun QuickActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val border = C.amber.copy(alpha = 0.32f)
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = C.amber.copy(alpha = 0.14f),
            contentColor = C.amber,
            disabledContainerColor = C.w04,
            disabledContentColor = C.t3
        ),
        border = BorderStroke(1.dp, border.copy(alpha = if (enabled) 1f else 0.5f)),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TransactionDetailSection(
    title: String,
    contentPadding: PaddingValues,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(contentPadding)) {
        Text(
            title.uppercase(Locale.getDefault()),
            color = C.t3,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp
        )
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun TransactionDetailInfoCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = C.surface.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.65f))
    ) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
private fun TransactionDetailInfoRow(
    label: String,
    value: String,
    mono: Boolean = false,
    muted: Boolean = false,
    accent: Color? = null,
    trailingChip: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label.uppercase(Locale.getDefault()),
            color = C.t3,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp
        )
        if (trailingChip) {
            SourceChip(text = value)
        } else {
            Text(
                value,
                color = when {
                    muted -> C.t3
                    accent != null -> accent
                    else -> C.t1
                },
                fontSize = if (mono) 12.sp else 13.sp,
                fontWeight = if (muted) FontWeight.Medium else FontWeight.SemiBold,
                fontFamily = if (mono) FontFamily.Monospace else null,
                textAlign = TextAlign.End,
                lineHeight = 18.sp,
                modifier = Modifier.padding(start = 10.dp)
            )
        }
    }
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(C.border.copy(alpha = 0.30f))
    )
}

@Composable
private fun SourceChip(text: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = C.greenDim,
        border = BorderStroke(1.dp, C.green.copy(alpha = 0.28f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(C.green)
            )
            Text(text, color = C.green, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        }
    }
}

@Composable
private fun UssdTerminal(
    ussdCode: String,
    response: String,
    onCopy: () -> Unit
) {
    val terminalBorder = C.green.copy(alpha = 0.28f)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF0A0E0B),
        border = BorderStroke(1.dp, terminalBorder)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.02f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    repeat(3) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f)))
                    }
                }
                Text(
                    "LIVE DIAL",
                    color = C.green.copy(alpha = 0.65f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.2.sp
                )
                IconButton(onClick = onCopy, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, tint = C.green.copy(alpha = 0.78f), modifier = Modifier.size(16.dp))
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "$ $ussdCode",
                    color = Color(0xFF7EF0A8),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 18.sp
                )
                if (response.isNotBlank()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                BorderStroke(
                                    1.dp,
                                    Brush.linearGradient(listOf(terminalBorder.copy(alpha = 0.42f), terminalBorder.copy(alpha = 0.12f)))
                                ),
                                RoundedCornerShape(14.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 12.dp)
                    ) {
                        Text(
                            response,
                            color = Color(0xFFCDEEDB),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LastResponsePanel(text: String, emptyMessage: String = "No final response captured yet.") {
    val hasResponse = text.isNotBlank()
    val strokeColor = if (hasResponse) C.green.copy(alpha = 0.28f) else C.border.copy(alpha = 0.60f)
    val bg = if (hasResponse) C.greenDim else Color.Transparent
    val iconColor = if (hasResponse) C.green else C.t3
    val messageColor = if (hasResponse) C.t2 else C.t2
    val dashEffect = if (hasResponse) null else PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(bg)
            .drawBehind {
                val stroke = Stroke(width = 2.dp.toPx(), pathEffect = dashEffect, cap = StrokeCap.Round)
                drawRoundRect(
                    color = strokeColor,
                    cornerRadius = CornerRadius(18.dp.toPx(), 18.dp.toPx()),
                    style = stroke
                )
            }
            .padding(horizontal = 14.dp, vertical = 14.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (hasResponse) C.green.copy(alpha = 0.16f) else C.surface.copy(alpha = 0.92f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (hasResponse) Icons.Outlined.CheckCircle else Icons.Outlined.ReportProblem,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(18.dp)
                )
            }
            Text(
                if (hasResponse) text else emptyMessage,
                color = messageColor,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                fontWeight = if (hasResponse) FontWeight.Medium else FontWeight.Normal,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

private fun avatarInitials(tx: Transaction): String {
    val base = tx.clientName.ifBlank { tx.description }.trim()
    val words = base.split(Regex("\\s+")).filter { it.isNotBlank() }
    if (words.isEmpty()) return "TX"
    val first = words.first().firstOrNull()?.uppercaseChar() ?: 'T'
    val second = words.drop(1).firstOrNull()?.firstOrNull()?.uppercaseChar()
        ?: words.first().drop(1).firstOrNull()?.uppercaseChar()
        ?: 'X'
    return "$first$second"
}

private fun maskPhone(phone: String): String {
    val digits = phone.filter(Char::isDigit)
    if (digits.length < 7) return phone
    val prefix = digits.take(min(4, digits.length))
    val suffix = digits.takeLast(3)
    return prefix + "***" + suffix
}

private fun formatTimestamp(ts: Long): String {
    if (ts <= 0L) return "Not recorded"
    return SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.getDefault()).format(Date(ts))
}

private fun formatCompletedAt(tx: Transaction): String {
    if (tx.completedAt > 0L) return formatTimestamp(tx.completedAt)
    return when (tx.statusEnum) {
        TransactionStatus.SUCCESS,
        TransactionStatus.FAILED,
        TransactionStatus.CANCELLED -> "Completed time not recorded"
        else -> "In progress"
    }
}

private fun formatDuration(tx: Transaction): String {
    val durationMs = when {
        tx.executionDurationMs > 0L -> tx.executionDurationMs
        tx.completedAt > 0L && tx.timestamp > 0L -> (tx.completedAt - tx.timestamp).coerceAtLeast(0L)
        else -> 0L
    }
    if (durationMs <= 0L) return "Not recorded"
    return when {
        durationMs < 1_000L -> "${durationMs} ms"
        durationMs < 60_000L -> "${durationMs} ms"
        else -> {
            val totalSeconds = durationMs / 1_000L
            val minutes = totalSeconds / 60L
            val seconds = totalSeconds % 60L
            "${minutes}m ${seconds}s (${durationMs}ms)"
        }
    }
}

private fun transactionSource(tx: Transaction): String = when (tx.source) {
    TX_SOURCE_AUTOMATED -> "Automated"
    TX_SOURCE_MANUAL -> "Manual"
    TX_SOURCE_SMS_COMMAND -> "SMS Command"
    TX_SOURCE_AIRTIME -> "Airtime"
    else -> "Activity"
}
