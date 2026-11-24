package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.SongItem
import com.example.ui.theme.TikTokBlack
import com.example.ui.theme.TikTokCyan
import com.example.ui.theme.TikTokDark
import com.example.ui.theme.TikTokDisabled
import com.example.ui.theme.TikTokGray
import com.example.ui.theme.TikTokRed
import com.example.ui.theme.TikTokWhite

/**
 * Clean, flat TikTok-inspired primary action button.
 */
@Composable
fun RetroPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 56.dp,
    containerColor: Color = TikTokRed,
    testTag: String = "retro_pill_button"
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale = if (isPressed) 0.97f else 1.0f

    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (enabled) containerColor else Color(0xFF2E2E2E),
            disabledContainerColor = Color(0xFF2E2E2E)
        ),
        contentPadding = PaddingValues(horizontal = 24.dp),
        modifier = modifier
            .testTag(testTag)
            .height(height)
            .scale(scale)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = TikTokWhite,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                ),
                color = TikTokWhite
            )
        }
    }
}

/**
 * TikTok-style right-side vertical interaction column.
 */
@Composable
fun RetroActionColumn(
    likeCount: Int,
    favoriteCount: Int,
    isLiked: Boolean,
    isFavorited: Boolean,
    onLikeClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onVinylClick: () -> Unit,
    modifier: Modifier = Modifier,
    showLikeButton: Boolean = true,
    showFavoriteButton: Boolean = true,
    onQrClick: () -> Unit = {},
    showQrButton: Boolean = true,
    isQrReady: Boolean = false,
    isQrUploading: Boolean = false
) {
    val likeScale = remember { Animatable(1f) }

    LaunchedEffect(isLiked) {
        if (isLiked) {
            likeScale.animateTo(1.3f, animationSpec = tween(100, easing = FastOutSlowInEasing))
            likeScale.animateTo(1.0f, animationSpec = tween(150, easing = FastOutSlowInEasing))
        }
    }

    Column(
        modifier = modifier
            .width(68.dp)
            .padding(bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Creator Avatar with follow badge
        Box(
            modifier = Modifier
                .size(48.dp)
                .testTag("action_creator_avatar"),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(TikTokDark)
                    .border(1.5.dp, TikTokWhite, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = "Creator Avatar",
                    tint = TikTokWhite,
                    modifier = Modifier.size(26.dp)
                )
            }
        }

        if (showLikeButton) {
            // Like Button
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clickable(onClick = onLikeClick)
                    .testTag("action_like_button")
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .scale(likeScale.value),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = "Like Video",
                        tint = if (isLiked) TikTokRed else TikTokWhite,
                        modifier = Modifier.size(36.dp)
                    )
                }
                Text(
                    text = likeCount.toString(),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TikTokWhite
                )
            }
        }

        if (showFavoriteButton) {
            // Favorite Button
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clickable(onClick = onFavoriteClick)
                    .testTag("action_favorite_button")
            ) {
                Box(
                    modifier = Modifier.size(46.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Bookmark,
                        contentDescription = "Favorite Video",
                        tint = if (isFavorited) Color(0xFFFFD700) else TikTokWhite,
                        modifier = Modifier.size(34.dp)
                    )
                }
                Text(
                    text = favoriteCount.toString(),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TikTokWhite
                )
            }
        }

        if (showQrButton) {
            val qrTint = when {
                isQrReady -> TikTokCyan
                isQrUploading -> Color(0xFFFFD700)
                else -> TikTokWhite
            }
            val qrBg = if (isQrReady) TikTokCyan.copy(alpha = 0.16f) else Color(0x88161616)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clickable(onClick = onQrClick)
                    .testTag("action_qr_button")
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(qrBg)
                        .border(
                            width = if (isQrReady) 1.5.dp else 1.dp,
                            color = if (isQrReady) TikTokCyan else Color(0x44FFFFFF),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCode2,
                        contentDescription = "Show Share QR Code",
                        tint = qrTint,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "QR Code",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isQrReady) TikTokCyan else TikTokWhite,
                    maxLines = 1
                )
            }
        }

        // Spinning Vinyl Disc
        RotatingVinylBadge(
            onClick = onVinylClick,
            modifier = Modifier.testTag("action_vinyl_badge")
        )
    }
}

/**
 * Animated spinning vinyl badge representing the currently playing song in TikTok style.
 */
@Composable
fun RotatingVinylBadge(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "VinylSpin")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "VinylRotation"
    )

    Box(
        modifier = modifier
            .size(50.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .size(46.dp)
                .rotate(rotation)
        ) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.width / 2f

            // Outer black vinyl disc
            drawCircle(color = Color(0xFF1E1E1E), radius = radius)
            // Grooves
            drawCircle(color = Color(0xFF2C2C2C), radius = radius * 0.85f, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
            drawCircle(color = Color(0xFF2C2C2C), radius = radius * 0.70f, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
            // Center red label
            drawCircle(color = TikTokRed, radius = radius * 0.40f)
            // Center spindle
            drawCircle(color = TikTokBlack, radius = radius * 0.12f)
        }
    }
}

