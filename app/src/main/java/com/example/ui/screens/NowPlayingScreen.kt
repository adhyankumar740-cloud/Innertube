package com.example.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.LyricLine
import com.example.data.model.Lyrics
import com.example.data.model.Track
import com.example.player.RepeatMode
import com.example.util.ThumbnailUtils
import java.util.concurrent.TimeUnit

private enum class NowPlayingTab { QUEUE, LYRICS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    track: Track,
    isPlaying: Boolean,
    isBuffering: Boolean,
    isResolvingAutoplay: Boolean,
    playbackPos: Long,
    duration: Long,
    queue: List<Track>,
    queueIndex: Int,
    isShuffleEnabled: Boolean,
    repeatMode: RepeatMode,
    lyrics: Lyrics?,
    isLoadingLyrics: Boolean,
    sleepTimerEndsAt: Long?,
    onSleepTimerSet: (minutes: Int) -> Unit,
    onSleepTimerCancel: () -> Unit,
    playbackSpeed: Float,
    onPlaybackSpeedSet: (Float) -> Unit,
    onPlayPauseClick: () -> Unit,
    onPrevClick: () -> Unit,
    onNextClick: () -> Unit,
    onSeek: (Long) -> Unit,
    onFavoriteClick: () -> Unit,
    onAddToPlaylistClick: () -> Unit,
    onShuffleClick: () -> Unit,
    onRepeatClick: () -> Unit,
    onQueueItemClick: (Int) -> Unit,
    onQueueItemRemove: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var activeTab by remember { mutableStateOf<NowPlayingTab?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
        // Soft blurred backdrop of the artwork, tinted to the theme background,
        // so the player has some depth/color instead of a flat black canvas -
        // without ever touching the crisp foreground artwork below, which is
        // what was making thumbnails look blurry before.
        AsyncImage(
            model = ThumbnailUtils.resized(track.artworkUrl, ThumbnailUtils.Size.CARD),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .blur(80.dp)
                .alpha(0.35f)
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.background.copy(alpha = 0.55f),
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.background
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            ) {
                AsyncImage(
                    model = ThumbnailUtils.resized(track.artworkUrl, ThumbnailUtils.Size.PLAYER),
                    contentDescription = "Now playing artwork",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surface)
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = track.title,
                color = Color.White,
                fontSize = 23.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = track.artist,
                color = Color.Gray,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

        var isDragging by remember { mutableStateOf(false) }
        var dragPosition by remember { mutableFloatStateOf(0f) }
        val sliderValue = if (isDragging) dragPosition else (if (duration > 0) playbackPos.toFloat() / duration else 0f)

        Slider(
            value = sliderValue.coerceIn(0f, 1f),
            onValueChange = {
                isDragging = true
                dragPosition = it
            },
            onValueChangeFinished = {
                onSeek((dragPosition * duration).toLong())
                isDragging = false
            },
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = Color.DarkGray
            )
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = formatMillis(if (isDragging) (dragPosition * duration).toLong() else playbackPos), color = Color.Gray, fontSize = 11.sp)
            Text(text = formatMillis(duration), color = Color.Gray, fontSize = 11.sp)
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onShuffleClick) {
                Icon(
                    imageVector = Icons.Default.Shuffle,
                    contentDescription = "Shuffle",
                    tint = if (isShuffleEnabled) MaterialTheme.colorScheme.primary else Color.Gray,
                    modifier = Modifier.size(22.dp)
                )
            }
            IconButton(onClick = onPrevClick) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", tint = Color.White, modifier = Modifier.size(32.dp))
            }

            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(enabled = !isBuffering) { onPlayPauseClick() },
                    contentAlignment = Alignment.Center
                ) {
                    if (isBuffering) {
                        CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.Black,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }

            IconButton(onClick = onNextClick) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = Color.White, modifier = Modifier.size(32.dp))
            }
            IconButton(onClick = onRepeatClick) {
                Icon(
                    imageVector = if (repeatMode == RepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                    contentDescription = "Repeat",
                    tint = if (repeatMode != RepeatMode.OFF) MaterialTheme.colorScheme.primary else Color.Gray,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        if (isResolvingAutoplay) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "Finding something similar to play next...", color = Color.Gray, fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            IconButton(onClick = onFavoriteClick) {
                Icon(
                    imageVector = if (track.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Favorite",
                    tint = if (track.isFavorite) MaterialTheme.colorScheme.tertiary else Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            IconButton(onClick = { activeTab = NowPlayingTab.QUEUE }) {
                Icon(Icons.Default.QueueMusic, contentDescription = "Queue", tint = Color.White, modifier = Modifier.size(24.dp))
            }
            IconButton(onClick = { activeTab = NowPlayingTab.LYRICS }) {
                Icon(Icons.Default.Subtitles, contentDescription = "Lyrics", tint = Color.White, modifier = Modifier.size(24.dp))
            }
            IconButton(onClick = onAddToPlaylistClick) {
                Icon(Icons.Default.PlaylistAdd, contentDescription = "Add to Playlist", tint = Color.White, modifier = Modifier.size(24.dp))
            }

            var speedMenuOpen by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { speedMenuOpen = true }) {
                    Icon(
                        Icons.Default.Speed,
                        contentDescription = "Playback speed",
                        tint = if (playbackSpeed != 1.0f) MaterialTheme.colorScheme.primary else Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                DropdownMenu(expanded = speedMenuOpen, onDismissRequest = { speedMenuOpen = false }) {
                    listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
                        DropdownMenuItem(
                            text = { Text(if (speed == 1.0f) "Normal" else "${speed}x") },
                            onClick = {
                                onPlaybackSpeedSet(speed)
                                speedMenuOpen = false
                            }
                        )
                    }
                }
            }

            var sleepMenuOpen by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { sleepMenuOpen = true }) {
                    Icon(
                        Icons.Default.Bedtime,
                        contentDescription = "Sleep timer",
                        tint = if (sleepTimerEndsAt != null) MaterialTheme.colorScheme.primary else Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                DropdownMenu(expanded = sleepMenuOpen, onDismissRequest = { sleepMenuOpen = false }) {
                    if (sleepTimerEndsAt != null) {
                        val minutesLeft = ((sleepTimerEndsAt - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
                        DropdownMenuItem(
                            text = { Text("Cancel (${minutesLeft}m left)") },
                            onClick = {
                                onSleepTimerCancel()
                                sleepMenuOpen = false
                            }
                        )
                    }
                    listOf(5, 15, 30, 45, 60).forEach { minutes ->
                        DropdownMenuItem(
                            text = { Text("$minutes minutes") },
                            onClick = {
                                onSleepTimerSet(minutes)
                                sleepMenuOpen = false
                            }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (activeTab != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { activeTab = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            dragHandle = {
                Icon(
                    imageVector = Icons.Default.DragHandle,
                    contentDescription = null,
                    tint = Color.Gray,
                    modifier = Modifier.padding(12.dp)
                )
            }
        ) {
            AnimatedContent(targetState = activeTab, label = "now-playing-tab") { tab ->
                when (tab) {
                    NowPlayingTab.QUEUE -> QueueSheetContent(
                        queue = queue,
                        queueIndex = queueIndex,
                        onItemClick = { onQueueItemClick(it); activeTab = null },
                        onItemRemove = onQueueItemRemove
                    )
                    NowPlayingTab.LYRICS -> LyricsSheetContent(
                        lyrics = lyrics,
                        isLoading = isLoadingLyrics,
                        playbackPos = playbackPos
                    )
                    null -> {}
                }
            }
        }
    }
}

@Composable
private fun QueueSheetContent(
    queue: List<Track>,
    queueIndex: Int,
    onItemClick: (Int) -> Unit,
    onItemRemove: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().height(420.dp)) {
        Text(
            text = "Up Next",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )
        val listState = rememberLazyListState()
        LaunchedEffect(queueIndex) {
            if (queueIndex >= 0) listState.animateScrollToItem(queueIndex)
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            items(queue.size) { index ->
                val track = queue[index]
                val isCurrent = index == queueIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isCurrent) Color.White.copy(alpha = 0.08f) else Color.Transparent)
                        .clickable { onItemClick(index) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = ThumbnailUtils.resized(track.artworkUrl, ThumbnailUtils.Size.LIST_ROW),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp))
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.title,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.White,
                            fontSize = 14.sp,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(text = track.artist, color = Color.Gray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (!isCurrent) {
                        IconButton(onClick = { onItemRemove(index) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Remove from queue", tint = Color.Gray, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricsSheetContent(
    lyrics: Lyrics?,
    isLoading: Boolean,
    playbackPos: Long
) {
    Column(
        modifier = Modifier.fillMaxWidth().height(420.dp).padding(horizontal = 20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            Icon(Icons.Default.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "Lyrics", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }

        when {
            isLoading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            lyrics == null || !lyrics.isAvailable -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "Lyrics not available for this track.", color = Color.Gray, textAlign = TextAlign.Center)
            }
            lyrics.synced.isNotEmpty() -> {
                val listState = rememberLazyListState()
                val activeIndex = lyrics.synced.indexOfLast { it.timeMs <= playbackPos }.coerceAtLeast(0)
                LaunchedEffect(activeIndex) {
                    listState.animateScrollToItem((activeIndex - 2).coerceAtLeast(0))
                }
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(lyrics.synced.size) { index ->
                        val line: LyricLine = lyrics.synced[index]
                        val isActive = index == activeIndex
                        val alphaValue by animateFloatAsState(if (isActive) 1f else 0.4f, animationSpec = tween(250), label = "lyric-alpha")
                        Text(
                            text = line.text,
                            color = if (isActive) MaterialTheme.colorScheme.primary else Color.White,
                            fontSize = if (isActive) 17.sp else 15.sp,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .alpha(alphaValue)
                        )
                    }
                }
            }
            else -> {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(lyrics.plain!!.lines()) { line ->
                        Text(
                            text = line,
                            color = Color.White,
                            fontSize = 15.sp,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun formatMillis(ms: Long): String {
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(ms.coerceAtLeast(0))
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%d:%02d", minutes, seconds)
}
