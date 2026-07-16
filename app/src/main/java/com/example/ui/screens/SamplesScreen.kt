package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.data.model.Track
import com.example.player.SamplesPlayerManager
import com.example.ui.viewmodel.SamplesViewModel
import kotlinx.coroutines.delay

@Composable
fun SamplesScreen(
    samplesViewModel: SamplesViewModel,
    modifier: Modifier = Modifier
) {
    val samples by samplesViewModel.samples.collectAsState()
    val isLoading by samplesViewModel.isLoading.collectAsState()
    val currentIndex by samplesViewModel.currentIndex.collectAsState()
    val isPlaying by samplesViewModel.playerManager.isPlaying.collectAsState()
    val isBuffering by samplesViewModel.playerManager.isBuffering.collectAsState()
    val isResolvingFullSong by samplesViewModel.isResolvingFullSong.collectAsState()

    DisposableEffect(Unit) {
        onDispose { samplesViewModel.playerManager.pause() }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (isLoading && samples.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else if (samples.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "Failed to load samples. Try searching on home page.", color = Color.Gray)
            }
        } else {
            val pagerState = rememberPagerState(initialPage = currentIndex, pageCount = { samples.size })

            LaunchedEffect(pagerState.currentPage) {
                samplesViewModel.onSwipe(pagerState.currentPage)
            }

            // beyondViewportPageCount = 1 keeps the next page composed (and its ExoPlayer
            // prepared/buffered ahead of time) so swiping to it is instant - real preloading,
            // not just a static image, and capped at 1 to keep memory/decoder usage bounded.
            VerticalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val track = samples[page]
                val isActivePage = page == pagerState.currentPage

                SampleFeedCard(
                    track = track,
                    isActivePage = isActivePage,
                    isPlaying = isPlaying && isActivePage,
                    isBuffering = isBuffering && isActivePage,
                    isResolvingFullSong = isResolvingFullSong,
                    playerManager = samplesViewModel.playerManager,
                    onFavoriteClick = { samplesViewModel.toggleFavorite(track) },
                    onDownloadClick = { samplesViewModel.toggleDownload(track) },
                    onPlayFullSongClick = { samplesViewModel.playFullSong(track) }
                )
            }
        }
    }
}

/**
 * Full-screen 9:16 video surface (Reels/Shorts style) for one feed page.
 * Each page owns its own ExoPlayer instance - the active page plays; kept-alive
 * neighbor pages (see beyondViewportPageCount) stay paused but fully prepared/
 * buffered, so swiping to them is instant. Using Media3's PlayerView with
 * useController=false (instead of the old android.widget.VideoView) is also
 * what fixes the "can't scroll" bug: VideoView intercepted touch/drag before
 * the outer VerticalPager could see the swipe gesture; PlayerView without its
 * own controls does not.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SampleVideoPage(
    track: Track,
    isActivePage: Boolean,
    playerManager: SamplesPlayerManager,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val exoPlayer = remember(track.id) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(track.previewUrl))
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 1f
            prepare()
        }
    }

    DisposableEffect(track.id) {
        onDispose { exoPlayer.release() }
    }

    LaunchedEffect(isActivePage, exoPlayer) {
        if (isActivePage) {
            playerManager.activePlayer = exoPlayer
            exoPlayer.playWhenReady = true
        } else {
            exoPlayer.playWhenReady = false
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                if (playerManager.activePlayer === exoPlayer) {
                    playerManager.reportActiveState(
                        isPlaying = isPlayingNow,
                        isBuffering = exoPlayer.playbackState == Player.STATE_BUFFERING,
                        positionMs = exoPlayer.currentPosition,
                        durationMs = exoPlayer.duration.coerceAtLeast(0L)
                    )
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (playerManager.activePlayer === exoPlayer) {
                    playerManager.reportActiveState(
                        isPlaying = exoPlayer.isPlaying,
                        isBuffering = state == Player.STATE_BUFFERING,
                        positionMs = exoPlayer.currentPosition,
                        durationMs = exoPlayer.duration.coerceAtLeast(0L)
                    )
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    // Position polling while this page is the active one.
    LaunchedEffect(isActivePage, exoPlayer) {
        while (isActivePage) {
            playerManager.reportActiveState(
                isPlaying = exoPlayer.isPlaying,
                isBuffering = exoPlayer.playbackState == Player.STATE_BUFFERING,
                positionMs = exoPlayer.currentPosition,
                durationMs = exoPlayer.duration.coerceAtLeast(0L)
            )
            delay(500)
        }
    }

    AndroidView(
        // The setOnTouchListener below only stops PlayerView's OWN internal gesture
        // handling - it does NOT stop Compose's AndroidView interop layer from
        // claiming the whole touch stream for itself by default. That claim is what
        // was actually blocking VerticalPager: it never even saw the swipe. This
        // pointerInteropFilter is what explicitly hands the gesture back to Compose
        // ancestors (the Pager) instead of letting the embedded native view keep it.
        modifier = modifier.pointerInteropFilter { false },
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM // crop-to-fill, true 9:16 like Shorts/Reels
                // Belt-and-suspenders: also disable PlayerView's own internal touch
                // handling (tap-to-toggle-controls gesture detector). The Box overlay
                // in SampleFeedCard already handles tap-to-play/pause.
                setOnTouchListener { _, _ -> false }
            }
        }
    )
}

@Composable
fun SampleFeedCard(
    track: Track,
    isActivePage: Boolean,
    isPlaying: Boolean,
    isBuffering: Boolean,
    isResolvingFullSong: Boolean,
    playerManager: SamplesPlayerManager,
    onFavoriteClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onPlayFullSongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Full-screen 9:16 video (fills the whole page, cropped not letterboxed)
        SampleVideoPage(
            track = track,
            isActivePage = isActivePage,
            playerManager = playerManager,
            modifier = Modifier.fillMaxSize()
        )

        // Dark gradient at the bottom for text legibility over the video
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.45f),
                            Color.Black.copy(alpha = 0.92f)
                        ),
                        startY = 400f
                    )
                )
        )

        // Tap-to-play/pause overlay (only meaningful on the active page)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(enabled = isActivePage) { playerManager.togglePlayPause() },
            contentAlignment = Alignment.Center
        ) {
            if (isActivePage) {
                if (isBuffering) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                } else if (!isPlaying) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.65f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Play video preview",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
            }
        }

        // Header badge
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, start = 20.dp, end = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = "Music sign",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "SAMPLES FEED",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = track.genre,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Bottom track details & actions - pushed further down (closer to nav bar) as requested.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.artist,
                    color = Color.LightGray,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.album,
                    color = Color.Gray,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = onPlayFullSongClick,
                    enabled = !isResolvingFullSong,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .height(44.dp)
                        .testTag("play_full_song_button")
                ) {
                    if (isResolvingFullSong) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.Black, strokeWidth = 2.dp)
                    } else {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Play full song",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Play Full Song", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }

            Column(
                modifier = Modifier.fillMaxHeight(0.3f),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                IconButton(
                    onClick = onFavoriteClick,
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f))
                ) {
                    Icon(
                        imageVector = if (track.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Sample Favorite",
                        tint = if (track.isFavorite) MaterialTheme.colorScheme.tertiary else Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                IconButton(
                    onClick = onDownloadClick,
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "Sample Download",
                        tint = if (track.isDownloaded) MaterialTheme.colorScheme.primary else Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}
