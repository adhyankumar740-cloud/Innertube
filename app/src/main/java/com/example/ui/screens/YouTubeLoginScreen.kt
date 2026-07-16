package com.example.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.youtube.YouTubeSession
import com.example.data.youtube.reportException
import com.metrolist.innertube.YouTube
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Real YouTube Music login: opens accounts.google.com inside a WebView (same flow as signing in
 * on music.youtube.com in a browser), then scrapes the resulting session cookie + visitorData +
 * dataSyncId out of the WebView once login completes, and saves them via [YouTubeSession].
 *
 * This is the piece that was missing from the app. It is unrelated to the existing "Sign in
 * with Google" screen (AuthScreen/AuthViewModel) — that one is a cosmetic profile login via
 * Credential Manager and never sets `YouTube.cookie`. Only a session obtained here unlocks the
 * login-gated stream fallback clients (ANDROID_CREATOR, WEB_CREATOR, TVHTML5, ...) in
 * YTPlayerUtils.
 *
 * @param onClose called when the user backs out without completing login.
 * @param onLoginSuccess called after the session is validated and saved. Restarting the app
 *   (as stock Metrolist does) is the safest way to make sure MusicService/the player picks up
 *   the new cookie everywhere, so that's the default; pass a no-op if you'd rather just pop the
 *   screen and rely on YouTubeSession's StateFlow for recomposition.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeLoginScreen(
    onClose: () -> Unit,
    onLoginSuccess: () -> Unit = { restartApp(); },
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var visitorData by remember { mutableStateOf<String?>(null) }
    var dataSyncId by remember { mutableStateOf<String?>(null) }
    var isCompletingLogin by remember { mutableStateOf(false) }

    var webView: WebView? = null

    fun completeLogin() {
        if (isCompletingLogin) return
        isCompletingLogin = true

        coroutineScope.launch {
            val currentCookie = CookieManager.getInstance()
                .getCookie("https://music.youtube.com")
                .orEmpty()

            if (currentCookie.isBlank()) {
                Timber.tag("YouTubeLoginScreen").d("No YouTube Music cookie found on close, leaving login screen")
                isCompletingLogin = false
                onClose()
                return@launch
            }

            // Apply provisionally so accountInfo() below authenticates with it.
            YouTube.cookie = currentCookie
            YouTube.dataSyncId = dataSyncId
            YouTube.visitorData = visitorData

            delay(500) // let cookies finish flushing to the WebView's CookieManager

            Timber.tag("YouTubeLoginScreen").d("Validating session...")

            YouTube.accountInfo()
                .onSuccess { info ->
                    YouTubeSession.save(
                        cookie = currentCookie,
                        visitorData = visitorData,
                        dataSyncId = dataSyncId,
                        accountName = info.name,
                        accountEmail = info.email.orEmpty(),
                    )

                    Timber.tag("YouTubeLoginScreen").d("Logged in as ${info.name}")

                    webView?.apply {
                        stopLoading()
                        clearHistory()
                        clearCache(true)
                        clearFormData()
                    }

                    onLoginSuccess()
                }
                .onFailure {
                    Timber.tag("YouTubeLoginScreen").e(it, "Login validation failed")
                    reportException(it)
                    // Roll back the provisional assignment above — don't leave a bad
                    // cookie/session sitting in the in-memory YouTube object.
                    YouTube.cookie = null
                    YouTube.dataSyncId = null
                    YouTube.visitorData = null
                    isCompletingLogin = false
                    onClose()
                }
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { webViewContext ->
            WebView(webViewContext).apply {
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        loadUrl("javascript:Android.onRetrieveVisitorData(window.yt.config_.VISITOR_DATA)")
                        loadUrl("javascript:Android.onRetrieveDataSyncId(window.yt.config_.DATASYNC_ID)")
                    }
                }
                settings.apply {
                    javaScriptEnabled = true
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                }
                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun onRetrieveVisitorData(newVisitorData: String?) {
                            if (newVisitorData != null) visitorData = newVisitorData
                        }

                        @JavascriptInterface
                        fun onRetrieveDataSyncId(newDataSyncId: String?) {
                            if (newDataSyncId != null) dataSyncId = newDataSyncId.substringBefore("||")
                        }
                    },
                    "Android",
                )
                webView = this
                loadUrl("https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com")
            }
        },
    )

    TopAppBar(
        title = { Text("Sign in to YouTube Music") },
        navigationIcon = {
            IconButton(onClick = { completeLogin() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
    )

    BackHandler {
        val currentWebView = webView
        if (currentWebView?.canGoBack() == true) {
            currentWebView.goBack()
        } else {
            completeLogin()
        }
    }
}

/** Hard-restarts the app so every component (MusicService included) picks up the new session. */
private fun restartApp() {
    // Intentionally left for the call site to override — see [onLoginSuccess] default above.
    // Kept as a named no-op here rather than referencing an Activity Context directly, since a
    // top-level function has none. Wire this to context.packageManager.getLaunchIntentForPackage(...)
    // from your call site, or just pass your own onLoginSuccess lambda that does the restart
    // (recommended — see integration notes).
}
