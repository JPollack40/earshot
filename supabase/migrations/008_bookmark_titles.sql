-- Earshot phase 14: automatic bookmark titles on the server. Run once in the Supabase dashboard after
-- 001-007: SQL Editor > New query > paste > Run. Safe to run again. Nothing existing is removed.
--
-- The phone and the web title a bookmark as soon as its episode has a timed transcript. Every 15 minutes
-- the "new-episodes" function (already scheduled by 005) also titles any bookmark still without one,
-- so a title isn't lost when Gemini was busy or the app was closed. It uses the three functions below
-- (service role only).
--
-- 1. bookmarks.title_tries / title_next_at   server-side retry count and when to try next (2, 4, 8 ...
--    hours apart, at most 6 tries). The apps don't read or write them.
-- 2. bookmarks_keep_title   a device that saves a bookmark without a title (because it hadn't heard of
--    the title yet, for example after editing the note) doesn't wipe a title made elsewhere.
-- 3. bookmark_titles_todo(max)   untitled bookmarks with a timed transcript, and their passage text.
-- 4. bookmark_title_set(...)    saves a title (only if the bookmark still has none) as a normal synced
--    change, so the phone and web pull it.
-- 5. bookmark_title_failed(...) counts a failed try and sets when to try again.

alter table public.bookmarks add column if not exists title_tries int not null default 0;
alter table public.bookmarks add column if not exists title_next_at timestamptz;

create or replace function public.bookmarks_keep_title() returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if (new.title is null or new.title = '') and old.title is not null and old.title <> '' then
    new.title := old.title;
  end if;
  return new;
end;
$$;

-- Runs before bookmarks_stamp (triggers fire in name order).
drop trigger if exists bookmarks_keep_title on public.bookmarks;
create trigger bookmarks_keep_title before update on public.bookmarks
  for each row execute function public.bookmarks_keep_title();

create or replace function public.bookmark_titles_todo(max_rows int default 10)
returns table (user_id uuid, id text, episode_id text, episode_title text, podcast_title text, passage text)
language sql
security definer
set search_path = ''
as $$
  select b.user_id, b.id, b.episode_id, b.episode_title, b.podcast_title,
    left(coalesce((
      select string_agg(regexp_replace(x.seg->>'t', '\s+', ' ', 'g'), ' ' order by x.ord)
      from jsonb_array_elements(t.segments) with ordinality as x(seg, ord)
      where greatest(coalesce((x.seg->>'e')::numeric, 0), coalesce((x.seg->>'s')::numeric, 0)) >= b.start_ms - 5000
        and coalesce((x.seg->>'s')::numeric, 0) <= b.end_ms + 5000
    ), ''), 4000) as passage
  from public.bookmarks b
  join public.transcripts t on t.user_id = b.user_id and t.episode_id = b.episode_id and t.timed
  where not b.deleted
    and (b.title is null or b.title = '')
    and b.title_tries < 6
    and (b.title_next_at is null or b.title_next_at <= now())
  order by b.created_at desc
  limit greatest(1, least(max_rows, 50));
$$;

create or replace function public.bookmark_title_set(p_user uuid, p_id text, p_title text)
returns boolean
language sql
security definer
set search_path = ''
as $$
  with u as (
    update public.bookmarks b
    set title = p_title,
        changed_at = greatest(b.changed_at + 1, (extract(epoch from clock_timestamp()) * 1000)::bigint)
    where b.user_id = p_user and b.id = p_id and not b.deleted and (b.title is null or b.title = '')
    returning 1
  )
  select exists (select 1 from u);
$$;

-- Doesn't touch changed_at, so devices ignore it (bookmarks_stamp still gives the row a new rev).
create or replace function public.bookmark_title_failed(p_user uuid, p_id text)
returns void
language sql
security definer
set search_path = ''
as $$
  update public.bookmarks b
  set title_tries = b.title_tries + 1,
      title_next_at = now() + make_interval(hours => (2 ^ least(b.title_tries + 1, 5))::int)
  where b.user_id = p_user and b.id = p_id;
$$;

revoke execute on function public.bookmark_titles_todo(int) from public, anon, authenticated;
revoke execute on function public.bookmark_title_set(uuid, text, text) from public, anon, authenticated;
revoke execute on function public.bookmark_title_failed(uuid, text) from public, anon, authenticated;
grant execute on function public.bookmark_titles_todo(int) to service_role;
grant execute on function public.bookmark_title_set(uuid, text, text) to service_role;
grant execute on function public.bookmark_title_failed(uuid, text) to service_role;

notify pgrst, 'reload schema';
