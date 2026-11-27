-- Earshot phase 7: automation rules, smart playlists and listening history. Run once in the Supabase
-- dashboard after 001 and 002: SQL Editor > New query > paste > Run. Safe to run again.
-- Same model as before: changed_at (ms, device clock) decides which change wins, and every accepted
-- write gets a new rev from public.sync_rev so devices can pull "everything since rev N".
-- Nothing existing is changed: new columns are nullable (null = rule not set) and the tables are new.

-- New-episode and tidy-up rules, per podcast (see android/.../data/Automation.kt).
--   new_episode_action: 'inbox' (New Episodes), 'queue_next', 'queue_last' or 'skip'; null = 'inbox'
--   include_words / exclude_words: comma-separated words or phrases matched against the title
--   min_minutes / max_minutes: length range (episodes of unknown length pass)
--   inbox_days: archive New Episodes released more than N days ago (0 = never, null = app default)
--   inbox_keep: keep only the latest N in New Episodes (0 = no limit, null = app default)
-- The app-wide tidy-up defaults live in settings.playback as "inboxDays" and "inboxKeep".
alter table public.podcasts add column if not exists new_episode_action text;
alter table public.podcasts add column if not exists include_words text;
alter table public.podcasts add column if not exists exclude_words text;
alter table public.podcasts add column if not exists min_minutes int;
alter table public.podcasts add column if not exists max_minutes int;
alter table public.podcasts add column if not exists inbox_days int;
alter table public.podcasts add column if not exists inbox_keep int;

-- Smart playlists. id is made on the device (a UUID). filters is JSON:
--   { "podcasts": [feed urls, empty = all], "status": "unplayed"|"not_started"|"in_progress"|"played"|"any",
--     "downloaded": bool (phone only), "minMinutes": int|null, "maxMinutes": int|null,
--     "withinDays": int|null, "keywords": "comma, separated", "sort": "newest"|"oldest"|"shortest"|"longest",
--     "limit": int }
-- deleted = true is a tombstone.
create table if not exists public.playlists (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  id text not null,
  name text not null default '',
  position int not null default 0,
  filters jsonb not null default '{}'::jsonb,
  deleted boolean not null default false,
  changed_at bigint not null default 0,
  rev bigint not null default 0,
  primary key (user_id, id)
);

-- Listening history, for a future Stats page. One row per stretch of listening (play to pause, a
-- change of episode, or 30 minutes). Episode details are copied in so history outlives the library.
--   listened_ms: real time heard; from_ms..to_ms: the part of the episode covered
--   device: 'phone', 'car' (Android Auto connected) or 'web'
create table if not exists public.listens (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  id text not null,
  episode_id text not null,
  feed_url text not null default '',
  podcast_title text not null default '',
  episode_title text not null default '',
  started_at bigint not null default 0,
  ended_at bigint not null default 0,
  listened_ms bigint not null default 0,
  from_ms bigint not null default 0,
  to_ms bigint not null default 0,
  speed real not null default 1,
  device text not null default 'phone',
  changed_at bigint not null default 0,
  rev bigint not null default 0,
  primary key (user_id, id)
);

create index if not exists playlists_rev on public.playlists (user_id, rev);
create index if not exists listens_rev on public.listens (user_id, rev);
create index if not exists listens_started on public.listens (user_id, started_at desc);

drop trigger if exists playlists_stamp on public.playlists;
create trigger playlists_stamp before insert or update on public.playlists
  for each row execute function public.sync_stamp();
drop trigger if exists listens_stamp on public.listens;
create trigger listens_stamp before insert or update on public.listens
  for each row execute function public.sync_stamp();

alter table public.playlists enable row level security;
alter table public.listens enable row level security;

revoke all on table public.playlists, public.listens from anon, authenticated;
grant select, insert, update, delete on table public.playlists, public.listens to authenticated;

drop policy if exists "own playlists" on public.playlists;
create policy "own playlists" on public.playlists for all to authenticated
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
drop policy if exists "own listens" on public.listens;
create policy "own listens" on public.listens for all to authenticated
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);

-- Make the Data API see the new columns and tables straight away.
notify pgrst, 'reload schema';
