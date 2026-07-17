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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.announcement.Announcement
import com.example.announcement.AnnouncementManager
import com.example.ui.viewmodel.AuthViewModel

/**
 * App Settings screen. Reachable via the gear icon on the Library tab.
 *
 * Includes a "What's New" section that shows the latest update announcement
 * pushed from the web Admin Panel (public/admin/index.html) - title,
 * version, message, and a "Visit Website" button that opens the developer's
 * website/download link in the browser. This is always available here
 * (unlike the popup, which only shows once a day).
 */
@Composable
fun SettingsScreen(
    authViewModel: AuthViewModel,
    announcementManager: AnnouncementManager,
    onBack: () -> Unit,
    onAdminClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val username by authViewModel.username.collectAsState()
    val email by authViewModel.email.collectAsState()

    var latestAnnouncement by remember { mutableStateOf<Announcement?>(null) }
    var loadedAnnouncement by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        latestAnnouncement = announcementManager.getLatestAnnouncement()
        loadedAnnouncement = true
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
                Text(
                    text = "Settings",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp)
            ) {
                item {
                    // Account block
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = username.take(1).uppercase(),
                                    color = Color.Black,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column {
                                Text(text = username, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Text(text = email, color = Color.Gray, fontSize = 12.sp)
                            }
                        }
                        IconButton(onClick = { authViewModel.logout() }) {
                            Icon(Icons.Default.Logout, contentDescription = "Logout", tint = MaterialTheme.colorScheme.tertiary)
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Text(
                        text = "WHAT'S NEW",
                        color = Color.Gray,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }

                item {
                    WhatsNewCard(
                        loaded = loadedAnnouncement,
                        announcement = latestAnnouncement,
                        onVisitWebsite = { link -> openLink(context, link) }
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "ABOUT",
                        color = Color.Gray,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }

                item {
                    SettingsRow(
                        icon = Icons.Default.Info,
                        title = "App version",
                        subtitle = BuildConfig.VERSION_NAME
                    )

                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "ADMIN",
                        color = Color.Gray,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    SettingsRow(
                        icon = Icons.Default.AdminPanelSettings,
                        title = "Send an announcement",
                        subtitle = "Admin sign-in required",
                        onClick = onAdminClick
                    )
                }
            }
        }
    }
}

@Composable
private fun WhatsNewCard(
    loaded: Boolean,
    announcement: Announcement?,
    onVisitWebsite: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(18.dp)
    ) {
        if (!loaded) {
            Text(text = "Checking for updates…", color = Color.Gray, fontSize = 13.sp)
            return@Column
        }

        if (announcement == null || !announcement.active) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Campaign, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text(text = "You're all caught up. No new announcements.", color = Color.Gray, fontSize = 13.sp)
            }
            return@Column
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.NewReleases, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = if (announcement.version.isNotBlank()) {
                    "${announcement.title} · v${announcement.version}"
                } else {
                    announcement.title
                },
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(text = announcement.message, color = Color.LightGray, fontSize = 13.sp)

        if (announcement.hasLink) {
            Spacer(modifier = Modifier.height(14.dp))
            Button(
                onClick = { onVisitWebsite(announcement.link) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.Black
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Visit Website", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(text = title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(text = subtitle, color = Color.Gray, fontSize = 12.sp)
            }
        }
        if (onClick != null) {
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.Gray)
        }
    }
}
