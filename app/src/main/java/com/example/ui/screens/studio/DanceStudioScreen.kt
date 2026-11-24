package com.example.ui.screens.studio

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.camera.BoundCameraDiagnostics
import com.example.camera.CameraPreviewController
import com.example.camera.CameraRecordingController
import com.example.camera.FocusSupportStatus
import com.example.model.RetroEffectType
import kotlinx.coroutines.delay
import com.example.ui.components.RetroTimecodeBadge
import com.example.video.CameraFramingMode
import com.example.video.EffectShaderConfig
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.NeonMagenta
import com.example.ui.theme.PhosphorGreen
import com.example.ui.theme.RetroGold
import com.example.ui.theme.RetroOnyx
import com.example.ui.theme.RetroSurfaceElevated
import com.example.ui.theme.ScrimVignette
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
fun DanceStudioScreen(
    uiState: StudioUiState,
    cameraPreviewController: CameraPreviewController? = null,
    cameraRecordingController: CameraRecordingController? = null,
    onSongPillClick: () -> Unit,
    onEffectSelect: (RetroEffectType) -> Unit,
    onDurationSelect: (Int) -> Unit = {},
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onToggleMirror: () -> Unit,
    onToggleGrid: () -> Unit,
    onCloseClick: () -> Unit,
    modifier: Modifier = Modifier,
    onRetryCamera: () -> Unit = {},
    onSwitchCamera: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    val diagnostics by (cameraPreviewController?.activeDiagnostics?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf(BoundCameraDiagnostics()) })
    val controllerBindingError by (cameraPreviewController?.bindingError?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf<String?>(null) })

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(hasCameraPermission) {
        if (!hasCameraPermission) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    BackHandler {
        when (uiState.studioState) {
            StudioState.RECORDING, StudioState.INITIALIZING_CAMERA -> onStopRecording()
            StudioState.COUNTDOWN, StudioState.FINALIZING, StudioState.PROCESSING -> {
                // Do not abort active countdown or composition pipeline on accidental back press
            }
            else -> onCloseClick()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TikTokBlack)
            .testTag("dance_studio_screen")
    ) {
        // Real Live Camera Viewfinder with CameraX binding, tap-to-focus, and runtime permission guard (API 23)
        LiveCameraViewfinder(
            cameraPreviewController = cameraPreviewController,
            cameraRecordingController = cameraRecordingController,
            hasCameraPermission = hasCameraPermission,
            isCameraDisconnected = uiState.isCameraDisconnected,
            studioState = uiState.studioState,
            cameraBindRetryCounter = uiState.cameraBindRetryCounter,
            onRequestCameraPermission = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
            isMirrored = uiState.isMirrored,
            framingMode = uiState.cameraFramingMode,
            effect = uiState.selectedEffect,
            onPreviewViewAvailable = { previewViewRef = it }
        )

        // Top Control Bar: TikTok-style minimal layout
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .zIndex(1f)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xCC000000), Color.Transparent)
                    )
                )
                .align(Alignment.TopCenter)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Cancel / Close Button
                    IconButton(
                        onClick = onCloseClick,
                        enabled = uiState.studioState != StudioState.RECORDING &&
                            uiState.studioState != StudioState.INITIALIZING_CAMERA &&
                            uiState.studioState != StudioState.PROCESSING &&
                            uiState.studioState != StudioState.FINALIZING,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color(0x66000000))
                            .testTag("studio_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel",
                            tint = TikTokWhite,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // Selected Sound Pill (TikTok-style "Add sound")
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0x99000000))
                            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(20.dp))
                            .clickable(enabled = uiState.studioState == StudioState.READY, onClick = onSongPillClick)
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .testTag("studio_song_pill")
                    ) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = TikTokWhite,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = uiState.selectedSong?.let { "${it.title} - ${it.artist}" } ?: "Add sound",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = TikTokWhite
                            ),
                            maxLines = 1
                        )
                    }

                    // Camera Controls: Separate Flip Camera (BACK/FRONT) & Mirror Preview toggles
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val canFlipCamera = uiState.studioState == StudioState.READY &&
                            (cameraPreviewController == null || (diagnostics.hasBackCamera && diagnostics.hasFrontCamera))
                        IconButton(
                            onClick = {
                                onSwitchCamera()
                                val view = previewViewRef
                                if (cameraPreviewController != null && view != null) {
                                    cameraPreviewController.switchCamera(
                                        lifecycleOwner = lifecycleOwner,
                                        previewView = view,
                                        isMirrored = uiState.isMirrored,
                                        framingMode = uiState.cameraFramingMode,
                                        recordingController = cameraRecordingController
                                    )
                                }
                            },
                            enabled = canFlipCamera,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(if (canFlipCamera) Color(0x88000000) else Color(0x33000000))
                                .testTag("studio_flip_camera_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.FlipCameraAndroid,
                                contentDescription = "Flip Camera",
                                tint = if (canFlipCamera) TikTokWhite else TikTokGray,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        IconButton(
                            onClick = onToggleMirror,
                            enabled = uiState.studioState == StudioState.READY,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(if (uiState.isMirrored) TikTokRed.copy(alpha = 0.45f) else Color(0x66000000))
                                .testTag("studio_mirror_toggle")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Flip,
                                contentDescription = "Mirror Preview",
                                tint = TikTokWhite,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                if (uiState.missingSongNotice) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(TikTokRed)
                            .clickable(onClick = onSongPillClick)
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                            .align(Alignment.CenterHorizontally)
                    ) {
                        Text(
                            text = "Please select a sound first to start recording",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TikTokWhite
                        )
                    }
                }

                val displayedError = uiState.errorMessage ?: uiState.cameraBindingError ?: controllerBindingError
                if (displayedError != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xD9B00020))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .testTag("studio_error_banner")
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = TikTokWhite, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = displayedError,
                                fontSize = 12.sp,
                                color = TikTokWhite,
                                modifier = Modifier.weight(1f)
                            )
                            if (uiState.isCameraDisconnected ||
                                !uiState.isVideoCaptureBound ||
                                uiState.cameraBindingError != null ||
                                uiState.studioState == StudioState.ERROR
                            ) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = onRetryCamera,
                                    colors = ButtonDefaults.buttonColors(containerColor = TikTokWhite),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.testTag("studio_retry_camera_button")
                                ) {
                                    Text("Retry", color = TikTokBlack, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                } else if (uiState.isCameraDisconnected) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xD93E2700))
                            .border(1.dp, RetroGold, RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .testTag("studio_camera_unavailable_banner")
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = RetroGold, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Camera Unavailable",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = RetroGold
                                )
                                Text(
                                    text = uiState.cameraStatusMessage ?: "Reconnect external USB webcam and tap Retry Camera.",
                                    fontSize = 11.sp,
                                    color = TikTokWhite
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = onRetryCamera,
                                colors = ButtonDefaults.buttonColors(containerColor = RetroGold),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.testTag("studio_retry_camera_button")
                            ) {
                                Icon(imageVector = Icons.Default.Refresh, contentDescription = null, tint = TikTokBlack, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Retry", color = TikTokBlack, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // Live Countdown Overlay (3..2..1)
        AnimatedVisibility(
            visible = uiState.studioState == StudioState.COUNTDOWN,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            val countdownScale = remember { Animatable(0.5f) }
            LaunchedEffect(uiState.countdownNumber) {
                countdownScale.snapTo(0.5f)
                countdownScale.animateTo(1.4f, animationSpec = tween(700, easing = FastOutSlowInEasing))
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = uiState.countdownNumber.toString(),
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontSize = 120.sp,
                        fontWeight = FontWeight.Black,
                        color = NeonMagenta
                    ),
                    modifier = Modifier.scale(countdownScale.value)
                )
                Text(
                    text = "GET READY TO DANCE!",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = ElectricCyan,
                    letterSpacing = 2.sp
                )
            }
        }

        // Camera Capture Initializing Overlay (waiting for VideoRecordEvent.Start)
        AnimatedVisibility(
            visible = uiState.studioState == StudioState.INITIALIZING_CAMERA,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xCC161616))
                    .padding(horizontal = 24.dp, vertical = 18.dp)
                    .testTag("studio_initializing_overlay")
            ) {
                CircularProgressIndicator(
                    color = TikTokRed,
                    trackColor = Color(0xFF292929),
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = uiState.processingStageLabel.ifBlank { "Starting camera recording..." },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TikTokWhite
                )
            }
        }

        // Processing State Overlay (GPU effect rendering + audio composition progress)
        AnimatedVisibility(
            visible = uiState.studioState == StudioState.PROCESSING || uiState.studioState == StudioState.FINALIZING,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            val pct = (uiState.processingProgress * 100f).toInt().coerceIn(0, 100)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .padding(horizontal = 32.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xEB161616))
                    .border(1.dp, Color(0xFF292929), RoundedCornerShape(16.dp))
                    .padding(24.dp)
                    .testTag("studio_processing_overlay")
            ) {
                CircularProgressIndicator(
                    progress = { uiState.processingProgress.coerceIn(0.05f, 1f) },
                    color = TikTokRed,
                    trackColor = Color(0xFF292929),
                    modifier = Modifier.size(54.dp)
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "Rendering Video • $pct%",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = TikTokWhite
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = uiState.processingStageLabel.ifBlank {
                        "Composing ${uiState.selectedEffect.title} & local soundtrack"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = TikTokGray
                )
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { uiState.processingProgress.coerceIn(0f, 1f) },
                    color = TikTokRed,
                    trackColor = Color(0xFF292929),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                )
            }
        }

        // Bottom Controls Scrim & Recording Cluster
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
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Duration Picker (15s, 30s, 60s)
                AnimatedVisibility(visible = uiState.studioState == StudioState.READY) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf(15, 30, 60).forEach { sec ->
                            val isSelected = uiState.maxDurationSeconds == sec
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(if (isSelected) TikTokWhite else Color(0x801F1F1F))
                                    .clickable { onDurationSelect(sec) }
                                    .padding(horizontal = 14.dp, vertical = 6.dp)
                                    .testTag("duration_pill_${sec}s")
                            ) {
                                Text(
                                    text = "${sec}s",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) TikTokBlack else TikTokWhite
                                )
                            }
                        }
                    }
                }

                // Retro Effect Carousel (Hidden while recording)
                AnimatedVisibility(visible = uiState.studioState == StudioState.READY) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(RetroEffectType.values()) { effect ->
                            val isSelected = uiState.selectedEffect == effect
                            EffectChip(
                                effect = effect,
                                isSelected = isSelected,
                                onClick = { onEffectSelect(effect) }
                            )
                        }
                    }
                }

                // Timecode Readout
                RetroTimecodeBadge(
                    secondsElapsed = uiState.recordedSeconds,
                    totalSeconds = uiState.maxDurationSeconds,
                    isRecording = uiState.studioState == StudioState.RECORDING
                )

                // Big TikTok Circular Record / Stop Button
                StudioRecordButton(
                    studioState = uiState.studioState,
                    recordedSeconds = uiState.recordedSeconds,
                    maxSeconds = uiState.maxDurationSeconds,
                    isCameraReady = !uiState.isCameraDisconnected && uiState.isVideoCaptureBound,
                    onStartClick = onStartRecording,
                    onStopClick = onStopRecording
                )
            }
        }
    }
}

