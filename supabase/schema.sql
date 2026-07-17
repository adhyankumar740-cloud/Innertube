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
  reactions jsonb not null default '{}'::jsonb, -- { "ðŸ‘": ["uid1","uid2"], ... }
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