/**
 * Clean TikTok-style music ticker. When [onClick] is provided and the song has a catalog ID,
 * serves as a tappable "Use this song" entry point with a subtle visual affordance.
 */
@Composable
fun RetroMarqueeTicker(
    song: SongItem,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    isLoading: Boolean = false
) {
    val isInteractive = onClick != null && song.id.isNotBlank()
    val containerModifier = if (isInteractive) {
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x55161616))
            .border(0.75.dp, Color(0x44FFFFFF), RoundedCornerShape(10.dp))
            .clickable(
                enabled = enabled && !isLoading,
                onClickLabel = "Use ${song.title} in Dance Studio",
                onClick = { onClick?.invoke() }
            )
            .padding(horizontal = 10.dp, vertical = 8.dp)
    } else {
        modifier.padding(vertical = 4.dp)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = containerModifier
    ) {
        Icon(
            imageVector = Icons.Default.MusicNote,
            contentDescription = null,
            tint = if (isInteractive) TikTokCyan else TikTokWhite,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "${song.title} - ${song.artist}",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = TikTokWhite,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (isInteractive) Modifier.weight(1f, fill = false) else Modifier
        )
        if (isInteractive) {
            Spacer(modifier = Modifier.width(8.dp))
            if (isLoading) {
                CircularProgressIndicator(
                    color = TikTokCyan,
                    strokeWidth = 1.5.dp,
                    modifier = Modifier.size(14.dp)
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(TikTokCyan.copy(alpha = 0.16f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "Use",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TikTokCyan
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = TikTokCyan,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

/**
 * Clean minimal REC timecode badge.
 */
@Composable
fun RetroTimecodeBadge(
    secondsElapsed: Int,
    totalSeconds: Int,
    isRecording: Boolean,
    modifier: Modifier = Modifier
) {
    val elapsedFormatted = String.format("%02d:%02d", secondsElapsed / 60, secondsElapsed % 60)
    val totalFormatted = String.format("%02d:%02d", totalSeconds / 60, totalSeconds % 60)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x99000000))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        if (isRecording) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(TikTokRed)
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = "$elapsedFormatted / $totalFormatted",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = TikTokWhite
        )
    }
}

/**
 * Unobstructed preview: CRT scanlines removed per section 1.4 specification.
 */
@Composable
fun RetroScanlinesOverlay(
    modifier: Modifier = Modifier
) {
    // Left empty: CRT scanlines and decorative grid overlays are intentionally removed
    // to provide a clean, unobstructed live camera and video preview.
}

/**
 * Iconic TikTok-style center "+" button with cyan & red side accents.
 */
@Composable
fun TikTokCreateButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(width = 48.dp, height = 32.dp)
            .clickable(onClick = onClick)
            .testTag("tiktok_create_button"),
        contentAlignment = Alignment.Center
    ) {
        // Cyan background box (left offset)
        Box(
            modifier = Modifier
                .size(width = 40.dp, height = 30.dp)
                .offset(x = (-3).dp)
                .clip(RoundedCornerShape(8.dp))
                .background(TikTokCyan)
        )
        // Red background box (right offset)
        Box(
            modifier = Modifier
                .size(width = 40.dp, height = 30.dp)
                .offset(x = 3.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(TikTokRed)
        )
        // White inner box with black plus
        Box(
            modifier = Modifier
                .size(width = 40.dp, height = 30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(TikTokWhite),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Create Video",
                tint = TikTokBlack,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Minimal TikTok-inspired bottom navigation bar.
 */
@Composable
fun TikTokBottomNavigation(
    selectedTab: String,
    onTabSelected: (String) -> Unit,
    onCreateClick: () -> Unit,
    modifier: Modifier = Modifier,
    showDiscoverTab: Boolean = true,
    showFavoritesTab: Boolean = true
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(58.dp)
            .background(TikTokBlack)
            .border(width = 0.5.dp, color = Color(0xFF1E1E1E))
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Home / Feed tab
            BottomNavItem(
                label = "Home",
                icon = Icons.Default.Home,
                isSelected = selectedTab == "home",
                onClick = { onTabSelected("home") }
            )

            if (showDiscoverTab) {
                // Discover / Music tab
                BottomNavItem(
                    label = "Discover",
                    icon = Icons.Default.Explore,
                    isSelected = selectedTab == "discover",
                    onClick = { onTabSelected("discover") }
                )
            }

            // Center TikTok Create Button
            TikTokCreateButton(
                onClick = onCreateClick
            )

            if (showFavoritesTab) {
                // Favorites tab
                BottomNavItem(
                    label = "Favorites",
                    icon = Icons.Default.Bookmark,
                    isSelected = selectedTab == "favorites",
                    onClick = { onTabSelected("favorites") }
                )
            }
        }
    }
}

@Composable
private fun BottomNavItem(
    label: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (isSelected) TikTokWhite else TikTokDisabled,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) TikTokWhite else TikTokDisabled
        )
    }
}
