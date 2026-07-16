package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ChatMessage
import com.example.data.model.MessageStatus
import com.example.jam.JamManager
import com.example.ui.viewmodel.AuthViewModel
import com.example.ui.viewmodel.JamViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun JamScreen(
    jamViewModel: JamViewModel,
    authViewModel: AuthViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by jamViewModel.uiState.collectAsState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (!uiState.isInRoom) {
            JamLobby(jamViewModel = jamViewModel, authViewModel = authViewModel)
        } else {
            JamRoomContent(jamViewModel = jamViewModel)
        }
    }
}

@Composable
private fun JamLobby(
    jamViewModel: JamViewModel,
    authViewModel: AuthViewModel
) {
    val uiState by jamViewModel.uiState.collectAsState()
    val username by authViewModel.username.collectAsState()
    var joinCode by remember { mutableStateOf("") }
    var showJoinField by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Group,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Jam with friends",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Listen together in real time and chat while the music plays.",
            color = Color.Gray,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 28.dp)
        )

        uiState.errorMessage?.let { error ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF3A1F1F)),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Text(
                    text = error,
                    color = Color(0xFFFF8A80),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        Button(
            onClick = { jamViewModel.createRoom(displayName = username) },
            enabled = !uiState.isConnecting,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            if (uiState.isConnecting) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.Black, strokeWidth = 2.dp)
            } else {
                Text("Create Jam Session", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = { showJoinField = !showJoinField },
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text("Join with a Code", fontWeight = FontWeight.Bold)
        }

        AnimatedVisibility(visible = showJoinField) {
            Column(modifier = Modifier.padding(top = 16.dp)) {
                OutlinedTextField(
                    value = joinCode,
                    onValueChange = { joinCode = it.uppercase() },
                    placeholder = { Text("Enter room code e.g. NIR482", color = Color.Gray) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("jam_join_code_input")
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { jamViewModel.joinRoom(code = joinCode, displayName = username) },
                    enabled = !uiState.isConnecting && joinCode.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Text("Join Jam", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun JamRoomContent(jamViewModel: JamViewModel) {
    val uiState by jamViewModel.uiState.collectAsState()
    val messages by jamViewModel.messages.collectAsState()
    val participants by jamViewModel.participants.collectAsState()
    val typingUids by jamViewModel.typingUsers.collectAsState()
    val replyMessage by jamViewModel.replyMessage.collectAsState()
    val currentTrack by jamViewModel.musicPlayer.currentTrack.collectAsState()
    val myUid = jamViewModel.myUid
    val clipboard = LocalClipboardManager.current

    var textInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Also re-scroll when the typing indicator appears/disappears (it renders
    // as an extra item below the last message) - previously only messages.size
    // was watched, so the indicator popping in/out could shift what's visible
    // without the list following it.
    val typingIndicatorVisible = typingUids.isNotEmpty()
    LaunchedEffect(messages.size, typingIndicatorVisible) {
        if (messages.isNotEmpty() || typingIndicatorVisible) {
            listState.animateScrollToItem((messages.size - 1 + if (typingIndicatorVisible) 1 else 0).coerceAtLeast(0))
        }
    }

    // FIX: previously had its own imePadding() here too, ON TOP OF the
    // Scaffold's innerPadding (Material3 Scaffold's default contentWindowInsets
    // is WindowInsets.safeDrawing, which already includes ime) that MainActivity
    // applies to this screen's parent Box. That meant the keyboard's height got
    // reserved TWICE - once via Scaffold's innerPadding, once via this
    // imePadding() - producing the large empty gap between the chat input and
    // the keyboard. The parent Box in MainActivity already shrinks to make room
    // for the keyboard, so this Column doesn't need to do it again.
    Column(modifier = Modifier.fillMaxSize()) {
        // Jam Top Header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable {
                            uiState.roomCode?.let { clipboard.setText(AnnotatedString(it)) }
                        }
                    ) {
                        Text(
                            text = "Room ${uiState.roomCode ?: ""}",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy room code",
                            tint = Color.Gray,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Group,
                            contentDescription = "Participants Count",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${participants.size} active listener${if (participants.size == 1) "" else "s"}",
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    currentTrack?.let { track ->
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.Black.copy(alpha = 0.5f))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.MusicNote,
                                contentDescription = "Jam track indicator",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Text(
                                    text = track.title,
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.width(80.dp)
                                )
                                Text(
                                    text = track.artist,
                                    color = Color.Gray,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.width(80.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    IconButton(onClick = { jamViewModel.leaveRoom() }) {
                        Icon(
                            imageVector = Icons.Default.ExitToApp,
                            contentDescription = "Leave Jam",
                            tint = Color.Gray,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // Scrolling chat messages list
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(messages) { message ->
                ChatMessageRow(
                    message = message,
                    myUid = myUid,
                    onReplySelect = { jamViewModel.setReplyTo(message) },
                    onReactionSelect = { emoji -> jamViewModel.toggleReaction(message.id, emoji) }
                )
            }

            val typingNames = typingUids.mapNotNull { uid -> participants.find { it.uid == uid }?.name }
            if (typingNames.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        typingNames.forEach { name -> TypingIndicatorBubble(name = name) }
                    }
                }
            }
        }

        // Active Reply Preview bar
        AnimatedVisibility(
            visible = replyMessage != null,
            enter = fadeIn(),
            exit = fadeOut() + shrinkVertically()
        ) {
            replyMessage?.let { msg ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1B1B22))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Reply,
                            contentDescription = "Reply visual indicator",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Replying to ${msg.senderName}",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = msg.text,
                                color = Color.LightGray,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    IconButton(onClick = { jamViewModel.setReplyTo(null) }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel reply",
                            tint = Color.Gray,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        // Message Bottom Input Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = textInput,
                onValueChange = {
                    textInput = it
                    jamViewModel.setUserTyping(it.isNotEmpty())
                },
                placeholder = { Text("Chime in the Jam...", color = Color.Gray) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color.Transparent,
                    focusedContainerColor = Color(0xFF0F0F14),
                    unfocusedContainerColor = Color(0xFF0F0F14)
                ),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .weight(1f)
                    .testTag("jam_chat_input"),
                singleLine = true
            )

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = {
                    if (textInput.trim().isNotEmpty()) {
                        jamViewModel.sendMessage(textInput.trim())
                        textInput = ""
                        jamViewModel.setUserTyping(false)
                    }
                },
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send message",
                    tint = Color.Black
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatMessageRow(
    message: ChatMessage,
    myUid: String?,
    onReplySelect: () -> Unit,
    onReactionSelect: (String) -> Unit
) {
    val isMe = myUid != null && message.senderId == myUid
    var showQuickMenu by remember { mutableStateOf(false) }

    val alignment = if (isMe) Alignment.End else Alignment.Start
    val bubbleColor = if (isMe) MaterialTheme.colorScheme.primary else Color(0xFF1E1E24)
    val textColor = if (isMe) Color.Black else Color.White

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        // Participant username (above bubble if not me)
        if (!isMe) {
            Text(
                text = message.senderName,
                color = Color.Gray,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
            )
        }

        // Quick Reactions Overlay bar
        AnimatedVisibility(visible = showQuickMenu) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF2C2C35))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                listOf("👍", "❤️", "🔥", "😂", "😮", "🙌").forEach { emoji ->
                    Text(
                        text = emoji,
                        fontSize = 16.sp,
                        modifier = Modifier
                            .clickable {
                                onReactionSelect(emoji)
                                showQuickMenu = false
                            }
                            .padding(4.dp)
                    )
                }
                Icon(
                    imageVector = Icons.Default.Reply,
                    contentDescription = "Reply action inline",
                    tint = Color.White,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable {
                            onReplySelect()
                            showQuickMenu = false
                        }
                        .padding(2.dp)
                )
            }
        }

        // Main message bubble
        Card(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isMe) 16.dp else 0.dp,
                bottomEnd = if (isMe) 0.dp else 16.dp
            ),
            colors = CardDefaults.cardColors(containerColor = bubbleColor),
            modifier = Modifier
                .combinedClickable(
                    onClick = { showQuickMenu = !showQuickMenu },
                    onLongClick = { onReplySelect() }
                )
                .width(280.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // If it replies to a parent message
                if (message.replyToId != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.15f))
                            .padding(8.dp)
                    ) {
                        Column {
                            Text(
                                text = "Replying to ${message.replyToSenderName ?: "Participant"}",
                                color = if (isMe) Color.Black.copy(alpha = 0.7f) else MaterialTheme.colorScheme.primary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = message.replyToText ?: "",
                                color = if (isMe) Color.Black.copy(alpha = 0.6f) else Color.LightGray,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }

                // Message Text
                Text(
                    text = message.text,
                    color = textColor,
                    fontSize = 14.sp
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Bottom Status indicators & relative timestamp
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatTimestamp(message.timestamp),
                        color = if (isMe) Color.Black.copy(alpha = 0.6f) else Color.Gray,
                        fontSize = 10.sp
                    )

                    if (isMe) {
                        Icon(
                            imageVector = when (message.status) {
                                MessageStatus.SENT -> Icons.Default.Done
                                MessageStatus.DELIVERED -> Icons.Default.DoneAll
                                MessageStatus.READ -> Icons.Default.DoneAll
                            },
                            contentDescription = "Read receipts status icon",
                            tint = if (message.status == MessageStatus.READ) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.5f),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }

        // Active Reactions Row under message bubble
        if (message.reactions.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .padding(top = 2.dp, bottom = 4.dp, start = if (isMe) 0.dp else 4.dp, end = if (isMe) 4.dp else 0.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF22222B))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                message.reactions.forEach { rx ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { onReactionSelect(rx.emoji) }
                    ) {
                        Text(text = rx.emoji, fontSize = 12.sp)
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = rx.userIds.size.toString(),
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TypingIndicatorBubble(name: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF1A1A22))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = "$name is typing...",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
