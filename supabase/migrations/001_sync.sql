-- Earshot sync schema (phase 5b). Run once in the Supabase dashboard: SQL Editor > New query > paste > Run.
-- Safe to run again: everything is "if not exists" / "or replace".
--
-- Model: each device keeps its own copy and syncs rows with the server.
--  * Every row carries changed_at: the time (ms since 1970, device clock) of the user's change.
--    An older change never overwrites a newer one (see the trigger), so the last change wins.
--  * Every accepted write stamps rev from one sequence. A device asks for "rows with rev > the
--    last rev I saw" to get everything that changed since its last sync.

create sequence if not exists public.sync_rev;

-- Podcasts: subscriptions (subscribed = false: kept only for queued episodes), pins and per-podcast settings.
create table if not exists public.podcasts (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  feed_url text not null,
  title text not null default '',
  author text not null default '',
  description text not null default '',
  image_url text,
  subscribed boolean not null default true,
  pinned boolean not null default false,
  speed real,
  smart_speed boolean,
  voice_boost boolean,
  skip_back_ms bigint,
  skip_forward_ms bigint,
  changed_at bigint not null default 0,
  rev bigint not null default 0,
  primary key (user_id, feed_url)
);

-- Episodes: listening state plus enough of the feed's data to show and play the episode without
-- fetching the feed (queue, New Episodes, Latest). id = the app's stable episode id
-- (first 10 bytes of SHA-1(feed_url + "\n" + guid), hex).
create table if not exists public.episodes (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  id text not null,
  feed_url text not null,
  guid text not null,
  title text not null default '',
  description text not null default '',
  pub_date bigint not null default 0,
  audio_url text not null,
  mime_type text,
  duration_ms bigint not null default 0,
  image_url text,
  chapters_url text,
  transcript_url text,
  transcript_type text,
  position_ms bigint not null default 0,
  played boolean not null default false,
  last_played_at bigint not null default 0,
  in_inbox boolean not null default false,
  queue_position bigint,
  changed_at bigint not null default 0,
  rev bigint not null default 0,
  primary key (user_id, id)
);

-- App-wide settings (playback defaults), one row per user.
create table if not exists public.settings (
  user_id uuid not null default auth.uid() primary key references auth.users (id) on delete cascade,
  playback jsonb not null default '{}'::jsonb,
  changed_at bigint not null default 0,
  rev bigint not null default 0
);

create index if not exists podcasts_rev on public.podcasts (user_id, rev);
create index if not exists episodes_rev on public.episodes (user_id, rev);
create index if not exists episodes_pub on public.episodes (user_id, pub_date desc);

-- Last change wins; every accepted write gets a new rev.
create or replace function public.sync_stamp() returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if tg_op = 'UPDATE' and new.changed_at < old.changed_at then
    return null; -- an older change: keep the newer one already stored
  end if;
  new.rev := nextval('public.sync_rev');
  return new;
end;
$$;

drop trigger if exists podcasts_stamp on public.podcasts;
create trigger podcasts_stamp before insert or update on public.podcasts
  for each row execute function public.sync_stamp();
drop trigger if exists episodes_stamp on public.episodes;
create trigger episodes_stamp before insert or update on public.episodes
  for each row execute function public.sync_stamp();
drop trigger if exists settings_stamp on public.settings;
create trigger settings_stamp before insert or update on public.settings
  for each row execute function public.sync_stamp();

-- Only the signed-in owner can see or change their rows. Nothing for anonymous callers.
alter table public.podcasts enable row level security;
alter table public.episodes enable row level security;
alter table public.settings enable row level security;

revoke all on table public.podcasts, public.episodes, public.settings from anon, authenticated;
grant select, insert, update, delete on table public.podcasts, public.episodes, public.settings to authenticated;
grant usage on sequence public.sync_rev to authenticated;

drop policy if exists "own podcasts" on public.podcasts;
create policy "own podcasts" on public.podcasts for all to authenticated
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
drop policy if exists "own episodes" on public.episodes;
create policy "own episodes" on public.episodes for all to authenticated
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
drop policy if exists "own settings" on public.settings;
create policy "own settings" on public.settings for all to authenticated
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