@Composable
private fun StudioRecordButton(
    studioState: StudioState,
    recordedSeconds: Int,
    maxSeconds: Int,
    isCameraReady: Boolean = true,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val progress = remember(recordedSeconds, maxSeconds) {
        if (maxSeconds > 0) recordedSeconds.toFloat() / maxSeconds.toFloat() else 0f
    }

    Box(
        modifier = modifier
            .size(92.dp)
            .testTag("studio_record_button"),
        contentAlignment = Alignment.Center
    ) {
        // Outer White Ring with Progress Sweep
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 4.dp.toPx()
            // Outer white ring
            drawCircle(
                color = TikTokWhite,
                radius = (size.minDimension - strokeWidth) / 2,
                style = Stroke(strokeWidth)
            )
            // Progress sweep when recording
            if (studioState == StudioState.RECORDING) {
                drawArc(
                    color = TikTokRed,
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    style = Stroke(strokeWidth, cap = StrokeCap.Round)
                )
            }
        }

        // Inner Trigger Button
        if (studioState == StudioState.RECORDING) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(TikTokRed)
                    .clickable(onClick = onStopClick)
            )
        } else {
            val canStart = studioState == StudioState.READY && isCameraReady
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(if (canStart) TikTokRed else TikTokDisabled)
                    .clickable(enabled = canStart, onClick = onStartClick)
            )
        }
    }
}

