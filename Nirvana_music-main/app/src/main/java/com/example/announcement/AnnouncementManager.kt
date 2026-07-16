package com.example.announcement

import android.content.Context
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.suspendCancellableCoroutine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/** A single announcement/update popup, pushed from the web Admin Panel. */
data class Announcement(
    val id: String,
    val title: String,
    val message: String,
    val active: Boolean,
    val updatedAt: Long
)

/**
 * Reads announcements written by the GitHub-Pages-hosted Admin Panel
 * (public/admin/index.html) from Firebase Realtime Database at
 * `announcements/current`, and decides whether to show one - at most once
 * per calendar day, and only while it's still marked `active`.
 *
 * The RTDB path is public-read (see public/admin/README.md for the
 * recommended security rule), so no auth/sign-in is needed here - only the
 * Admin Panel itself needs to authenticate to write.
 */
class AnnouncementManager(private val context: Context) {

    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val prefs = context.applicationContext
        .getSharedPreferences("announcement_prefs", Context.MODE_PRIVATE)

    private val dbRef by lazy {
        FirebaseDatabase.getInstance().getReference("announcements/current")
    }

    /** Fetches the current announcement once (not a live listener - a popup only needs the value at app-open time). */
    private suspend fun fetchCurrent(): Announcement? = suspendCancellableCoroutine { cont ->
        dbRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val announcement = parseSnapshot(snapshot)
                if (cont.isActive) cont.resume(announcement)
            }

            override fun onCancelled(error: DatabaseError) {
                if (cont.isActive) cont.resume(null)
            }
        })
    }

    private fun parseSnapshot(snapshot: DataSnapshot): Announcement? {
        if (!snapshot.exists()) return null
        val id = snapshot.child("id").getValue(String::class.java)
            ?: snapshot.key
            ?: return null
        val title = snapshot.child("title").getValue(String::class.java) ?: "Announcement"
        val message = snapshot.child("message").getValue(String::class.java) ?: return null
        val active = snapshot.child("active").getValue(Boolean::class.java) ?: true
        val updatedAt = snapshot.child("updatedAt").getValue(Long::class.java) ?: 0L
        if (message.isBlank()) return null
        return Announcement(id = id, title = title, message = message, active = active, updatedAt = updatedAt)
    }

    /**
     * Returns the announcement that should be shown right now (as a popup),
     * or null if there's nothing new/active, or if one has already been
     * shown today. Call this once per app launch/foreground.
     */
    suspend fun getAnnouncementToShow(): Announcement? {
        val announcement = fetchCurrent() ?: return null
        if (!announcement.active) return null

        val today = dayFormat.format(Date())
        val lastShownDay = prefs.getString(KEY_LAST_SHOWN_DAY, null)

        // Strictly once per calendar day, regardless of whether the
        // announcement content changed since the last one shown.
        val alreadyShownToday = lastShownDay == today
        return if (alreadyShownToday) null else announcement
    }

    fun markShown(announcement: Announcement) {
        prefs.edit()
            .putString(KEY_LAST_SHOWN_DAY, dayFormat.format(Date()))
            .putString(KEY_LAST_SHOWN_ID, announcement.id)
            .apply()
    }

    companion object {
        private const val KEY_LAST_SHOWN_DAY = "last_shown_day"
        private const val KEY_LAST_SHOWN_ID = "last_shown_id"
    }
}
