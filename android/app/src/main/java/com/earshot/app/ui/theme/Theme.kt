@file:OptIn(ExperimentalTextApi::class)

package com.earshot.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.earshot.app.R

private val Amber = Color(0xFFFFB74D)

/** Classic: the look before phase 5c, unchanged. */
private val ClassicColors = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF1E1400),
    primaryContainer = Color(0xFF4A3300),
    onPrimaryContainer = Color(0xFFFFDDB3),
    secondary = Color(0xFF9EC9FF),
    onSecondary = Color(0xFF00213F),
    background = Color(0xFF101114),
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF101114),
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF24262B),
    onSurfaceVariant = Color(0xFFA9A9B2),
    surfaceContainerLowest = Color(0xFF0B0C0E),
    surfaceContainerLow = Color(0xFF15161A),
    surfaceContainer = Color(0xFF191A1F),
    surfaceContainerHigh = Color(0xFF202127),
    surfaceContainerHighest = Color(0xFF2A2B31),
    outline = Color(0xFF45464F),
    outlineVariant = Color(0xFF2E2F35),
)

/** Glow: the same palette as the web player's Glow theme (web/src/glow.css). */
val GlowAmber = Color(0xFFFFB547)
val GlowOnAmber = Color(0xFF1B1405)
val GlowBackground = Color(0xFF0F0E14)

private val GlowColors = darkColorScheme(
    primary = GlowAmber,
    onPrimary = GlowOnAmber,
    primaryContainer = Color(0xFF4A3410),
    onPrimaryContainer = Color(0xFFFFDDB0),
    secondary = Color(0xFFC6A3FF),
    onSecondary = Color(0xFF240B4D),
    background = GlowBackground,
    onBackground = Color(0xFFEFEEF5),
    surface = GlowBackground,
    onSurface = Color(0xFFEFEEF5),
    surfaceVariant = Color(0xFF1F1C28),
    onSurfaceVariant = Color(0xFFA3A0B6),
    surfaceContainerLowest = Color(0xFF0A090E),
    surfaceContainerLow = Color(0xFF15131C),
    surfaceContainer = Color(0xFF1A1822),
    surfaceContainerHigh = Color(0xFF1F1C28),
    surfaceContainerHighest = Color(0xFF272431),
    outline = Color(0xFF4A4656),
    outlineVariant = Color(0xFF2C2938),
)

/** Sora (SIL Open Font License, see android/third_party/Sora-OFL.txt), a variable font, for titles in Glow. */
val Sora = FontFamily(
    Font(R.font.sora, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.sora, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.sora, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

private fun glowTypography(): Typography {
    val t = Typography()
    return t.copy(
        headlineLarge = t.headlineLarge.copy(fontFamily = Sora, fontWeight = FontWeight.Bold),
        headlineMedium = t.headlineMedium.copy(fontFamily = Sora, fontWeight = FontWeight.Bold),
        headlineSmall = t.headlineSmall.copy(fontFamily = Sora, fontWeight = FontWeight.Bold),
        titleLarge = t.titleLarge.copy(fontFamily = Sora, fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontFamily = Sora, fontWeight = FontWeight.SemiBold),
    )
}

/** True when the Glow theme is on, for screens whose layout differs between themes. */
val LocalGlow = staticCompositionLocalOf { false }

/** Dark and dense by default. [glow] picks the phase 5c look; false is Classic. */
@Composable
fun EarshotTheme(glow: Boolean = false, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalGlow provides glow) {
        if (glow) {
            MaterialTheme(colorScheme = GlowColors, typography = glowTypography(), content = content)
        } else {
            MaterialTheme(colorScheme = ClassicColors, content = content)
        }
    }
}
