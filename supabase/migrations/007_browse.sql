-- Earshot phase 13: Browse Podcasts, auto-titled bookmarks. Run once in the Supabase dashboard after
-- 001-006: SQL Editor > New query > paste > Run. Safe to run again. Nothing existing is changed or removed.
--
-- 1. browse_prefs     one row per user: chart country, category rows, hidden sections and their order,
--                     and the opt-in for "Popular with your friends". Read and written by the phone and
--                     the web directly (Browse is online only, so it isn't part of the sync tables).
-- 2. browse_hidden    shows the user said "Not interested" to. Browse never shows them again.
-- 3. browse_cache     the "browse" edge function's shared cache (charts, trending lists, Podcast Index
--                     lookups, podrolls). Only the function (service role) can read or write it.
-- 4. browse_friends_popular()   shows that at least 2 OTHER opted-in people listened to in the last
--                     90 days, without names. Only for a caller who has opted in themselves.
-- 5. browse_my_top_shows()     the caller's own most-listened shows (90 days), for "You may be into".
-- 6. bookmarks.title  a short title made from the bookmark's transcript passage (nullable).

create table if not exists public.browse_prefs (
  user_id uuid not null default auth.uid() primary key references auth.users (id) on delete cascade,
  country text not null default 'us',
  categories text[] not null default array['News', 'Music', 'History', 'Comedy', 'True Crime', 'Science', 'Technology', 'Sports'],
  hidden_sections text[] not null default '{}',
  section_order text[] not null default '{}',
  friends_opt_in boolean not null default false,
  updated_at timestamptz not null default now()
);
alter table public.browse_prefs enable row level security;
revoke all on table public.browse_prefs from anon, authenticated;
grant select, insert, update, delete on table public.browse_prefs to authenticated;
drop policy if exists "own browse prefs" on public.browse_prefs;
create policy "own browse prefs" on public.browse_prefs for all to authenticated
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);

create table if not exists public.browse_hidden (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  feed_url text not null,
  title text not null default '',
  image_url text,
  created_at timestamptz not null default now(),
  primary key (user_id, feed_url)
);
alter table public.browse_hidden enable row level security;
revoke all on table public.browse_hidden from anon, authenticated;
grant select, insert, update, delete on table public.browse_hidden to authenticated;
drop policy if exists "own browse hidden" on public.browse_hidden;
create policy "own browse hidden" on public.browse_hidden for all to authenticated
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);

create table if not exists public.browse_cache (
  key text primary key,
  data jsonb not null default '{}'::jsonb,
  fetched_at timestamptz not null default now()
);
alter table public.browse_cache enable row level security;
revoke all on table public.browse_cache from anon, authenticated;
grant select, insert, update, delete on table public.browse_cache to service_role;

-- "Popular with your friends": counts distinct opted-in listeners per feed over the last 90 days,
-- leaving out the caller (so a show the caller listens to needs 2 OTHER listeners), and only answers a
-- caller who has opted in. Returns no user ids or names. The caller's own followed shows are left out.
create or replace function public.browse_friends_popular()
returns table (feed_url text, podcast_title text, listeners int)
language sql
stable
security definer
set search_path = ''
as $$
  with me as (select (select auth.uid()) as id),
  opted as (
    select bp.user_id from public.browse_prefs bp where bp.friends_opt_in
  ),
  recent as (
    select l.user_id, l.feed_url, max(l.podcast_title) as podcast_title
    from public.listens l
    join opted o on o.user_id = l.user_id
    where l.feed_url <> ''
      and l.started_at > (extract(epoch from now()) * 1000)::bigint - 90::bigint * 24 * 60 * 60 * 1000
      and l.user_id <> (select id from me)
    group by l.user_id, l.feed_url
    having sum(l.listened_ms) >= 10 * 60 * 1000  -- at least 10 minutes counts as listening
  )
  select r.feed_url, max(r.podcast_title), count(distinct r.user_id)::int as listeners
  from recent r
  where exists (select 1 from public.browse_prefs bp where bp.user_id = (select id from me) and bp.friends_opt_in)
    and not exists (
      select 1 from public.podcasts p
      where p.user_id = (select id from me) and p.subscribed and p.feed_url = r.feed_url
    )
  group by r.feed_url
  having count(distinct r.user_id) >= 2
  order by listeners desc, max(r.podcast_title)
  limit 30;
$$;
revoke all on function public.browse_friends_popular() from public, anon;
grant execute on function public.browse_friends_popular() to authenticated;

-- The caller's most-listened shows over the last [days] days (for "You may be into"), from the listens log.
create or replace function public.browse_my_top_shows(days int default 90)
returns table (feed_url text, podcast_title text, listened_ms bigint)
language sql
stable
security invoker
set search_path = ''
as $$
  select l.feed_url, max(l.podcast_title), sum(l.listened_ms)::bigint
  from public.listens l
  where l.user_id = (select auth.uid())
    and l.feed_url <> ''
    and l.started_at > (extract(epoch from now()) * 1000)::bigint - days::bigint * 24 * 60 * 60 * 1000
  group by l.feed_url
  order by 3 desc
  limit 50;
$$;
revoke all on function public.browse_my_top_shows(int) from public, anon;
grant execute on function public.browse_my_top_shows(int) to authenticated;

-- Auto-titled bookmarks.
alter table public.bookmarks add column if not exists title text;

-- Make the Data API see the new tables and column straight away.
notify pgrst, 'reload schema';
