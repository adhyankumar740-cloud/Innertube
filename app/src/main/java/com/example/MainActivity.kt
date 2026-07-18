package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.announcement.Announcement
import com.example.data.model.Track
import com.example.data.youtube.YouTubeSession
import com.example.di.AppContainer
import com.example.ui.screens.AddToPlaylistDialog
import com.example.ui.screens.AdminScreen
import com.example.ui.screens.AnnouncementDialog
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.BottomPlayerTray
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.JamScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.NowPlayingScreen
import com.example.ui.screens.OnboardingScreen
import com.example.ui.screens.SamplesScreen
import com.example.ui.screens.SearchScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.AuthViewModel
import com.example.ui.viewmodel.JamViewModel
import com.example.ui.viewmodel.MusicViewModel
import com.example.ui.viewmodel.OnboardingViewModel
import com.example.ui.viewmodel.PlaylistViewModel
import com.example.ui.viewmodel.SamplesViewModel

class MainActivity : ComponentActivity() {

    // Initialize di container locally
    private val appContainer by lazy { AppContainer(applicationContext) }

    // Setup all ViewModels cleanly with factories
    private val authViewModel: AuthViewModel by viewModels {
        AuthViewModel.Factory(applicationContext)
    }

    private val onboardingViewModel: OnboardingViewModel by viewModels {
        OnboardingViewModel.Factory(applicationContext)
    }

    private val musicViewModel: MusicViewModel by viewModels {
        MusicViewModel.Factory(appContainer.musicRepository, appContainer.musicPlayer)
    }

    private val samplesViewModel: SamplesViewModel by viewModels {
        SamplesViewModel.Factory(appContainer.musicRepository, appContainer.samplesPlayerManager, appContainer.musicPlayer)
    }

    private val jamViewModel: JamViewModel by viewModels {
        JamViewModel.Factory(appContainer.jamManager, appContainer.jamChatManager, appContainer.musicPlayer)
    }

    private val playlistViewModel: PlaylistViewModel by viewModels {
        PlaylistViewModel.Factory(
            appContainer.musicRepository,
            appContainer.musicPlayer,
            appContainer.playlistCloudSync,
            authViewModel.isCloudSynced
        )
    }

    // No-op either way: if denied, the foreground service/playback still runs,
    // it just won't be able to show the media notification or lock-screen
    // controls (required as a runtime permission on Android 13+/API 33+).
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // BUG FIX: unlike maybeRequestIgnoreBatteryOptimizations() below, this had no
    // "already asked" flag at all - checkSelfPermission() stays DENIED for as long
    // as the user doesn't explicitly grant it, so this dialog was popping up on
    // literally every single app launch until the user granted it. Same one-time
    // pattern as the battery-optimization prompt: ask once, remember the choice,
    // never nag again (user can still flip it on manually from app settings).
    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        val prefs = getSharedPreferences("battery_opt_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("notification_prompt_shown", false)) return

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        prefs.edit().putBoolean("notification_prompt_shown", true).apply()
    }