@Composable
private fun EffectChip(
    effect: RetroEffectType,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val chipColor = Color(effect.badgeColorHex)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (isSelected) Color(0xFF292929) else Color(0x801F1F1F))
            .border(
                1.dp,
                if (isSelected) TikTokWhite else Color.Transparent,
                RoundedCornerShape(20.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
            .testTag("effect_chip_${effect.name}")
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(chipColor)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = effect.title,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 12.sp,
            color = if (isSelected) TikTokWhite else TikTokGray
        )
    }
}

/**
 * Live Camera Viewfinder displaying clean, unobstructed live camera preview per Section 1.4.
 * Applies a subtle API-23-safe color tint from EffectShaderConfig matching the permanent export shader,
 * while keeping RetroEffectType.NONE completely clear with zero overlays.
 */
@Composable
private fun LiveCameraViewfinder(
    cameraPreviewController: CameraPreviewController?,
    cameraRecordingController: CameraRecordingController?,
    hasCameraPermission: Boolean,
    isCameraDisconnected: Boolean,
    studioState: StudioState,
    cameraBindRetryCounter: Int,
    onRequestCameraPermission: () -> Unit,
    isMirrored: Boolean,
    framingMode: CameraFramingMode,
    effect: RetroEffectType,
    onPreviewViewAvailable: (PreviewView) -> Unit = {}
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewTint = remember(effect) { EffectShaderConfig.getSpec(effect).previewTintColor }
    var tapFocusPoint by remember { mutableStateOf<Offset?>(null) }
    var tapFocusSupported by remember { mutableStateOf(true) }

    LaunchedEffect(tapFocusPoint) {
        if (tapFocusPoint != null) {
            delay(1100L)
            tapFocusPoint = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TikTokBlack)
    ) {
        if (cameraPreviewController != null && hasCameraPermission) {
            var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
            val isPreviewActive by cameraPreviewController.isPreviewActive.collectAsStateWithLifecycle()
            var handledRetryCounter by remember { mutableIntStateOf(cameraBindRetryCounter) }

            LaunchedEffect(
                previewViewRef,
                lifecycleOwner,
                hasCameraPermission,
                isCameraDisconnected,
                studioState,
                cameraBindRetryCounter
            ) {
                val view = previewViewRef ?: return@LaunchedEffect
                val retryRequested = cameraBindRetryCounter != handledRetryCounter
                if (retryRequested) {
                    handledRetryCounter = cameraBindRetryCounter
                    cameraPreviewController.unbind()
                }
                if (!isCameraDisconnected &&
                    (studioState == StudioState.READY || studioState == StudioState.IDLE) &&
                    (!isPreviewActive || retryRequested)
                ) {
                    cameraPreviewController.bindPreview(
                        lifecycleOwner = lifecycleOwner,
                        previewView = view,
                        isMirrored = isMirrored,
                        framingMode = framingMode,
                        recordingController = cameraRecordingController
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(cameraPreviewController, hasCameraPermission) {
                        detectTapGestures { offset ->
                            val view = previewViewRef ?: return@detectTapGestures
                            val started = cameraPreviewController.triggerTapToFocus(view, offset.x, offset.y)
                            tapFocusSupported = started
                            tapFocusPoint = offset
                        }
                    }
            ) {
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            scaleType = when (framingMode) {
                                CameraFramingMode.PORTRAIT_CROP -> PreviewView.ScaleType.FILL_CENTER
                                CameraFramingMode.WIDE_FIT -> PreviewView.ScaleType.FIT_CENTER
                            }
                            previewViewRef = this
                            onPreviewViewAvailable(this)
                        }
                    },
                    update = { view ->
                        if (previewViewRef !== view) {
                            previewViewRef = view
                            onPreviewViewAvailable(view)
                        }
                        cameraPreviewController.setFramingMode(view, framingMode)
                        cameraPreviewController.setMirrored(view, isMirrored)
                    },
                    modifier = Modifier.fillMaxSize()
                )

                tapFocusPoint?.let { pt ->
                    val ringColor = if (tapFocusSupported) ElectricCyan else RetroGold
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(pt.x.toInt() - 32, pt.y.toInt() - 32) }
                            .size(64.dp)
                            .border(2.dp, ringColor, CircleShape)
                    )
                }
            }

            DisposableEffect(lifecycleOwner) {
                onDispose {
                    cameraPreviewController.unbind()
                }
            }
        } else if (!hasCameraPermission) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Camera permission is required for live recording",
                    color = TikTokWhite,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Button(
                    onClick = onRequestCameraPermission,
                    colors = ButtonDefaults.buttonColors(containerColor = TikTokRed),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Grant Camera Access", color = TikTokWhite, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (previewTint != Color.Transparent) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(previewTint)
            )
        }
    }
}
