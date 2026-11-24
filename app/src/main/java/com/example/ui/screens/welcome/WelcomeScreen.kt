package com.example.ui.screens.welcome

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.EventModeStatus
import com.example.model.KioskSettings
import com.example.ui.components.RetroPillButton
import com.example.ui.components.RetroScanlinesOverlay
import com.example.ui.components.RotatingVinylBadge
import com.example.ui.theme.BrandGlowGradient
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.NeonMagenta
import com.example.ui.theme.PhosphorGreen
import com.example.ui.theme.RetroGold
import com.example.ui.theme.RetroOnyx
import com.example.ui.theme.RetroSurface
import com.example.ui.theme.RetroSurfaceElevated
import com.example.ui.theme.SynthPurple
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TikTokRed

@Composable
fun WelcomeScreen(
    onExploreFeedClick: () -> Unit,
    onStartRecordingClick: () -> Unit,
    onAdminClick: () -> Unit,
    modifier: Modifier = Modifier,
    settings: KioskSettings = KioskSettings()
) {
    val infiniteTransition = rememberInfiniteTransition(label = "NeonPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "AlphaPulse"
    )

    val statusColor = when (settings.eventModeStatus) {
        EventModeStatus.ACTIVE -> PhosphorGreen
        EventModeStatus.PAUSED -> RetroGold
        EventModeStatus.ENDED -> TikTokRed
        EventModeStatus.NOT_STARTED -> ElectricCyan
    }
    val statusLabel = when (settings.eventModeStatus) {
        EventModeStatus.ACTIVE -> "EVENT KIOSK LIVE"
        EventModeStatus.PAUSED -> "GUEST ACCESS PAUSED"
        EventModeStatus.ENDED -> "EVENT ENDED"
        EventModeStatus.NOT_STARTED -> "EVENT STANDBY"
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(RetroOnyx)
            .testTag("welcome_screen")
    ) {
        RetroScanlinesOverlay()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Bar with Event Status Badge & Admin Lock
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(RetroSurfaceElevated)
                        .border(1.dp, statusColor.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag("welcome_event_status_badge")
                ) {
                    Icon(
                        imageVector = if (settings.eventModeStatus == EventModeStatus.PAUSED) {
                            Icons.Default.PauseCircle
                        } else {
                            Icons.Default.FiberManualRecord
                        },
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(10.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = statusLabel,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = statusColor
                    )
                }

                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(RetroSurface)
                        .clickable(onClick = onAdminClick)
                        .testTag("welcome_admin_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Admin Access",
                        tint = TextMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Central Hero Block
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(bottom = 18.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(156.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    listOf(NeonMagenta.copy(alpha = 0.35f * pulseAlpha), Color.Transparent)
                                )
                            )
                    )
                    RotatingVinylBadge(
                        onClick = onExploreFeedClick,
                        modifier = Modifier.size(112.dp)
                    )
                }

                Text(
                    text = "RETROTOK",
                    style = MaterialTheme.typography.displayLarge.copy(
                        brush = BrandGlowGradient,
                        letterSpacing = 2.sp
                    ),
                    textAlign = TextAlign.Center
                )

                Text(
                    text = settings.eventName.uppercase(),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = ElectricCyan,
                    letterSpacing = 1.2.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp, bottom = 6.dp)
                )

                if (settings.eventDescription.isNotBlank()) {
                    Text(
                        text = settings.eventDescription,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                    )
                }

                // Decade Badges Row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 8.dp)
                ) {
                    DecadePill("70s DISCO", RetroGold)
                    DecadePill("80s SYNTH", NeonMagenta)
                    DecadePill("90s EURO", ElectricCyan)
                    DecadePill("2000s POP", SynthPurple)
                }

                if (!settings.isAcceptingGuestRecordings) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(statusColor.copy(alpha = 0.14f))
                            .border(1.dp, statusColor.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                            .testTag("welcome_paused_banner")
                    ) {
                        Text(
                            text = when (settings.eventModeStatus) {
                                EventModeStatus.PAUSED -> "New guest recordings are temporarily paused by the organizer. You can still watch the Event Feed below!"
                                EventModeStatus.ENDED -> "This event has wrapped up! You can still enjoy existing dance videos in the Event Feed."
                                else -> "Recording booth is standing by for the organizer to start the event."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    Text(
                        text = "Pick an iconic soundtrack, step in front of the camera, and add your dance moves to tonight's event feed!",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = TextSecondary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            // Bottom CTA Buttons
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                RetroPillButton(
                    text = when (settings.eventModeStatus) {
                        EventModeStatus.ACTIVE -> "STEP UP & DANCE"
                        EventModeStatus.PAUSED -> "RECORDING PAUSED"
                        EventModeStatus.ENDED -> "EVENT ENDED"
                        EventModeStatus.NOT_STARTED -> "BOOTH STANDBY"
                    },
                    onClick = onStartRecordingClick,
                    enabled = settings.isAcceptingGuestRecordings,
                    icon = Icons.Default.Videocam,
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "welcome_start_button"
                )

                OutlinedButton(
                    onClick = onExploreFeedClick,
                    shape = RoundedCornerShape(32.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag("welcome_feed_button")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = ElectricCyan,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "WATCH EVENT FEED",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                    }
                }

                Text(
                    text = "100% Local Event Storage • No Internet or Cloud Uploads",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = TextMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun DecadePill(label: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.15f))
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            color = color
        )
    }
}
