package com.example.ui.screens.preview

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.RetroEffectType
import com.example.ui.components.RetroMarqueeTicker
import com.example.ui.components.RetroPillButton
import com.example.ui.components.RetroScanlinesOverlay
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.NeonMagenta
import com.example.ui.theme.PhosphorGreen
import com.example.ui.theme.RetroGold
import com.example.ui.theme.RetroOnyx
import com.example.ui.theme.RetroSurface
import com.example.ui.theme.RetroSurfaceBorder
import com.example.ui.theme.RetroSurfaceElevated
import com.example.ui.theme.ScrimVignette
import com.example.ui.theme.SynthPurple
import com.example.ui.theme.TikTokBlack
import com.example.ui.theme.TikTokCyan
import com.example.ui.theme.TikTokDark
import com.example.ui.theme.TikTokDisabled
import com.example.ui.theme.TikTokGray
import com.example.ui.theme.TikTokRed
import com.example.ui.theme.TikTokWhite
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TopBarScrim

@Composable
fun VideoPreviewScreen(
    uiState: PreviewUiState,
    onNicknameChange: (String) -> Unit,
    onEffectChange: (RetroEffectType) -> Unit,
    onAddToFeedClick: () -> Unit,
    onConfirmPublish: () -> Unit,
    onDismissPublishModal: () -> Unit,
    onRetakeClick: () -> Unit,
    onConfirmDiscard: () -> Unit,
    onDismissDiscardModal: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler {
        onRetakeClick()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TikTokBlack)
            .testTag("video_preview_screen")
    ) {
        // Real Video Player or Looping Preview Simulation
        RealVideoPlayerInPreview(
            videoFilePath = uiState.videoFilePath,
            effect = uiState.effect
        )

        // Top Header & Back/Retake Button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xCC000000), Color.Transparent)
                    )
                )
                .align(Alignment.TopCenter)
                .padding(horizontal = 16.dp, vertical = 20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Retake Button
                IconButton(
                    onClick = onRetakeClick,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(0x66000000))
                        .testTag("preview_retake_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Retake",
                        tint = TikTokWhite,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Effect Badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x66161616))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "${uiState.effect.title} • ${uiState.durationSeconds}s",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        color = TikTokWhite
                    )
                }
            }
        }

        // Bottom Controls Scrim & Publishing Cluster
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color(0xCC000000))
                    )
                )
                .align(Alignment.BottomCenter)
                .padding(horizontal = 20.dp, vertical = 24.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Soundtrack indicator
                RetroMarqueeTicker(
                    song = uiState.song,
                    modifier = Modifier.fillMaxWidth()
                )

                // Nickname / Caption Input
                OutlinedTextField(
                    value = uiState.guestNickname,
                    onValueChange = onNicknameChange,
                    placeholder = {
                        Text(
                            text = if (uiState.requireNickname) {
                                "Enter your nickname or dance crew (required)"
                            } else {
                                "Add your name or dance crew (optional)"
                            },
                            color = TikTokGray
                        )
                    },
                    isError = uiState.nicknameError != null,
                    supportingText = uiState.nicknameError?.let { err ->
                        {
                            Text(
                                text = err,
                                color = Color(0xFFFF5252),
                                fontSize = 12.sp,
                                modifier = Modifier.testTag("preview_nickname_error")
                            )
                        }
                    },
                    leadingIcon = {
                        Icon(imageVector = Icons.Default.Person, contentDescription = null, tint = TikTokGray)
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = TikTokDark,
                        unfocusedContainerColor = TikTokDark,
                        focusedBorderColor = TikTokWhite,
                        unfocusedBorderColor = Color(0xFF292929),
                        errorBorderColor = Color(0xFFFF5252),
                        focusedTextColor = TikTokWhite,
                        unfocusedTextColor = TikTokWhite
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("preview_nickname_input")
                )

                // Action Buttons Row: Retake (neutral) & Add to Feed (TikTok red)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onRetakeClick,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF292929)),
                        modifier = Modifier
                            .weight(1f)
                            .height(54.dp)
                            .testTag("preview_retake_button_bottom")
                    ) {
                        Text(
                            text = "Retake",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = TikTokWhite
                        )
                    }

                    Button(
                        onClick = onAddToFeedClick,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = TikTokRed),
                        modifier = Modifier
                            .weight(2f)
                            .height(54.dp)
                            .testTag("preview_add_to_feed_button")
                    ) {
                        Text(
                            text = "Add to Event Feed",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = TikTokWhite
                        )
                    }
                }
            }
        }

        // Publishing Confirmation Modal with Privacy Disclaimer
        if (uiState.showPublishModal) {
            AlertDialog(
                onDismissRequest = { if (!uiState.isPublishing) onDismissPublishModal() },
                containerColor = RetroSurface,
                title = {
                    Text(
                        text = "ADD TO EVENT FEED",
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black),
                        color = TextPrimary
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = PhosphorGreen,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (uiState.isCloudBackupEnabled) {
                                    "Saved Locally First • Event Cloud Sync Enabled"
                                } else {
                                    "100% Local Event Storage"
                                },
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = PhosphorGreen
                            )
                        }

                        Text(
                            text = if (uiState.isCloudBackupEnabled) {
                                "Your video is saved on this kiosk device right away for the Event Feed, and queued in the background for organizer cloud upload when online."
                            } else {
                                "Your video will be saved on this device and added to the RetroTok event feed for guests to enjoy. It will not be uploaded or shared online."
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextSecondary
                        )

                        if (uiState.publishErrorMessage != null) {
                            Text(
                                text = uiState.publishErrorMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFFF5252)
                            )
                        }

                        if (uiState.isPublishing) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                            ) {
                                CircularProgressIndicator(color = NeonMagenta, modifier = Modifier.size(28.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "Saving to event feed...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextPrimary
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = onConfirmPublish,
                        enabled = !uiState.isPublishing,
                        colors = ButtonDefaults.buttonColors(containerColor = NeonMagenta),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.testTag("publish_confirm_button")
                    ) {
                        Text(
                            text = "CONFIRM & ADD",
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                },
                dismissButton = {
                    OutlinedButton(
                        onClick = onDismissPublishModal,
                        enabled = !uiState.isPublishing,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.testTag("publish_cancel_button")
                    ) {
                        Text(text = "CANCEL", color = TextSecondary)
                    }
                }
            )
        }

        // Discard Confirmation Modal
        if (uiState.showDiscardModal) {
            AlertDialog(
                onDismissRequest = onDismissDiscardModal,
                containerColor = RetroSurface,
                title = {
                    Text(
                        text = "DISCARD TAKE?",
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black),
                        color = TextPrimary
                    )
                },
                text = {
                    Text(
                        text = "Are you sure you want to discard this recording and record again? Your recorded take will not be saved.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextSecondary
                    )
                },
                confirmButton = {
                    Button(
                        onClick = onConfirmDiscard,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.testTag("discard_confirm_button")
                    ) {
                        Text(text = "DISCARD & RETAKE", fontWeight = FontWeight.Bold, color = TextPrimary)
                    }
                },
                dismissButton = {
                    OutlinedButton(
                        onClick = onDismissDiscardModal,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.testTag("discard_cancel_button")
                    ) {
                        Text(text = "KEEP EDITING", color = TextSecondary)
                    }
                }
            )
        }
    }
}

