package com.example.ui.screens.feed

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.repository.VideoSearchSort
import com.example.model.VideoItem
import com.example.ui.theme.TikTokBlack
import com.example.ui.theme.TikTokDark
import com.example.ui.theme.TikTokGray
import com.example.ui.theme.TikTokRed
import com.example.ui.theme.TikTokWhite
import java.io.File

/**
 * Local Offline Discovery & Search overlay styled in flat TikTok dark UI.
 * Searches persisted Room videos by nickname, caption, hashtags, soundtrack title, and artist.
 */
@Composable
fun DiscoverVideosSheet(
    query: String,
    activeSort: VideoSearchSort,
    results: List<VideoItem>,
    onQueryChange: (String) -> Unit,
    onSortSelected: (VideoSearchSort) -> Unit,
    onVideoSelected: (VideoItem) -> Unit,
    onBrowseSoundsClick: () -> Unit,
    onCloseClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler {
        onCloseClick()
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("discover_videos_sheet"),
        color = TikTokBlack
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 20.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Discover",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                    color = TikTokWhite
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(TikTokDark)
                            .border(1.dp, Color(0xFF292929), RoundedCornerShape(18.dp))
                            .clickable(onClick = onBrowseSoundsClick)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .testTag("discover_browse_sounds_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = TikTokRed,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Sounds",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TikTokWhite
                        )
                    }

                    IconButton(
                        onClick = onCloseClick,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(TikTokDark)
                            .testTag("discover_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Discover",
                            tint = TikTokWhite
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Local Search Input
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = {
                    Text(
                        text = "Search dancers, #hashtags, or soundtracks",
                        color = TikTokGray,
                        fontSize = 13.sp
                    )
                },
                leadingIcon = {
                    Icon(imageVector = Icons.Default.Search, contentDescription = null, tint = TikTokGray)
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "Clear search", tint = TikTokGray)
                        }
                    }
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
                    .testTag("discover_search_input")
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Sort / Filter Chips
            val filters = listOf(
                VideoSearchSort.NEWEST to "Newest",
                VideoSearchSort.MOST_LIKED to "Most Liked",
                VideoSearchSort.FAVORITES to "Favorites",
                VideoSearchSort.SOUNDTRACK to "Soundtrack"
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(filters) { (sort, label) ->
                    val selected = activeSort == sort
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(if (selected) TikTokWhite else TikTokDark)
                            .clickable { onSortSelected(sort) }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                            .testTag("discover_filter_${sort.name}")
                    ) {
                        Text(
                            text = label,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selected) TikTokBlack else TikTokWhite
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (results.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("discover_empty_state"),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Videocam,
                            contentDescription = null,
                            tint = TikTokGray,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = if (query.isBlank()) "No matching event videos" else "No results for \"$query\"",
                            color = TikTokWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Try another dancer name, hashtag, or song title.",
                            color = TikTokGray,
                            fontSize = 12.sp
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(results, key = { it.id }) { video ->
                        DiscoverVideoCard(
                            video = video,
                            onClick = { onVideoSelected(video) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoverVideoCard(
    video: VideoItem,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val thumbFile = remember(video.thumbnailPath) {
        video.thumbnailPath?.let { path ->
            val file = if (path.startsWith("/")) File(path) else File(context.filesDir, path)
            if (file.exists() && file.length() > 0L) file else null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(9f / 14f)
            .clip(RoundedCornerShape(12.dp))
            .background(TikTokDark)
            .clickable(onClick = onClick)
            .testTag("discover_video_card_${video.id}")
    ) {
        if (thumbFile != null) {
            AsyncImage(
                model = thumbFile,
                contentDescription = "@${video.creatorNickname}",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = TikTokGray,
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color(0xDD000000))
                    )
                )
                .padding(10.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = "@${video.creatorNickname}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = TikTokWhite,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = video.song.title,
                    fontSize = 11.sp,
                    color = TikTokGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${video.durationSeconds}s",
                        fontSize = 10.sp,
                        color = TikTokGray
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Favorite,
                            contentDescription = null,
                            tint = TikTokRed,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = video.likeCount.toString(),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TikTokWhite
                        )
                    }
                }
            }
        }
    }
}
