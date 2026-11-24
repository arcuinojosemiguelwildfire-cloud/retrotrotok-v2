package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.cloud.QrCodeGenerationResult
import com.example.cloud.QrCodeGenerator
import com.example.model.CloudUploadStatus
import com.example.model.VideoItem
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.RetroGold
import com.example.ui.theme.RetroSurface
import com.example.ui.theme.RetroSurfaceBorder
import com.example.ui.theme.RetroSurfaceElevated
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TikTokGray
import com.example.ui.theme.TikTokRed
import com.example.ui.theme.TikTokWhite

/**
 * Reusable guest-facing QR dialog that reacts to live CloudUploadSummary updates
 * and displays either a large scannable QR code or clear progress/fallback messaging.
 */
@Composable
fun VideoQrDialog(
    video: VideoItem,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val summary = video.cloudUploadSummary
    val uploadStatus = summary?.status ?: CloudUploadStatus.LOCAL_ONLY

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = RetroSurface,
            border = BorderStroke(1.dp, RetroSurfaceBorder),
            tonalElevation = 8.dp,
            modifier = modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 400.dp)
                .testTag("video_qr_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(ElectricCyan.copy(alpha = 0.14f))
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCode2,
                        contentDescription = null,
                        tint = ElectricCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "WATCH & DOWNLOAD",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.8.sp,
                        color = ElectricCyan
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "@${video.creatorNickname}",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "${video.song.title} • ${video.song.artist}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(12.dp))

                when (uploadStatus) {
                    CloudUploadStatus.UPLOADED -> {
                        val viewingUrl = summary?.viewingUrl
                        if (viewingUrl.isNullOrBlank()) {
                            QrStatusMessageCard(
                                icon = Icons.Default.CloudOff,
                                iconTint = RetroGold,
                                title = "Watch Link Unavailable",
                                message = "This video finished uploading, but its online watch link is not available on this kiosk.",
                                testTag = "qr_dialog_unavailable_message"
                            )
                        } else {
                            val qrResult = remember(viewingUrl) {
                                QrCodeGenerator.generate(viewingUrl, sizePx = QrCodeGenerator.DEFAULT_QR_SIZE_PX)
                            }
                            when (qrResult) {
                                is QrCodeGenerationResult.Success -> {
                                    val imageBitmap = remember(qrResult.bitmap) {
                                        qrResult.bitmap.asImageBitmap()
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(210.dp)
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(Color.White)
                                            .padding(8.dp)
                                            .testTag("qr_dialog_code_container"),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Image(
                                            bitmap = imageBitmap,
                                            contentDescription = "QR code to watch @${video.creatorNickname} video",
                                            contentScale = ContentScale.Fit,
                                            filterQuality = FilterQuality.None,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .testTag("qr_dialog_bitmap")
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))

                                    Text(
                                        text = "Scan with your phone camera to watch & download",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = TextPrimary,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "No login required • Available for 30 days",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = TextMuted,
                                        textAlign = TextAlign.Center
                                    )
                                }
                                is QrCodeGenerationResult.InvalidUrl,
                                is QrCodeGenerationResult.EncodingError -> {
                                    QrStatusMessageCard(
                                        icon = Icons.Default.ErrorOutline,
                                        iconTint = TikTokRed,
                                        title = "QR Code Unavailable",
                                        message = "The watch link for this video could not be encoded into a scannable QR code.",
                                        testTag = "qr_dialog_unavailable_message"
                                    )
                                }
                            }
                        }
                    }

                    CloudUploadStatus.QUEUED -> {
                        QrProgressCard(
                            title = "Waiting for Internet",
                            message = "Your video is saved on the kiosk. Once connected online, your scannable QR code will appear here automatically.",
                            indicatorColor = RetroGold
                        )
                    }

                    CloudUploadStatus.REQUESTING_UPLOAD,
                    CloudUploadStatus.UPLOADING,
                    CloudUploadStatus.VERIFYING -> {
                        QrProgressCard(
                            title = "Preparing Share Link...",
                            message = "Uploading your video to the cloud. Your scannable QR code will appear automatically once finished.",
                            indicatorColor = ElectricCyan
                        )
                    }

                    CloudUploadStatus.RETRY_PENDING -> {
                        QrStatusMessageCard(
                            icon = Icons.Default.Schedule,
                            iconTint = RetroGold,
                            title = "Upload Retry Scheduled",
                            message = "The cloud upload was interrupted and will retry automatically. Please check back in a moment.",
                            detail = summary?.lastErrorMessage,
                            testTag = "qr_dialog_retry_pending"
                        )
                    }

                    CloudUploadStatus.FAILED -> {
                        QrStatusMessageCard(
                            icon = Icons.Default.ErrorOutline,
                            iconTint = TikTokRed,
                            title = "Cloud Upload Failed",
                            message = "Your video is saved locally on the kiosk, but the online watch link could not be generated yet. Ask an event organizer to retry in Admin Console.",
                            detail = summary?.lastErrorMessage,
                            testTag = "qr_dialog_failed"
                        )
                    }

                    CloudUploadStatus.LOCAL_ONLY -> {
                        QrStatusMessageCard(
                            icon = Icons.Default.Lock,
                            iconTint = TikTokGray,
                            title = "Saved Locally Only",
                            message = "This video is stored on the kiosk only. Online QR sharing requires cloud sync to be paired in Admin Settings.",
                            testTag = "qr_dialog_local_only"
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = TikTokRed),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("qr_dialog_done_button")
                ) {
                    Text(
                        text = "Done",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = TikTokWhite
                    )
                }
            }
        }
    }
}

@Composable
private fun QrProgressCard(
    title: String,
    message: String,
    indicatorColor: Color
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(RetroSurfaceElevated)
            .padding(horizontal = 20.dp, vertical = 26.dp)
            .testTag("qr_dialog_progress"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(indicatorColor.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                color = indicatorColor,
                strokeWidth = 3.5.dp,
                modifier = Modifier.size(36.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = TextPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun QrStatusMessageCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    title: String,
    message: String,
    testTag: String,
    detail: String? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(RetroSurfaceElevated)
            .padding(horizontal = 20.dp, vertical = 24.dp)
            .testTag(testTag),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(iconTint.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(32.dp)
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = TextPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
        if (!detail.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}
