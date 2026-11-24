# RetroTok — UI/UX Design System Specification

**Document ID:** RT-DOC-07  
**Version:** 1.0.0  
**Design Philosophy:** "Neo-Retro Disco & Synthwave" meets Modern Short-Video Ergonomics  
**Display Target:** 720 × 1280 Portrait Touchscreen Kiosk  

---

## 1. Visual Identity & Design Philosophy

The RetroTok visual identity bridges two worlds:
1. **The Modern TikTok Interaction Model:** Full-screen vertical media, edge-to-edge video, high-density right-rail actions, bottom-anchored content tickers, and intuitive swipe gestures.
2. **Nostalgic Era Aesthetics (1970–2001):** Neon signage, vinyl groove textures, CRT monitor phosphor scans, VHS timecodes, and cassette tape styling.

The result is premium, vibrant, tactile, and playful—expressly avoiding sterile corporate palettes or generic "AI slop" templates.

---

## 2. Color Palette & Theming Tokens

### 2.1 Core Palette (Dark Kiosk Theme)
The application operates in a dedicated dark mode optimized for event venue illumination (dim ballroom lighting, laser projectors, strobe accents):

| Token Name | Hex Code | Semantic Role |
| :--- | :--- | :--- |
| `RetroOnyx` | `#0B0813` | Root kiosk background & letterbox fill |
| `RetroSurface` | `#161126` | Card backgrounds, bottom sheets, dialogs |
| `RetroSurfaceElevated` | `#231B3D` | Active list items, elevated action bars |
| `NeonMagenta` | `#FF2A85` | Primary brand accent, like button, record trigger |
| `ElectricCyan` | `#00F0FF` | Secondary accent, favorite star, selection ring |
| `RetroGold` | `#FFB800` | Decade 70s badge, featured video star |
| `PhosphorGreen` | `#39FF14` | Active recording timecode indicator, live status |
| `SynthPurple` | `#8A2BE2` | Gradient stop, button highlights |
| `TextPrimary` | `#FFFFFF` | Headlines, nicknames, primary captions |
| `TextSecondary` | `#D1C4E9` | Song artists, release years, timestamps |
| `TextMuted` | `#7E7399` | Inactive icons, subtle divider strokes |

### 2.2 Gradient Specifications
- **Brand Glow Gradient:** `Brush.horizontalGradient(listOf(Color(0xFFFF2A85), Color(0xFF8A2BE2)))`
- **Neon Border Glow:** `Brush.linearGradient(listOf(Color(0xFF00F0FF), Color(0xFFFF2A85)))`
- **Scrim Vignette (Video Bottom):** `Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC0B0813)))`

---

## 3. Typography Scale (Compose M3)

Typography combines high-energy geometric display headlines with ultra-readable sans-serif body copy:

```kotlin
val RetroTokTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 44.sp,
        letterSpacing = (-1).sp,
        color = Color.White
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 24.sp,
        color = Color.White
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        color = Color.White
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        color = Color(0xFFD1C4E9)
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Monospace, // Retro digital timecode readout
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        letterSpacing = 1.sp,
        color = Color(0xFF39FF14)
    )
)
```

---

## 4. Kiosk-Optimized Component Library

### 4.1 Primary CTA: "ADD TO EVENT FEED" Button
- **Height:** 64dp (exceeds standard 48dp for confident touchscreen tap).
- **Width:** `Modifier.fillMaxWidth().padding(horizontal = 24.dp)`.
- **Shape:** `RoundedCornerShape(32.dp)` (Pill shape).
- **Background:** Dual neon gradient (`#FF2A85` to `#8A2BE2`) with 8dp elevation shadow.
- **Icon:** Bold forward-sparkle icon alongside all-caps typography: **"ADD TO EVENT FEED"**.

### 4.2 Dance Studio Record Button
- **Diameter:** 88dp circular target.
- **Resting State:** Neon red core (`#FF2A85`) surrounded by an animated pulsing ring (`#00F0FF`).
- **Active Recording State:** Transitions to a square Stop icon (32dp × 32dp) enclosed in a 360-degree sweep progress arc rendered via `Canvas` drawing `drawArc()`.

### 4.3 Spinning Vinyl / Cassette Badge
- **Dimension:** 56dp × 56dp.
- **Animation:** `InfiniteTransition` with linear easing rotating 0 to 360 degrees over 3000ms.
- **Center Label:** Miniature album artwork thumbnail clipped to circle with vinyl grooved outer ring.

### 4.4 Right Action Column (Like, Favorite)
- **Container Width:** 64dp aligned to right edge with 16dp margin.
- **Touch Targets:** 64dp × 64dp minimum interactive zone.
- **Like Animation:** Tapping scales the heart from 1.0 to 1.4 then snaps back with an elastic bounce curve (`FastOutSlowInEasing`), triggering 6 radial micro-particles.
- **Double-Tap Screen Gesture:** Spawns a 96dp translucent neon pink heart at the touch coordinates that scales up and fades out over 600ms.

### 4.5 CRT & VHS Visual Overlays
- **REC Timecode Pill:** Monospace font inside a semi-transparent black pill with a flashing red dot (500ms cycle).
- **Decade Badges:** Compact chips with decade-themed styling:
  - `70s Classics`: Amber Gold `#FFB800` with retro serif hint.
  - `80s Hits`: Hot Magenta `#FF2A85` with neon italic glow.
  - `90s Favorites`: Electric Cyan `#00F0FF` with geometric outline.
  - `2000–2001`: Silver Chrome `#E2E8F0` with glossy sheen.

---

## 5. Touchscreen Interaction & Motion Curves

1. **Gestural Responsiveness:** Swiping between videos follows Compose `VerticalPager` physics with a velocity threshold tuned for kiosks (preventing accidental skips while maintaining effortless navigation).
2. **Haptic & Visual Feedback:** Every button press produces an immediate visual state change (`scale(0.96f)` pressed state) within 1 frame (16ms) to assure users their touch was registered.
3. **Modal Dialog Transitions:** Bottom sheets and modals slide in from the bottom with `slideInVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))`.
