package com.example.ui.screens.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.CloudPairingState
import com.example.model.CloudPairingStatus
import com.example.model.CloudQueueCounts
import com.example.model.CloudUploadStatus
import com.example.model.CloudUploadSummary
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.NeonMagenta
import com.example.ui.theme.PhosphorGreen
import com.example.ui.theme.RetroGold
import com.example.ui.theme.RetroSurface
import com.example.ui.theme.RetroSurfaceBorder
import com.example.ui.theme.RetroSurfaceElevated
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TikTokRed

@Composable
fun CloudSyncOverviewCard(
    pairingState: CloudPairingState,
    queueCounts: CloudQueueCounts,
    isOnline: Boolean,
    isSyncing: Boolean,
    onSyncPendingClick: () -> Unit,
    onConfigureClick: () -> Unit
) {
    val statusColor = when (pairingState.pairingStatus) {
        CloudPairingStatus.PAIRED -> PhosphorGreen
        CloudPairingStatus.PAIRING -> ElectricCyan
        CloudPairingStatus.PAIRING_FAILED,
        CloudPairingStatus.REVOKED -> TikTokRed
        CloudPairingStatus.UNPAIRED -> RetroGold
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = RetroSurfaceElevated),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, statusColor.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
            .testTag("admin_cloud_overview_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (pairingState.isPairedAndAuthorized) Icons.Default.CloudDone else Icons.Default.CloudOff,
                        contentDescription = "Cloud pairing status",
                        tint = statusColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "CLOUD SYNC & R2 BACKUP",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = statusColor
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isOnline) Icons.Default.Wifi else Icons.Default.WifiOff,
                        contentDescription = if (isOnline) "Online" else "Offline",
                        tint = if (isOnline) PhosphorGreen else RetroGold,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isOnline) "ONLINE" else "OFFLINE",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isOnline) PhosphorGreen else RetroGold
                    )
                }
            }

            Text(
                text = if (pairingState.isPairedAndAuthorized) {
                    "Paired: ${pairingState.pairedEventName ?: "Event #${pairingState.pairedEventId}"} (${pairingState.pairedEventStatus ?: "VERIFIED"})"
                } else {
                    "Status: ${pairingState.pairingStatus.label} — Local offline kiosk active"
                },
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )

            Text(
                text = "Uploaded: ${queueCounts.uploadedCount} • Pending: ${queueCounts.pendingOrRetryCount} • Failed: ${queueCounts.failedCount} • Local-only: ${queueCounts.localOnlyCount}",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = TextSecondary,
                modifier = Modifier.testTag("cloud_queue_summary")
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onConfigureClick,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("admin_open_cloud_settings_button")
                ) {
                    Text(
                        text = if (pairingState.isPairedAndAuthorized) "Manage Pairing" else "Pair Device",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = ElectricCyan
                    )
                }

                if (pairingState.isPairedAndAuthorized) {
                    Button(
                        onClick = onSyncPendingClick,
                        enabled = !isSyncing,
                        colors = ButtonDefaults.buttonColors(containerColor = PhosphorGreen),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("admin_overview_sync_button")
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                color = Color.Black,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(14.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.CloudUpload,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isSyncing) "Syncing..." else "Sync Pending",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CloudSyncPairingCard(
    pairingState: CloudPairingState,
    queueCounts: CloudQueueCounts,
    isOnline: Boolean,
    isSyncing: Boolean,
    isBusy: Boolean,
    actionMessage: String?,
    onPairDevice: (pairingCode: String, eventIdRaw: String, deviceName: String) -> Unit,
    onTestHeartbeat: () -> Unit,
    onSyncPending: () -> Unit,
    onUnpairDevice: () -> Unit,
    onDismissActionMessage: () -> Unit
) {
    var pairingCode by remember { mutableStateOf("") }
    var showPairingCode by remember { mutableStateOf(false) }
    var eventIdText by remember(pairingState.pairedEventId) {
        mutableStateOf(pairingState.pairedEventId?.toString() ?: "")
    }
    var deviceName by remember(pairingState.deviceName) {
        mutableStateOf(pairingState.deviceName)
    }

    val statusColor = when (pairingState.pairingStatus) {
        CloudPairingStatus.PAIRED -> PhosphorGreen
        CloudPairingStatus.PAIRING -> ElectricCyan
        CloudPairingStatus.PAIRING_FAILED,
        CloudPairingStatus.REVOKED -> TikTokRed
        CloudPairingStatus.UNPAIRED -> RetroGold
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = RetroSurfaceElevated),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("cloud_pairing_settings_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "CLOUD PAIRING & UPLOAD SYNC",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = ElectricCyan
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(statusColor.copy(alpha = 0.2f))
                        .border(1.dp, statusColor, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                        .testTag("cloud_pairing_status_pill")
                ) {
                    Text(
                        text = pairingState.pairingStatus.name,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = statusColor
                    )
                }
            }

            Text(
                text = "Endpoint: https://wildfirexstream.com/retrotok/api/v1/ (${if (isOnline) "Network Online" else "Offline — Local Queue Ready"})",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = if (isOnline) PhosphorGreen else RetroGold
            )

            Text(
                text = "Device Identifier: ${pairingState.deviceIdentifier}",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = TextMuted
            )

            if (actionMessage != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(RetroSurface)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = actionMessage,
                        fontSize = 12.sp,
                        color = TextPrimary,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("cloud_action_message")
                    )
                    IconButton(onClick = onDismissActionMessage, modifier = Modifier.size(24.dp)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss cloud status message",
                            tint = TextSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            pairingState.lastErrorMessage?.let { err ->
                Text(
                    text = err,
                    fontSize = 11.sp,
                    color = TikTokRed,
                    modifier = Modifier.testTag("cloud_last_error_text")
                )
            }

            if (pairingState.isPairedAndAuthorized) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(RetroSurface)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Device #${pairingState.deviceId ?: "-"} • ${pairingState.deviceName}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = TextPrimary
                    )
                    Text(
                        text = "Event #${pairingState.pairedEventId}: ${pairingState.pairedEventName ?: "Verified"} (${pairingState.pairedEventStatus ?: "UNKNOWN"})",
                        fontSize = 12.sp,
                        color = ElectricCyan
                    )
                    pairingState.remainingCapacity?.let { cap ->
                        Text(
                            text = "Event Remaining Cloud Capacity: $cap video(s)",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = TextSecondary
                        )
                    }
                    Text(
                        text = "Last Heartbeat: ${pairingState.lastHeartbeatAt ?: "Pending"}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = TextMuted
                    )
                    Text(
                        text = "Queue — Uploaded: ${queueCounts.uploadedCount} | Queued/Retry: ${queueCounts.queuedCount + queueCounts.retryPendingCount} | Uploading: ${queueCounts.uploadingCount} | Failed: ${queueCounts.failedCount}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = PhosphorGreen
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onTestHeartbeat,
                        enabled = !isBusy,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("cloud_heartbeat_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudSync,
                            contentDescription = null,
                            tint = ElectricCyan,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Heartbeat", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ElectricCyan)
                    }

                    Button(
                        onClick = onSyncPending,
                        enabled = !isBusy && !isSyncing,
                        colors = ButtonDefaults.buttonColors(containerColor = PhosphorGreen),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("cloud_sync_pending_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isSyncing) "Syncing..." else "Sync Pending",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                    }
                }

                OutlinedButton(
                    onClick = onUnpairDevice,
                    enabled = !isBusy,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cloud_unpair_button")
                ) {
                    Text(
                        text = "Unpair Device (Preserves All Local Videos)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TikTokRed
                    )
                }
            } else {
                OutlinedTextField(
                    value = eventIdText,
                    onValueChange = { raw -> eventIdText = raw.filter { it.isDigit() }.take(12) },
                    label = { Text("Target Event ID (numeric)") },
                    placeholder = { Text("e.g. 1") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = ElectricCyan,
                        unfocusedBorderColor = RetroSurfaceBorder
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cloud_event_id_input")
                )

                OutlinedTextField(
                    value = pairingCode,
                    onValueChange = { if (it.length <= 64) pairingCode = it },
                    label = { Text("Organizer Pairing Code") },
                    placeholder = { Text("Enter one-time pairing code") },
                    singleLine = true,
                    visualTransformation = if (showPairingCode) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPairingCode = !showPairingCode }) {
                            Icon(
                                imageVector = if (showPairingCode) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showPairingCode) "Hide pairing code" else "Show pairing code",
                                tint = TextSecondary
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = NeonMagenta,
                        unfocusedBorderColor = RetroSurfaceBorder
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cloud_pairing_code_input")
                )

                OutlinedTextField(
                    value = deviceName,
                    onValueChange = { if (it.length <= 60) deviceName = it },
                    label = { Text("Kiosk Device Name") },
                    placeholder = { Text("Main Ballroom Kiosk") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = ElectricCyan,
                        unfocusedBorderColor = RetroSurfaceBorder
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cloud_device_name_input")
                )

                Button(
                    onClick = { onPairDevice(pairingCode, eventIdText, deviceName) },
                    enabled = !isBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = NeonMagenta),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cloud_pair_button")
                ) {
                    if (isBusy) {
                        CircularProgressIndicator(
                            color = TextPrimary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = if (isBusy) "Pairing & Verifying Event..." else "PAIR KIOSK & VERIFY EVENT",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = TextPrimary
                    )
                }
            }
        }
    }
}

@Composable
fun AdminVideoCloudStatusBadge(
    summary: CloudUploadSummary?,
    isPaired: Boolean,
    onRetryUpload: () -> Unit,
    videoId: String
) {
    val status = summary?.status ?: CloudUploadStatus.LOCAL_ONLY
    val (badgeColor, label) = when (status) {
        CloudUploadStatus.UPLOADED -> PhosphorGreen to "CLOUD READY"
        CloudUploadStatus.REQUESTING_UPLOAD -> ElectricCyan to "REGISTERING"
        CloudUploadStatus.UPLOADING -> ElectricCyan to "UPLOADING"
        CloudUploadStatus.VERIFYING -> ElectricCyan to "VERIFYING"
        CloudUploadStatus.QUEUED -> RetroGold to "QUEUED"
        CloudUploadStatus.RETRY_PENDING -> RetroGold to "RETRY SCHEDULED"
        CloudUploadStatus.FAILED -> TikTokRed to "UPLOAD FAILED"
        CloudUploadStatus.LOCAL_ONLY -> TextMuted to "LOCAL ONLY"
    }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(badgeColor.copy(alpha = 0.18f))
                    .border(1.dp, badgeColor.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .testTag("admin_video_cloud_status_$videoId")
            ) {
                Text(
                    text = label,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = badgeColor
                )
            }

            if (isPaired && (status == CloudUploadStatus.FAILED ||
                    status == CloudUploadStatus.RETRY_PENDING ||
                    status == CloudUploadStatus.QUEUED ||
                    status == CloudUploadStatus.LOCAL_ONLY)
            ) {
                IconButton(
                    onClick = onRetryUpload,
                    modifier = Modifier
                        .size(26.dp)
                        .testTag("admin_retry_upload_$videoId")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Retry cloud upload",
                        tint = ElectricCyan,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }

        if (status == CloudUploadStatus.UPLOADED && !summary?.viewingUrl.isNullOrBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Link,
                    contentDescription = null,
                    tint = PhosphorGreen,
                    modifier = Modifier.size(11.dp)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = summary?.viewingUrl ?: "",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = PhosphorGreen,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else if (!summary?.lastErrorMessage.isNullOrBlank() &&
            (status == CloudUploadStatus.FAILED || status == CloudUploadStatus.RETRY_PENDING)
        ) {
            Text(
                text = summary?.lastErrorMessage ?: "",
                fontSize = 10.sp,
                color = if (status == CloudUploadStatus.FAILED) TikTokRed else RetroGold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