@Composable
private fun RealVideoPlayerInPreview(
    videoFilePath: String,
    effect: RetroEffectType
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val file = remember(videoFilePath) { java.io.File(videoFilePath) }
    val fileExists = remember(file) { file.exists() && file.length() > 0 }

    if (fileExists) {
        val exoPlayer = remember(videoFilePath) {
            androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
                setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(file)))
                repeatMode = androidx.media3.common.Player.REPEAT_MODE_ALL
                prepare()
                play()
            }
        }

        androidx.compose.runtime.DisposableEffect(exoPlayer) {
            onDispose {
                exoPlayer.release()
            }
        }

        // Composed MP4 already contains permanently baked retro effect, portrait 9:16 crop, and soundtrack
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { ctx ->
                androidx.media3.ui.PlayerView(ctx).apply {
                    useController = false
                    player = exoPlayer
                    resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    } else {
        SimulatedVideoLoop(effect = effect)
    }
}

@Composable
private fun SimulatedVideoLoop(
    effect: RetroEffectType
) {
    val backgroundBrush = when (effect) {
        RetroEffectType.VHS_RETRO -> Brush.verticalGradient(listOf(Color(0xFF28003A), Color(0xFF0F2027), Color(0xFF203A43)))
        RetroEffectType.NEON_80S -> Brush.verticalGradient(listOf(Color(0xFF1A0033), Color(0xFF0052D4), Color(0xFF4364F7)))
        RetroEffectType.VINTAGE_70S -> Brush.verticalGradient(listOf(Color(0xFF331600), Color(0xFF783800), Color(0xFFC76900)))
        RetroEffectType.FILM_90S -> Brush.verticalGradient(listOf(Color(0xFF141A1C), Color(0xFF2B3A42), Color(0xFF141A1C)))
        RetroEffectType.BW_NOIR -> Brush.verticalGradient(listOf(Color(0xFF111111), Color(0xFF333333), Color(0xFF111111)))
        RetroEffectType.NONE -> Brush.verticalGradient(listOf(Color(0xFF181028), Color(0xFF0F0B18)))
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundBrush)
        )

        // Center Stage Preview Label
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = PhosphorGreen,
                modifier = Modifier.size(54.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "RECORDING READY TO PREVIEW",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = PhosphorGreen
            )
            Text(
                text = "Looping with synchronized soundtrack",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }

        RetroScanlinesOverlay()
    }
}

