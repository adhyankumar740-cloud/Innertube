-- ============================================================================
-- Jam feature schema for Supabase (replaces the old Firebase Realtime Database
-- "jams/{code}/..." tree). Run this once in your Supabase project's
-- SQL Editor (Dashboard -> SQL Editor -> New query -> paste -> Run).
--
-- Design notes:
--  - jam_rooms is ONE ROW PER ROOM holding the flattened playback state
--    (song + isPlaying + positionMs + senderUid), mirroring the old Firebase
--    "playback" child node. Every push (song change / play-pause / seek) does
--    a single UPDATE across all these columns in one round trip - same intent
--    as the old atomic `updateChildren` call.
--  - Realtime *Broadcast* (not Postgres Changes) is what actually delivers
--    instant playback/chat updates to other devices - this table is for
--    PERSISTENCE + "what's the current state" when a guest joins, not for the
--    live sync path itself. That keeps latency low (no DB repliation lag on
--    the hot path) while still surviving reconnects.
--  - Participants are NOT a table - they're tracked with Supabase Realtime
--    Presence (see JamManager.kt), which - like Firebase's onDisconnect() -
--    automatically removes a participant the moment their socket disconnects,
--    with no server-side row to clean up.
--  - jam_messages is a normal append-only table for chat history + reactions.
-- ============================================================================

