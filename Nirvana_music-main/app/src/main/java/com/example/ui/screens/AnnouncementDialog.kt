package com.example.ui.screens

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.example.announcement.Announcement

/** Shown at most once per day when the Admin Panel has an active announcement waiting. */
@Composable
fun AnnouncementDialog(
    announcement: Announcement,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = announcement.title,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                text = announcement.message,
                color = Color.LightGray
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Got it", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }
    )
}
