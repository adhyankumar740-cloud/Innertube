package com.example.announcement

import android.content.Context
import com.example.jam.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.time.OffsetDateTime
import java.util.Date
import java.util.Locale

/** A single announcement/update popup, sent from inside the app's own Admin screen. */
data class Announcement(
    val id: String,
    val title: String,
    val message: String,
    val version: String,
    val link: String,
    val active: Boolean,
    val updatedAt: Long
) {
    /** True when the admin attached a website/update link to this announcement. */
    val hasLink: Boolean get() = link.isNotBlank()
}

/**
 * Reads AND writes announcements - backed by **Supabase** (Postgrest, table
 * `announcements` - see `supabase/schema.sql`), the same backend already
 * used for the Jam feature. Everything happens inside the app itself
 * (Settings -> Admin) - there's no separate website/admin page anymore.
 *
 * Admin "login" is intentionally a simple hardcoded ID/password check (see
 * [ADMIN_ID]/[ADMIN_PASSWORD] below) rather than a real Supabase Auth
 * account - change those two constants before you ship, and don't commit
 * your real password to a public repo. This is a UI gate only (same trust
 * model the Jam tables already use with permissive RLS): it stops a casual
 * user from finding the Admin screen and posting something, but it does not
 * stop someone who has your Supabase anon key from writing directly via the
 * REST API. If you need real protection, swap this for Supabase Auth later
 * and lock the table's RLS policy to a specific authenticated user.
 */
class AnnouncementManager(private val context: Context) {

    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val prefs = context.applicationContext
        .getSharedPreferences("announcement_prefs", Context.MODE_PRIVATE)

    // In-memory only - admin has to "sign in" again each time the app is
    // relaunched, which is intentional for a hardcoded-password gate.
    private var adminSessionActive = false

    // -------------------------------------------------------------------
    // Reading (used by the popup + Settings "What's New" - no login needed)
    // -------------------------------------------------------------------

    @Serializable
    private data class AnnouncementRow(
        val id: String,
        val title: String,
        val version: String? = null,
        val message: String,
        val link: String? = null,
        val active: Boolean = true,
        @SerialName("created_at") val createdAt: String? = null
    )

    private fun AnnouncementRow.toAnnouncement(): Announcement {
        val millis = createdAt?.let {
            runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
        } ?: 0L
        return Announcement(
            id = id,
            title = title,
            message = message,
            version = version.orEmpty(),
            link = link.orEmpty(),
            active = active,
            updatedAt = millis
        )
    }

    /** Fetches the most recently created announcement row, or null if the table is empty. */
    private suspend fun fetchLatestRow(): Announcement? =
        SupabaseClient.client.postgrest[TABLE]
            .select(columns = Columns.ALL) {
                order("created_at", Order.DESCENDING)
                limit(1)
            }
            .decodeSingleOrNull<AnnouncementRow>()
            ?.toAnnouncement()

    /**
     * Returns the announcement that should be shown right now (as a popup),
     * or null if there's nothing new/active, or if one has already been
     * shown today. Call this once per app launch/foreground.
     */
    suspend fun getAnnouncementToShow(): Announcement? {
        val announcement = fetchLatestRow() ?: return null
        if (!announcement.active) return null

        val today = dayFormat.format(Date())
        val lastShownDay = prefs.getString(KEY_LAST_SHOWN_DAY, null)

        // Strictly once per calendar day, regardless of whether the
        // announcement content changed since the last one shown.
        val alreadyShownToday = lastShownDay == today
        return if (alreadyShownToday) null else announcement
    }

    /**
     * Fetches the current announcement regardless of whether one has already
     * been shown as a popup today. Used by the Settings screen's "What's
     * New" section, which should always be able to show the latest update
     * (and its website/download link) on demand, not just once a day.
     */
    suspend fun getLatestAnnouncement(): Announcement? = fetchLatestRow()

    fun markShown(announcement: Announcement) {
        prefs.edit()
            .putString(KEY_LAST_SHOWN_DAY, dayFormat.format(Date()))
            .putString(KEY_LAST_SHOWN_ID, announcement.id)
            .apply()
    }

    // -------------------------------------------------------------------
    // Admin (in-app) - hardcoded ID/password gate, used by AdminScreen.kt
    // -------------------------------------------------------------------

    val isAdminSignedIn: Boolean get() = adminSessionActive

    /** Checks credentials against the hardcoded admin ID/password. Throws with a friendly message on mismatch. */
    fun signInAsAdmin(adminId: String, password: String) {
        if (adminId.trim() == ADMIN_ID && password == ADMIN_PASSWORD) {
            adminSessionActive = true
        } else {
            throw IllegalArgumentException("Wrong admin ID or password.")
        }
    }

    fun signOutAdmin() {
        adminSessionActive = false
    }

    @Serializable
    private data class AnnouncementInsert(
        val title: String,
        val version: String,
        val message: String,
        val link: String,
        val active: Boolean = true
    )

    @Serializable
    private data class ActiveFlagUpdate(val active: Boolean)

    /**
     * Publishes a new announcement (visible to every user's app) as a new
     * row - older rows stay in the table as history. Requires an admin
     * session (see [signInAsAdmin]).
     */
    suspend fun publishAnnouncement(
        title: String,
        version: String,
        message: String,
        link: String
    ) {
        check(adminSessionActive) { "Sign in as admin first." }
        require(message.isNotBlank()) { "Message can't be empty." }

        SupabaseClient.client.postgrest[TABLE].insert(
            AnnouncementInsert(
                title = title.ifBlank { "Announcement" },
                version = version,
                message = message,
                link = link,
                active = true
            )
        )
    }

    /** Flips the most recent announcement's `active` flag off without deleting it. */
    suspend fun deactivateCurrentAnnouncement() {
        check(adminSessionActive) { "Sign in as admin first." }
        val latestId = SupabaseClient.client.postgrest[TABLE]
            .select(columns = Columns.list("id")) {
                order("created_at", Order.DESCENDING)
                limit(1)
            }
            .decodeSingleOrNull<Map<String, String>>()
            ?.get("id") ?: return

        SupabaseClient.client.postgrest[TABLE].update(ActiveFlagUpdate(active = false)) {
            filter { eq("id", latestId) }
        }
    }

    companion object {
        private const val KEY_LAST_SHOWN_DAY = "last_shown_day"
        private const val KEY_LAST_SHOWN_ID = "last_shown_id"
        private const val TABLE = "announcements"

        // ---------------------------------------------------------------
        // CHANGE THESE before you ship. This is the only thing gating the
        // in-app Admin screen - see the class doc comment above for the
        // trust model this does (and doesn't) give you.
        // ---------------------------------------------------------------
        private const val ADMIN_ID = "Admin"
        private const val ADMIN_PASSWORD = "change"
    }
}
