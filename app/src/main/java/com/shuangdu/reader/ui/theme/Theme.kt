package com.shuangdu.reader.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.shuangdu.reader.data.Skin

/** 皮肤色板：一套管住书架、面板、弹窗、阅读页 */
class SkinPalette(
    val skin: Skin,
    val appBg: Color,
    val appBg2: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val text: Color,
    val sub: Color,
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val outline: Color,
    val readBg: Color,
    val readBg2: Color,
    val readText: Color,
    val readSub: Color,
    val readLine: Color
) {
    val isDark: Boolean get() = skin.isDark
}

private val PAPER = SkinPalette(
    skin = Skin.PAPER,
    appBg = Color(0xFFF7F3EA),
    appBg2 = Color(0xFFF2ECDF),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEFEAE0),
    text = Color(0xFF191A17),
    sub = Color(0xFF57534B),
    primary = Color(0xFF2A6459),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE1EEE8),
    onPrimaryContainer = Color(0xFF10332C),
    outline = Color(0xFFD9D2C4),
    readBg = Color(0xFFF6EEDD),
    readBg2 = Color(0xFFF1E7D2),
    readText = Color(0xFF241F19),
    readSub = Color(0xFF6E6252),
    readLine = Color(0x33A08B65)
)

private val INK_GREEN = SkinPalette(
    skin = Skin.INK_GREEN,
    appBg = Color(0xFF1C231A),
    appBg2 = Color(0xFF10140F),
    surface = Color(0xFF1C2219),
    surfaceVariant = Color(0xFF222A1E),
    text = Color(0xFFE6EADF),
    sub = Color(0xFF9BA491),
    primary = Color(0xFF86B06A),
    onPrimary = Color(0xFF10200C),
    primaryContainer = Color(0xFF26321E),
    onPrimaryContainer = Color(0xFFD6E6C8),
    outline = Color(0xFF2B3327),
    readBg = Color(0xFF242C20),
    readBg2 = Color(0xFF12170F),
    readText = Color(0xFFDFE5D3),
    readSub = Color(0xFF8C9681),
    readLine = Color(0x3386B06A)
)

private val DUSK_PINK = SkinPalette(
    skin = Skin.DUSK_PINK,
    appBg = Color(0xFF2A1D21),
    appBg2 = Color(0xFF1D1417),
    surface = Color(0xFF34242A),
    surfaceVariant = Color(0xFF3E2C33),
    text = Color(0xFFF1E1E5),
    sub = Color(0xFFB2969E),
    primary = Color(0xFFD98BA0),
    onPrimary = Color(0xFF2A1016),
    primaryContainer = Color(0xFF3E282D),
    onPrimaryContainer = Color(0xFFF6DBE2),
    outline = Color(0xFF382B2F),
    readBg = Color(0xFF2C1F24),
    readBg2 = Color(0xFF22181C),
    readText = Color(0xFFEEDCE0),
    readSub = Color(0xFFA98A93),
    readLine = Color(0x33D98BA0)
)

private val INK_BLUE = SkinPalette(
    skin = Skin.INK_BLUE,
    appBg = Color(0xFF1A2130),
    appBg2 = Color(0xFF111722),
    surface = Color(0xFF222B3B),
    surfaceVariant = Color(0xFF2A3546),
    text = Color(0xFFDFE6EF),
    sub = Color(0xFF95A2B3),
    primary = Color(0xFF7CA0CF),
    onPrimary = Color(0xFF0C1622),
    primaryContainer = Color(0xFF243248),
    onPrimaryContainer = Color(0xFFD3E1F2),
    outline = Color(0xFF2A3543),
    readBg = Color(0xFF212B3A),
    readBg2 = Color(0xFF1A2230),
    readText = Color(0xFFD9E2EC),
    readSub = Color(0xFF8B98A8),
    readLine = Color(0x337CA0CF)
)

private val BLACK = SkinPalette(
    skin = Skin.BLACK,
    appBg = Color(0xFF000000),
    appBg2 = Color(0xFF000000),
    surface = Color(0xFF0B0B0C),
    surfaceVariant = Color(0xFF161617),
    text = Color(0xFFEAEAEA),
    sub = Color(0xFF9A9A9A),
    primary = Color(0xFF9DB4C4),
    onPrimary = Color(0xFF08141A),
    primaryContainer = Color(0xFF1C2529),
    onPrimaryContainer = Color(0xFFD5E3EA),
    outline = Color(0xFF262626),
    readBg = Color(0xFF000000),
    readBg2 = Color(0xFF000000),
    readText = Color(0xFFD2D2D2),
    readSub = Color(0xFF7E7E7E),
    readLine = Color(0x33FFFFFF)
)

fun paletteOf(skin: Skin): SkinPalette = when (skin) {
    Skin.PAPER -> PAPER
    Skin.INK_GREEN -> INK_GREEN
    Skin.DUSK_PINK -> DUSK_PINK
    Skin.INK_BLUE -> INK_BLUE
    Skin.BLACK -> BLACK
}

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
fun SdTheme(skin: Skin, content: @Composable () -> Unit) {
    val p = paletteOf(skin)
    val scheme = if (p.isDark) {
        darkColorScheme(
            primary = p.primary,
            onPrimary = p.onPrimary,
            primaryContainer = p.primaryContainer,
            onPrimaryContainer = p.onPrimaryContainer,
            inversePrimary = p.primary,
            secondary = p.primary,
            onSecondary = p.onPrimary,
            secondaryContainer = p.primaryContainer,
            onSecondaryContainer = p.onPrimaryContainer,
            tertiary = p.primary,
            onTertiary = p.onPrimary,
            tertiaryContainer = p.primaryContainer,
            onTertiaryContainer = p.onPrimaryContainer,
            background = p.appBg,
            onBackground = p.text,
            surface = p.surface,
            onSurface = p.text,
            surfaceVariant = p.surfaceVariant,
            onSurfaceVariant = p.sub,
            surfaceTint = p.primary,
            surfaceContainerLowest = p.appBg,
            surfaceContainerLow = p.surface,
            surfaceContainer = p.surface,
            surfaceContainerHigh = p.surfaceVariant,
            surfaceContainerHighest = p.surfaceVariant,
            outline = p.outline,
            outlineVariant = p.outline,
            scrim = Color.Black
        )
    } else {
        lightColorScheme(
            primary = p.primary,
            onPrimary = p.onPrimary,
            primaryContainer = p.primaryContainer,
            onPrimaryContainer = p.onPrimaryContainer,
            secondary = Color(0xFF8A6D4B),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFEFE6D5),
            onSecondaryContainer = Color(0xFF3A2E1C),
            tertiary = p.primary,
            onTertiary = p.onPrimary,
            background = p.appBg,
            onBackground = p.text,
            surface = p.surface,
            onSurface = p.text,
            surfaceVariant = p.surfaceVariant,
            onSurfaceVariant = p.sub,
            surfaceTint = p.primary,
            surfaceContainerLowest = p.surface,
            surfaceContainerLow = p.surface,
            surfaceContainer = p.surface,
            surfaceContainerHigh = p.surface,
            surfaceContainerHighest = p.surfaceVariant,
            outline = p.outline,
            outlineVariant = p.outline,
            scrim = Color.Black
        )
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
}
