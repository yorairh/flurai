package com.flurai.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Indigo40,
    onPrimary = Color.White,
    primaryContainer = Indigo90,
    onPrimaryContainer = Indigo10,
    secondary = Vermilion40,
    onSecondary = Color.White,
    secondaryContainer = Vermilion90,
    onSecondaryContainer = Vermilion10,
    tertiary = Matcha40,
    onTertiary = Color.White,
    tertiaryContainer = Matcha90,
    onTertiaryContainer = Matcha10,
    background = Washi,
    onBackground = Ink,
    surface = Washi,
    onSurface = Ink,
    surfaceVariant = WashiVariant,
    onSurfaceVariant = InkVariant,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F2EC),
    surfaceContainer = Color(0xFFF2ECE5),
    surfaceContainerHigh = Color(0xFFECE6DE),
    surfaceContainerHighest = Color(0xFFE6E0D8),
)

private val DarkColors = darkColorScheme(
    primary = Indigo80,
    onPrimary = Indigo10,
    primaryContainer = Indigo40,
    onPrimaryContainer = Indigo90,
    secondary = Vermilion80,
    onSecondary = Vermilion10,
    secondaryContainer = Color(0xFF8C1D12),
    onSecondaryContainer = Vermilion90,
    tertiary = Matcha80,
    onTertiary = Matcha10,
    tertiaryContainer = Color(0xFF43521A),
    onTertiaryContainer = Matcha90,
    background = Night,
    onBackground = NightOnSurface,
    surface = Night,
    onSurface = NightOnSurface,
    surfaceVariant = NightVariant,
    onSurfaceVariant = NightOnVariant,
    surfaceContainerLowest = Color(0xFF0F1116),
    surfaceContainerLow = Color(0xFF181B22),
    surfaceContainer = NightSurface,
    surfaceContainerHigh = Color(0xFF242832),
    surfaceContainerHighest = Color(0xFF2F333D),
)

@Composable
fun FluRaiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = FluRaiTypography,
        content = content,
    )
}
