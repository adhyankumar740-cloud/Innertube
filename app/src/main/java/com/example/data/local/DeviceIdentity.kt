package com.example.data.local

import android.content.Context
import java.util.UUID

/**
 * Stable per-device identity used by the Jam feature (group listening + chat)
 * to tell participants apart, and to suppress echoing a device's own
 * broadcast back to itself.
 *
 * Replaces the old Firebase Anonymous Auth uid. That uid was never tied to
 * "which Google account is logged into the app" - it only existed to hand
 * Jam a stable random identifier, so a plain locally-generated UUID (kept in
 * SharedPreferences) is a drop-in replacement with the exact same behavior,
 * minus the Firebase dependency and the network round-trip needed to obtain
 * it.
 */
object DeviceIdentity {

    private const val PREFS_NAME = "device_identity_prefs"
    private const val KEY_UID = "device_uid"

    /** Returns this device's stable random id, generating and persisting one on first call. */
    fun getUid(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_UID, null)?.let { return it }

        val newUid = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_UID, newUid).apply()
        return newUid
    }
}
