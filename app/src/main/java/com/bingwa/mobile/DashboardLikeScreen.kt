package com.bingwa.mobile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun DashboardLikeScreen(
    airBal: String,
    tokenBal: Int,
    recentTransactions: List<Transaction>,
    running: Boolean,
    isRefreshing: Boolean,
    unlimitedLabel: String?,
    unlimitedRemaining: String?,
    onRefresh: () -> Unit
) {
    val (airtimeCurrency, airtimeAmount) = splitAirtimeBalance(airBal)

    Column(
        Modifier.background(C.bg)
            .verticalScroll(rememberScrollState())
            .padding(UiDimens.ScreenPadding)
    ) {
        DashboardHeader(
            running = running,
            automatedCount = recentTransactions.size,
            tokenBal = tokenBal,
            unlimitedLabel = unlimitedLabel
        )

        Spacer(Modifier.height(UiDimens.SpacingXl))
        BalanceOverviewCard(
            airtimeCurrency = airtimeCurrency,
            airtimeAmount = airtimeAmount,
            tokenBal = tokenBal,
            unlimitedLabel = unlimitedLabel,
            unlimitedRemaining = unlimitedRemaining,
            isRefreshing = isRefreshing,
            onRefresh = onRefresh
        )

        if (!unlimitedLabel.isNullOrBlank()) {
            Spacer(Modifier.height(UiDimens.SpacingLg))
            HighlightCard(
                title = unlimitedLabel,
                detail = unlimitedRemaining ?: "Unlimited plan active"
            )
        }

        Spacer(Modifier.height(UiDimens.CardPadding))
        RecentActivitySection(recentTransactions = recentTransactions)
        Spacer(Modifier.height(UiDimens.SpacingMd))
    }
}

@Composable
private fun DashboardHeader(running: Boolean, automatedCount: Int, tokenBal: Int, unlimitedLabel: String?) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppBrandMark()
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Bingwa Mobile", color = C.t1, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(6.dp))
                Text("USSD Automation Platform", color = C.t2, fontSize = 15.sp)
            }
            NotificationButton()
        }

        Spacer(Modifier.height(18.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusChip(
                text = if (running) "AUTOMATION LIVE" else "AUTOMATION IDLE",
                healthy = running,
                modifier = Modifier.weight(1f)
            )
            HeaderCountChip(text = "$automatedCount automated")
            HeaderCountChip(text = unlimitedLabel ?: "$tokenBal tokens")
        }
        Spacer(Modifier.height(10.dp))
        RelayStatusPill()
    }
}

@Composable
private fun AppBrandMark() {
    Surface(
        color = C.cardHi.copy(alpha = 0.92f),
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, C.orange.copy(alpha = 0.35f)),
        modifier = Modifier.size(92.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        listOf(
                            C.orange.copy(alpha = 0.24f),
                            C.cardHi.copy(alpha = 0.96f),
                            C.surface.copy(alpha = 0.96f)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.Bolt,
                null,
                tint = C.orange,
                modifier = Modifier.size(44.dp)
            )
        }
    }
}

@Composable
private fun NotificationButton() {
    Surface(
        color = C.surface.copy(alpha = 0.88f),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.9f)),
        modifier = Modifier.size(84.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Outlined.NotificationsNone,
                null,
                tint = C.t1,
                modifier = Modifier.size(34.dp)
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 18.dp, end = 18.dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(C.orange)
            )
        }
    }
}

@Composable
private fun StatusChip(text: String, healthy: Boolean, modifier: Modifier = Modifier) {
    val chipColor = if (healthy) C.green else C.orange
    val chipFill = if (healthy) C.greenDim else C.orangeDim
    Surface(
        color = chipFill,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, chipColor.copy(alpha = 0.35f)),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(chipColor))
            Spacer(Modifier.width(10.dp))
            Text(text, color = chipColor, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
    }
}

@Composable
private fun HeaderCountChip(text: String) {
    Surface(
        color = C.cardHi.copy(alpha = 0.76f),
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, C.orange.copy(alpha = 0.15f))
    ) {
        Text(
            text = text,
            color = C.blue,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp)
        )
    }
}

