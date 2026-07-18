package com.example.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.jam.SupabaseClient
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** UI state for the auth screen (sign in / sign up / recovery flows). */
sealed interface AuthUiState {
    /** Nothing to show - idle form. */
    data object Idle : AuthUiState
    data object Loading : AuthUiState
    data class Error(val message: String) : AuthUiState

    /** Neutral/success banner, e.g. "check your email to confirm your account". */
    data class Info(val message: String) : AuthUiState

    /** A password-reset code was emailed - show the "enter code + new password" step. */
    data class PasswordResetCodeSent(val userId: String) : AuthUiState

    /** Password was changed successfully (user is left signed in). */
    data object PasswordResetSuccess : AuthUiState

    /** A User ID recovery code was emailed - show the "enter code" step. */
    data class UserIdRecoveryCodeSent(val recoveryEmail: String) : AuthUiState

    /** The account's User ID was found and can be shown to the user. */
    data class UserIdRecovered(val userId: String) : AuthUiState
}

/**
 * Custom account system backed by **Supabase Auth + Postgrest** (replaces the
 * old native "Sign in with Google" flow).
 *
 * Users sign up / log in with a unique **User ID** and **password** - not an
 * email address. Under the hood, Supabase Auth still needs an email per
 * account (that's what actually receives password-reset / verification
 * codes), so sign-up also collects a **recovery email**:
 *  - `auth.users.email`            -> the recovery email (Supabase Auth's own login email)
 *  - `public.profiles.user_id`     -> the public-facing unique handle the user types to log in
 *  - `public.profiles.recovery_email` -> same value as auth.users.email, readable by the owner
 *
 * A Postgres trigger (`handle_new_user`, see supabase/schema.sql) creates the
 * `profiles` row automatically right after Supabase Auth creates the
 * `auth.users` row, so the client never has to (and never could, before a
 * session exists to satisfy Row Level Security).
 *
 * Because Supabase Auth's `signInWith(Email)` needs an email, logging in by
 * User ID first resolves the associated email via the `resolve_login_email`
 * Postgres RPC (SECURITY DEFINER, so it can be called before the user has a
 * session) and then signs in with that email + the entered password.
 *
 * Recovery flows:
 *  - **Forgot Password**: enter your User ID -> we resolve its recovery email
 *    and call `resetPasswordForEmail` -> you get a 6-digit code by email ->
 *    entering it + a new password verifies the code (OtpType.Email.RECOVERY)
 *    and updates the password. You're left signed in afterwards.
 *  - **Forgot User ID**: enter your recovery email -> we send a sign-in code
 *    to it (`signInWith(OTP)`) -> entering the code verifies it
 *    (OtpType.Email.EMAIL), which briefly authenticates the session just long
 *    enough to read your own `profiles` row and show you your User ID. We
 *    then immediately sign back out, so recovering your User ID never by
 *    itself logs you into the app - you still log in normally afterwards.
 *
 * Guest mode (no account at all) is untouched - it's a purely local flag
 * unrelated to Supabase, kept for anyone who doesn't want an account. Guest
 * sessions never sync to the cloud, exactly as before.
 */
class AuthViewModel(private val context: Context) : ViewModel() {

