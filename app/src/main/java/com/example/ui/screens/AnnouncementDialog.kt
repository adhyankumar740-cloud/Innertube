package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.announcement.Announcement

/** Shown at most once per day when the Admin Panel has an active announcement waiting. */
@Composable
fun AnnouncementDialog(
    announcement: Announcement,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = if (announcement.version.isNotBlank()) {
                    "${announcement.title} (v${announcement.version})"
                } else {
                    announcement.title
                },
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = announcement.message,
                    color = Color.LightGray
                )
            }
        },
        confirmButton = {
            if (announcement.hasLink) {
                TextButton(
                    onClick = {
                        openLink(context, announcement.link)
                        onDismiss()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                    Text("Visit Website", fontWeight = FontWeight.Bold)
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("Got it", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            if (announcement.hasLink) {
                TextButton(onClick = onDismiss) {
                    Text("Later", color = Color.Gray)
                }
            }
        }
    )
}

/** Opens a website/update link in the user's browser, redirecting them away from the app. */
internal fun openLink(context: android.content.Context, url: String) {
    if (url.isBlank()) return
    val normalizedUrl = if (url.startsWith("http://") || url.startsWith("https://")) {
        url
    } else {
        "https://$url"
    }
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(normalizedUrl)))
    }
}