    // RESTORED: this prompt was previously removed because it "looked scary"
    // right on install - but that's exactly what was causing playback/autoplay
    // to die the instant the app was minimized. Without this exemption,
    // aggressive OEM battery managers (MIUI/Xiaomi, Vivo, Oppo, OnePlus,
    // Realme etc. - extremely common) freeze or kill the background
    // foreground-service within seconds of the app leaving the foreground,
    // REGARDLESS of the WAKE_LOCK / foregroundServiceType=mediaPlayback setup
    // in PlaybackService - stock-Android-only fixes (wake lock, foreground
    // service) can't do anything about an OEM deciding to freeze the process.
    // Same one-time "ask once, never nag again" pattern as the notification
    // permission above - user can still manage it later from app settings.
    private fun maybeRequestIgnoreBatteryOptimizations() {
        val prefs = getSharedPreferences("battery_opt_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("battery_opt_prompt_shown", false)) return
        prefs.edit().putBoolean("battery_opt_prompt_shown", true).apply()

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return

        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            // Some OEMs block/replace this intent entirely - no crash-worthy
            // fallback exists here, user can still do it manually from
            // Settings > Apps > Nirvana > Battery.
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Restore any previously saved YouTube Music login (cookie/visitorData/dataSyncId)
        // before anything else touches YouTube/playback, so login-gated stream fallback
        // clients (ANDROID_CREATOR, WEB_CREATOR, TVHTML5, ...) are available from the start.
        YouTubeSession.restore(applicationContext)

        // If that didn't restore a logged-in visitorData (guest / never logged in), fetch an
        // anonymous one. Without it, YTPlayerUtils can never generate a PoToken, and every
        // client - WEB_REMIX and all its fallbacks - gets rejected as "Sign in to confirm
        // you're not a bot", even for guests who were never expected to log in.
        lifecycleScope.launch { YouTubeSession.ensureVisitorData() }

        maybeRequestNotificationPermission()
        maybeRequestIgnoreBatteryOptimizations()

        setContent {
            MyApplicationTheme {
                val isLoggedIn by authViewModel.isLoggedIn.collectAsState()
                val isInitializing by authViewModel.isInitializing.collectAsState()
                val isOnboardingComplete by onboardingViewModel.isComplete.collectAsState()

                when {
                    // Briefly true on cold start while Supabase Auth restores any
                    // saved session - avoids flashing the sign-in screen for
                    // already-logged-in users before that restore finishes.
                    isInitializing -> Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                    )
                    !isLoggedIn -> AuthScreen(authViewModel = authViewModel)
                    // Shown exactly once, right after the very first login on this
                    // device (OnboardingPreferences persists completion locally).
                    !isOnboardingComplete -> OnboardingScreen(onboardingViewModel = onboardingViewModel)
                    else -> MainAppLayout(
                        musicViewModel = musicViewModel,
                        authViewModel = authViewModel,
                        samplesViewModel = samplesViewModel,
                        jamViewModel = jamViewModel,
                        playlistViewModel = playlistViewModel,
                        appContainer = appContainer
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Do NOT pause musicPlayer here: it's backed by a Media3 foreground
        // service (PlaybackService) specifically so iTunes-sourced playback
        // keeps running after this Activity is destroyed (backgrounded,
        // rotated, or swiped from Recents). Pausing it here was killing
        // background playback - PlaybackService.onTaskRemoved() stops itself
        // once nothing is playing, so this call was tearing down the service
        // right as the app was backgrounded.
        // The Samples feed is foreground-only content (its ExoPlayer instances
        // live in Compose, not a service), so it's correct to pause that one.
        appContainer.samplesPlayerManager.pause()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppLayout(
    musicViewModel: MusicViewModel,
    authViewModel: AuthViewModel,
    samplesViewModel: SamplesViewModel,
    jamViewModel: JamViewModel,
    playlistViewModel: PlaylistViewModel,
    appContainer: AppContainer
) {
    val selectedTab by musicViewModel.selectedTab.collectAsState()

    // "Play Full Song" on the Samples feed starts playback in musicViewModel.player
    // (see SamplesViewModel.playFullSong) but never left the Samples tab, so the
    // song audibly started while the screen stayed on the sample feed - it looked
    // like tapping the button did nothing. Once a full song actually starts
    // playing while the user is still on "samples", jump them to Home.
    val fullSongCurrentTrack by musicViewModel.player.currentTrack.collectAsState()
    val isResolvingFullSong by samplesViewModel.isResolvingFullSong.collectAsState()
    LaunchedEffect(fullSongCurrentTrack, isResolvingFullSong) {
        if (selectedTab == "samples" && !isResolvingFullSong && fullSongCurrentTrack != null) {
            musicViewModel.selectTab("home")
        }
    }

    // playbackError was already being set internally by MusicPlayer whenever
    // auto-skip gives up after repeated failures, but nothing ever displayed
    // it - so a failure just looked like silent, endless skipping with no
    // explanation. Surfacing it here (once, at the app root) fixes that for
    // every screen, not just Now Playing.
    val snackbarHostState = remember { SnackbarHostState() }
    val playbackError by musicViewModel.player.playbackError.collectAsState()
    LaunchedEffect(playbackError) {
        val message = playbackError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        musicViewModel.player.clearPlaybackError()
    }

    // REMOVED: there used to be a "phone-only debug dialog" here that popped
    // up an AlertDialog every single time lastStreamErrorDebug changed - and
    // MusicPlayer sets that on the *first* onPlayerError/resolve attempt too
    // (see playYoutubeTrack/onPlayerError), even when the very next retry
    // succeeds and the song plays perfectly fine. That's why an error popup
    // was showing up on songs that actually played: a transient first
    // attempt (expired signed URL, brief relay hiccup, etc.) is normal and
    // gets silently retried - it was never meant to be user-facing. The real,
    // user-facing failure signal is `playbackError` (Snackbar above), which
    // only fires once retries are exhausted and playback actually gives up.

    // Admin Panel announcement/update popup (see public/admin/index.html),
    // checked once per app launch and shown at most once per day.
    var announcementToShow by remember { mutableStateOf<Announcement?>(null) }
    LaunchedEffect(Unit) {
        announcementToShow = appContainer.announcementManager.getAnnouncementToShow()
    }

    // Persistent mini player state - lives here (not inside any one tab
    // screen) so it stays visible and pinned to the bottom across Home,
    // Samples, Jam, and Library, replacing the space the YouTube WebView
    // used to occupy.
    val currentTrack by musicViewModel.player.currentTrack.collectAsState()
    val isPlaying by musicViewModel.player.isPlaying.collectAsState()
    val playbackPos by musicViewModel.player.playbackPosition.collectAsState()
    val duration by musicViewModel.player.duration.collectAsState()
    val isBuffering by musicViewModel.player.isBuffering.collectAsState()
    var showPlayerDetail by remember { mutableStateOf(false) }
    // "Add to Playlist" from the Now Playing screen - same reusable dialog
    // Home/Search already use, just triggered from a different place.
    var trackPendingPlaylistAdd by remember { mutableStateOf<Track?>(null) }

    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(snackbarData = data, containerColor = MaterialTheme.colorScheme.errorContainer)
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == "home",
                    onClick = { 
                        musicViewModel.selectTab("home")
                        samplesViewModel.playerManager.pause() // Pause sample player when switching tabs
                    },
                    icon = { Icon(Icons.Default.Home, contentDescription = "Home tab", modifier = Modifier.size(24.dp)) },
                    label = { Text("Home", style = MaterialTheme.typography.labelMedium) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.Black,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primary,
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray
                    )
                )

                NavigationBarItem(
                    selected = selectedTab == "search",
                    onClick = {
                        musicViewModel.selectTab("search")
                        samplesViewModel.playerManager.pause()
                    },
                    icon = { Icon(Icons.Default.Search, contentDescription = "Search tab", modifier = Modifier.size(24.dp)) },
                    label = { Text("Search", style = MaterialTheme.typography.labelMedium) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.Black,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primary,
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray
                    )
                )

                NavigationBarItem(
                    selected = selectedTab == "samples",
                    onClick = { 
                        musicViewModel.selectTab("samples")
                        musicViewModel.player.pause() // Pause main player when viewing samples feed
                    },
                    icon = { Icon(Icons.Default.MusicNote, contentDescription = "Samples tab", modifier = Modifier.size(24.dp)) },
                    label = { Text("Samples", style = MaterialTheme.typography.labelMedium) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.Black,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primary,
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray
                    )
                )

                NavigationBarItem(
                    selected = selectedTab == "jam",
                    onClick = { 
                        musicViewModel.selectTab("jam")
                        samplesViewModel.playerManager.pause()
                    },
                    icon = { Icon(Icons.Default.Group, contentDescription = "Jam tab", modifier = Modifier.size(24.dp)) },
                    label = { Text("Jam Room", style = MaterialTheme.typography.labelMedium) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.Black,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primary,
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray
                    )
                )

                NavigationBarItem(
                    selected = selectedTab == "library",
                    onClick = { 
                        musicViewModel.selectTab("library")
                        samplesViewModel.playerManager.pause()
                    },
                    icon = { Icon(Icons.Default.LibraryMusic, contentDescription = "Library tab", modifier = Modifier.size(24.dp)) },
                    label = { Text("Library", style = MaterialTheme.typography.labelMedium) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.Black,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primary,
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray
                    )
                )
            }
        },
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        // consumeWindowInsets is important here: without it, the bottom-nav-bar
        // space reserved by innerPadding stacks on top of JamScreen's own
        // imePadding() when the keyboard opens - pushing the message input box
        // way above the keyboard instead of sitting right above it.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
        ) {
            // Screen content - reserves space at the bottom for the mini
            // player (below) whenever something is loaded/playing, so list
            // content never sits hidden behind the tray.
            // FIX: this reservation used to apply unconditionally, even while
            // the keyboard was open - the tray is covered by the keyboard anyway
            // in that state, so keeping it reserved just stacked an extra fixed
            // 72dp on top of JamScreen's own imePadding(), pushing the whole
            // chat noticeably further up than the keyboard alone would require.
            // Dropping the reservation while the IME is visible fixes that.
            val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
            val reserveTrayGap = currentTrack != null && !imeVisible && selectedTab != "jam"
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = if (reserveTrayGap) 72.dp else 0.dp)
            ) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    when (selectedTab) {
                        "home" -> HomeScreen(
                            musicViewModel = musicViewModel,
                            authViewModel = authViewModel,
                            playlistViewModel = playlistViewModel,
                            onSearchClick = { musicViewModel.selectTab("search") }
                        )
                        "search" -> SearchScreen(musicViewModel = musicViewModel, playlistViewModel = playlistViewModel)
                        "samples" -> SamplesScreen(samplesViewModel = samplesViewModel)
                        "jam" -> JamScreen(jamViewModel = jamViewModel, authViewModel = authViewModel)
                        "library" -> LibraryScreen(
                            musicViewModel = musicViewModel,
                            authViewModel = authViewModel,
                            playlistViewModel = playlistViewModel,
                            onSettingsClick = { musicViewModel.selectTab("settings") }
                        )
                        "settings" -> SettingsScreen(
                            authViewModel = authViewModel,
                            announcementManager = appContainer.announcementManager,
                            onBack = { musicViewModel.selectTab("library") },
                            onAdminClick = { musicViewModel.selectTab("admin") }
                        )
                        "admin" -> AdminScreen(
                            announcementManager = appContainer.announcementManager,
                            onBack = { musicViewModel.selectTab("settings") }
                        )
                    }
                }
            }

            // Persistent mini player - pinned to the bottom of the screen,
            // above the bottom nav bar, visible from every tab. This is what
            // now fills the space that used to be an empty black bar left by
            // the (now hidden) YouTube WebView.
            // Skipped on the Jam tab specifically: JamScreen already shows a
            // compact "now playing" chip in its own header, so the tray was
            // just redundant clutter sitting right above the chat input there.
            if (currentTrack != null && selectedTab != "jam") {
                BottomPlayerTray(
                    track = currentTrack!!,
                    isPlaying = isPlaying,
                    isBuffering = isBuffering,
                    onPlayPauseClick = {
                        if (isPlaying) musicViewModel.player.pause() else musicViewModel.player.resume()
                    },
                    onNextClick = { musicViewModel.player.skipNext() },
                    onCloseClick = { musicViewModel.player.stopAndDismiss() },
                    onTrayClick = { showPlayerDetail = true },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }

            // Fullscreen Player Detail Bottom Sheet
            if (showPlayerDetail && currentTrack != null) {
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ModalBottomSheet(
                    onDismissRequest = { showPlayerDetail = false },
                    sheetState = sheetState,
                    containerColor = MaterialTheme.colorScheme.background,
                    dragHandle = {
                        IconButton(onClick = { showPlayerDetail = false }) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Close player detail",
                                tint = Color.White
                            )
                        }
                    }
                ) {
                    NowPlayingScreen(
                        track = currentTrack!!,
                        isPlaying = isPlaying,
                        isBuffering = isBuffering,
                        isResolvingAutoplay = musicViewModel.player.isResolvingAutoplay.collectAsState().value,
                        playbackPos = playbackPos,
                        duration = duration,
                        queue = musicViewModel.player.queue.collectAsState().value,
                        queueIndex = musicViewModel.player.queueIndex.collectAsState().value,
                        isShuffleEnabled = musicViewModel.player.isShuffleEnabled.collectAsState().value,
                        repeatMode = musicViewModel.player.repeatMode.collectAsState().value,
                        lyrics = musicViewModel.lyrics.collectAsState().value,
                        isLoadingLyrics = musicViewModel.isLoadingLyrics.collectAsState().value,
                        sleepTimerEndsAt = musicViewModel.player.sleepTimerEndsAt.collectAsState().value,
                        onSleepTimerSet = { minutes -> musicViewModel.player.setSleepTimer(minutes) },
                        onSleepTimerCancel = { musicViewModel.player.cancelSleepTimer() },
                        playbackSpeed = musicViewModel.player.playbackSpeed.collectAsState().value,
                        onPlaybackSpeedSet = { speed -> musicViewModel.player.setPlaybackSpeed(speed) },
                        onPlayPauseClick = {
                            if (isPlaying) musicViewModel.player.pause() else musicViewModel.player.resume()
                        },
                        onPrevClick = { musicViewModel.player.skipPrevious() },
                        onNextClick = { musicViewModel.player.skipNext() },
                        onSeek = { musicViewModel.player.seekTo(it) },
                        onFavoriteClick = { musicViewModel.toggleFavorite(currentTrack!!) },
                        onAddToPlaylistClick = { trackPendingPlaylistAdd = currentTrack },
                        onShuffleClick = { musicViewModel.player.toggleShuffle() },
                        onRepeatClick = { musicViewModel.player.cycleRepeatMode() },
                        onQueueItemClick = { musicViewModel.player.playQueueItem(it) },
                        onQueueItemRemove = { musicViewModel.player.removeFromQueue(it) }
                    )
                }
            }

            trackPendingPlaylistAdd?.let { track ->
                AddToPlaylistDialog(
                    track = track,
                    playlistViewModel = playlistViewModel,
                    onDismiss = { trackPendingPlaylistAdd = null }
                )
            }

            // Admin Panel announcement popup - shown at most once per day.
            announcementToShow?.let { announcement ->
                AnnouncementDialog(
                    announcement = announcement,
                    onDismiss = {
                        appContainer.announcementManager.markShown(announcement)
                        announcementToShow = null
                    }
                )
            }
        }
    }
}
