package com.gavrovis.navyhod.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFFB45309),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE3C4),
    onPrimaryContainer = Color(0xFF3B1B00),
    secondary = Color(0xFF4B607C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7E3F7),
    onSecondaryContainer = Color(0xFF0B1D33),
    background = Color(0xFFFDF8F2),
    onBackground = Color(0xFF1F1B16),
    surface = Color(0xFFFFFBF7),
    onSurface = Color(0xFF1F1B16),
    surfaceVariant = Color(0xFFF2E6D8),
    onSurfaceVariant = Color(0xFF5A4A3A),
    outline = Color(0xFFB9A897),
    outlineVariant = Color(0xFFE0D0BF),
    surfaceDim = Color(0xFFE2D8CC),
    surfaceBright = Color(0xFFFFFBF7),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF6EC),
    surfaceContainer = Color(0xFFF8EFE3),
    surfaceContainerHigh = Color(0xFFF2E9DD),
    surfaceContainerHighest = Color(0xFFECE3D7),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB86B),
    onPrimary = Color(0xFF472A00),
    primaryContainer = Color(0xFF653E00),
    onPrimaryContainer = Color(0xFFFFDCBE),
    secondary = Color(0xFFB9C8DE),
    onSecondary = Color(0xFF233246),
    secondaryContainer = Color(0xFF33445A),
    onSecondaryContainer = Color(0xFFD7E3F7),
    background = Color(0xFF141210),
    onBackground = Color(0xFFEDE0D4),
    surface = Color(0xFF1B1815),
    onSurface = Color(0xFFEDE0D4),
    surfaceVariant = Color(0xFF2A241E),
    onSurfaceVariant = Color(0xFFD5C4B4),
    outline = Color(0xFF8C7B6B),
    outlineVariant = Color(0xFF3F372F),
    surfaceDim = Color(0xFF141210),
    surfaceBright = Color(0xFF3B342C),
    surfaceContainerLowest = Color(0xFF0F0D0B),
    surfaceContainerLow = Color(0xFF1B1815),
    surfaceContainer = Color(0xFF1F1C18),
    surfaceContainerHigh = Color(0xFF2A251F),
    surfaceContainerHighest = Color(0xFF352F28),
)

internal val AlarmButtonDark = LightColors.onPrimaryContainer
internal val AlarmButtonMedium = LightColors.primary
internal val AlarmButtonLight = LightColors.primaryContainer

@Composable
fun NaVykhodTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