@Composable
private fun BalanceOverviewCard(
    airtimeCurrency: String,
    airtimeAmount: String,
    tokenBal: Int,
    unlimitedLabel: String?,
    unlimitedRemaining: String?,
    isRefreshing: Boolean,
    onRefresh: () -> Unit
) {
    val airtimeValueFontSize = balanceValueFontSize(airtimeAmount, 56.sp, 48.sp, 40.sp, 34.sp)
    val tokenValue = unlimitedLabel ?: tokenBal.toString()
    val tokenValueFontSize = balanceValueFontSize(tokenValue, 42.sp, 36.sp, 30.sp, 26.sp)
    Surface(
        color = C.cardHi.copy(alpha = 0.97f),
        shape = RoundedCornerShape(34.dp),
        border = BorderStroke(1.dp, C.borderHi.copy(alpha = 0.95f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color(0xFF4A3C2C).copy(alpha = 0.72f),
                            C.cardHi.copy(alpha = 0.98f),
                            Color(0xFF101625).copy(alpha = 0.96f)
                        )
                    )
                )
                .padding(horizontal = 18.dp, vertical = 20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MetricHeading(text = "AIRTIME BALANCE", leadingDot = true)
                MetricHeading(text = if (unlimitedLabel != null) "UNLIMITED" else "TOKENS", trailingDot = true)
            }

            Spacer(Modifier.height(12.dp))
            Box(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .width(1.dp)
                        .height(118.dp)
                        .background(C.w08)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 50.dp)
                    ) {
                        Text(airtimeCurrency, color = C.t1, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                        Text(
                            airtimeAmount,
                            color = C.t1,
                            fontSize = airtimeValueFontSize,
                            fontWeight = FontWeight.ExtraBold,
                            lineHeight = airtimeValueFontSize * 0.96f,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip
                        )
                        Spacer(Modifier.height(UiDimens.SpacingSm))
                        Text(
                            if (isRefreshing) "Checking balance" else "Tap to refresh",
                            color = C.t3,
                            fontSize = 13.sp,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 50.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            tokenValue,
                            color = if (unlimitedLabel != null) C.green else C.t1,
                            fontSize = tokenValueFontSize,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip
                        )
                        Text(
                            unlimitedRemaining ?: if (unlimitedLabel != null) "Unlimited active" else "tokens",
                            color = C.t2,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                OutlinedButton(
                    onClick = onRefresh,
                    enabled = !isRefreshing,
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, C.border),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = C.surface,
                        contentColor = C.t1
                    ),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(74.dp)
                ) {
                    Icon(Icons.Outlined.Autorenew, null, tint = C.t2, modifier = Modifier.size(24.dp))
                }
            }

            Spacer(Modifier.height(16.dp))
            BalanceAccentWave()
        }
    }
}

@Composable
private fun HighlightCard(title: String, detail: String) {
    Surface(
        color = C.cardHi.copy(alpha = 0.94f),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.9f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(UiDimens.CardPadding)) {
            Text("Unlimited Plan", color = C.t3, fontSize = 12.sp)
            Spacer(Modifier.height(UiDimens.SpacingSm))
            Text(title, color = C.t1, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(UiDimens.SpacingXs))
            Text(detail, color = C.t2, fontSize = 12.sp)
        }
    }
}

