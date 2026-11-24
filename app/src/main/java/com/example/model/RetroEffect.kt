package com.example.model

enum class RetroEffectType(
    val title: String,
    val subtitle: String,
    val iconName: String,
    val badgeColorHex: Long
) {
    VHS_RETRO("VHS Retro", "Tracking Glitch & Analog CRT", "Tape", 0xFFFF2A85),
    NEON_80S("80s Neon", "Magenta & Cyan Cyberpunk Glow", "Flare", 0xFF00F0FF),
    VINTAGE_70S("70s Vintage", "Warm Sepia & Golden Hour", "WbSunny", 0xFFFFB800),
    FILM_90S("90s Camcorder", "Film Grain & Timecode Stamp", "Videocam", 0xFF39FF14),
    BW_NOIR("Noir Classic", "Monochrome High Silver", "Contrast", 0xFFE2E8F0),
    NONE("Original", "Clean Camera Passthrough", "AutoAwesome", 0xFF8A2BE2)
}
