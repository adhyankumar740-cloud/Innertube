package com.example.jam

import com.example.BuildConfig
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime

/**
 * Single shared Supabase client for the Jam feature (group listening + live chat).
 * Replaces JamManager/JamChatManager's old Firebase Realtime Database usage.
 *
 * Requires SUPABASE_URL + SUPABASE_ANON_KEY in `local.properties` (see
 * SETUP_GUIDE.md section 6) and the tables/policies from `supabase/schema.sql`
 * to have been run once in your Supabase project's SQL editor.
 *
 * Only Postgrest (table reads/writes -> room + chat persistence) and Realtime
 * (Broadcast for instant playback/chat sync, Presence for the live participant
 * list) are installed. Jam intentionally does NOT use Supabase Auth - user
 * identity (uid) still comes from Firebase Auth (see JamManager.ensureSignedIn,
 * unchanged from before), so the jam_* Postgres tables use permissive RLS
 * policies (equivalent to the old Firebase "auth != null" testing rule) instead
 * of relying on a Supabase session. Tighten those policies later if you migrate
 * to real Supabase Auth.
 */
object SupabaseClient {
    val client by lazy {
        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY,
        ) {
            install(Postgrest)
            install(Realtime)
        }
    }
}
