package com.example.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.Track
import com.example.ui.viewmodel.AuthViewModel
import com.example.ui.viewmodel.MusicViewModel
import com.example.ui.viewmodel.PlaylistViewModel
import com.example.util.ThumbnailUtils
import java.util.Calendar

// BUG FIX: this was a hardcoded "Good Evening," string, so the header always
// showed evening greeting no matter what time it actually was. Using
// java.util.Calendar (not java.time.LocalTime) since minSdk 24 has no core
// library desugaring enabled, so java.time isn't safely available.
private fun currentGreeting(): String {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when (hour) {
        in 5..11 -> "Good Morning,"
        in 12..16 -> "Good Afternoon,"
        in 17..20 -> "Good Evening,"
        else -> "Good Night,"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    musicViewModel: MusicViewModel,
    authViewModel: AuthViewModel,
    playlistViewModel: PlaylistViewModel,
    modifier: Modifier = Modifier,
    // Search now lives on its own bottom-nav tab. This just jumps the user
    // there when they tap the search icon in the Home header, instead of
    // Home owning its own search box + results list.
    onSearchClick: () -> Unit = {}
) {
    val username by authViewModel.username.collectAsState()
    val homeTracks by musicViewModel.homeTracks.collectAsState()
    val keepListening by musicViewModel.keepListening.collectAsState()
    val forgottenFavorites by musicViewModel.forgottenFavorites.collectAsState()

    var selectedCategory by remember { mutableStateOf("Chill") }
    val greeting = remember { currentGreeting() }

    val categories = listOf("Chill", "Pop", "Lo-Fi", "Electronic", "Alternative")

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = greeting,
                        color = Color.Gray,
                        fontSize = 14.sp
                    )
                    Text(
                        text = username,
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                IconButton(
                    onClick = onSearchClick,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Go to search",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Categories
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(categories) { category ->
                    val isSelected = category == selectedCategory
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
                            )
                            .clickable {
                                selectedCategory = category
                                musicViewModel.fetchHomeRecommendations()
                            }
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = category,
                            color = if (isSelected) Color.Black else Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 20.dp)
            ) {
                if (keepListening.isNotEmpty()) {
                    item {
                        Column {
                            Text(
                                text = "Keep Listening",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 20.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp)
                            ) {
                                items(keepListening) { track ->
                                    TrackCardCompact(
                                        track = track,
                                        onPlayClick = { musicViewModel.playTrack(track, keepListening) }
                                    )
                                }
                            }
                        }
                    }
                }

                if (forgottenFavorites.isNotEmpty()) {
                    item {
                        Column {
                            Text(
                                text = "Forgotten Favorites",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 20.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp)
                            ) {
                                items(forgottenFavorites) { track ->
                                    TrackCardCompact(
                                        track = track,
                                        onPlayClick = { musicViewModel.playTrack(track, forgottenFavorites) }
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Column {
                        Text(
                            text = "Recommended for You",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 20.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        // Clean, uncluttered rows: just artwork, title/artist, and
                        // tap to play. Favorite/add-to-playlist controls stay on
                        // the Search screen where the user is actively picking a
                        // specific track, instead of crowding every recommendation
                        // on Home.
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            homeTracks.forEach { track ->
                                TrackRow(
                                    track = track,
                                    onPlayClick = { musicViewModel.playTrack(track, homeTracks) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TrackCardCompact(
    track: Track,
    onPlayClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(120.dp)
            .clickable { onPlayClick() }
    ) {
        AsyncImage(
            model = ThumbnailUtils.resized(track.artworkUrl, ThumbnailUtils.Size.CARD),
            contentDescription = "${track.title} artwork",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(120.dp)
                .clip(RoundedCornerShape(10.dp))
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = track.title,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = track.artist,
            color = Color.Gray,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun TrackRow(
    track: Track,
    onPlayClick: () -> Unit,
    onFavoriteClick: (() -> Unit)? = null,
    onAddToPlaylistClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onPlayClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = ThumbnailUtils.resized(track.artworkUrl, ThumbnailUtils.Size.LIST_ROW),
                contentDescription = "${track.title} artwork",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.artist,
                    color = Color.Gray,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Add-to-playlist control - only shown when the caller wires it up
            // (Home/Search rows do; Library's Favorites/Playlists rows don't).
            if (onAddToPlaylistClick != null) {
                IconButton(onClick = onAddToPlaylistClick) {
                    Icon(
                        imageVector = Icons.Default.PlaylistAdd,
                        contentDescription = "Add to playlist",
                        tint = Color.Gray
                    )
                }
            }

            // Favorite control - only shown when the caller wires it up
            // (Search does; Home's Recommended rows keep it minimal).
            if (onFavoriteClick != null) {
                IconButton(onClick = onFavoriteClick) {
                    Icon(
                        imageVector = if (track.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Favorite icon",
                        tint = if (track.isFavorite) MaterialTheme.colorScheme.tertiary else Color.Gray
                    )
                }
            }
        }
    }
}

@Composable
fun BottomPlayerTray(
    track: Track,
    isPlaying: Boolean,
    isBuffering: Boolean,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onCloseClick: () -> Unit,
    onTrayClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(Color(0xFF13131A))
            .clickable { onTrayClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = ThumbnailUtils.resized(track.artworkUrl, ThumbnailUtils.Size.LIST_ROW),
                    contentDescription = "${track.title} art",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = track.title,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = track.artist,
                        color = Color.Gray,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPlayPauseClick) {
                    if (isBuffering) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
                    } else {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause Icon",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                IconButton(onClick = onNextClick) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Skip Next",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }

                IconButton(onClick = onCloseClick) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Stop and close player",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