@Composable
private fun RecentActivitySection(recentTransactions: List<Transaction>) {
    var selectedTx by remember { mutableStateOf<Transaction?>(null) }
    var dayKey by remember { mutableIntStateOf(currentDayKey()) }
    LaunchedEffect(dayKey) {
        delay(millisUntilNextMidnight() + 250L)
        dayKey = currentDayKey()
    }
    val todayTransactions = remember(recentTransactions, dayKey) {
        recentTransactions
            .asSequence()
            .filter { transactionDayKey(it) == dayKey }
            .sortedByDescending { transactionTimestamp(it) }
            .toList()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .width(8.dp)
                        .height(44.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(C.orange, C.orange.copy(alpha = 0.18f))
                            )
                        )
                )
                Spacer(Modifier.width(18.dp))
                Text("Recent Activity", color = C.t1, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            }

            HeaderCountChip(
                text = "${todayTransactions.size} automated"
            )
        }

        Spacer(Modifier.height(20.dp))
        if (todayTransactions.isEmpty()) {
            RecentActivityShowcase()
        } else {
            Surface(
                color = C.cardHi.copy(alpha = 0.9f),
                shape = RoundedCornerShape(30.dp),
                border = BorderStroke(1.dp, C.border.copy(alpha = 0.9f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(18.dp)) {
                    todayTransactions.forEachIndexed { index, tx ->
                        if (index > 0) {
                            Spacer(Modifier.height(12.dp))
                        }
                        ActivityRow(tx = tx, onClick = { selectedTx = tx })
                    }
                }
            }
        }
    }

    selectedTx?.let { tx ->
        TransactionDetailDialog(
            tx = tx,
            onDismiss = { selectedTx = null }
        )
    }
}

@Composable
private fun ActivityInsightChip(label: String, value: String, tint: Color, modifier: Modifier = Modifier) {
    Surface(
        color = C.surface.copy(alpha = 0.82f),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.7f)),
        modifier = modifier
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(label, color = C.t3, fontSize = 11.sp, lineHeight = 14.sp)
            Text(value, color = tint, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun RecentActivityShowcase() {
    Surface(
        color = C.cardHi.copy(alpha = 0.92f),
        shape = RoundedCornerShape(32.dp),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.9f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(270.dp)
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color(0xFF4B3A2B).copy(alpha = 0.45f),
                            Color(0xFF2B4331).copy(alpha = 0.75f),
                            Color(0xFF19212F).copy(alpha = 0.92f)
                        )
                    )
                )
                .padding(22.dp)
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(212.dp)
                    .clip(RoundedCornerShape(42.dp))
                    .background(C.green.copy(alpha = 0.10f))
            )
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(126.dp)
                    .clip(RoundedCornerShape(30.dp))
                    .background(C.orange)
                    .border(1.dp, C.orange.copy(alpha = 0.45f), RoundedCornerShape(30.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.Bolt, null, tint = C.bg, modifier = Modifier.size(54.dp))
            }
            Canvas(modifier = Modifier.fillMaxSize()) {
                val dotColors = listOf(C.green.copy(alpha = 0.7f), C.orange.copy(alpha = 0.6f), C.blue.copy(alpha = 0.55f))
                val offsets = listOf(
                    Offset(size.width * 0.22f, size.height * 0.28f),
                    Offset(size.width * 0.78f, size.height * 0.31f),
                    Offset(size.width * 0.30f, size.height * 0.64f),
                    Offset(size.width * 0.70f, size.height * 0.67f),
                    Offset(size.width * 0.50f, size.height * 0.14f)
                )
                offsets.forEachIndexed { index, offset ->
                    drawCircle(
                        color = dotColors[index % dotColors.size],
                        radius = if (index == 4) 7f else 5f,
                        center = offset
                    )
                }
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Automated activity appears here", color = C.t1, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Show customer, bundle, amount, run time, and failure reason so it complements full history in Settings.",
                    color = C.t2,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
private fun ActivityRow(tx: Transaction, onClick: () -> Unit) {
    val liveExecution = isTransactionActivelyExecuting(tx)
    val title = tx.clientName.ifBlank {
        tx.description.ifBlank { "Transaction" }
    }
    val phoneLabel = tx.phoneNumber
        .takeIf { it.isNotBlank() }
        ?.let(::maskActivityPhone)
        ?: "Number not captured"
    val amountLabel = formatActivityAmount(tx.amount)
    val timeLabel = formatActivityTime(tx)
    val relativeLabel = formatActivityRelativeTime(tx)
    val statusLabel = activityStatusLabel(tx)
    val statusColor = when (tx.statusEnum) {
        TransactionStatus.SUCCESS -> C.green
        TransactionStatus.FAILED, TransactionStatus.CANCELLED -> C.red
        else -> C.orange
    }
    val anim = rememberInfiniteTransition(label = "activity_row_processing")
    val liveDotAlpha by anim.animateFloat(
        initialValue = 0.38f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "activity_row_processing_dot"
    )
    val liveSweep by anim.animateFloat(
        initialValue = -0.30f,
        targetValue = 1.10f,
        animationSpec = infiniteRepeatable(tween(1500)),
        label = "activity_row_processing_sweep"
    )
    Surface(
        color = C.surface.copy(alpha = 0.95f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            1.dp,
            if (liveExecution) statusColor.copy(alpha = 0.28f) else C.border.copy(alpha = 0.72f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .drawBehind {
                if (!liveExecution) return@drawBehind
                val beamWidth = size.width * 0.34f
                val startX = (size.width * liveSweep) - beamWidth
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            statusColor.copy(alpha = 0.16f),
                            Color.Transparent
                        ),
                        startX = startX,
                        endX = startX + beamWidth
                    ),
                    cornerRadius = CornerRadius(16.dp.toPx(), 16.dp.toPx())
                )
            }
            .clickable(onClick = onClick)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(10.dp)
                    .clip(CircleShape)
                    .background(statusColor.copy(alpha = if (liveExecution) liveDotAlpha else 1f))
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = C.t1,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    phoneLabel,
                    color = C.t3,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        timeLabel,
                        color = C.t3,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                    if (relativeLabel.isNotBlank()) {
                        Text(
                            "•",
                            color = C.t3.copy(alpha = 0.7f),
                            fontSize = 11.sp
                        )
                        Text(
                            relativeLabel,
                            color = statusColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    amountLabel,
                    color = C.t1,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                ActivityStatusPill(
                    label = statusLabel,
                    accent = statusColor
                )
            }
        }
    }
}

