package com.example.ui.screens.music

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.audio.AudioPreviewManager
import com.example.data.local.storage.MusicStorageManager
import com.example.model.RetroDecade
import com.example.model.SongItem
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.NeonMagenta
import com.example.ui.theme.PhosphorGreen
import com.example.ui.theme.RetroGold
import com.example.ui.theme.RetroOnyx
import com.example.ui.theme.RetroSurface
import com.example.ui.theme.RetroSurfaceBorder
import com.example.ui.theme.RetroSurfaceElevated
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

@Composable
fun SongPickerSheet(
    songs: List<SongItem>,
    selectedSong: SongItem?,
    audioPreviewManager: AudioPreviewManager,
    storageManager: MusicStorageManager,
    onSongSelected: (SongItem) -> Unit,
    onCloseClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedDecade by remember { mutableStateOf(RetroDecade.ALL) }

    val playbackState by audioPreviewManager.playbackState.collectAsStateWithLifecycle()

    // Stop audio preview when leaving sheet
    DisposableEffect(Unit) {
        onDispose {
            audioPreviewManager.stop()
        }
    }

    // Only approved songs are shown in guest flow
    val approvedSongs = remember(songs) {
        songs.filter { it.isApprovedForGuestUse }
    }

    val filteredSongs = remember(approvedSongs, searchQuery, selectedDecade) {
        approvedSongs.filter { song ->
            val matchesDecade = selectedDecade == RetroDecade.ALL || song.decade == selectedDecade
            val matchesQuery = searchQuery.isBlank() ||
                song.title.contains(searchQuery, ignoreCase = true) ||
                song.artist.contains(searchQuery, ignoreCase = true) ||
                song.genre.contains(searchQuery, ignoreCase = true)
            matchesDecade && matchesQuery
        }
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("song_picker_sheet"),
        color = TikTokBlack
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 16.dp, start = 20.dp, end = 20.dp)
        ) {
            // Header Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Sounds",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = TikTokWhite
                    )
                    Text(
                        text = "Select a soundtrack for your video",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TikTokGray
                    )
                }

                IconButton(
                    onClick = {
                        audioPreviewManager.stop()
                        onCloseClick()
                    },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(TikTokDark)
                        .testTag("song_picker_close_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Picker",
                        tint = TikTokWhite,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Decade Filter Tabs
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                val decades = listOf(
                    RetroDecade.ALL,
                    RetroDecade.DECADE_70S,
                    RetroDecade.DECADE_80S,
                    RetroDecade.DECADE_90S,
                    RetroDecade.DECADE_00S
                )
                items(decades) { decade ->
                    val isSelected = selectedDecade == decade

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isSelected) TikTokWhite else TikTokDark)
                            .clickable { selectedDecade = decade }
                            .padding(horizontal = 14.dp, vertical = 7.dp)
                            .testTag("decade_chip_${decade.name}")
                    ) {
                        Text(
                            text = decade.label,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            color = if (isSelected) TikTokBlack else TikTokWhite
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Search Text Field
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search songs, artists, or genres...", color = TikTokGray) },
                leadingIcon = {
                    Icon(imageVector = Icons.Default.Search, contentDescription = null, tint = TikTokGray)
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = TikTokDark,
                    unfocusedContainerColor = TikTokDark,
                    focusedBorderColor = TikTokWhite,
                    unfocusedBorderColor = Color(0xFF292929),
                    focusedTextColor = TikTokWhite,
                    unfocusedTextColor = TikTokWhite
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("song_picker_search_input")
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Empty State
            if (approvedSongs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.MusicOff,
                            contentDescription = null,
                            tint = TikTokGray,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No approved soundtracks yet",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = TikTokWhite
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Import and approve MP3/AAC songs in the Admin Console to build your music catalog.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TikTokGray,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else if (filteredSongs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No songs found matching \"$searchQuery\"",
                        color = TikTokGray,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag("song_picker_list")
                ) {
                    items(filteredSongs) { song ->
                        val isCurrentSelected = selectedSong?.id == song.id
                        val isPreviewingThis = playbackState.activeSongId == song.id && playbackState.isPlaying

                        SongRowItem(
                            song = song,
                            isSelected = isCurrentSelected,
                            isPreviewing = isPreviewingThis,
                            isBuffering = playbackState.activeSongId == song.id && playbackState.isBuffering,
                            onPreviewToggle = {
                                if (song.audioRelativePath.isNotBlank()) {
                                    val file = storageManager.getAbsoluteFile(song.audioRelativePath)
                                    audioPreviewManager.togglePlaySong(song, file)
                                }
                            },
                            onSelectClick = {
                                audioPreviewManager.stop()
                                onSongSelected(song)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SongRowItem(
    song: SongItem,
    isSelected: Boolean,
    isPreviewing: Boolean,
    isBuffering: Boolean,
    onPreviewToggle: () -> Unit,
    onSelectClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(TikTokDark)
            .border(
                1.dp,
                if (isSelected) TikTokWhite else Color.Transparent,
                RoundedCornerShape(12.dp)
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Preview Play/Pause Button
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (isPreviewing) TikTokRed else Color(0xFF292929))
                .clickable(onClick = onPreviewToggle)
                .testTag("song_preview_button_${song.id}"),
            contentAlignment = Alignment.Center
        ) {
            if (isBuffering) {
                CircularProgressIndicator(
                    color = TikTokWhite,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp)
                )
            } else {
                Icon(
                    imageVector = if (isPreviewing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "Preview Audio",
                    tint = TikTokWhite,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Song Info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                color = TikTokWhite,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${song.artist} • ${song.durationSeconds}s",
                style = MaterialTheme.typography.bodyMedium,
                color = TikTokGray,
                maxLines = 1
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Select / Use Button
        Button(
            onClick = onSelectClick,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isSelected) Color(0xFF292929) else TikTokRed
            ),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            modifier = Modifier.testTag("song_select_button_${song.id}")
        ) {
            Text(
                text = if (isSelected) "Selected" else "Use",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = TikTokWhite
            )
        }
    }
}
