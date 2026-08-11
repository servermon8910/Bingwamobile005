package com.bingwa.mobile

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.ui.graphics.vector.ImageVector

internal sealed class Screen(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val iconSel: ImageVector
) {
    data object Home : Screen("home", "Home", Icons.Outlined.Home, Icons.Filled.Home)
    data object Manual : Screen("manual", "Manual", Icons.Outlined.Terminal, Icons.Filled.Terminal)
    data object Tokens : Screen("tokens", "Tokens", Icons.Rounded.Layers, Icons.Rounded.Layers)
    data object Contacts : Screen("contacts", "Contacts", Icons.Outlined.Contacts, Icons.Filled.Contacts)
    data object Blacklist : Screen("blacklist", "Blacklist", Icons.Outlined.Block, Icons.Filled.Block)
    data object Settings : Screen("settings", "Settings", Icons.Outlined.Settings, Icons.Filled.Settings)
}

internal val NAV_ITEMS = listOf(Screen.Home, Screen.Manual, Screen.Tokens, Screen.Settings)
