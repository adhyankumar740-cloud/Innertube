package com.example.ui.screens

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.viewmodel.OnboardingViewModel

/**
 * First-launch-only onboarding: pick a few genres, then a few artists.
 * Selections are saved via [OnboardingViewModel] and used to personalize
 * Home/Samples recommendations from the very first session.
 *
 * LIST SIZE FIX: genres/artists used to be a fixed list of 12/16 hardcoded
 * strings, which looked sparse next to Spotify/YT Music's own onboarding.
 * Both grids now render whatever [OnboardingViewModel] pulled live from
 * InnerTube (real Moods & Genres list, and artist search seeded by the
 * genres you picked) - typically 30-60+ items - with a 3-column grid and a
 * loading/retry state while that network call is in flight.
 */
@Composable
fun OnboardingScreen(
    onboardingViewModel: OnboardingViewModel,
    modifier: Modifier = Modifier
) {
    val step by onboardingViewModel.step.collectAsState()
    val selectedGenres by onboardingViewModel.selectedGenres.collectAsState()
    val selectedArtists by onboardingViewModel.selectedArtists.collectAsState()
    val availableGenres by onboardingViewModel.availableGenres.collectAsState()
    val isLoadingGenres by onboardingViewModel.isLoadingGenres.collectAsState()
    val availableArtists by onboardingViewModel.availableArtists.collectAsState()
    val isLoadingArtists by onboardingViewModel.isLoadingArtists.collectAsState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (step == 0) {
            GenrePickerStep(
                genres = availableGenres,
                isLoading = isLoadingGenres,
                selectedGenres = selectedGenres,
                onToggleGenre = onboardingViewModel::toggleGenre,
                onContinue = onboardingViewModel::goToArtistStep,
                onSkip = onboardingViewModel::skipOnboarding,
                onRetry = onboardingViewModel::retryLoadGenres
            )
        } else {
            ArtistPickerStep(
                artists = availableArtists,
                isLoading = isLoadingArtists,
                selectedArtists = selectedArtists,
                onToggleArtist = onboardingViewModel::toggleArtist,
                onBack = onboardingViewModel::goBackToGenreStep,
                onFinish = onboardingViewModel::finishOnboarding,
                onSkip = onboardingViewModel::skipOnboarding,
                onRetry = onboardingViewModel::retryLoadArtists
            )
        }
    }
}

/** Centered spinner (loading) or "nothing came back" message + Retry button, shared by both steps. */
@Composable
private fun LoadingOrEmptyState(isLoading: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (isLoading) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Couldn't load suggestions. Check your connection.",
                    color = Color.Gray,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp)
                )
                TextButton(onClick = onRetry) {
                    Text("Retry", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun GenrePickerStep(
    genres: List<String>,
    isLoading: Boolean,
    selectedGenres: Set<String>,
    onToggleGenre: (String) -> Unit,
    onContinue: () -> Unit,
    onSkip: () -> Unit,
    onRetry: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        OnboardingHeader(
            title = "What do you want to listen to?",
            subtitle = "Pick at least ${OnboardingViewModel.MIN_GENRE_SELECTIONS} genres - we'll use these to personalize your Home feed.",
            onSkip = onSkip
        )

        if (isLoading || genres.isEmpty()) {
            LoadingOrEmptyState(
                isLoading = isLoading,
                onRetry = onRetry,
                modifier = Modifier.weight(1f).padding(top = 40.dp)
            )
        } else {
            // 3 columns instead of 2 - the real InnerTube genre list is much
            // longer (30-40+ entries) than the old 12-item hardcoded one, so
            // a denser grid keeps it scannable instead of one long scroll.
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(genres) { genre ->
                    SelectableChipCard(
                        label = genre,
                        icon = Icons.Default.MusicNote,
                        isSelected = genre in selectedGenres,
                        onClick = { onToggleGenre(genre) }
                    )
                }
            }
        }

        Button(
            onClick = onContinue,
            enabled = selectedGenres.size >= OnboardingViewModel.MIN_GENRE_SELECTIONS,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .height(52.dp)
        ) {
            Text(
                text = "Continue (${selectedGenres.size} selected)",
                color = Color.Black,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ArtistPickerStep(
    artists: List<String>,
    isLoading: Boolean,
    selectedArtists: Set<String>,
    onToggleArtist: (String) -> Unit,
    onBack: () -> Unit,
    onFinish: () -> Unit,
    onSkip: () -> Unit,
    onRetry: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        OnboardingHeader(
            title = "Now pick some artists",
            subtitle = "Choose at least ${OnboardingViewModel.MIN_ARTIST_SELECTIONS} artists you love.",
            onSkip = onSkip
        )

        if (isLoading || artists.isEmpty()) {
            LoadingOrEmptyState(
                isLoading = isLoading,
                onRetry = onRetry,
                modifier = Modifier.weight(1f).padding(top = 40.dp)
            )
        } else {
            // Same reasoning as the genre grid - the real artist list (based
            // on InnerTube search for each genre you picked) is much bigger
            // than the old fixed 16, so 3 columns keeps it browsable.
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(artists) { artist ->
                    SelectableChipCard(
                        label = artist,
                        icon = Icons.Default.Person,
                        isSelected = artist in selectedArtists,
                        onClick = { onToggleArtist(artist) }
                    )
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
            TextButton(onClick = onBack, modifier = Modifier.padding(end = 8.dp)) {
                Text("Back", color = Color.Gray)
            }
            Button(
                onClick = onFinish,
                enabled = selectedArtists.size >= OnboardingViewModel.MIN_ARTIST_SELECTIONS,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
            ) {
                Text(
                    text = "Get Started (${selectedArtists.size} selected)",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun OnboardingHeader(title: String, subtitle: String, onSkip: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = subtitle,
                    color = Color.Gray,
                    fontSize = 14.sp
                )
            }
            TextButton(onClick = onSkip) {
                Text("Skip", color = Color.Gray)
            }
        }
    }
}

@Composable
private fun SelectableChipCard(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surface)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.DarkGray,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = label,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = Color.Black,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
    }
}