    private val prefs = context.applicationContext
        .getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)

    private val auth get() = SupabaseClient.client.auth

    @Serializable
    private data class ProfileRow(
        @SerialName("user_id") val userId: String,
        @SerialName("recovery_email") val recoveryEmail: String
    )

    // ---- Guest (local-only) state -----------------------------------------

    private val _isGuest = MutableStateFlow(prefs.getBoolean(KEY_IS_GUEST, false))

    // ---- Cloud (Supabase Auth) state ---------------------------------------

    /** True once Supabase Auth has finished restoring any saved session (avoids an auth-screen flash). */
    private val _isInitializing = MutableStateFlow(true)
    val isInitializing = _isInitializing.asStateFlow()

    private val isCloudAuthenticated = auth.sessionStatus
        .map { it is SessionStatus.Authenticated }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Drives whether [AuthScreen] or the main app is shown. */
    val isLoggedIn = combine(isCloudAuthenticated, _isGuest) { cloud, guest -> cloud || guest }
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.getBoolean(KEY_IS_GUEST, false))

    /** True only for a real signed-in account - gates cloud playlist sync (guests never sync). */
    val isCloudSynced = isCloudAuthenticated

    private val _userId = MutableStateFlow("")
    /** The signed-in account's unique User ID, or "" for guests / signed-out. */
    val userId = _userId.asStateFlow()

    private val _username = MutableStateFlow(prefs.getString(KEY_GUEST_LABEL, "Guest") ?: "Guest")
    /** Display label used across the app's Home/Library/Settings/Jam screens. */
    val username = _username.asStateFlow()

    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val uiState = _uiState.asStateFlow()

    // Email addresses in flight for the two-step recovery flows. Not
    // persisted on purpose - if the process dies mid-flow, the user just
    // restarts the (cheap, re-requestable) recovery step.
    private var pendingPasswordResetEmail: String? = null
    private var pendingUserIdRecoveryEmail: String? = null

    init {
        viewModelScope.launch {
            auth.sessionStatus.collectLatest { status ->
                when (status) {
                    is SessionStatus.Authenticated -> {
                        _isInitializing.value = false
                        val uid = status.session.user?.id
                        val profile = uid?.let { fetchOwnProfile(it) }
                        _userId.value = profile?.userId ?: ""
                        _username.value = profile?.userId?.takeIf { it.isNotBlank() } ?: "Account"
                    }
                    is SessionStatus.NotAuthenticated -> {
                        _isInitializing.value = false
                        if (!_isGuest.value) {
                            _userId.value = ""
                            _username.value = "Guest"
                        }
                    }
                    is SessionStatus.RefreshFailure -> {
                        _isInitializing.value = false
                        if (!_isGuest.value) {
                            _userId.value = ""
                            _username.value = "Guest"
                        }
                    }
                    else -> { /* Initializing - keep whatever was showing until it resolves. */ }
                }
            }
        }
    }

    // ---- Sign up ------------------------------------------------------------

    /**
     * Creates a new account. [userId] is the unique public handle the user
     * will log in with; [recoveryEmail] is only ever used to deliver
     * password-reset / User-ID-recovery codes, never shown to other users.
     */
    fun signUp(userId: String, password: String, confirmPassword: String, recoveryEmail: String) {
        val id = userId.trim()
        val email = recoveryEmail.trim()

        if (!id.matches(USER_ID_REGEX)) {
            _uiState.value = AuthUiState.Error("User ID must be 3-24 characters: letters, numbers, and underscores only.")
            return
        }
        if (password.length < 6) {
            _uiState.value = AuthUiState.Error("Password must be at least 6 characters.")
            return
        }
        if (password != confirmPassword) {
            _uiState.value = AuthUiState.Error("Passwords don't match.")
            return
        }
        if (!EMAIL_REGEX.matches(email)) {
            _uiState.value = AuthUiState.Error("Enter a valid recovery email - it's only used to recover your account.")
            return
        }

        _uiState.value = AuthUiState.Loading
        viewModelScope.launch {
            try {
                val available = SupabaseClient.client.postgrest
                    .rpc("user_id_available", buildJsonObject { put("p_user_id", id) })
                    .decodeAs<Boolean>()
                if (!available) {
                    _uiState.value = AuthUiState.Error("That User ID is already taken. Try another one.")
                    return@launch
                }

                auth.signUpWith(Email) {
                    this.email = email
                    this.password = password
                    data = buildJsonObject { put("user_id", id) }
                }

                if (auth.currentSessionOrNull() == null) {
                    // Email confirmation is required by the project's Auth settings -
                    // there's no session yet, so isLoggedIn won't flip true on its own.
                    _uiState.value = AuthUiState.Info(
                        "Account created! Check $email to confirm it, then sign in with your User ID."
                    )
                } else {
                    _uiState.value = AuthUiState.Idle // isLoggedIn flips true via sessionStatus above
                }
            } catch (e: Exception) {
                _uiState.value = AuthUiState.Error(friendlyMessage(e, "Sign up failed."))
            }
        }
    }

    // ---- Sign in --------------------------------------------------------------

    fun signIn(userId: String, password: String) {
        val id = userId.trim()
        if (id.isEmpty() || password.isEmpty()) {
            _uiState.value = AuthUiState.Error("Enter your User ID and password.")
            return
        }

        _uiState.value = AuthUiState.Loading
        viewModelScope.launch {
            try {
                val email = resolveLoginEmail(id)
                if (email.isNullOrBlank()) {
                    _uiState.value = AuthUiState.Error("No account found for that User ID.")
                    return@launch
                }
                auth.signInWith(Email) {
                    this.email = email
                    this.password = password
                }
                _uiState.value = AuthUiState.Idle // isLoggedIn flips true via sessionStatus above
            } catch (e: Exception) {
                _uiState.value = AuthUiState.Error("Incorrect User ID or password.")
            }
        }
    }

    // ---- Guest ------------------------------------------------------------

    /** Lets someone use the app without creating an account - purely local, never synced. */
    fun loginAsGuest() {
        prefs.edit()
            .putBoolean(KEY_IS_GUEST, true)
            .putString(KEY_GUEST_LABEL, "Guest")
            .apply()
        _isGuest.value = true
        _userId.value = ""
        _username.value = "Guest"
        _uiState.value = AuthUiState.Idle
    }

    // ---- Forgot Password ----------------------------------------------------

    /** Step 1: request a password-reset code by User ID. */
    fun requestPasswordReset(userId: String) {
        val id = userId.trim()
        if (id.isEmpty()) {
            _uiState.value = AuthUiState.Error("Enter your User ID.")
            return
        }
        _uiState.value = AuthUiState.Loading
        viewModelScope.launch {
            try {
                val email = resolveLoginEmail(id)
                if (email.isNullOrBlank()) {
                    _uiState.value = AuthUiState.Error("No account found for that User ID.")
                    return@launch
                }
                pendingPasswordResetEmail = email
                auth.resetPasswordForEmail(email)
                _uiState.value = AuthUiState.PasswordResetCodeSent(id)
            } catch (e: Exception) {
                _uiState.value = AuthUiState.Error(friendlyMessage(e, "Couldn't send a reset code. Try again."))
            }
        }
    }

    /** Step 2: verify the emailed code and set a new password. */
    fun confirmPasswordReset(code: String, newPassword: String) {
        val email = pendingPasswordResetEmail
        if (email == null) {
            _uiState.value = AuthUiState.Error("Request a new code and try again.")
            return
        }
        if (newPassword.length < 6) {
            _uiState.value = AuthUiState.Error("Password must be at least 6 characters.")
            return
        }
        _uiState.value = AuthUiState.Loading
        viewModelScope.launch {
            try {
                auth.verifyEmailOtp(type = OtpType.Email.RECOVERY, email = email, token = code.trim())
                auth.updateUser { password = newPassword }
                pendingPasswordResetEmail = null
                _uiState.value = AuthUiState.PasswordResetSuccess // isLoggedIn is already true from the verified session
            } catch (e: Exception) {
                _uiState.value = AuthUiState.Error("That code is invalid or expired. Request a new one.")
            }
        }
    }

    // ---- Forgot User ID -----------------------------------------------------

    /** Step 1: request a verification code by recovery email. */
    fun requestUserIdRecovery(recoveryEmail: String) {
        val email = recoveryEmail.trim()
        if (!EMAIL_REGEX.matches(email)) {
            _uiState.value = AuthUiState.Error("Enter the recovery email you signed up with.")
            return
        }
        _uiState.value = AuthUiState.Loading
        viewModelScope.launch {
            try {
                pendingUserIdRecoveryEmail = email
                auth.signInWith(OTP) { this.email = email }
                _uiState.value = AuthUiState.UserIdRecoveryCodeSent(email)
            } catch (e: Exception) {
                _uiState.value = AuthUiState.Error(friendlyMessage(e, "Couldn't send a code. Try again."))
            }
        }
    }

    /** Step 2: verify the emailed code and reveal the account's User ID. */
    fun confirmUserIdRecovery(code: String) {
        val email = pendingUserIdRecoveryEmail
        if (email == null) {
            _uiState.value = AuthUiState.Error("Request a new code and try again.")
            return
        }
        _uiState.value = AuthUiState.Loading
        viewModelScope.launch {
            try {
                auth.verifyEmailOtp(type = OtpType.Email.EMAIL, email = email, token = code.trim())
                val uid = auth.currentUserOrNull()?.id
                val profile = uid?.let { fetchOwnProfile(it) }
                pendingUserIdRecoveryEmail = null

                // Recovering a User ID should never by itself log someone into
                // the app - sign back out once we've read the profile so the
                // person still goes through a normal User ID + password login.
                try {
                    auth.signOut()
                } catch (_: Exception) { /* best-effort */ }

                _uiState.value = if (profile != null) {
                    AuthUiState.UserIdRecovered(profile.userId)
                } else {
                    AuthUiState.Error("Couldn't find an account for that email.")
                }
            } catch (e: Exception) {
                _uiState.value = AuthUiState.Error("That code is invalid or expired. Request a new one.")
            }
        }
    }

    // ---- Shared -------------------------------------------------------------

    fun dismiss() {
        _uiState.value = AuthUiState.Idle
    }

    fun logout() {
        viewModelScope.launch {
            try {
                auth.signOut()
            } catch (_: Exception) {
                // Best-effort - local state below is cleared regardless.
            }
            prefs.edit().clear().apply()
            _isGuest.value = false
            _userId.value = ""
            _username.value = "Guest"
            _uiState.value = AuthUiState.Idle
        }
    }

    // ---- Internal helpers ---------------------------------------------------

    /** Resolves the login email behind a User ID via the `resolve_login_email` RPC (works pre-auth). */
    private suspend fun resolveLoginEmail(userId: String): String? = try {
        SupabaseClient.client.postgrest
            .rpc("resolve_login_email", buildJsonObject { put("p_user_id", userId) })
            .decodeAs<String?>()
            ?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    /** Reads the caller's own `profiles` row - only works with an authenticated session (RLS-scoped). */
    private suspend fun fetchOwnProfile(uid: String): ProfileRow? = try {
        SupabaseClient.client.postgrest["profiles"]
            .select {
                filter { eq("id", uid) }
            }
            .decodeSingleOrNull<ProfileRow>()
    } catch (e: Exception) {
        null
    }

    private fun friendlyMessage(e: Exception, fallback: String): String {
        val msg = e.message ?: return fallback
        return when {
            msg.contains("already registered", ignoreCase = true) -> "That recovery email is already in use."
            msg.contains("network", ignoreCase = true) -> "Network error. Check your connection and try again."
            else -> fallback
        }
    }

    companion object {
        private const val KEY_IS_GUEST = "is_guest"
        private const val KEY_GUEST_LABEL = "guest_label"

        private val USER_ID_REGEX = Regex("^[a-zA-Z0-9_]{3,24}$")
        private val EMAIL_REGEX = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AuthViewModel(context) as T
        }
    }
}
