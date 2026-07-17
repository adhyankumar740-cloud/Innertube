package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.announcement.AnnouncementManager
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.launch

/**
 * In-app admin screen for sending update announcements - no website or
 * separate page needed. Reachable from Settings via a low-key "Admin"
 * row, gated behind a hardcoded admin ID/password check
 * ([AnnouncementManager.signInAsAdmin]) - see that file's ADMIN_ID/
 * ADMIN_PASSWORD constants. Writes go straight to Supabase (table
 * `announcements`, see supabase/schema.sql).
 */
@Composable
fun AdminScreen(
    announcementManager: AnnouncementManager,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var signedIn by remember { mutableStateOf(announcementManager.isAdminSignedIn) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Text(text = "Admin", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }

            if (signedIn) {
                AdminPublishPanel(
                    announcementManager = announcementManager,
                    onSignedOut = { signedIn = false }
                )
            } else {
                AdminLoginPanel(
                    announcementManager = announcementManager,
                    onSignedIn = { signedIn = true }
                )
            }
        }
    }
}

@Composable
private fun AdminLoginPanel(
    announcementManager: AnnouncementManager,
    onSignedIn: () -> Unit
) {
    var adminId by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        Text(
            text = "Sign in with your admin ID/password to send announcements to every user.",
            color = Color.Gray,
            fontSize = 13.sp
        )
        Spacer(modifier = Modifier.height(20.dp))

        OutlinedTextField(
            value = adminId,
            onValueChange = { adminId = it; error = null },
            label = { Text("Admin ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = adminFieldColors()
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it; error = null },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = null,
                        tint = Color.Gray
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = adminFieldColors()
        )

        error?.let {
            Spacer(modifier = Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = {
                if (adminId.isBlank() || password.isBlank()) {
                    error = "Enter the admin ID and password."
                    return@Button
                }
                try {
                    announcementManager.signInAsAdmin(adminId, password)
                    error = null
                    onSignedIn()
                } catch (e: Exception) {
                    error = e.message ?: "Sign-in failed."
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = Color.Black),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Sign in", fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "The admin ID/password are hardcoded in AnnouncementManager.kt (ADMIN_ID / " +
                "ADMIN_PASSWORD) - change them there before you ship, and don't commit your real " +
                "password to a public repo.",
            color = Color.DarkGray,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun AdminPublishPanel(
    announcementManager: AnnouncementManager,
    onSignedOut: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var version by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var deactivating by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Signed in as admin", color = Color.Gray, fontSize = 12.sp)
            OutlinedButton(onClick = {
                announcementManager.signOutAdmin()
                onSignedOut()
            }) {
                Icon(Icons.Default.Logout, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Sign out")
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text(text = "Send an announcement", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(
            text = "Shown as a popup (once/day) and always under Settings -> What's New.",
            color = Color.Gray,
            fontSize = 12.sp
        )
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Title") },
            placeholder = { Text("e.g. New update available!") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = adminFieldColors()
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = version,
            onValueChange = { version = it },
            label = { Text("New version (optional)") },
            placeholder = { Text("e.g. 2.1.0") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = adminFieldColors()
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = message,
            onValueChange = { message = it },
            label = { Text("Message") },
            placeholder = { Text("What's new?") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
            colors = adminFieldColors()
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = link,
            onValueChange = { link = it },
            label = { Text("Website / download link (optional)") },
            placeholder = { Text("https://your-website.com") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = adminFieldColors()
        )

        statusMessage?.let {
            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (statusIsError) Icons.Default.Error else Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = if (statusIsError) MaterialTheme.colorScheme.error else Color(0xFF6BFFB0),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = it,
                    color = if (statusIsError) MaterialTheme.colorScheme.error else Color(0xFF6BFFB0),
                    fontSize = 12.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))
        Button(
            onClick = {
                if (message.isBlank()) {
                    statusMessage = "Message can't be empty."
                    statusIsError = true
                    return@Button
                }
                sending = true
                statusMessage = null
                scope.launch {
                    try {
                        announcementManager.publishAnnouncement(
                            title = title.trim(),
                            version = version.trim(),
                            message = message.trim(),
                            link = link.trim()
                        )
                        statusMessage = "Announcement sent! Live for every user now."
                        statusIsError = false
                        title = ""; version = ""; message = ""; link = ""
                    } catch (e: Exception) {
                        statusMessage = e.message ?: "Failed to send."
                        statusIsError = true
                    } finally {
                        sending = false
                    }
                }
            },
            enabled = !sending,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = Color.Black),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (sending) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.Black, strokeWidth = 2.dp)
            } else {
                Text("Send Announcement", fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        OutlinedButton(
            onClick = {
                deactivating = true
                statusMessage = null
                scope.launch {
                    try {
                        announcementManager.deactivateCurrentAnnouncement()
                        statusMessage = "Current announcement deactivated."
                        statusIsError = false
                    } catch (e: Exception) {
                        statusMessage = e.message ?: "Failed to deactivate."
                        statusIsError = true
                    } finally {
                        deactivating = false
                    }
                }
            },
            enabled = !deactivating,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Deactivate current announcement")
        }
    }
}

@Composable
private fun adminFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = Color.DarkGray,
    focusedLabelColor = MaterialTheme.colorScheme.primary,
    unfocusedLabelColor = Color.Gray,
    cursorColor = MaterialTheme.colorScheme.primary
)
