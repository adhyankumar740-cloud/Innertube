package com.example.data.youtube

import android.content.Context
import com.metrolist.innertube.YouTube
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Persists the real YouTube Music login (InnerTube cookie + visitorData + dataSyncId) and
 * restores it into [YouTube] on app start.
 *
 * This did not exist anywhere in the app before: `YouTube.cookie` was read in [YTPlayerUtils]
 * (to decide whether login-gated stream clients like ANDROID_CREATOR / WEB_CREATOR / TVHTML5
 * can be tried) but nothing ever *set* it, and nothing persisted it across process restarts.
 * The separate "Sign in with Google" screen (AuthScreen/AuthViewModel) is unrelated — it's a
 * cosmetic profile login via Credential Manager and never touches this.
 *
 * Call [restore] once, early in Application/MainActivity.onCreate, before any playback or
 * InnerTube network call happens.
 */
object YouTubeSession {
    private const val PREFS_NAME = "youtube_session"
    private const val KEY_COOKIE = "innertube_cookie"
    private const val KEY_VISITOR_DATA = "visitor_data"
    private const val KEY_DATA_SYNC_ID = "data_sync_id"
    private const val KEY_ACCOUNT_NAME = "account_name"
    private const val KEY_ACCOUNT_EMAIL = "account_email"

    private const val TAG = "YouTubeSession"

    private lateinit var prefs: android.content.SharedPreferences

    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn = _isLoggedIn.asStateFlow()

    private val _accountName = MutableStateFlow("")
    val accountName = _accountName.asStateFlow()

    private val _accountEmail = MutableStateFlow("")
    val accountEmail = _accountEmail.asStateFlow()

    /** Loads any saved cookie/visitorData/dataSyncId into [YouTube]. Safe to call multiple times. */
    fun restore(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val cookie = prefs.getString(KEY_COOKIE, null)
        if (!cookie.isNullOrBlank()) {
            YouTube.cookie = cookie
            YouTube.visitorData = prefs.getString(KEY_VISITOR_DATA, null)
            YouTube.dataSyncId = prefs.getString(KEY_DATA_SYNC_ID, null)
            _accountName.value = prefs.getString(KEY_ACCOUNT_NAME, "") ?: ""
            _accountEmail.value = prefs.getString(KEY_ACCOUNT_EMAIL, "") ?: ""
            _isLoggedIn.value = true
            Timber.tag(TAG).d("Restored saved YouTube Music session")
        } else {
            Timber.tag(TAG).d("No saved YouTube Music session found")
        }
    }

    /** Saves a freshly obtained login and applies it to [YouTube] immediately. */
    fun save(
        cookie: String,
        visitorData: String?,
        dataSyncId: String?,
        accountName: String,
        accountEmail: String,
    ) {
        prefs.edit()
            .putString(KEY_COOKIE, cookie)
            .putString(KEY_VISITOR_DATA, visitorData)
            .putString(KEY_DATA_SYNC_ID, dataSyncId)
            .putString(KEY_ACCOUNT_NAME, accountName)
            .putString(KEY_ACCOUNT_EMAIL, accountEmail)
            .apply()

        YouTube.cookie = cookie
        YouTube.visitorData = visitorData
        YouTube.dataSyncId = dataSyncId
        _accountName.value = accountName
        _accountEmail.value = accountEmail
        _isLoggedIn.value = true
    }

    /**
     * Guest/not-logged-in devices never had [YouTube.visitorData] set (it was only ever
     * populated by [save], which only runs after a real YT Music login). Without it,
     * [com.example.data.youtube.YTPlayerUtils] can't request a PoToken for any client
     * (WEB_REMIX or the ANDROID_VR/etc. fallbacks), and every one of them gets rejected
     * with "Sign in to confirm you're not a bot".
     *
     * Fetches an anonymous visitorData (same one music.youtube.com hands out to a guest
     * browser tab) and applies it in-memory so unauthenticated playback has a session to
     * attach a PoToken to. Does nothing if a visitorData is already set (logged-in session
     * from [restore], or an earlier call to this function). Not persisted to disk - guests
     * get a fresh one each app start, which matches how YouTube treats anonymous visitors.
     *
     * Safe/cheap to call every app start; call it right after [restore] and before any
     * playback-triggering InnerTube call.
     */
    suspend fun ensureVisitorData() {
        if (YouTube.visitorData != null) return
        YouTube.visitorData()
            .onSuccess { data ->
                YouTube.visitorData = data
                Timber.tag(TAG).d("Fetched anonymous visitorData for guest session")
            }.onFailure {
                Timber.tag(TAG).e(it, "Failed to fetch anonymous visitorData; guest playback may fail bot checks")
            }
    }

    /** Signs out: clears the saved session and the in-memory [YouTube] state. */
    fun logout() {
        prefs.edit().clear().apply()
        YouTube.cookie = null
        YouTube.visitorData = null
        YouTube.dataSyncId = null
        _accountName.value = ""
        _accountEmail.value = ""
        _isLoggedIn.value = false
    }
}
