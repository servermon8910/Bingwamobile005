package com.bingwa.mobile

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

internal object C {
    var bg by mutableStateOf(Color(0xFF0C1017))
    var surface by mutableStateOf(Color(0xFF19161D))
    var card by mutableStateOf(Color(0xFF2B262A))
    var cardHi by mutableStateOf(Color(0xFF343035))
    var border by mutableStateOf(Color(0xFF5B4F54))
    var borderHi by mutableStateOf(Color(0xFF75696E))
    var cyan by mutableStateOf(Color(0xFFF7A600))
    var cyanDim by mutableStateOf(cyan.copy(alpha = 0.12f))
    var cyanGlow by mutableStateOf(cyan.copy(alpha = 0.20f))
    var orange by mutableStateOf(Color(0xFFF7A600))
    var orangeDim by mutableStateOf(orange.copy(alpha = 0.12f))
    var purple by mutableStateOf(Color(0xFFB6BDC9))
    var purpleDim by mutableStateOf(purple.copy(alpha = 0.13f))
    var green by mutableStateOf(Color(0xFF16C784))
    var greenDim by mutableStateOf(green.copy(alpha = 0.10f))
    var greenGlow by mutableStateOf(green.copy(alpha = 0.22f))
    var red by mutableStateOf(Color(0xFFF6465D))
    var redDim by mutableStateOf(red.copy(alpha = 0.10f))
    var amber by mutableStateOf(Color(0xFFF7A600))
    var amberDim by mutableStateOf(amber.copy(alpha = 0.10f))
    var blue by mutableStateOf(Color(0xFFFFD38A))
    var blueDim by mutableStateOf(blue.copy(alpha = 0.10f))
    var t1 by mutableStateOf(Color(0xFFFFFFFF))
    var t2 by mutableStateOf(Color(0xFFD0D5DD))
    var t3 by mutableStateOf(Color(0xFF98A2B3))
    var w12 by mutableStateOf(Color.White.copy(alpha = 0.12f))
    var w08 by mutableStateOf(Color.White.copy(alpha = 0.08f))
    var w04 by mutableStateOf(Color.White.copy(alpha = 0.04f))
}

internal enum class ThemeMode { DARK }

internal enum class ThemeAccent(val label: String) {
    BYBIT("Bybit Yellow")
}

private data class AccentPaletteSpec(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val primaryContainer: Color
)

internal fun themeAccentFromName(value: String?): ThemeAccent =
    ThemeAccent.BYBIT

internal fun themeAccentOptions(): List<ThemeAccent> = ThemeAccent.values().toList()

internal fun themeAccentLabel(accent: ThemeAccent): String = accent.label

private fun accentPaletteSpec(accent: ThemeAccent): AccentPaletteSpec = when (accent) {
    ThemeAccent.BYBIT -> AccentPaletteSpec(
        primary = Color(0xFFF7A600),
        secondary = Color(0xFFB6BDC9),
        tertiary = Color(0xFFF7A600),
        primaryContainer = Color(0xFFFFE1A6)
    )
}

private fun onColorFor(color: Color): Color =
    if (color.luminance() > 0.5f) Color(0xFF0B0E11) else Color(0xFFF7F8FA)

internal fun buildAppColorScheme(accent: ThemeAccent, dark: Boolean): ColorScheme {
    val palette = accentPaletteSpec(accent)
    val background = Color(0xFF0C1017)
    val surface = Color(0xFF19161D)
    val surfaceVariantBase = Color(0xFF312B30)
    val surfaceVariant = lerp(surfaceVariantBase, palette.primary, 0.10f)
    val outline = lerp(Color(0xFF5B4F54), palette.primary, 0.16f)
    val outlineVariant = lerp(Color(0xFF75696E), palette.secondary, 0.12f)

    return darkColorScheme(
        primary = palette.primary,
        onPrimary = onColorFor(palette.primary),
        secondary = palette.secondary,
        onSecondary = onColorFor(palette.secondary),
        tertiary = palette.tertiary,
        onTertiary = onColorFor(palette.tertiary),
        primaryContainer = palette.primaryContainer,
        onPrimaryContainer = onColorFor(palette.primaryContainer),
        background = background,
        surface = surface,
        surfaceVariant = surfaceVariant,
        onBackground = Color(0xFFF8FAFC),
        onSurface = Color(0xFFF8FAFC),
        outline = outline,
        outlineVariant = outlineVariant,
        error = Color(0xFFF6465D)
    )
}

internal object AppTheme {
    var mode by mutableStateOf(ThemeMode.DARK)
    var useDynamicColors by mutableStateOf(false)
    var accent by mutableStateOf(ThemeAccent.BYBIT)

    fun load(context: Context) {
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        mode = ThemeMode.DARK
        useDynamicColors = false
        accent = ThemeAccent.BYBIT
    }
}

internal fun applyVolcanicPaletteFromScheme(s: ColorScheme, dark: Boolean) {
    C.bg = s.background
    C.surface = s.surface
    C.card = lerp(s.surface, s.surfaceVariant, 0.82f)
    C.cardHi = lerp(C.card, s.primary, 0.08f)
    C.border = s.outline
    C.borderHi = s.outlineVariant
    C.cyan = s.primary
    C.cyanDim = s.primary.copy(alpha = 0.12f)
    C.cyanGlow = s.primary.copy(alpha = 0.24f)
    C.purple = Color(0xFFB6BDC9)
    C.purpleDim = C.purple.copy(alpha = 0.13f)
    C.orange = s.primary
    C.orangeDim = s.primary.copy(alpha = 0.12f)
    C.green = Color(0xFF16C784)
    C.greenDim = C.green.copy(alpha = 0.10f)
    C.greenGlow = C.green.copy(alpha = 0.22f)
    C.red = Color(0xFFF6465D)
    C.redDim = C.red.copy(alpha = 0.10f)
    C.amber = s.primary
    C.amberDim = C.amber.copy(alpha = 0.10f)
    C.blue = Color(0xFFFFD38A)
    C.blueDim = C.blue.copy(alpha = 0.10f)
    val base = Color.White
    C.t1 = base
    C.t2 = base.copy(alpha = 0.78f)
    C.t3 = base.copy(alpha = 0.56f)
    C.w12 = base.copy(alpha = 0.12f)
    C.w08 = base.copy(alpha = 0.08f)
    C.w04 = base.copy(alpha = 0.04f)
}

internal fun surfaceGradient(): Brush = Brush.linearGradient(listOf(C.cardHi, C.card, C.surface))

internal fun accentSurfaceGradient(accent: Color): Brush =
    Brush.linearGradient(listOf(accent.copy(alpha = 0.16f), C.card))
