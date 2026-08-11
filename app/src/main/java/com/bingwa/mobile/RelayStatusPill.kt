package com.bingwa.mobile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun RelayStatusPill(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    LaunchedEffect(ctx) { RelayManager.load(ctx) }
    val cfg by RelayManager.configState.collectAsState()
    if (!cfg.enabled) return

    val hotspotState by RelayManager.hotspotState.collectAsState()
    val (label, color, icon) = when {
        cfg.role == "PRIMARY" && cfg.method == "HOTSPOT" -> when (hotspotState) {
            RelayManager.HotspotLinkState.CONNECTED -> Triple("RELAY CONNECTED", C.green, Icons.Rounded.Wifi)
            RelayManager.HotspotLinkState.CHECKING -> Triple("RELAY CHECKING", C.orange, Icons.Rounded.Wifi)
            RelayManager.HotspotLinkState.DISCONNECTED -> Triple("RELAY DISCONNECTED", C.red, Icons.Rounded.Wifi)
            RelayManager.HotspotLinkState.DISABLED -> Triple("RELAY DISABLED", C.orange, Icons.Rounded.Wifi)
        }
        cfg.role == "PRIMARY" && cfg.method == "SMS" -> Triple("RELAY VIA SMS", C.orange, Icons.Rounded.Sms)
        cfg.role == "RELAY" && cfg.method == "HOTSPOT" -> Triple("RELAY MODE · HOTSPOT", C.cyan, Icons.Rounded.PhoneAndroid)
        cfg.role == "RELAY" && cfg.method == "SMS" -> Triple("RELAY MODE · SMS", C.cyan, Icons.Rounded.PhoneAndroid)
        cfg.role == "RELAY" -> Triple("RELAY MODE", C.cyan, Icons.Rounded.PhoneAndroid)
        else -> Triple("RELAY ENABLED", C.cyan, Icons.Rounded.PhoneAndroid)
    }

    Surface(
        shape = RoundedCornerShape(999.dp),
        color = C.surface.copy(alpha = 0.40f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.34f)),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = color, modifier = Modifier.size(11.dp))
            }
            Text(label, color = color, fontWeight = FontWeight.ExtraBold, fontSize = 9.5.sp, letterSpacing = 0.8.sp)
        }
    }
}
