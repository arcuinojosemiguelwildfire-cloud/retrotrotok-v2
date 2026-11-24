package com.example.ui.screens.hardware

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.HardwareDiagnosticState
import com.example.model.HardwareValidationCheck
import com.example.model.ValidationStatus
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.NeonMagenta
import com.example.ui.theme.PhosphorGreen
import com.example.ui.theme.RetroGold
import com.example.ui.theme.RetroOnyx
import com.example.ui.theme.RetroSurfaceBorder
import com.example.ui.theme.RetroSurfaceElevated
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun HardwareValidationScreen(
    uiState: HardwareDiagnosticState,
    onRunDiagnostics: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler {
        onBackClick()
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("hardware_validation_screen"),
        color = RetroOnyx
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 24.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(RetroSurfaceElevated)
                            .testTag("hardware_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "HARDWARE SPIKE",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
                            color = ElectricCyan
                        )
                        Text(
                            text = "Logitech USB Webcam Validation Matrix",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }

                Button(
                    onClick = onRunDiagnostics,
                    enabled = !uiState.isRunningDiagnostics,
                    colors = ButtonDefaults.buttonColors(containerColor = NeonMagenta),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.testTag("run_diagnostics_button")
                ) {
                    if (uiState.isRunningDiagnostics) {
                        CircularProgressIndicator(color = TextPrimary, modifier = Modifier.size(16.dp))
                    } else {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "SWEEP", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Detected Device Summary Banner
            Card(
                colors = CardDefaults.cardColors(containerColor = RetroSurfaceElevated),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, ElectricCyan, RoundedCornerShape(18.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(ElectricCyan.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(imageVector = Icons.Default.Usb, contentDescription = null, tint = ElectricCyan)
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "CAMERA CAPTURE & DIAGNOSTICS",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = PhosphorGreen
                                )
                                Text(
                                    text = uiState.detectedDeviceName,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = TextPrimary
                                )
                                Text(
                                    text = uiState.boundCameraSummary,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = TextSecondary
                                )
                            }
                        }

                        // State badge
                        val stateColor = when (uiState.connectionState) {
                            com.example.camera.CameraConnectionState.CONNECTED -> PhosphorGreen
                            com.example.camera.CameraConnectionState.CONNECTING -> ElectricCyan
                            com.example.camera.CameraConnectionState.PERMISSION_REQUIRED,
                            com.example.camera.CameraConnectionState.UNSUPPORTED -> RetroGold
                            com.example.camera.CameraConnectionState.DISCONNECTED -> Color.Red
                            else -> TextMuted
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(stateColor.copy(alpha = 0.2f))
                                .border(1.dp, stateColor, RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = uiState.connectionState.name,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black,
                                color = stateColor
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Hardware capabilities row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "CAPTURE BACKEND", fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = TextMuted)
                            Text(
                                text = when (uiState.selectedBackend) {
                                    com.example.camera.CameraBackendType.CAMERA2_EXTERNAL -> "Camera2 External HAL"
                                    com.example.camera.CameraBackendType.NATIVE_USB_UVC -> "USB UVC (Unsupported)"
                                    com.example.camera.CameraBackendType.CAMERA2_INTERNAL -> "Camera2 On-Board"
                                    com.example.camera.CameraBackendType.DEV_PREVIEW -> "Unbound / Dev Mode"
                                },
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = if (uiState.selectedBackend == com.example.camera.CameraBackendType.NATIVE_USB_UVC) RetroGold else ElectricCyan
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "NEGOTIATED STREAM", fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = TextMuted)
                            Text(text = uiState.activeResolution, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = TextPrimary)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "FOCUS / ENCODER", fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = TextMuted)
                            Text(
                                text = uiState.focusCapabilitySummary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                color = PhosphorGreen
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 8 Validation Checks List
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("hardware_checks_list")
            ) {
                items(uiState.checks) { check ->
                    HardwareCheckCard(check = check)
                }
            }
        }
    }
}

@Composable
private fun HardwareCheckCard(check: HardwareValidationCheck) {
    val (statusColor, statusLabel) = when (check.status) {
        ValidationStatus.PASSED -> PhosphorGreen to "PASSED"
        ValidationStatus.WARNING -> RetroGold to "PHYSICAL CHECK"
        ValidationStatus.FAILED -> Color.Red to "FAILED"
        ValidationStatus.RUNNING -> ElectricCyan to "TESTING"
        ValidationStatus.PENDING -> TextMuted to "PENDING"
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = RetroSurfaceElevated),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = check.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(statusColor.copy(alpha = 0.15f))
                        .border(1.dp, statusColor, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = statusLabel,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        color = statusColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = check.description,
                fontSize = 12.sp,
                color = TextSecondary
            )

            if (check.diagnosticDetails.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0F0B18))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = check.diagnosticDetails,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = if (check.status == ValidationStatus.WARNING) RetroGold else TextPrimary
                    )
                }
            }
        }
    }
}
