-- Earshot phase 10: listening upgrades. Run once in the Supabase dashboard after 001-003:
-- SQL Editor > New query > paste > Run. Safe to run again. Nothing existing is changed or removed.
--
-- 1. New synced columns (all nullable):
--    podcasts.ad_trim           'start' | 'end' | null: trim ads stitched onto episodes (phone)
--    episodes.skipped_chapters  JSON array of chapter start times (ms) the user unticked
--    episodes.season, episodes.episode_no   for season/episode badges on the web
-- 2. Server-side new-episode check (the "new-episodes" edge function, every 15 minutes):
--    push_devices   phones that want "New episode" notifications (Firebase Cloud Messaging)
--    feed_checks    the server's own bookkeeping per feed (Podcast Index id, last check)
--    new_episodes_feeds(), new_episodes_insert(items)   called only by the function (service role)
--    run_new_episode_check()   what the schedule runs; run it by hand to check right now
-- The schedule itself is in 005_schedule_new_episodes.sql (needs the function deployed first).

alter table public.podcasts add column if not exists ad_trim text;
alter table public.episodes add column if not exists skipped_chapters jsonb;
alter table public.episodes add column if not exists season int;
alter table public.episodes add column if not exists episode_no double precision;

-- Phones that receive "New episode" notifications. token = the phone's FCM registration token.
-- notify: 'all' (every new episode that goes to New Episodes or the queue), 'pinned' (pinned podcasts only), 'off'.
create table if not exists public.push_devices (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  token text not null,
  platform text not null default 'android',
  notify text not null default 'all',
  updated_at timestamptz not null default now(),
  primary key (user_id, token)
);
alter table public.push_devices enable row level security;
revoke all on table public.push_devices from anon, authenticated;
grant select, insert, update, delete on table public.push_devices to authenticated;
drop policy if exists "own devices" on public.push_devices;
create policy "own devices" on public.push_devices for all to authenticated
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);

-- Server bookkeeping per feed address (shared by everyone subscribed to it). No client access.
--   pi_feed_id: Podcast Index's id for the feed (null = not found there; looked up again after a day)
--   checked_since: unix seconds; the next check asks Podcast Index for episodes since then
create table if not exists public.feed_checks (
  feed_url text primary key,
  pi_feed_id bigint,
  looked_up_at timestamptz,
  checked_since bigint not null default 0,
  last_error text
);
alter table public.feed_checks enable row level security;
revoke all on table public.feed_checks from anon, authenticated;

-- Every subscribed feed (any user) with its check state. Finished shows are left out by the function.
create or replace function public.new_episodes_feeds()
returns table (feed_url text, pi_feed_id bigint, looked_up_at timestamptz, checked_since bigint)
language sql
security definer
set search_path = ''
as $$
  select distinct p.feed_url, f.pi_feed_id, f.looked_up_at, coalesce(f.checked_since, 0)
  from public.podcasts p
  left join public.feed_checks f on f.feed_url = p.feed_url
  where p.subscribed;
$$;

