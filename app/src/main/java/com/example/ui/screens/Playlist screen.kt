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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Track
import com.example.data.repository.Playlist
import com.example.ui.viewmodel.PlaylistViewModel

/**
 * Library "Playlists" tab. Two states in one composable (no navigation
 * library in this project): the playlist list, or - when [PlaylistViewModel.openPlaylistId]
 * is set - a single playlist's track detail view.
 */
@Composable
fun PlaylistScreen(
    playlistViewModel: PlaylistViewModel,
    modifier: Modifier = Modifier
) {
    val openPlaylistId by playlistViewModel.openPlaylistId.collectAsState()

    if (openPlaylistId == null) {
        PlaylistListView(playlistViewModel = playlistViewModel, modifier = modifier)
    } else {
        PlaylistDetailView(
            playlistId = openPlaylistId!!,
            playlistViewModel = playlistViewModel,
            modifier = modifier
        )
    }
}

@Composable
private fun PlaylistListView(
    playlistViewModel: PlaylistViewModel,
    modifier: Modifier = Modifier
) {
    val playlists by playlistViewModel.playlists.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = { showCreateDialog = true },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Create playlist", tint = Color.Black)
                Spacer(modifier = Modifier.width(6.dp))
                Text("New Playlist", color = Color.Black, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = { showImportDialog = true },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.FileUpload, contentDescription = "Import playlist", tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Import", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        if (playlists.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.QueueMusic,
                        contentDescription = "No playlists",
                        tint = Color.DarkGray,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("No playlists yet", color = Color.Gray, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Create one, or import a list of songs",
                        color = Color.DarkGray,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(playlists, key = { it.id }) { playlist ->
                    PlaylistRow(
                        playlist = playlist,
                        onClick = { playlistViewModel.openPlaylist(playlist.id) },
                        onDelete = { playlistViewModel.deletePlaylist(playlist.id) }
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name ->
                playlistViewModel.createPlaylist(name) { id -> playlistViewModel.openPlaylist(id) }
                showCreateDialog = false
            }
        )
    }

    if (showImportDialog) {
        ImportPlaylistDialog(
            playlistViewModel = playlistViewModel,
            onDismiss = { showImportDialog = false }
        )
    }
}

@Composable
private fun PlaylistRow(
    playlist: Playlist,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.QueueMusic, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${playlist.trackCount} song${if (playlist.trackCount == 1) "" else "s"}",
                color = Color.Gray,
                fontSize = 13.sp
            )
        }

        IconButton(onClick = { showDeleteConfirm = true }) {
            Icon(Icons.Default.Delete, contentDescription = "Delete playlist", tint = Color.Gray)
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete \"${playlist.name}\"?") },
            text = { Text("This removes the playlist. Songs stay in your Favorites if saved there separately.") },
            confirmButton = {
                TextButton(onClick = { onDelete(); showDeleteConfirm = false }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun CreatePlaylistDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Playlist") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("Playlist name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name.ifBlank { "New Playlist" }) }
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ImportPlaylistDialog(
    playlistViewModel: PlaylistViewModel,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var songsText by remember { mutableStateOf("") }
    val isImporting by playlistViewModel.isImporting.collectAsState()
    val result by playlistViewModel.importResult.collectAsState()

    AlertDialog(
        onDismissRequest = { if (!isImporting) onDismiss() },
        title = { Text(if (result != null) "Import Complete" else "Import Playlist") },
        text = {
            if (result != null) {
                val r = result!!
                Column {
                    Text("\"${r.playlistName}\" - matched ${r.matchedCount} of ${r.totalCount} songs.")
                    if (r.unmatchedLines.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Not found:", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = r.unmatchedLines.joinToString("\n"),
                            color = Color.Gray,
                            fontSize = 12.sp,
                            modifier = Modifier.heightIn(max = 120.dp)
                        )
                    }
                }
            } else if (isImporting) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Matching songs...", color = Color.Gray, fontSize = 13.sp)
                }
            } else {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = { Text("Playlist name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = songsText,
                        onValueChange = { songsText = it },
                        placeholder = { Text("One song per line, e.g.\nBlinding Lights - The Weeknd\nAs It Was - Harry Styles") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 140.dp)
                    )
                }
            }
        },
        confirmButton = {
            if (result != null) {
                TextButton(onClick = { playlistViewModel.dismissImportResult(); onDismiss() }) { Text("Done") }
            } else if (!isImporting) {
                TextButton(
                    onClick = { playlistViewModel.importPlaylist(name, songsText) },
                    enabled = songsText.isNotBlank()
                ) { Text("Import") }
            }
        },
        dismissButton = {
            if (result == null && !isImporting) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Composable
private fun PlaylistDetailView(
    playlistId: Long,
    playlistViewModel: PlaylistViewModel,
    modifier: Modifier = Modifier
) {
    val playlists by playlistViewModel.playlists.collectAsState()
    val tracks by playlistViewModel.openPlaylistTracks.collectAsState()
    val playlist = playlists.firstOrNull { it.id == playlistId }
    var showRenameDialog by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { playlistViewModel.closePlaylist() }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back to playlists", tint = Color.White)
            }
            Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                Text(
                    text = playlist?.name ?: "Playlist",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${tracks.size} song${if (tracks.size == 1) "" else "s"}",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            }
            IconButton(onClick = { showRenameDialog = true }) {
                Icon(Icons.Default.Edit, contentDescription = "Rename playlist", tint = Color.Gray)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (tracks.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { playlistViewModel.playPlaylist(tracks) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Play All", color = Color.Black, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = { playlistViewModel.shufflePlayPlaylist(tracks) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Shuffle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Shuffle", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (tracks.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.QueueMusic, contentDescription = null, tint = Color.DarkGray, modifier = Modifier.size(64.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("No songs in this playlist yet", color = Color.Gray, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Add songs from Home or Search results",
                        color = Color.DarkGray,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(tracks, key = { it.id }) { track ->
                    PlaylistTrackRow(
                        track = track,
                        onPlayClick = { playlistViewModel.playPlaylist(tracks, startTrack = track) },
                        onRemoveClick = { playlistViewModel.removeTrackFromPlaylist(playlistId, track) }
                    )
                }
            }
        }
    }

    if (showRenameDialog && playlist != null) {
        var newName by remember { mutableStateOf(playlist.name) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename Playlist") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    playlistViewModel.renamePlaylist(playlistId, newName)
                    showRenameDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun PlaylistTrackRow(
    track: Track,
    onPlayClick: () -> Unit,
    onRemoveClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable { onPlayClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
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
        IconButton(onClick = onRemoveClick) {
            Icon(Icons.Default.Close, contentDescription = "Remove from playlist", tint = Color.Gray)
        }
    }
}

/**
 * Reusable "Add to Playlist" dialog, invoked from Home/Search track rows.
 * Tapping a playlist adds the track to it immediately (no separate save
 * step) so it works as a quick one-tap action from anywhere in the app.
 */
@Composable
fun AddToPlaylistDialog(
    track: Track,
    playlistViewModel: PlaylistViewModel,
    onDismiss: () -> Unit
) {
    val playlists by playlistViewModel.playlists.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }
    // Tap par turant "Added" dikhane ke liye local state - pehle koi feedback
    // nahi tha toh tap karne pe kuch hota hua dikhta hi nahi tha, even though
    // the track was actually being added in the background.
    var addedPlaylistIds by remember { mutableStateOf(setOf<Long>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to Playlist") },
        text = {
            if (playlists.isEmpty()) {
                Text("You don't have any playlists yet.", color = Color.Gray)
            } else {
                Column {
                    playlists.forEach { playlist ->
                        val isAdded = playlist.id in addedPlaylistIds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isAdded) {
                                    playlistViewModel.addTrackToPlaylist(playlist.id, track)
                                    addedPlaylistIds = addedPlaylistIds + playlist.id
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (isAdded) Icons.Default.Check else Icons.Default.QueueMusic,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(playlist.name, color = Color.White, modifier = Modifier.weight(1f))
                            if (isAdded) {
                                Text("Added", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("New Playlist")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )

    if (showCreateDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name ->
                playlistViewModel.createPlaylist(name) { id ->
                    playlistViewModel.addTrackToPlaylist(id, track)
                    addedPlaylistIds = addedPlaylistIds + id
                }
                showCreateDialog = false
            }
        )
    }
}
