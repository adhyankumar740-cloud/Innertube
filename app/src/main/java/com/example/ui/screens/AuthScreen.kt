package com.example.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.viewmodel.AuthUiState
import com.example.ui.viewmodel.AuthViewModel

/** Which top-level form the auth screen is showing. */
private enum class AuthMode { SIGN_IN, SIGN_UP, FORGOT_PASSWORD, FORGOT_USER_ID }

@Composable
fun AuthScreen(
    authViewModel: AuthViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by authViewModel.uiState.collectAsState()
    var mode by remember { mutableStateOf(AuthMode.SIGN_IN) }
    var visible by remember { mutableStateOf(false) }

    // Prefill the sign-in User ID field right after a successful "Forgot User ID" recovery.
    var prefillUserId by remember { mutableStateOf("") }
    LaunchedEffect(uiState) {
        val state = uiState
        if (state is AuthUiState.UserIdRecovered) {
            // handled inline in ForgotUserIdContent below; nothing to do here.
        }
    }

    LaunchedEffect(Unit) { visible = true }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
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
                .verticalScroll(rememberScrollState())
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

            Spacer(modifier = Modifier.height(40.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AnimatedContent(targetState = mode, label = "auth_mode") { current ->
                    when (current) {
                        AuthMode.SIGN_IN -> SignInContent(
                            authViewModel = authViewModel,
                            uiState = uiState,
                            initialUserId = prefillUserId,
                            onGoToSignUp = { authViewModel.dismiss(); mode = AuthMode.SIGN_UP },
                            onGoToForgotPassword = { authViewModel.dismiss(); mode = AuthMode.FORGOT_PASSWORD },
                            onGoToForgotUserId = { authViewModel.dismiss(); mode = AuthMode.FORGOT_USER_ID },
                            onGuestLogin = { authViewModel.loginAsGuest() }
                        )
                        AuthMode.SIGN_UP -> SignUpContent(
                            authViewModel = authViewModel,
                            uiState = uiState,
                            onGoToSignIn = { authViewModel.dismiss(); mode = AuthMode.SIGN_IN }
                        )
                        AuthMode.FORGOT_PASSWORD -> ForgotPasswordContent(
                            authViewModel = authViewModel,
                            uiState = uiState,
                            onBackToSignIn = { authViewModel.dismiss(); mode = AuthMode.SIGN_IN }
                        )
                        AuthMode.FORGOT_USER_ID -> ForgotUserIdContent(
                            authViewModel = authViewModel,
                            uiState = uiState,
                            onBackToSignIn = { recoveredId ->
                                prefillUserId = recoveredId
                                authViewModel.dismiss()
                                mode = AuthMode.SIGN_IN
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorOrInfoBanner(uiState: AuthUiState) {
    val (text, color) = when (uiState) {
        is AuthUiState.Error -> uiState.message to MaterialTheme.colorScheme.error
        is AuthUiState.Info -> uiState.message to MaterialTheme.colorScheme.primary
        else -> return
    }
    Text(
        text = text,
        color = color,
        fontSize = 12.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
            .testTag("auth_message")
    )
}

@Composable
private fun authFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = Color.Gray,
    cursorColor = MaterialTheme.colorScheme.primary,
    focusedLabelColor = MaterialTheme.colorScheme.primary,
    unfocusedLabelColor = Color.Gray
)

@Composable
private fun PrimaryButton(
    text: String,
    isLoading: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = !isLoading,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = Color.Black
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .testTag(testTag)
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.height(20.dp).testTag("auth_loading_indicator"),
                color = Color.Black,
                strokeWidth = 2.dp
            )
        } else {
            Text(text = text, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
        }
    }
}

@Composable
private fun SignInContent(
    authViewModel: AuthViewModel,
    uiState: AuthUiState,
    initialUserId: String,
    onGoToSignUp: () -> Unit,
    onGoToForgotPassword: () -> Unit,
    onGoToForgotUserId: () -> Unit,
    onGuestLogin: () -> Unit
) {
    var userId by remember { mutableStateOf(initialUserId) }
    var password by remember { mutableStateOf("") }
    val isLoading = uiState is AuthUiState.Loading

    Text(text = "Welcome Back", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
    Text(
        text = "Sign in with your User ID and password",
        fontSize = 12.sp,
        color = Color.Gray,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 4.dp)
    )
    Spacer(modifier = Modifier.height(24.dp))

    ErrorOrInfoBanner(uiState)

    OutlinedTextField(
        value = userId,
        onValueChange = { userId = it },
        label = { Text("User ID") },
        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = Color.Gray) },
        singleLine = true,
        colors = authFieldColors(),
        modifier = Modifier.fillMaxWidth().testTag("signin_userid_field")
    )
    Spacer(modifier = Modifier.height(12.dp))
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("Password") },
        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = Color.Gray) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        colors = authFieldColors(),
        modifier = Modifier.fillMaxWidth().testTag("signin_password_field")
    )

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        TextButton(
            onClick = onGoToForgotUserId,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 4.dp)
        ) {
            Text(
                "Forgot User ID?",
                color = Color.Gray,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        TextButton(
            onClick = onGoToForgotPassword,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 4.dp)
        ) {
            Text(
                "Forgot Password?",
                color = Color.Gray,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End
            )
        }
    }

    Spacer(modifier = Modifier.height(8.dp))
    PrimaryButton(text = "Sign In", isLoading = isLoading, testTag = "signin_button") {
        authViewModel.signIn(userId, password)
    }

    Spacer(modifier = Modifier.height(14.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("New here?", color = Color.Gray, fontSize = 12.sp)
        TextButton(onClick = onGoToSignUp, modifier = Modifier.testTag("go_to_signup_button")) {
            Text("Create an account", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }

    Spacer(modifier = Modifier.height(4.dp))
    TextButton(onClick = onGuestLogin, modifier = Modifier.testTag("guest_login_button")) {
        Text("Continue as Guest", color = Color.Gray, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SignUpContent(
    authViewModel: AuthViewModel,
    uiState: AuthUiState,
    onGoToSignIn: () -> Unit
) {
    var userId by remember { mutableStateOf("") }
    var recoveryEmail by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    val isLoading = uiState is AuthUiState.Loading

    Text(text = "Create Your Account", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
    Text(
        text = "Pick a unique User ID - this is what you'll log in with",
        fontSize = 12.sp,
        color = Color.Gray,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 4.dp)
    )
    Spacer(modifier = Modifier.height(20.dp))

    ErrorOrInfoBanner(uiState)

    OutlinedTextField(
        value = userId,
        onValueChange = { userId = it },
        label = { Text("User ID") },
        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = Color.Gray) },
        singleLine = true,
        colors = authFieldColors(),
        modifier = Modifier.fillMaxWidth().testTag("signup_userid_field")
    )
    Spacer(modifier = Modifier.height(12.dp))
    OutlinedTextField(
        value = recoveryEmail,
        onValueChange = { recoveryEmail = it },
        label = { Text("Recovery Email") },
        leadingIcon = { Icon(Icons.Default.MailOutline, contentDescription = null, tint = Color.Gray) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        colors = authFieldColors(),
        modifier = Modifier.fillMaxWidth().testTag("signup_email_field")
    )
    Spacer(modifier = Modifier.height(12.dp))
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("Password") },
        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = Color.Gray) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        colors = authFieldColors(),
        modifier = Modifier.fillMaxWidth().testTag("signup_password_field")
    )
    Spacer(modifier = Modifier.height(12.dp))
    OutlinedTextField(
        value = confirmPassword,
        onValueChange = { confirmPassword = it },
        label = { Text("Confirm Password") },
        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = Color.Gray) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        colors = authFieldColors(),
        modifier = Modifier.fillMaxWidth().testTag("signup_confirm_password_field")
    )

    Spacer(modifier = Modifier.height(20.dp))
    PrimaryButton(text = "Create Account", isLoading = isLoading, testTag = "signup_button") {
        authViewModel.signUp(userId, password, confirmPassword, recoveryEmail)
    }

    Spacer(modifier = Modifier.height(14.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Already have an account?", color = Color.Gray, fontSize = 12.sp)
        TextButton(onClick = onGoToSignIn, modifier = Modifier.testTag("go_to_signin_button")) {
            Text("Sign in", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ForgotPasswordContent(
    authViewModel: AuthViewModel,
    uiState: AuthUiState,
    onBackToSignIn: () -> Unit
) {
    var userId by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    val isLoading = uiState is AuthUiState.Loading

    if (uiState is AuthUiState.PasswordResetSuccess) {
        Text(text = "Password Updated", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(
            text = "Your password was changed and you're signed in.",
            fontSize = 12.sp,
            color = Color.Gray,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
        )
        return
    }

    val codeSent = uiState is AuthUiState.PasswordResetCodeSent

    Text(text = "Reset Password", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
    Text(
        text = if (!codeSent) "Enter your User ID and we'll email a reset code to your recovery address"
        else "Enter the code we emailed you and choose a new password",
        fontSize = 12.sp,
        color = Color.Gray,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 4.dp)
    )
    Spacer(modifier = Modifier.height(20.dp))

    ErrorOrInfoBanner(uiState)

    if (!codeSent) {
        OutlinedTextField(
            value = userId,
            onValueChange = { userId = it },
            label = { Text("User ID") },
            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = Color.Gray) },
            singleLine = true,
            colors = authFieldColors(),
            modifier = Modifier.fillMaxWidth().testTag("forgot_password_userid_field")
        )
        Spacer(modifier = Modifier.height(20.dp))
        PrimaryButton(text = "Send Reset Code", isLoading = isLoading, testTag = "send_reset_code_button") {
            authViewModel.requestPasswordReset(userId)
        }
    } else {
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text("6-digit code") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors = authFieldColors(),
            modifier = Modifier.fillMaxWidth().testTag("reset_code_field")
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = newPassword,
            onValueChange = { newPassword = it },
            label = { Text("New Password") },
            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = Color.Gray) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            colors = authFieldColors(),
            modifier = Modifier.fillMaxWidth().testTag("new_password_field")
        )
        Spacer(modifier = Modifier.height(20.dp))
        PrimaryButton(text = "Update Password", isLoading = isLoading, testTag = "confirm_reset_button") {
            authViewModel.confirmPasswordReset(code, newPassword)
        }
    }

    Spacer(modifier = Modifier.height(14.dp))
    TextButton(onClick = onBackToSignIn, modifier = Modifier.testTag("back_to_signin_button")) {
        Text("Back to Sign In", color = Color.Gray, fontSize = 12.sp)
    }
}

@Composable
private fun ForgotUserIdContent(
    authViewModel: AuthViewModel,
    uiState: AuthUiState,
    onBackToSignIn: (recoveredUserId: String) -> Unit
) {
    var recoveryEmail by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val isLoading = uiState is AuthUiState.Loading

    if (uiState is AuthUiState.UserIdRecovered) {
        Text(text = "We Found It!", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(
            text = "Your User ID is:",
            fontSize = 12.sp,
            color = Color.Gray,
            modifier = Modifier.padding(top = 8.dp)
        )
        Text(
            text = uiState.userId,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = 12.dp).testTag("recovered_user_id")
        )
        PrimaryButton(text = "Continue to Sign In", isLoading = false, testTag = "recovered_continue_button") {
            onBackToSignIn(uiState.userId)
        }
        return
    }

    val codeSent = uiState is AuthUiState.UserIdRecoveryCodeSent

    Text(text = "Find Your User ID", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
    Text(
        text = if (!codeSent) "Enter your recovery email and we'll send a verification code"
        else "Enter the code we emailed you",
        fontSize = 12.sp,
        color = Color.Gray,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 4.dp)
    )
    Spacer(modifier = Modifier.height(20.dp))

    ErrorOrInfoBanner(uiState)

    if (!codeSent) {
        OutlinedTextField(
            value = recoveryEmail,
            onValueChange = { recoveryEmail = it },
            label = { Text("Recovery Email") },
            leadingIcon = { Icon(Icons.Default.MailOutline, contentDescription = null, tint = Color.Gray) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            colors = authFieldColors(),
            modifier = Modifier.fillMaxWidth().testTag("forgot_userid_email_field")
        )
        Spacer(modifier = Modifier.height(20.dp))
        PrimaryButton(text = "Send Code", isLoading = isLoading, testTag = "send_userid_code_button") {
            authViewModel.requestUserIdRecovery(recoveryEmail)
        }
    } else {
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text("6-digit code") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors = authFieldColors(),
            modifier = Modifier.fillMaxWidth().testTag("userid_recovery_code_field")
        )
        Spacer(modifier = Modifier.height(20.dp))
        PrimaryButton(text = "Verify Code", isLoading = isLoading, testTag = "confirm_userid_recovery_button") {
            authViewModel.confirmUserIdRecovery(code)
        }
    }

    Spacer(modifier = Modifier.height(14.dp))
    TextButton(onClick = { onBackToSignIn("") }, modifier = Modifier.testTag("back_to_signin_from_userid_button")) {
        Text("Back to Sign In", color = Color.Gray, fontSize = 12.sp)
    }
}
