-- Earshot phase 6: transcripts and bookmarks. Run once in the Supabase dashboard after 001_sync.sql:
-- SQL Editor > New query > paste > Run. Safe to run again (everything is "if not exists" / "or replace").
-- Uses the same model as 001_sync.sql: changed_at (ms, device clock) decides which change wins, and
-- every accepted write gets a new rev from public.sync_rev so devices can pull "everything since rev N".

-- Transcripts, one per episode. segments is a JSON array of
--   { "s": start ms, "e": end ms, "t": text, "sp": speaker label or null }
-- source: "feed" (the podcast's own Podcasting 2.0 transcript) or "gemini" (Gemini 3.5 Transcribe).
-- timed = false when the feed's transcript has no timings (plain text or HTML): then it's one segment.
create table if not exists public.transcripts (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  episode_id text not null,
  source text not null,
  timed boolean not null default true,
  segments jsonb not null default '[]'::jsonb,
  changed_at bigint not null default 0,
  rev bigint not null default 0,
  primary key (user_id, episode_id)
);

-- Bookmarks. id is made on the device (a UUID). Episode details are copied in so a bookmark still
-- shows (and exports) after its episode has left the library.
-- The quote is not stored: it's the transcript between start_ms and end_ms, read when shown, so a
-- transcript made after bookmarking (e.g. in the car) fills it in. deleted = true is a tombstone.
create table if not exists public.bookmarks (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  id text not null,
  episode_id text not null,
  feed_url text not null default '',
  episode_title text not null default '',
  podcast_title text not null default '',
  image_url text,
  audio_url text,
  position_ms bigint not null default 0,
  start_ms bigint not null default 0,
  end_ms bigint not null default 0,
  note text not null default '',
  created_at bigint not null default 0,
  deleted boolean not null default false,
  changed_at bigint not null default 0,
  rev bigint not null default 0,
  primary key (user_id, id)
);

create index if not exists transcripts_rev on public.transcripts (user_id, rev);
create index if not exists bookmarks_rev on public.bookmarks (user_id, rev);

drop trigger if exists transcripts_stamp on public.transcripts;
create trigger transcripts_stamp before insert or update on public.transcripts
  for each row execute function public.sync_stamp();
drop trigger if exists bookmarks_stamp on public.bookmarks;
create trigger bookmarks_stamp before insert or update on public.bookmarks
  for each row execute function public.sync_stamp();

alter table public.transcripts enable row level security;
alter table public.bookmarks enable row level security;

revoke all on table public.transcripts, public.bookmarks from anon, authenticated;
grant select, insert, update, delete on table public.transcripts, public.bookmarks to authenticated;

drop policy if exists "own transcripts" on public.transcripts;
create policy "own transcripts" on public.transcripts for all to authenticated
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
drop policy if exists "own bookmarks" on public.bookmarks;
create policy "own bookmarks" on public.bookmarks for all to authenticated
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);

-- Search across the signed-in user's transcripts (used by the web player). Same rules as the phone:
-- terms are lower-cased words or phrases (the app splits the query; "quoted phrases" stay whole), found
-- anywhere in the text (so "heart" finds "hearts"), curly quotes counted as straight ones. A segment
-- matches when every term is in it or runs on into the next segment, and at least one starts in it.
-- Newest-changed transcripts first, at most max_results segments.
drop function if exists public.search_transcripts(text, int);
create or replace function public.search_transcripts(terms text[], max_results int default 300)
returns table (episode_id text, start_ms bigint, text text)
language sql
stable
security invoker
set search_path = ''
as $$
  with candidates as (
    -- Whole transcript first (segments joined by spaces), so most transcripts are ruled out quickly.
    select t.episode_id, t.changed_at, t.segments
    from public.transcripts t
    where t.user_id = (select auth.uid())
      and cardinality(terms) > 0
      and (
        select bool_and(strpos(translate(lower(replace(jsonb_path_query_array(t.segments, '$[*].t')::text, '", "', ' ')), '’‘“”', '''''""'), term) > 0)
        from unnest(terms) as term
      )
  ),
  segs as (
    select c.episode_id, c.changed_at, (x.seg ->> 's')::bigint as s, x.seg ->> 't' as t,
      translate(lower(x.seg ->> 't'), '’‘“”', '''''""') as cur,
      coalesce(lead(translate(lower(x.seg ->> 't'), '’‘“”', '''''""')) over (partition by c.episode_id order by x.n), '') as nxt
    from candidates c
    cross join lateral jsonb_array_elements(c.segments) with ordinality as x(seg, n)
  )
  select s2.episode_id, s2.s, s2.t
  from segs s2
  where (select bool_and(strpos(s2.cur || ' ' || s2.nxt, term) > 0) from unnest(terms) as term)
    and (select bool_or(strpos(s2.cur || ' ' || s2.nxt, term) between 1 and length(s2.cur)) from unnest(terms) as term)
  order by s2.changed_at desc, s2.episode_id, s2.s
  limit max_results;
$$;

revoke all on function public.search_transcripts(text[], int) from public, anon;
grant execute on function public.search_transcripts(text[], int) to authenticated;
