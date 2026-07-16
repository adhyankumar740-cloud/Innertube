package com.example.ui.viewmodel

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.UUID

/** UI state for the Google sign-in screen. */
sealed interface AuthUiState {
    data object SignedOut : AuthUiState
    data object Loading : AuthUiState
    data class Error(val message: String) : AuthUiState
}

/**
 * Real login: native "Sign in with Google" via Android's Credential Manager.
 *
 * This replaces the old Firebase email-link (magic-link) flow entirely.
 * There's no backend, no Firebase Auth, no emailed link, and no App Links /
 * deep-link setup - the Google account picker shows up on-device as a
 * bottom sheet, the user taps their account once, and sign-in completes
 * immediately. It's completely free (no usage limits, no plan required)
 * and doesn't depend on email deliverability or link verification, which
 * is what made the old flow unreliable.
 *
 * We don't run a backend, so there's no server-side session - login state
 * is simply "does this device have a saved Google identity", persisted
 * locally in SharedPreferences. That's enough for a display-name/email
 * profile; nothing in this app gates access to a remote resource on it.
 *
 * Note: JamManager separately uses Firebase Anonymous Auth under the hood -
 * that is NOT this login. It only exists to satisfy the Realtime Database's
 * "auth != null" security rule for the Jam feature and is invisible to the
 * user, so it's left untouched.
 */
class AuthViewModel(private val context: Context) : ViewModel() {

    private val credentialManager = CredentialManager.create(context)
    private val prefs = context.applicationContext
        .getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)

    private val _isLoggedIn = MutableStateFlow(prefs.contains(KEY_EMAIL))
    val isLoggedIn = _isLoggedIn.asStateFlow()

    private val _username = MutableStateFlow(prefs.getString(KEY_USERNAME, "MelodyMinds") ?: "MelodyMinds")
    val username = _username.asStateFlow()

    private val _email = MutableStateFlow(prefs.getString(KEY_EMAIL, "") ?: "")
    val email = _email.asStateFlow()

    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.SignedOut)
    val uiState = _uiState.asStateFlow()

    /**
     * Launches the native Google account picker and signs the user in.
     * [activityContext] must be an Activity context (not applicationContext) -
     * Credential Manager needs it to host the picker UI. Pass `LocalContext.current`
     * from the composable that calls this.
     */
    fun signInWithGoogle(activityContext: Context) {
        _uiState.value = AuthUiState.Loading

        val nonce = MessageDigest.getInstance("SHA-256")
            .digest(UUID.randomUUID().toString().toByteArray())
            .joinToString("") { "%02x".format(it) }

        // filterByAuthorizedAccounts = false shows every Google account on the
        // device (not just ones already linked to this app), so first-time
        // sign-in always works in a single step - no retry-on-empty-list logic.
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(WEB_CLIENT_ID)
            .setAutoSelectEnabled(false)
            .setNonce(nonce)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        viewModelScope.launch {
            // Credential Manager's getCredential() is known to intermittently
            // throw a generic GetCredentialException on the first attempt or
            // two (Play Services/account-picker warm-up) and then succeed
            // right after - which is why it used to take 3-4 manual taps.
            // Retrying a couple of times in the background (silently, before
            // showing any error) fixes that without the user noticing.
            val maxAttempts = 3
            var lastError: Exception? = null

            for (attempt in 1..maxAttempts) {
                try {
                    val result = credentialManager.getCredential(context = activityContext, request = request)
                    val credential = result.credential

                    if (credential !is CustomCredential ||
                        credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                    ) {
                        _uiState.value = AuthUiState.Error("Ye credential Google ka nahi tha. Dobara try karo.")
                        return@launch
                    }

                    val googleCredential = try {
                        GoogleIdTokenCredential.createFrom(credential.data)
                    } catch (e: GoogleIdTokenParsingException) {
                        _uiState.value = AuthUiState.Error("Google response parse nahi hua. Dobara try karo.")
                        return@launch
                    }

                    val displayName = googleCredential.displayName?.takeIf { it.isNotBlank() }
                        ?: googleCredential.id.substringBefore("@")
                    val userEmail = googleCredential.id // Google ID token's "id" is the account email.

                    prefs.edit()
                        .putString(KEY_USERNAME, displayName)
                        .putString(KEY_EMAIL, userEmail)
                        .putBoolean(KEY_IS_GUEST, false)
                        .apply()

                    _username.value = displayName
                    _email.value = userEmail
                    _isLoggedIn.value = true
                    _uiState.value = AuthUiState.SignedOut // reset - screen won't show since isLoggedIn is now true
                    return@launch
                } catch (e: GetCredentialCancellationException) {
                    // User dismissed the picker - not an error, just go back to idle.
                    _uiState.value = AuthUiState.SignedOut
                    return@launch
                } catch (e: GetCredentialException) {
                    lastError = e
                    if (attempt < maxAttempts) {
                        delay(400L)
                        continue
                    }
                    _uiState.value = AuthUiState.Error(
                        "Google sign-in fail ho gaya. Dobara try karo."
                    )
                } catch (e: Exception) {
                    lastError = e
                    if (attempt < maxAttempts) {
                        delay(400L)
                        continue
                    }
                    _uiState.value = AuthUiState.Error(e.message ?: "Kuch galat ho gaya. Dobara try karo.")
                }
            }
        }
    }

    /**
     * Lets someone use the app without a Google account - stores a local
     * "Guest" identity the same way a real sign-in would, so the rest of the
     * app (which only checks [isLoggedIn]) doesn't need to know the difference.
     */
    fun loginAsGuest() {
        prefs.edit()
            .putString(KEY_USERNAME, "Guest")
            .putString(KEY_EMAIL, "")
            .putBoolean(KEY_IS_GUEST, true)
            .apply()
        _username.value = "Guest"
        _email.value = ""
        _isLoggedIn.value = true
        _uiState.value = AuthUiState.SignedOut
    }

    fun dismissError() {
        _uiState.value = AuthUiState.SignedOut
    }

    fun logout() {
        viewModelScope.launch {
            try {
                // Clears Credential Manager's own sign-in state so the account
                // picker shows up fresh next time instead of silently
                // re-selecting the same account.
                credentialManager.clearCredentialState(ClearCredentialStateRequest())
            } catch (_: Exception) {
                // Best-effort - local state below is cleared regardless.
            }
            prefs.edit().clear().apply()
            _username.value = "MelodyMinds"
            _email.value = ""
            _isLoggedIn.value = false
            _uiState.value = AuthUiState.SignedOut
        }
    }

    companion object {
        private const val KEY_USERNAME = "username"
        private const val KEY_EMAIL = "email"
        private const val KEY_IS_GUEST = "is_guest"

        // The WEB (client_type 3) OAuth client from app/google-services.json -
        // required by GetGoogleIdOption.setServerClientId(). This is NOT the
        // Android client ID; Google Sign-In always needs the web one here,
        // even for a purely native Android app with no backend.
        private const val WEB_CLIENT_ID =
            "645739166879-r25846lbjpje0tgsdf2kaaddk2slspoe.apps.googleusercontent.com"
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AuthViewModel(context) as T
        }
    }
}
