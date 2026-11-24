package com.example.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// TikTok-Inspired Clean Flat Palette
val TikTokBlack = Color(0xFF000000)
val TikTokDark = Color(0xFF161616)
val TikTokElevated = Color(0xFF222222)
val TikTokBorder = Color(0xFF292929)
val TikTokDisabled = Color(0xFF666666)

val TikTokRed = Color(0xFFFE2C55) // Primary Accent
val TikTokCyan = Color(0xFF25F4EE) // Secondary Accent
val TikTokWhite = Color(0xFFFFFFFF)
val TikTokGray = Color(0xFFB3B3B3)
val TikTokLightGray = Color(0xFFE0E0E0)

// Aliases for retro compatibility
val RetroOnyx = TikTokBlack
val RetroSurface = TikTokDark
val RetroSurfaceElevated = TikTokElevated
val RetroSurfaceBorder = TikTokBorder

val NeonMagenta = TikTokRed
val NeonMagentaDark = Color(0xFFD61E44)
val ElectricCyan = TikTokCyan
val ElectricCyanDark = Color(0xFF1EC9C4)
val RetroGold = Color(0xFFFFD700)
val PhosphorGreen = Color(0xFF25F4EE)
val SynthPurple = Color(0xFF8A2BE2)
val DeepViolet = Color(0xFF1F1F1F)

val TextPrimary = TikTokWhite
val TextSecondary = TikTokGray
val TextMuted = Color(0xFF888888)

// Clean transparent scrims for feed overlays
val ScrimVignette = Brush.verticalGradient(
    listOf(Color.Transparent, Color(0x66000000), Color(0xCC000000))
)

val TopBarScrim = Brush.verticalGradient(
    listOf(Color(0xCC000000), Color(0x55000000), Color.Transparent)
)

val BrandGlowGradient = Brush.horizontalGradient(
    listOf(TikTokCyan, TikTokRed)
)

val ActionButtonGradient = Brush.horizontalGradient(
    listOf(TikTokRed, TikTokRed)
)

val CyanPurpleGradient = Brush.linearGradient(
    listOf(TikTokCyan, TikTokRed)
)
