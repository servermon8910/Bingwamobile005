package com.bingwa.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Status badge for transaction cards showing transaction state
 */
@Composable
fun TransactionStatusBadge(status: String, modifier: Modifier = Modifier) {
    val (backgroundColor, textColor, displayText) = when (status.lowercase()) {
        "success" -> Triple(Color(0xFF4CAF50), Color.White, "✓ Completed")
        "failed" -> Triple(Color(0xFFE53935), Color.White, "✗ Failed")
        "pending" -> Triple(Color(0xFFFFC107), Color(0xFF333333), "⏳ Pending")
        "processing" -> Triple(Color(0xFF2196F3), Color.White, "⚙ Processing")
        "undermaintenance" -> Triple(Color(0xFFFF9800), Color.White, "🔧 Maintenance")
        "retrying" -> Triple(Color(0xFF9C27B0), Color.White, "🔄 Retrying")
        "cancelled" -> Triple(Color(0xFF757575), Color.White, "⊘ Cancelled")
        else -> Triple(Color(0xFF9E9E9E), Color.White, status.uppercase())
    }

    Box(
        modifier = modifier
            .background(backgroundColor, RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = displayText,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Get status color for indicators
 */
fun getStatusColor(status: String): Color = when (status.lowercase()) {
    "success" -> Color(0xFF4CAF50)
    "failed" -> Color(0xFFE53935)
    "pending" -> Color(0xFFFFC107)
    "processing" -> Color(0xFF2196F3)
    "undermaintenance" -> Color(0xFFFF9800)
    "retrying" -> Color(0xFF9C27B0)
    "cancelled" -> Color(0xFF757575)
    else -> Color(0xFF9E9E9E)
}
