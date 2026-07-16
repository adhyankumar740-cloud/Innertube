package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.viewmodel.AuthUiState
import com.example.ui.viewmodel.AuthViewModel

@Composable
fun AuthScreen(
    authViewModel: AuthViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by authViewModel.uiState.collectAsState()
    val context = LocalContext.current

    var errorMsg by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        visible = true
    }

    // Surface sign-in errors (cancelled picker, no Google account, network
    // issue...) in the same inline error slot.
    LaunchedEffect(uiState) {
        errorMsg = (uiState as? AuthUiState.Error)?.message ?: ""
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Decorative top radial glow
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f),
                            Color.Transparent
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn() + slideInVertically(initialOffsetY = { -50 })
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Headphones,
                        contentDescription = "Harmonix Logo",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.height(80.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "NIRVANA",
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        letterSpacing = 4.sp
                    )
                    Text(
                        text = "Where Sound Connects Minds",
                        fontSize = 14.sp,
                        color = Color.Gray,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                GoogleSignInContent(
                    errorMsg = errorMsg,
                    isLoading = uiState is AuthUiState.Loading,
                    onSignIn = { authViewModel.signInWithGoogle(context) },
                    onGuestLogin = { authViewModel.loginAsGuest() }
                )
            }
        }
    }
}

@Composable
private fun GoogleSignInContent(
    errorMsg: String,
    isLoading: Boolean,
    onSignIn: () -> Unit,
    onGuestLogin: () -> Unit
) {
    Text(
        text = "Welcome Onboard",
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White
    )
    Text(
        text = "Sign in with your Google account - one tap, no password",
        fontSize = 12.sp,
        color = Color.Gray,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 4.dp)
    )
    Spacer(modifier = Modifier.height(32.dp))

    if (errorMsg.isNotEmpty()) {
        Text(
            text = errorMsg,
            color = MaterialTheme.colorScheme.error,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        )
    }

    Button(
        onClick = onSignIn,
        enabled = !isLoading,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White,
            contentColor = Color.Black
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .testTag("google_signin_button")
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .height(20.dp)
                    .testTag("signing_indicator"),
                color = Color.Black,
                strokeWidth = 2.dp
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.AccountCircle,
                    contentDescription = null,
                    tint = Color(0xFF4285F4),
                    modifier = Modifier.height(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Continue with Google",
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(14.dp))

    OutlinedButton(
        onClick = onGuestLogin,
        enabled = !isLoading,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = Color.Gray
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .testTag("guest_login_button")
    ) {
        Icon(
            imageVector = Icons.Default.AccountCircle,
            contentDescription = null,
            tint = Color.Gray,
            modifier = Modifier.height(20.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(text = "Continue as Guest", fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
    }
}
