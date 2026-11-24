package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val RetroTokDarkColorScheme = darkColorScheme(
    primary = TikTokRed,
    onPrimary = TikTokWhite,
    primaryContainer = NeonMagentaDark,
    onPrimaryContainer = TikTokWhite,
    secondary = TikTokCyan,
    onSecondary = TikTokBlack,
    secondaryContainer = TikTokDark,
    onSecondaryContainer = TikTokCyan,
    tertiary = RetroGold,
    onTertiary = TikTokBlack,
    background = TikTokBlack,
    onBackground = TikTokWhite,
    surface = TikTokDark,
    onSurface = TikTokWhite,
    surfaceVariant = TikTokElevated,
    onSurfaceVariant = TikTokGray,
    outline = TikTokBorder,
    outlineVariant = TikTokDisabled
)

@Composable
fun RetroTokTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = RetroTokDarkColorScheme,
        typography = RetroTokTypography,
        content = content
    )
}
