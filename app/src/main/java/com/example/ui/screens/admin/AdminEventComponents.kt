package com.example.ui.screens.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.EventModeStatus
import com.example.model.FeedSortOrder
import com.example.model.KioskSettings
import com.example.model.VideoItem
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun EventOperationsCard(
    settings: KioskSettings,
    metrics: EventMonitoringMetrics,
    isBusy: Boolean,
    actionMessage: String?,
    onSaveDetails: (String, String) -> Unit,
    onStartEvent: (String, String) -> Unit,
    onPauseEvent: () -> Unit,
    onResumeEvent: () -> Unit,
    onRequestEndEvent: () -> Unit,
    onRequestResetStats: () -> Unit,
    onDismissMessage: () -> Unit
) {
    var editingDetails by remember { mutableStateOf(false) }
    var draftName by remember(settings.eventName) { mutableStateOf(settings.eventName) }
    var draftDesc by remember(settings.eventDescription) { mutableStateOf(settings.eventDescription) }
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.US) }

    val statusColor = when (settings.eventModeStatus) {
        EventModeStatus.ACTIVE -> PhosphorGreen
        EventModeStatus.PAUSED -> RetroGold
        EventModeStatus.ENDED -> TikTokRed
        EventModeStatus.NOT_STARTED -> ElectricCyan
    }
    val statusText = when (settings.eventModeStatus) {
        EventModeStatus.ACTIVE -> "ACTIVE (ACCEPTING GUESTS)"
        EventModeStatus.PAUSED -> "PAUSED (GUEST RECORDING BLOCKED)"
        EventModeStatus.ENDED -> "ENDED (RETURNED TO WELCOME)"
        EventModeStatus.NOT_STARTED -> "NOT STARTED"
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = RetroSurfaceElevated),
        shape = RoundedCornerShape(18.dp)
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
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(statusColor.copy(alpha = 0.18f))
                        .border(1.dp, statusColor, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("admin_event_status_pill")
                ) {
                    Text(
                        text = statusText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = statusColor
                    )
                }

                IconButton(
                    onClick = { editingDetails = !editingDetails },
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("admin_edit_event_details_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit Event Details",
                        tint = ElectricCyan,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            if (editingDetails) {
                OutlinedTextField(
                    value = draftName,
                    onValueChange = { if (it.length <= 50) draftName = it },
                    label = { Text("Event Name") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("admin_event_name_input")
                )
                OutlinedTextField(
                    value = draftDesc,
                    onValueChange = { if (it.length <= 120) draftDesc = it },
                    label = { Text("Optional Event Description") },
                    maxLines = 2,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("admin_event_desc_input")
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            onSaveDetails(draftName, draftDesc)
                            editingDetails = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("admin_save_event_details_button")
                    ) {
                        Text("Save Details", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            draftName = settings.eventName
                            draftDesc = settings.eventDescription
                            editingDetails = false
                        },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Cancel", color = TextSecondary, fontSize = 12.sp)
                    }
                }
            } else {
                Text(
                    text = settings.eventName,
                    fontWeight = FontWeight.Black,
                    fontSize = 18.sp,
                    color = TextPrimary
                )
                if (settings.eventDescription.isNotBlank()) {
                    Text(
                        text = settings.eventDescription,
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                } else {
                    Text(
                        text = settings.eventTheme,
                        fontSize = 12.sp,
                        color = TextMuted
                    )
                }
            }

            // Start & End Timestamps + Quick Event Counters
            val startedStr = settings.eventStartedAtMs?.let { dateFormat.format(Date(it)) } ?: "Not recorded"
            val endedStr = settings.eventEndedAtMs?.let { dateFormat.format(Date(it)) } ?: "Active / Ongoing"
            Text(
                text = "Started: $startedStr\nEnded: $endedStr",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = TextMuted
            )
            Text(
                text = "Published: ${metrics.publishedCount} • Recordings: ${metrics.totalRecordingCount} • Event Storage: ${formatBytesReadable(metrics.totalEventStorageBytes)}",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = ElectricCyan
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
                        color = PhosphorGreen,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismissMessage, modifier = Modifier.size(24.dp)) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Dismiss", tint = TextSecondary, modifier = Modifier.size(14.dp))
                    }
                }
            }

            // Event Mode Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (settings.eventModeStatus) {
                    EventModeStatus.NOT_STARTED, EventModeStatus.ENDED -> {
                        Button(
                            onClick = { onStartEvent(draftName, draftDesc) },
                            enabled = !isBusy,
                            colors = ButtonDefaults.buttonColors(containerColor = PhosphorGreen),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("admin_start_event_button")
                        ) {
                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (settings.eventModeStatus == EventModeStatus.ENDED) "Start New Event" else "Start Event",
                                color = Color.Black,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                    EventModeStatus.ACTIVE -> {
                        OutlinedButton(
                            onClick = onPauseEvent,
                            enabled = !isBusy,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("admin_pause_event_button")
                        ) {
                            Icon(imageVector = Icons.Default.Pause, contentDescription = null, tint = RetroGold, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Pause Guests", color = RetroGold, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        Button(
                            onClick = onRequestEndEvent,
                            enabled = !isBusy,
                            colors = ButtonDefaults.buttonColors(containerColor = TikTokRed),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("admin_end_event_button")
                        ) {
                            Icon(imageVector = Icons.Default.Stop, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("End Event", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                    EventModeStatus.PAUSED -> {
                        Button(
                            onClick = onResumeEvent,
                            enabled = !isBusy,
                            colors = ButtonDefaults.buttonColors(containerColor = PhosphorGreen),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("admin_resume_event_button")
                        ) {
                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Resume", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        Button(
                            onClick = onRequestEndEvent,
                            enabled = !isBusy,
                            colors = ButtonDefaults.buttonColors(containerColor = TikTokRed),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("admin_end_event_button")
                        ) {
                            Icon(imageVector = Icons.Default.Stop, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("End Event", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }

            // Reset Event Statistics (preserves all published MP4s and Room rows)
            OutlinedButton(
                onClick = onRequestResetStats,
                enabled = !isBusy,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("admin_reset_stats_button")
            ) {
                Icon(imageVector = Icons.Default.Refresh, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Reset Event Statistics (Preserves All Videos)",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
fun EventMonitoringPanel(
    metrics: EventMonitoringMetrics,
    settings: KioskSettings
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.US) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(
            colors = CardDefaults.cardColors(containerColor = RetroSurfaceElevated),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "REAL-TIME EVENT MONITORING",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = NeonMagenta
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MiniMetricTile("PUBLISHED", "${metrics.publishedCount} (${metrics.hiddenCount} hidden)", Icons.Default.Videocam, NeonMagenta, Modifier.weight(1f))
                    MiniMetricTile("RECORDINGS", "${metrics.totalRecordingCount} (${metrics.recoverableCount} recov)", Icons.Default.Storage, ElectricCyan, Modifier.weight(1f))
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MiniMetricTile("LIKES / FAVS", "${metrics.totalLikesCount} / ${metrics.totalFavoritesCount}", Icons.Default.Favorite, RetroGold, Modifier.weight(1f))
                    MiniMetricTile("TOTAL DURATION", formatDurationSec(metrics.totalDurationSeconds), Icons.Default.Bookmark, PhosphorGreen, Modifier.weight(1f))
                }
                Text(
                    text = "Event Media Used: ${formatBytesReadable(metrics.totalEventStorageBytes)} • Free Device Space: ${formatBytesReadable(metrics.availableDeviceBytes)}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = TextSecondary
                )
                settings.lastSavedEventSummary?.let { summary ->
                    Text(
                        text = "Last Saved Summary (${summary.eventName}): ${summary.publishedVideoCount} published, ${summary.totalLikesCount} likes",
                        fontSize = 11.sp,
                        color = RetroGold
                    )
                }
            }
        }

        // Recent Publication Activity (zero fabricated data)
        Card(
            colors = CardDefaults.cardColors(containerColor = RetroSurfaceElevated),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "RECENT PUBLICATION ACTIVITY",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = ElectricCyan
                )
                if (metrics.recentPublications.isEmpty()) {
                    Text(
                        text = "No publications recorded in this monitoring window yet.",
                        fontSize = 12.sp,
                        color = TextMuted
                    )
                } else {
                    metrics.recentPublications.forEach { video ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "@${video.creatorNickname} • ${video.song.title}",
                                fontSize = 12.sp,
                                color = if (video.isHidden) TextMuted else TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${if (video.isHidden) "HIDDEN • " else ""}${timeFormat.format(Date(video.createdAt))}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = if (video.isHidden) RetroGold else TextMuted
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniMetricTile(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(RetroSurface)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(text = label, fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = TextMuted)
        }
        Text(text = value, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun FeedControlsSettingsSection(
    settings: KioskSettings,
    onUpdateFeedControls: (
        showFeatured: Boolean,
        enableLikes: Boolean,
        enableFavorites: Boolean,
        enableDiscover: Boolean,
        allowDeletion: Boolean,
        sortOrder: FeedSortOrder,
        requireNickname: Boolean
    ) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = RetroSurfaceElevated),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "EVENT FEED & GUEST CONTROLS",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = ElectricCyan
            )

            ControlToggleRow(
                title = "Featured Section Tab",
                subtitle = "Show curated Featured feed tab to guests",
                checked = settings.showFeaturedSection,
                testTag = "toggle_featured_section",
                onToggle = {
                    onUpdateFeedControls(
                        it,
                        settings.enableGuestLikes,
                        settings.enableGuestFavorites,
                        settings.enableDiscover,
                        settings.allowGuestDeletion,
                        settings.defaultFeedSort,
                        settings.requireGuestNickname
                    )
                }
            )

            ControlToggleRow(
                title = "Guest Likes",
                subtitle = "Allow heart button and double-tap likes",
                checked = settings.enableGuestLikes,
                testTag = "toggle_guest_likes",
                onToggle = {
                    onUpdateFeedControls(
                        settings.showFeaturedSection,
                        it,
                        settings.enableGuestFavorites,
                        settings.enableDiscover,
                        settings.allowGuestDeletion,
                        settings.defaultFeedSort,
                        settings.requireGuestNickname
                    )
                }
            )

            ControlToggleRow(
                title = "Guest Favorites",
                subtitle = "Allow bookmarking and Favorites tab",
                checked = settings.enableGuestFavorites,
                testTag = "toggle_guest_favorites",
                onToggle = {
                    onUpdateFeedControls(
                        settings.showFeaturedSection,
                        settings.enableGuestLikes,
                        it,
                        settings.enableDiscover,
                        settings.allowGuestDeletion,
                        settings.defaultFeedSort,
                        settings.requireGuestNickname
                    )
                }
            )

            ControlToggleRow(
                title = "Discover & Search Tab",
                subtitle = "Allow guests to browse & search event clips",
                checked = settings.enableDiscover,
                testTag = "toggle_discover",
                onToggle = {
                    onUpdateFeedControls(
                        settings.showFeaturedSection,
                        settings.enableGuestLikes,
                        settings.enableGuestFavorites,
                        it,
                        settings.allowGuestDeletion,
                        settings.defaultFeedSort,
                        settings.requireGuestNickname
                    )
                }
            )

            ControlToggleRow(
                title = "Require Guest Nickname",
                subtitle = if (settings.requireGuestNickname) "Guests must enter a nickname to publish" else "Nickname is optional before publishing",
                checked = settings.requireGuestNickname,
                testTag = "toggle_require_nickname",
                onToggle = {
                    onUpdateFeedControls(
                        settings.showFeaturedSection,
                        settings.enableGuestLikes,
                        settings.enableGuestFavorites,
                        settings.enableDiscover,
                        settings.allowGuestDeletion,
                        settings.defaultFeedSort,
                        it
                    )
                }
            )

            Text(
                text = "DEFAULT FEED SORT ORDER",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = RetroGold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FeedSortOrder.values().forEach { order ->
                    val selected = settings.defaultFeedSort == order
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) NeonMagenta else RetroSurface)
                            .border(1.dp, if (selected) NeonMagenta else RetroSurfaceBorder, RoundedCornerShape(10.dp))
                            .clickable {
                                onUpdateFeedControls(
                                    settings.showFeaturedSection,
                                    settings.enableGuestLikes,
                                    settings.enableGuestFavorites,
                                    settings.enableDiscover,
                                    settings.allowGuestDeletion,
                                    order,
                                    settings.requireGuestNickname
                                )
                            }
                            .padding(vertical = 8.dp)
                            .testTag("sort_order_${order.name}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = order.label,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selected) TextPrimary else TextSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    testTag: String,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TextPrimary)
            Text(text = subtitle, fontSize = 11.sp, color = TextSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(checkedThumbColor = NeonMagenta),
            modifier = Modifier.testTag(testTag)
        )
    }
}

@Composable
fun ModerationVideoDialog(
    video: VideoItem,
    onDismiss: () -> Unit,
    onConfirmModeration: (hide: Boolean, reason: String?) -> Unit
) {
    val willHide = !video.isHidden
    var reason by remember(video.id) { mutableStateOf(video.moderationReason ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RetroSurface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (willHide) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = null,
                    tint = if (willHide) RetroGold else PhosphorGreen,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (willHide) "HIDE FROM GUEST FEED?" else "RESTORE VIDEO TO FEED?",
                    fontWeight = FontWeight.Black,
                    fontSize = 16.sp,
                    color = TextPrimary
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "@${video.creatorNickname} • ${video.song.title}",
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    fontSize = 13.sp
                )
                Text(
                    text = if (willHide) {
                        "Hiding removes this video from the guest feed, Discover, and Favorites while safely preserving its MP4 file and database record for Admin review."
                    } else {
                        "Restoring makes this video visible again in the guest feed and Discover."
                    },
                    color = TextSecondary,
                    fontSize = 12.sp
                )
                if (willHide) {
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { if (it.length <= 120) reason = it },
                        label = { Text("Moderation Reason (optional)") },
                        placeholder = { Text("e.g., Guest requested removal or off-theme") },
                        maxLines = 2,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("moderation_reason_input")
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirmModeration(willHide, reason) },
                colors = ButtonDefaults.buttonColors(containerColor = if (willHide) RetroGold else PhosphorGreen),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("confirm_moderation_button")
            ) {
                Text(
                    text = if (willHide) "HIDE VIDEO" else "RESTORE VIDEO",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = TextSecondary)
            }
        }
    )
}

@Composable
fun EndEventConfirmationDialog(
    isStudioBusy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RetroSurface,
        title = {
            Text(
                text = "END CURRENT EVENT?",
                fontWeight = FontWeight.Black,
                color = TikTokRed
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Ending the event will stop accepting new guest recording sessions, save a local event summary, and return the kiosk to the Welcome screen.",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
                Text(
                    text = "All published videos and recoverable takes remain 100% preserved.",
                    color = PhosphorGreen,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
                if (isStudioBusy) {
                    Text(
                        text = "An active guest recording or video processing job is running — it will be allowed to finish safely before switching back to Welcome.",
                        color = RetroGold,
                        fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = TikTokRed),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("confirm_end_event_button")
            ) {
                Text("END EVENT", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = TextSecondary)
            }
        }
    )
}

@Composable
fun ResetEventStatsConfirmationDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RetroSurface,
        title = {
            Text(
                text = "RESET EVENT STATISTICS?",
                fontWeight = FontWeight.Black,
                color = RetroGold
            )
        },
        text = {
            Text(
                text = "This resets the monitoring counters and clears the saved summary baseline for a new event window. Published MP4 files and Room video records will NEVER be deleted.",
                color = TextSecondary,
                fontSize = 13.sp
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = RetroGold),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("confirm_reset_stats_button")
            ) {
                Text("RESET STATS ONLY", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = TextSecondary)
            }
        }
    )
}

private fun formatDurationSec(totalSeconds: Int): String {
    val mins = totalSeconds / 60
    val secs = totalSeconds % 60
    return if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
}

private fun formatBytesReadable(bytes: Long): String {
    if (bytes <= 0L) return "0 KB"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
        mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
        else -> String.format(Locale.US, "%.0f KB", kb)
    }
}
