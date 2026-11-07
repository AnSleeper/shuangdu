package com.shuangdu.reader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.shuangdu.reader.data.ReadTheme

val Ink = Color(0xFF1B1D1E)
val Teal = Color(0xFF2E6F62)
val TealLight = Color(0xFF4C9A88)
val Paper = Color(0xFFF7F1E6)

private val LightScheme = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3F0EB),
    onPrimaryContainer = Color(0xFF10332C),
    secondary = Color(0xFF8A6D4B),
    onSecondary = Color.White,
    background = Color(0xFFFAF8F4),
    onBackground = Ink,
    surface = Color(0xFFFFFFFF),
    onSurface = Ink,
    surfaceVariant = Color(0xFFF0ECE4),
    onSurfaceVariant = Color(0xFF5B5B58),
    outline = Color(0xFFD8D2C7)
)

private val DarkScheme = darkColorScheme(
    primary = TealLight,
    onPrimary = Color(0xFF06211C),
    primaryContainer = Color(0xFF1D3B35),
    onPrimaryContainer = Color(0xFFC7E6DD),
    secondary = Color(0xFFD3B995),
    background = Color(0xFF101213),
    onBackground = Color(0xFFE9E6E1),
    surface = Color(0xFF171A1B),
    onSurface = Color(0xFFE9E6E1),
    surfaceVariant = Color(0xFF232728),
    onSurfaceVariant = Color(0xFFB0B4B3),
    outline = Color(0xFF33393A)
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 13.5.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp)
)

@Composable
fun SdTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = AppTypography,
        content = content
    )
}

/** 阅读页配色 */
class ReadPalette(
    val bg: Color,
    val text: Color,
    val sub: Color,
    val isDark: Boolean
)

fun paletteOf(theme: ReadTheme): ReadPalette = when (theme) {
    ReadTheme.WHITE -> ReadPalette(Color(0xFFFDFDFB), Color(0xFF232323), Color(0xFF9A9A96), false)
    ReadTheme.PAPER -> ReadPalette(Color(0xFFF6EEDD), Color(0xFF3B3229), Color(0xFFA79880), false)
    ReadTheme.GREEN -> ReadPalette(Color(0xFFD6E6D2), Color(0xFF2C3A2C), Color(0xFF7E9180), false)
    ReadTheme.DARK -> ReadPalette(Color(0xFF111213), Color(0xFF9E9E9C), Color(0xFF5A5C5E), true)
}