create table if not exists public.jam_rooms (
  code text primary key,
  host_id text not null,
  song_id text,
  title text,
  artist text,
  duration_ms bigint,
  source text,
  stream_url text,
  genre text,
  artwork text,
  youtube_video_id text,
  is_playing boolean not null default false,
  position_ms bigint not null default 0,
  sender_uid text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.jam_messages (
  id text primary key,
  code text not null references public.jam_rooms(code) on delete cascade,
  sender_id text not null,
  sender_name text not null,
  sender_avatar_url text not null default 'ðŸŽ§',
  text text not null,
  reply_to_id text,
  reply_to_text text,
  reply_to_sender_name text,
  reactions jsonb not null default '{}'::jsonb, -- { "ðŸ‘": ["uid1","uid2"], ... }
  created_at timestamptz not null default now()
);

create index if not exists jam_messages_code_created_at_idx
  on public.jam_messages (code, created_at);

-- Keep jam_rooms.updated_at fresh on every playback UPDATE, so it behaves like
-- Firebase's ServerValue.TIMESTAMP (server-authoritative write time).
create or replace function public.jam_rooms_set_updated_at()
returns trigger
language plpgsql
as $$
begin
  new.updated_at = now();
  return new;
end;
$$;

drop trigger if exists jam_rooms_updated_at on public.jam_rooms;
create trigger jam_rooms_updated_at
  before update on public.jam_rooms
  for each row execute function public.jam_rooms_set_updated_at();

-- ----------------------------------------------------------------------------
-- Row Level Security. Jam does NOT use Supabase Auth (identity comes from
-- Firebase Auth on the client, same as before), so requests hit these tables
-- as the `anon` role. These policies are the direct equivalent of the old
-- Firebase testing rule (".read"/".write": "auth != null") - open enough for
-- the feature to work, same trust model you already had. Tighten later if you
-- add real Supabase Auth (e.g. scope updates to auth.uid() = sender_uid).
-- ----------------------------------------------------------------------------

alter table public.jam_rooms enable row level security;
alter table public.jam_messages enable row level security;

drop policy if exists "jam_rooms_select" on public.jam_rooms;
create policy "jam_rooms_select" on public.jam_rooms for select using (true);

drop policy if exists "jam_rooms_insert" on public.jam_rooms;
create policy "jam_rooms_insert" on public.jam_rooms for insert with check (true);

drop policy if exists "jam_rooms_update" on public.jam_rooms;
create policy "jam_rooms_update" on public.jam_rooms for update using (true) with check (true);

drop policy if exists "jam_messages_select" on public.jam_messages;
create policy "jam_messages_select" on public.jam_messages for select using (true);

drop policy if exists "jam_messages_insert" on public.jam_messages;
create policy "jam_messages_insert" on public.jam_messages for insert with check (true);

drop policy if exists "jam_messages_update" on public.jam_messages;
create policy "jam_messages_update" on public.jam_messages for update using (true) with check (true);

-- ----------------------------------------------------------------------------
-- Realtime: Broadcast + Presence work over websockets and don't need a table
-- publication. Nothing else to enable for them. (We deliberately do NOT add
-- jam_rooms/jam_messages to `supabase_realtime` Postgres Changes - Broadcast
-- is used instead for the low-latency sync path, per the design notes above.)
-- ----------------------------------------------------------------------------

-- Optional housekeeping: delete rooms older than 24h that were never cleaned
-- up (e.g. app killed without leaveRoom() running). Safe to skip; run manually
-- or wire up as a Supabase cron/Edge Function later if you want it automatic.
-- delete from public.jam_rooms where created_at < now() - interval '24 hours';

-- ============================================================================
-- Custom account system (replaces Google Sign-In) - see AuthViewModel.kt.
-- Run this section once alongside the Jam section above.
--
-- Users sign up / log in with a unique **User ID** and **password** - not an
-- email address. Supabase Auth itself still requires one email per account
-- (it's what actually delivers password-reset / recovery codes), so sign-up
-- also collects a "recovery email":
--   auth.users.email            -> the recovery email (Supabase Auth's own login email)
--   public.profiles.user_id     -> the public-facing unique handle typed to log in
--   public.profiles.recovery_email -> same value as auth.users.email, owner-readable
--
-- Design notes:
--  - `handle_new_user` (trigger on auth.users) creates the matching
--    `profiles` row automatically right after sign-up, reading the
--    `user_id` the client passed in as sign-up metadata. This runs
--    SECURITY DEFINER so it works even before email confirmation (i.e.
--    before the client has any session to satisfy RLS with).
--  - `resolve_login_email` lets the client turn a typed User ID into the
--    email Supabase Auth's signInWith(Email)/resetPasswordForEmail expect -
--    it has to be callable by the anonymous role, since this happens
--    *before* the user is signed in, so it's SECURITY DEFINER too. It only
--    ever returns an email for a User ID that exists - nothing else.
--  - `user_id_available` gives sign-up a fast, friendly "already taken"
--    check before attempting the real signUpWith(Email) call. The unique
--    index below is the actual source of truth / race-condition guard.
--  - Once a user *is* authenticated, all further profile reads/writes go
--    through ordinary RLS (`auth.uid() = id`) rather than these functions.
-- ============================================================================

create table if not exists public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  user_id text not null,
  recovery_email text not null,
  created_at timestamptz not null default now()
);

-- Case-insensitive uniqueness - "Alice" and "alice" are the same User ID.
create unique index if not exists profiles_user_id_lower_idx
  on public.profiles (lower(user_id));

alter table public.profiles enable row level security;

drop policy if exists "profiles_select_own" on public.profiles;
create policy "profiles_select_own" on public.profiles
  for select using (auth.uid() = id);

drop policy if exists "profiles_update_own" on public.profiles;
create policy "profiles_update_own" on public.profiles
  for update using (auth.uid() = id) with check (auth.uid() = id);

-- No insert/delete policy for the anon/authenticated roles on purpose -
-- profile rows are only ever created by the trigger below (which runs as
-- the table owner and bypasses RLS), so a client can never insert/delete an
-- arbitrary profiles row directly.

create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
  insert into public.profiles (id, user_id, recovery_email)
  values (
    new.id,
    coalesce(new.raw_user_meta_data->>'user_id', split_part(new.email, '@', 1)),
    new.email
  )
  on conflict (id) do nothing;
  return new;
end;
$$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function public.handle_new_user();

create or replace function public.resolve_login_email(p_user_id text)
returns text
language sql
security definer
set search_path = public
stable
as $$
  select recovery_email
  from public.profiles
  where lower(user_id) = lower(p_user_id)
  limit 1;
$$;

revoke all on function public.resolve_login_email(text) from public;
grant execute on function public.resolve_login_email(text) to anon, authenticated;

create or replace function public.user_id_available(p_user_id text)
returns boolean
language sql
security definer
set search_path = public
stable
as $$
  select not exists (
    select 1 from public.profiles where lower(user_id) = lower(p_user_id)
  );
$$;

revoke all on function public.user_id_available(text) from public;
grant execute on function public.user_id_available(text) to anon, authenticated;

-- ----------------------------------------------------------------------------
-- Playlist cloud backup - see PlaylistCloudSync.kt / PlaylistViewModel.kt.
-- Every row belongs to exactly one account (`owner_id`, defaulting to
-- auth.uid() so the client never has to set it) and RLS makes sure a user
-- can only ever see or touch their own rows - a real per-account trust
-- model now that login is real Supabase Auth, unlike the permissive Jam
-- tables above (which intentionally stay open for group-listening rooms
-- shared across accounts/devices).
--
-- NOTE: this table is dropped and recreated (rather than `create table if
-- not exists`) because an earlier run may have created it in a different
-- shape without `owner_id`, which breaks the RLS policies below. If you
-- have existing backup rows you care about, back them up before running
-- this section - see the migration note at the bottom of this file.
-- ----------------------------------------------------------------------------

drop table if exists public.playlist_backups cascade;

create table public.playlist_backups (
  owner_id uuid not null references auth.users(id) on delete cascade default auth.uid(),
  remote_id text not null,
  name text not null,
  created_at bigint not null,
  tracks jsonb not null default '[]'::jsonb,
  primary key (owner_id, remote_id)
);

alter table public.playlist_backups enable row level security;

drop policy if exists "playlist_backups_select_own" on public.playlist_backups;
create policy "playlist_backups_select_own" on public.playlist_backups
  for select using (auth.uid() = owner_id);

drop policy if exists "playlist_backups_insert_own" on public.playlist_backups;
create policy "playlist_backups_insert_own" on public.playlist_backups
  for insert with check (auth.uid() = owner_id);

drop policy if exists "playlist_backups_update_own" on public.playlist_backups;
create policy "playlist_backups_update_own" on public.playlist_backups
  for update using (auth.uid() = owner_id) with check (auth.uid() = owner_id);

drop policy if exists "playlist_backups_delete_own" on public.playlist_backups;
create policy "playlist_backups_delete_own" on public.playlist_backups
  for delete using (auth.uid() = owner_id);

-- ----------------------------------------------------------------------------
-- Migration note: if you had existing rows in an old-shaped playlist_backups
-- table and need to preserve them, run this BEFORE the drop table above,
-- then re-insert afterward, adjusting column names to match your old schema:
--
--   create table public.playlist_backups_old_backup as
--     select * from public.playlist_backups;
-- ----------------------------------------------------------------------------
