package com.earshot.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Amber = Color(0xFFFFB74D)

private val EarshotColors = darkColorScheme(
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

/** Dark and dense by default. A light theme and density toggle can come later. */
@Composable
fun EarshotTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = EarshotColors, content = content)
}