-- True if a new episode passes a podcast's filters (same rules as the phone's Automation.passesFilters).
create or replace function public.new_episode_passes(
  title text, duration_ms bigint, include_words text, exclude_words text, min_minutes int, max_minutes int
) returns boolean
language plpgsql
immutable
set search_path = ''
as $$
declare
  t text := lower(replace(replace(coalesce(title, ''), '’', ''''), '‘', ''''));
  w text;
  any_include boolean := false;
  has_include boolean := false;
begin
  foreach w in array string_to_array(coalesce(include_words, ''), ',') loop
    w := btrim(lower(replace(replace(w, '’', ''''), '‘', '''')));
    if w <> '' then
      has_include := true;
      if position(w in t) > 0 then any_include := true; end if;
    end if;
  end loop;
  if has_include and not any_include then return false; end if;
  foreach w in array string_to_array(coalesce(exclude_words, ''), ',') loop
    w := btrim(lower(replace(replace(w, '’', ''''), '‘', '''')));
    if w <> '' and position(w in t) > 0 then return false; end if;
  end loop;
  if coalesce(duration_ms, 0) > 0 then
    if coalesce(min_minutes, 0) > 0 and duration_ms / 60000.0 < min_minutes then return false; end if;
    if coalesce(max_minutes, 0) > 0 and duration_ms / 60000.0 > max_minutes then return false; end if;
  end if;
  return true;
end;
$$;

-- Adds newly found episodes for every user subscribed to their feed, applying each user's
-- new-episode rule (New Episodes, top or end of the queue, or skip), and returns what was added
-- so the function can send notifications. items: [{ feed_url, id, guid, title,
-- description, pub_date, audio_url, mime_type, duration_ms, image_url, chapters_url,
-- transcript_url, transcript_type, season, episode_no }] (id = the app's episode id for that feed
-- address). An episode the user already has (same id,
-- or same audio address) is left alone, so the phone's own refresh and the server never double up.
create or replace function public.new_episodes_insert(items jsonb)
returns table (user_id uuid, episode_id text, title text, podcast_title text, image_url text, action text, pinned boolean)
language plpgsql
security definer
set search_path = ''
as $$
#variable_conflict use_column
declare
  it jsonb;
  p record;
  eid text;
  act text;
  pos bigint;
  now_ms bigint := (extract(epoch from clock_timestamp()) * 1000)::bigint;
begin
  for it in select * from jsonb_array_elements(items) loop
    for p in
      select pc.user_id, pc.feed_url, pc.title as ptitle, pc.image_url as pimage, pc.pinned,
             pc.new_episode_action, pc.include_words, pc.exclude_words, pc.min_minutes, pc.max_minutes
      from public.podcasts pc
      where pc.subscribed and pc.feed_url = it->>'feed_url'
    loop
      eid := it->>'id';
      if exists (
        select 1 from public.episodes e
        where e.user_id = p.user_id and (e.id = eid or e.audio_url = it->>'audio_url')
      ) then
        continue;
      end if;
      if not public.new_episode_passes(it->>'title', (it->>'duration_ms')::bigint, p.include_words, p.exclude_words, p.min_minutes, p.max_minutes) then
        act := 'skip';
      else
        act := coalesce(p.new_episode_action, 'inbox');
      end if;
      pos := null;
      if act = 'queue_last' then
        select coalesce(max(e.queue_position) + 1, 0) into pos from public.episodes e
          where e.user_id = p.user_id and e.queue_position is not null;
      elsif act = 'queue_next' then
        -- Second place (after what's playing); everything from there moves down one.
        select case when count(*) = 0 then 0 else 1 end into pos from public.episodes e
          where e.user_id = p.user_id and e.queue_position is not null;
        update public.episodes e set queue_position = e.queue_position + 1, changed_at = now_ms
          where e.user_id = p.user_id and e.queue_position is not null and e.queue_position >= pos;
      end if;
      insert into public.episodes (
        user_id, id, feed_url, guid, title, description, pub_date, audio_url, mime_type, duration_ms,
        image_url, chapters_url, transcript_url, transcript_type, season, episode_no,
        in_inbox, queue_position, changed_at
      ) values (
        p.user_id, eid, p.feed_url, it->>'guid', coalesce(it->>'title', ''), left(coalesce(it->>'description', ''), 20000),
        coalesce((it->>'pub_date')::bigint, 0), it->>'audio_url', it->>'mime_type', coalesce((it->>'duration_ms')::bigint, 0),
        it->>'image_url', it->>'chapters_url', it->>'transcript_url', it->>'transcript_type',
        (it->>'season')::int, (it->>'episode_no')::double precision,
        act = 'inbox', pos, now_ms
      );
      user_id := p.user_id;
      episode_id := eid;
      title := it->>'title';
      podcast_title := p.ptitle;
      image_url := coalesce(it->>'image_url', p.pimage);
      action := act;
      pinned := p.pinned;
      return next;
    end loop;
  end loop;
end;
$$;

revoke execute on function public.new_episodes_feeds() from public, anon, authenticated;
revoke execute on function public.new_episodes_insert(jsonb) from public, anon, authenticated;
grant execute on function public.new_episodes_feeds() to service_role;
grant execute on function public.new_episodes_insert(jsonb) to service_role;