@Composable
private fun ActivityStatusPill(label: String, accent: Color) {
    Surface(
        color = accent.copy(alpha = 0.12f),
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.24f))
    ) {
        Text(
            text = label,
            color = accent,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun ActivityProcessingDots(accent: Color) {
    val anim = rememberInfiniteTransition(label = "activity_processing_dots")
    val dot1 by anim.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "activity_processing_dot_1"
    )
    val dot2 by anim.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600, delayMillis = 160), RepeatMode.Reverse),
        label = "activity_processing_dot_2"
    )
    val dot3 by anim.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600, delayMillis = 320), RepeatMode.Reverse),
        label = "activity_processing_dot_3"
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        listOf(dot1, dot2, dot3).forEach { alpha ->
            Box(
                Modifier.size(5.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = alpha))
            )
        }
    }
}

@Composable
private fun ActivityProcessingPill(accent: Color, label: String) {
    val anim = rememberInfiniteTransition(label = "activity_processing_pill")
    val sweep by anim.animateFloat(
        initialValue = -0.35f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(1300)),
        label = "activity_processing_pill_sweep"
    )
    val phase by anim.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900)),
        label = "activity_processing_pill_phase"
    )
    Surface(
        color = accent.copy(alpha = 0.10f),
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.22f)),
        modifier = Modifier.drawBehind {
            val beamWidth = size.width * 0.42f
            val startX = (size.width * sweep) - beamWidth
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    listOf(
                        Color.Transparent,
                        accent.copy(alpha = 0.16f),
                        Color.Transparent
                    ),
                    startX = startX,
                    endX = startX + beamWidth
                ),
                cornerRadius = CornerRadius(size.height / 2f, size.height / 2f)
            )
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ActivityProcessingDots(accent)
            Text(
                "$label${animatedProcessingSuffix(phase)}",
                color = accent,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

private fun animatedProcessingSuffix(phase: Float): String {
    val step = (phase * 3f).toInt().coerceIn(0, 2)
    return ".".repeat(step + 1)
}

private fun maskActivityPhone(phone: String): String {
    val digits = phone.filter(Char::isDigit)
    if (digits.length < 7) return phone
    return digits.take(4) + "..." + digits.takeLast(2)
}

private fun formatActivityAmount(amount: String): String {
    val normalized = amount.trim().removePrefix("+").removePrefix("-").trim()
    if (normalized.isBlank()) return "KSh -"
    return when {
        normalized.startsWith("ksh", ignoreCase = true) -> "KSh " + normalized.substring(3).trim()
        normalized.startsWith("kes", ignoreCase = true) -> "KSh " + normalized.substring(3).trim()
        normalized.firstOrNull()?.isDigit() == true -> "KSh $normalized"
        else -> normalized
    }
}

private fun formatActivityTime(tx: Transaction): String {
    val timestamp = transactionTimestamp(tx)
    if (timestamp <= 0L) return tx.date.ifBlank { "--:--" }
    return android.text.format.DateFormat.format("h:mm a", timestamp).toString().uppercase()
}

private fun formatActivityRelativeTime(tx: Transaction): String {
    val timestamp = transactionTimestamp(tx)
    if (timestamp <= 0L) return ""
    val delta = (System.currentTimeMillis() - timestamp).coerceAtLeast(0L)
    val minutes = delta / 60_000L
    val hours = delta / 3_600_000L
    return when {
        minutes < 1L -> "Just now"
        minutes < 60L -> "${minutes}m ago"
        hours < 24L -> "${hours}h ago"
        else -> ""
    }
}

private fun activityStatusLabel(tx: Transaction): String {
    val rawStatus = tx.status.trim()
    return when {
        rawStatus.equals(TransactionStatus.SUCCESS.value, ignoreCase = true) -> "Completed"
        rawStatus.equals(TransactionStatus.FAILED.value, ignoreCase = true) -> "Failed"
        rawStatus.equals(TransactionStatus.CANCELLED.value, ignoreCase = true) -> "Cancelled"
        rawStatus.equals(TransactionStatus.RETRYING.value, ignoreCase = true) -> "Retrying"
        rawStatus.equals(TransactionStatus.PROCESSING.value, ignoreCase = true) -> "Processing"
        rawStatus.equals(TransactionStatus.PENDING.value, ignoreCase = true) -> "Pending"
        rawStatus.equals("UnderMaintenance", ignoreCase = true) -> "Maintenance"
        rawStatus.isNotBlank() -> rawStatus
        else -> transactionExecutionCopy(tx).statusLabel
    }
}

private fun formatActivityDuration(durationMs: Long): String {
    val seconds = (durationMs / 1000L).coerceAtLeast(1L)
    return when {
        seconds < 60L -> "${seconds}s"
        seconds < 3600L -> "${seconds / 60L}m"
        else -> "${seconds / 3600L}h"
    }
}

@Composable
private fun MetricHeading(text: String, leadingDot: Boolean = false, trailingDot: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (leadingDot) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(C.blue)
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text = text,
            color = C.t2,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.4.sp
        )
        if (trailingDot) {
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(C.orange)
            )
        }
    }
}

@Composable
private fun BalanceAccentWave() {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
    ) {
        val path = Path().apply {
            moveTo(0f, size.height * 0.55f)
            cubicTo(
                size.width * 0.18f,
                size.height * 0.18f,
                size.width * 0.27f,
                size.height * 1.02f,
                size.width * 0.42f,
                size.height * 0.60f
            )
            cubicTo(
                size.width * 0.60f,
                size.height * 0.22f,
                size.width * 0.75f,
                size.height * 0.92f,
                size.width,
                size.height * 0.12f
            )
        }
        drawPath(
            path = path,
            brush = Brush.horizontalGradient(
                listOf(
                    C.orange.copy(alpha = 0.10f),
                    C.orange.copy(alpha = 0.45f),
                    C.green.copy(alpha = 0.35f)
                )
            ),
            style = Stroke(width = 11f)
        )
    }
}

private fun splitAirtimeBalance(value: String): Pair<String, String> {
    val normalized = value.trim().ifBlank { "KSh --" }
    val parts = normalized.split(Regex("\\s+"), limit = 2)
    return if (parts.size == 2 && parts[0].any { it.isLetter() }) {
        parts[0].replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } to parts[1]
    } else {
        "KSh" to normalized
    }
}
