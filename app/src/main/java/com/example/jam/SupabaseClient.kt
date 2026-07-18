package com.example.jam

import com.example.BuildConfig
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime

/**
 * Single shared Supabase client for the whole app:
 *  - Auth (installed below) backs the app's own account system - unique
 *    User ID + password sign up/login, plus the "Forgot User ID/Password"
 *    recovery flows. See AuthViewModel.kt.
 *  - Postgrest (table reads/writes) backs Jam persistence, announcements,
 *    and per-account playlist cloud backup (all scoped to the signed-in
 *    user via Row Level Security once Auth is installed - see
 *    supabase/schema.sql).
 *  - Realtime (Broadcast for instant playback/chat sync, Presence for the
 *    live participant list) backs the Jam feature.
 *
 * Requires SUPABASE_URL + SUPABASE_ANON_KEY in `local.properties` (see
 * SETUP_GUIDE.md) and the tables/policies/functions from
 * `supabase/schema.sql` to have been run once in your Supabase project's
 * SQL editor.
 *
 * The Auth plugin persists the signed-in session to on-device storage
 * automatically (SharedPreferences-backed under the hood), so a user stays
 * logged in across app restarts without any extra code here - AuthViewModel
 * just observes `client.auth.sessionStatus`.
 */
object SupabaseClient {
    val client by lazy {
        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY,
        ) {
            install(Auth)
            install(Postgrest)
            install(Realtime)
        }
    }
}
